//! Task d5 (`odd/tasks/desktop-production-app.md`, sections 3.5 and 4.5): the production USB
//! [`PhoneLink`]. Each poll scans the bus, picks the phone with [`select_phone_candidate`],
//! asks a Samsung device (or the explicit override) to switch to accessory mode, and opens and
//! claims the accessory. An accessory exists long before the phone app opens it, so the link
//! keeps the claimed stream and hands it to the worker only once the phone has sent its first
//! bytes (the TLS ClientHello); until then every poll is one short idle read.

use std::{
    env,
    io::{self, Read, Write},
    time::{Duration, Instant},
};

use rusb::UsbContext;

use crate::{
    physical_location_for_rusb_device, AccessoryIdentity, AoaAccessoryHandleRegistry,
    AoaAccessoryReenumerationPoller, AoaObservedDevice, DeviceIdentifier, FrameTransferBudget,
    FramedUsbStream, HostAoaControlOptions, IdleReadTimeoutControl, LiveAoaControlRunner,
    PhoneLink, PhoneLinkError, ReenumerationWait, RusbAoaAccessoryHandleRegistry,
    RusbClaimedBulkIo, RusbUsbDeviceRegistry, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
    AOA_ACCESSORY_DEVICE_IDS, USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES,
};

pub const SAMSUNG_VENDOR_ID: u16 = 0x04e8;
/// Diagnostic escape hatch: an explicit `VVVV:PPPP` device to switch to accessory mode.
pub const USB_DEVICE_OVERRIDE_ENV: &str = "CHINCHILLACAM_USB_DEVICE";
/// Same bulk I/O attempt budget as the USB pairing helper.
const BULK_IO_ATTEMPTS: usize = 8192;
const FIRST_READ_BUFFER_BYTES: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PhoneCandidate {
    AccessoryMode(AoaObservedDevice),
    NeedsAccessorySwitch(AoaObservedDevice),
}

/// A device already in accessory mode wins; otherwise the first Samsung device, otherwise the
/// explicit override. No other device is ever selected.
pub fn select_phone_candidate(
    devices: &[AoaObservedDevice],
    device_override: Option<DeviceIdentifier>,
) -> Option<PhoneCandidate> {
    let find = |wanted: &dyn Fn(&DeviceIdentifier) -> bool| {
        devices
            .iter()
            .find(|device| wanted(device.identifier()))
            .cloned()
    };
    if let Some(device) = find(&|id| AOA_ACCESSORY_DEVICE_IDS.contains(id)) {
        return Some(PhoneCandidate::AccessoryMode(device));
    }
    find(&|id| id.vendor_id() == SAMSUNG_VENDOR_ID)
        .or_else(|| device_override.and_then(|wanted| find(&|id| *id == wanted)))
        .map(PhoneCandidate::NeedsAccessorySwitch)
}

/// Parses the override value: unset or blank is `None`; anything but `VVVV:PPPP` in hex fails.
pub fn parse_usb_device_override(
    value: Option<&str>,
) -> Result<Option<DeviceIdentifier>, UsbProbeError> {
    match value.map(str::trim) {
        None | Some("") => Ok(None),
        Some(text) if text.bytes().all(|b| b == b':' || b.is_ascii_hexdigit()) => {
            DeviceIdentifier::parse_vid_pid(text).map(Some)
        }
        Some(text) => Err(UsbProbeError::InvalidDeviceIdentifier(text.to_string())),
    }
}

/// Reads [`USB_DEVICE_OVERRIDE_ENV`] through [`parse_usb_device_override`].
pub fn usb_device_override_from_env() -> Result<Option<DeviceIdentifier>, UsbProbeError> {
    match env::var(USB_DEVICE_OVERRIDE_ENV) {
        Ok(value) => parse_usb_device_override(Some(&value)),
        Err(env::VarError::NotPresent) => Ok(None),
        Err(env::VarError::NotUnicode(value)) => Err(UsbProbeError::InvalidDeviceIdentifier(
            value.to_string_lossy().into_owned(),
        )),
    }
}

/// The USB operations the link needs; production uses [`RusbPhoneBackend`].
pub trait UsbPhoneBackend: Send + 'static {
    type Io: UsbBulkIo + Send + 'static;

    fn scan(&mut self) -> Result<Vec<AoaObservedDevice>, UsbProbeError>;
    /// Sends the AOA start sequence; the next scan should find the device in accessory mode.
    fn switch_to_accessory(&mut self, device: &AoaObservedDevice) -> Result<(), UsbProbeError>;
    /// Opens the accessory at the device's physical location and claims its bulk interface.
    fn open_accessory(&mut self, device: &AoaObservedDevice) -> Result<Self::Io, UsbProbeError>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UsbPhoneLinkConfig {
    pub device_override: Option<DeviceIdentifier>,
    /// After a failed scan, switch or open, polls return `Ok(None)` for this long.
    pub retry_backoff: Duration,
    /// Idle read timeout of each poll while waiting for the phone's first bytes.
    pub first_bytes_wait: Duration,
    /// Bulk transfer timeout once a frame has started (and after the stream is handed out).
    pub transfer_timeout: Duration,
}

impl UsbPhoneLinkConfig {
    /// Applies the diagnostic USB selector from the environment; the caller can surface a
    /// malformed value instead of silently scanning an unintended device.
    pub fn from_env() -> Result<Self, UsbProbeError> {
        Ok(Self {
            device_override: usb_device_override_from_env()?,
            ..Self::default()
        })
    }
}

impl Default for UsbPhoneLinkConfig {
    fn default() -> Self {
        Self {
            device_override: None,
            retry_backoff: Duration::from_secs(2),
            first_bytes_wait: Duration::from_millis(50),
            transfer_timeout: Duration::from_millis(5000),
        }
    }
}

pub struct UsbPhoneLink<B: UsbPhoneBackend> {
    backend: B,
    config: UsbPhoneLinkConfig,
    waiting: Option<UsbTlsCiphertextStream<B::Io>>,
    retry_at: Option<Instant>,
}

impl<B: UsbPhoneBackend> UsbPhoneLink<B> {
    pub fn new(backend: B, config: UsbPhoneLinkConfig) -> Self {
        Self {
            backend,
            config,
            waiting: None,
            retry_at: None,
        }
    }

    /// Scans and either switches the candidate or opens a waiting accessory stream.
    fn open_phone(&mut self) -> Result<(), PhoneLinkError> {
        let devices = self.backend.scan().map_err(PhoneLinkError::Scan)?;
        match select_phone_candidate(&devices, self.config.device_override) {
            None => Err(PhoneLinkError::NoPhoneFound),
            Some(PhoneCandidate::NeedsAccessorySwitch(device)) => self
                .backend
                .switch_to_accessory(&device)
                .map_err(PhoneLinkError::Switch),
            Some(PhoneCandidate::AccessoryMode(device)) => {
                let io = self
                    .backend
                    .open_accessory(&device)
                    .map_err(PhoneLinkError::Open)?;
                let budget = FrameTransferBudget::new(
                    self.config.transfer_timeout,
                    USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES,
                    BULK_IO_ATTEMPTS,
                )
                .map_err(PhoneLinkError::Open)?;
                let mut stream = UsbTlsCiphertextStream::new(FramedUsbStream::new(io, budget));
                stream
                    .set_idle_read_timeout(Some(self.config.first_bytes_wait))
                    .map_err(PhoneLinkError::Open)?;
                self.waiting = Some(stream);
                Ok(())
            }
        }
    }

    /// One idle read: the stream is handed out with the bytes read once the phone spoke; an
    /// idle timeout keeps waiting; end of stream or any other error drops it.
    fn take_if_phone_spoke(&mut self) -> Option<PeekedStream<UsbTlsCiphertextStream<B::Io>>> {
        let mut stream = self.waiting.take()?;
        let mut prefix = vec![0; FIRST_READ_BUFFER_BYTES];
        match stream.read(&mut prefix) {
            Ok(0) => None,
            Ok(read) => {
                prefix.truncate(read);
                stream.set_idle_read_timeout(None).ok()?;
                Some(PeekedStream::new(prefix, stream))
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                ) =>
            {
                self.waiting = Some(stream);
                None
            }
            Err(_) => None,
        }
    }
}

impl<B: UsbPhoneBackend> PhoneLink for UsbPhoneLink<B> {
    type Stream = PeekedStream<UsbTlsCiphertextStream<B::Io>>;

    fn poll_phone(&mut self) -> Result<Option<Self::Stream>, PhoneLinkError> {
        if self.waiting.is_none() {
            let now = Instant::now();
            if self.retry_at.is_some_and(|retry_at| now < retry_at) {
                return Ok(None);
            }
            self.retry_at = None;
            if let Err(error) = self.open_phone() {
                if error != PhoneLinkError::NoPhoneFound {
                    self.retry_at = Some(now + self.config.retry_backoff);
                }
                return Err(error);
            }
        }
        Ok(self.take_if_phone_spoke())
    }
}

/// The accessory identity the Android filter matches (manufacturer and model), the same values
/// as the `usb-probe` CLI.
pub fn chinchillacam_accessory_identity() -> AccessoryIdentity {
    AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "safe host CLI boundary",
        "0.5.0",
        "https://example.invalid/chinchillacam",
        "prototype-t5a",
    )
}

/// libusb backend: AOA control with a 250 ms timeout and a 1500 ms re-enumeration wait.
#[derive(Debug, Default)]
pub struct RusbPhoneBackend;

impl UsbPhoneBackend for RusbPhoneBackend {
    type Io = RusbClaimedBulkIo<rusb::Context>;

    fn scan(&mut self) -> Result<Vec<AoaObservedDevice>, UsbProbeError> {
        let failed =
            |error: rusb::Error| UsbProbeError::UsbControlTransferFailed(error.to_string());
        let context = rusb::Context::new().map_err(failed)?;
        let devices = context.devices().map_err(failed)?;
        Ok(devices
            .iter()
            .filter_map(|device| {
                let descriptor = device.device_descriptor().ok()?;
                let identifier = DeviceIdentifier::VidPid {
                    vendor_id: descriptor.vendor_id(),
                    product_id: descriptor.product_id(),
                };
                let location = physical_location_for_rusb_device(&device).ok();
                Some(AoaObservedDevice::new(identifier, location))
            })
            .collect())
    }

    fn switch_to_accessory(&mut self, device: &AoaObservedDevice) -> Result<(), UsbProbeError> {
        let options = HostAoaControlOptions::new(
            *device.identifier(),
            Duration::from_millis(250),
            ReenumerationWait::bounded(Duration::from_millis(1500)),
        );
        LiveAoaControlRunner::new(RusbUsbDeviceRegistry::default())
            .start_accessory_and_poll(
                &chinchillacam_accessory_identity(),
                options,
                AoaAccessoryReenumerationPoller::rusb(),
            )
            .map(|_| ())
    }

    fn open_accessory(&mut self, device: &AoaObservedDevice) -> Result<Self::Io, UsbProbeError> {
        let bound = RusbAoaAccessoryHandleRegistry::default()
            .open_bound_accessory_handle(device.required_physical_location()?)?;
        let (handle, identifier, _) = bound.into_parts();
        RusbClaimedBulkIo::claim_accessory(handle, identifier)
    }
}

/// Serves already-read bytes before the inner stream; writes and the idle timeout delegate.
#[derive(Debug)]
pub struct PeekedStream<S> {
    prefix: Vec<u8>,
    served: usize,
    inner: S,
}

impl<S> PeekedStream<S> {
    pub fn new(prefix: Vec<u8>, inner: S) -> Self {
        Self {
            prefix,
            served: 0,
            inner,
        }
    }
}

impl<S: Read> Read for PeekedStream<S> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        let left = &self.prefix[self.served..];
        if left.is_empty() {
            return self.inner.read(out);
        }
        let count = left.len().min(out.len());
        out[..count].copy_from_slice(&left[..count]);
        self.served += count;
        Ok(count)
    }
}

impl<S: Write> Write for PeekedStream<S> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.inner.write(bytes)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

impl<S: IdleReadTimeoutControl> IdleReadTimeoutControl for PeekedStream<S> {
    fn set_idle_read_timeout(
        &mut self,
        idle_read_timeout: Option<Duration>,
    ) -> Result<(), UsbProbeError> {
        self.inner.set_idle_read_timeout(idle_read_timeout)
    }
}
