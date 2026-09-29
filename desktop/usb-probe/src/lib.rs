mod desktop_receiver;
mod desktop_tls_identity;
mod encoded_video_sink;
mod h264_access_unit;
mod h264_config;
mod loopback_pairing_proof_server;
mod pairing_proof_endpoint;
mod pairing_proof_protocol;
mod pairing_qr;
mod pairing_qr_issuer;
mod phone_client_cert_verifier;
mod session_frame;
mod trusted_phone_store;
mod usb_tls_ciphertext_stream;
mod usb_tls_pairing_proof;
mod video_decoder;
mod video_fragment_reassembler;

pub use desktop_receiver::{
    receive_desktop_video_frame, DesktopReceiverError, DesktopVideoSessionReceiver,
    StaticFrameKindClassifier, VideoFrameKindClassifier, USB_SESSION_FRAME_STREAM_ID,
};
pub use desktop_tls_identity::{
    DesktopTlsIdentity, DesktopTlsIdentityError, DesktopTlsIdentityStore,
};
pub use encoded_video_sink::{
    BoundedEncodedVideoQueue, EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind,
    EncodedVideoSink, EncodedVideoSinkError, PresentationTimestamp,
};
pub use h264_access_unit::{
    convert_h264_access_unit_to_length_prefixed, H264AccessUnitError, MAX_H264_ACCESS_UNIT_BYTES,
    MAX_H264_ACCESS_UNIT_NAL_UNITS,
};
pub use h264_config::{
    parse_h264_config, H264ConfigError, H264InputFraming, H264ParameterSets, MAX_H264_CONFIG_BYTES,
    MAX_H264_CONFIG_NAL_UNITS, MAX_H264_PARAMETER_SET_BYTES,
};
pub use loopback_pairing_proof_server::{
    LoopbackPairingProofServer, LoopbackPairingProofServerError,
};
pub use pairing_proof_endpoint::{PairingProofEndpoint, PairingProofEndpointError};
pub use pairing_proof_protocol::{
    PairingProofFrame, PairingProofProtocolError, PairingProofRequest, PairingProofResponse,
};
pub use pairing_qr::{PairingQrError, PairingQrPayload, PairingQrProducer};
pub use pairing_qr_issuer::{
    IssuedPairingQr, OsPairingQrNonceGenerator, PairingQrIssuer, PairingQrIssuerError,
    PairingQrNonceGenerator,
};
pub use phone_client_cert_verifier::{
    is_canonical_p256_spki, phone_id_for_spki, PhoneClientCertVerifier, TrustedPhoneLookup,
    TrustedPhoneLookupError, TrustedPhoneStatus,
};
pub use session_frame::{
    SessionFrame, SessionFrameCodec, SessionFrameDecodeError, SessionFrameEncodeError,
    SessionFramePayload, VideoFrameKind,
};
pub use trusted_phone_store::{
    FileTrustedPhoneStore, TrustUnlessRevoked, TrustedPhoneIdentity, TrustedPhoneStoreError,
    TrustedPhoneStoreWriteCoordinator,
};
pub use usb_tls_ciphertext_stream::{
    UsbTlsCiphertextStream, USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES,
    USB_TLS_CIPHERTEXT_MAX_PENDING_READ_BYTES, USB_TLS_CIPHERTEXT_STREAM_ID,
};
pub use usb_tls_pairing_proof::{
    complete_trusted_phone_handshake, CompletedPairingProof, CompletedTrustedHandshake,
    PairedPhoneCandidate, PairedPhoneCandidateConfirmError, UsbTlsPairingProofError,
    UsbTlsPairingProofServer,
};
pub use video_decoder::{DecodingEncodedVideoSink, VideoDecoder, VideoDecoderError};
pub use video_fragment_reassembler::{
    ReassembledVideoChunk, VideoFragmentReassembler, VideoFragmentReassemblerError,
};

use std::{
    collections::VecDeque,
    fmt,
    marker::PhantomData,
    thread,
    time::{Duration, Instant},
};

use rusb::UsbContext;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AoaProtocolVersion(u16);

impl AoaProtocolVersion {
    pub fn new(value: u16) -> Result<Self, UsbProbeError> {
        if value == 0 {
            return Err(UsbProbeError::UnsupportedProtocolVersion(value));
        }

        Ok(Self(value))
    }

    pub fn value(self) -> u16 {
        self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UsbProbeError {
    UnsupportedProtocolVersion(u16),
    InvalidAccessoryIdentity,
    UsbControlTransferFailed(String),
    MissingExplicitDeviceIdentifier,
    InvalidDeviceIdentifier(String),
    InvalidBulkEndpointClaim,
    BulkInterfaceNotFound,
    BulkInterfaceAmbiguous,
    BulkInterfaceClaimFailed(String),
    BulkAlternateSettingFailed(String),
    BulkInterfaceNotClaimed,
    ActiveConfigurationUnavailable,
    InvalidBulkTransferBudget,
    EmptyBulkFrame,
    OversizeBulkFrame { length: usize, max: usize },
    BulkFrameHeaderTruncated,
    BulkFramePayloadTruncated { expected: usize, actual: usize },
    BulkShortWrite,
    BulkTransferCountExceeded { count: usize, limit: usize },
    UsbBulkTransferFailed(String),
    SelectedDeviceNotFound(DeviceIdentifier),
    InvalidReenumerationWait,
    PhysicalIdentityUnavailable,
    AoaReenumerationAmbiguous,
    AoaReenumerationTimedOut,
    AoaHandlePhysicalIdentityMismatch,
}

pub fn parse_protocol_version_response(
    little_endian_bytes: [u8; 2],
) -> Result<AoaProtocolVersion, UsbProbeError> {
    AoaProtocolVersion::new(u16::from_le_bytes(little_endian_bytes))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccessoryIdentity {
    manufacturer: String,
    model: String,
    description: String,
    version: String,
    uri: String,
    serial: String,
}

impl AccessoryIdentity {
    pub fn new(
        manufacturer: impl Into<String>,
        model: impl Into<String>,
        description: impl Into<String>,
        version: impl Into<String>,
        uri: impl Into<String>,
        serial: impl Into<String>,
    ) -> Self {
        Self {
            manufacturer: manufacturer.into(),
            model: model.into(),
            description: description.into(),
            version: version.into(),
            uri: uri.into(),
            serial: serial.into(),
        }
    }

    pub fn is_valid_for_aoa_handshake(&self) -> bool {
        !self.manufacturer.trim().is_empty()
            && !self.model.trim().is_empty()
            && !self.description.trim().is_empty()
            && !self.version.trim().is_empty()
    }

    fn aoa_identity_strings(&self) -> [(&str, u16); 6] {
        [
            (&self.manufacturer, 0),
            (&self.model, 1),
            (&self.description, 2),
            (&self.version, 3),
            (&self.uri, 4),
            (&self.serial, 5),
        ]
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceSummary {
    vendor_id: u16,
    product_id: u16,
    protocol_version: AoaProtocolVersion,
}

impl DeviceSummary {
    pub fn new(vendor_id: u16, product_id: u16, protocol_version: AoaProtocolVersion) -> Self {
        Self {
            vendor_id,
            product_id,
            protocol_version,
        }
    }
}

impl fmt::Display for DeviceSummary {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "USB device {:04x}:{:04x} reports AOA protocol v{}; hardware transport remains unvalidated",
            self.vendor_id,
            self.product_id,
            self.protocol_version.value()
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceIdentifier {
    VidPid { vendor_id: u16, product_id: u16 },
}

impl DeviceIdentifier {
    pub fn vendor_id(&self) -> u16 {
        match self {
            Self::VidPid { vendor_id, .. } => *vendor_id,
        }
    }

    pub fn product_id(&self) -> u16 {
        match self {
            Self::VidPid { product_id, .. } => *product_id,
        }
    }

    pub fn parse_required(input: Option<&str>) -> Result<Self, UsbProbeError> {
        let input = input.ok_or(UsbProbeError::MissingExplicitDeviceIdentifier)?;
        Self::parse_vid_pid(input)
    }

    pub fn parse_vid_pid(input: &str) -> Result<Self, UsbProbeError> {
        let (vendor, product) = input
            .split_once(':')
            .ok_or_else(|| UsbProbeError::InvalidDeviceIdentifier(input.to_string()))?;

        if vendor.len() != 4 || product.len() != 4 {
            return Err(UsbProbeError::InvalidDeviceIdentifier(input.to_string()));
        }

        let vendor_id = u16::from_str_radix(vendor, 16)
            .map_err(|_| UsbProbeError::InvalidDeviceIdentifier(input.to_string()))?;
        let product_id = u16::from_str_radix(product, 16)
            .map_err(|_| UsbProbeError::InvalidDeviceIdentifier(input.to_string()))?;

        Ok(Self::VidPid {
            vendor_id,
            product_id,
        })
    }
}

impl fmt::Display for DeviceIdentifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::VidPid {
                vendor_id,
                product_id,
            } => write!(formatter, "{vendor_id:04x}:{product_id:04x}"),
        }
    }
}

pub const AOA_ACCESSORY_DEVICE_IDS: [DeviceIdentifier; 2] = [
    DeviceIdentifier::VidPid {
        vendor_id: 0x18d1,
        product_id: 0x2d00,
    },
    DeviceIdentifier::VidPid {
        vendor_id: 0x18d1,
        product_id: 0x2d01,
    },
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbPhysicalLocation {
    bus_number: u8,
    port_path: Vec<u8>,
}

impl UsbPhysicalLocation {
    pub fn new(bus_number: u8, port_path: Vec<u8>) -> Result<Self, UsbProbeError> {
        if port_path.is_empty() {
            return Err(UsbProbeError::PhysicalIdentityUnavailable);
        }

        Ok(Self {
            bus_number,
            port_path,
        })
    }

    pub fn bus_number(&self) -> u8 {
        self.bus_number
    }

    pub fn port_path(&self) -> &[u8] {
        &self.port_path
    }
}

impl fmt::Display for UsbPhysicalLocation {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let ports = self
            .port_path
            .iter()
            .map(u8::to_string)
            .collect::<Vec<_>>()
            .join(".");
        write!(formatter, "bus {} ports {ports}", self.bus_number)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AoaObservedDevice {
    identifier: DeviceIdentifier,
    physical_location: Option<UsbPhysicalLocation>,
}

impl AoaObservedDevice {
    pub fn new(
        identifier: DeviceIdentifier,
        physical_location: Option<UsbPhysicalLocation>,
    ) -> Self {
        Self {
            identifier,
            physical_location,
        }
    }

    pub fn identifier(&self) -> &DeviceIdentifier {
        &self.identifier
    }

    pub fn physical_location(&self) -> Option<&UsbPhysicalLocation> {
        self.physical_location.as_ref()
    }

    pub fn required_physical_location(&self) -> Result<&UsbPhysicalLocation, UsbProbeError> {
        self.physical_location
            .as_ref()
            .ok_or(UsbProbeError::PhysicalIdentityUnavailable)
    }
}

impl fmt::Display for AoaObservedDevice {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.physical_location {
            Some(location) => write!(formatter, "{} at {location}", self.identifier),
            None => write!(
                formatter,
                "{} with unavailable physical location",
                self.identifier
            ),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoundAoaAccessoryHandle<I = ()> {
    io: I,
    identifier: DeviceIdentifier,
    physical_location: UsbPhysicalLocation,
    bulk_interface_claimed: bool,
}

impl BoundAoaAccessoryHandle<()> {
    pub fn metadata_only(
        identifier: DeviceIdentifier,
        physical_location: UsbPhysicalLocation,
    ) -> Self {
        Self::new((), identifier, physical_location)
    }
}

impl<I> BoundAoaAccessoryHandle<I> {
    pub fn new(
        io: I,
        identifier: DeviceIdentifier,
        physical_location: UsbPhysicalLocation,
    ) -> Self {
        Self {
            io,
            identifier,
            physical_location,
            bulk_interface_claimed: false,
        }
    }

    pub fn io(&self) -> &I {
        &self.io
    }

    pub fn identifier(&self) -> &DeviceIdentifier {
        &self.identifier
    }

    pub fn physical_location(&self) -> &UsbPhysicalLocation {
        &self.physical_location
    }

    pub fn bulk_interface_claimed(&self) -> bool {
        self.bulk_interface_claimed
    }

    pub fn into_parts(self) -> (I, DeviceIdentifier, UsbPhysicalLocation) {
        (self.io, self.identifier, self.physical_location)
    }
}

pub trait AoaAccessoryHandleRegistry {
    type Io;

    fn open_bound_accessory_handle(
        &mut self,
        physical_location: &UsbPhysicalLocation,
    ) -> Result<BoundAoaAccessoryHandle<Self::Io>, UsbProbeError>;
}

#[derive(Debug, Clone)]
pub struct RecordingAoaAccessoryHandleRegistry {
    observed_devices: Vec<AoaObservedDevice>,
    opened_handle_location: Option<UsbPhysicalLocation>,
    open_attempts: Vec<DeviceIdentifier>,
}

impl RecordingAoaAccessoryHandleRegistry {
    pub fn with_observed_devices(observed_devices: Vec<AoaObservedDevice>) -> Self {
        Self {
            observed_devices,
            opened_handle_location: None,
            open_attempts: Vec::new(),
        }
    }

    pub fn with_opened_handle_location(mut self, physical_location: UsbPhysicalLocation) -> Self {
        self.opened_handle_location = Some(physical_location);
        self
    }

    pub fn open_attempts(&self) -> &[DeviceIdentifier] {
        &self.open_attempts
    }
}

impl AoaAccessoryHandleRegistry for RecordingAoaAccessoryHandleRegistry {
    type Io = ();

    fn open_bound_accessory_handle(
        &mut self,
        physical_location: &UsbPhysicalLocation,
    ) -> Result<BoundAoaAccessoryHandle<Self::Io>, UsbProbeError> {
        let mut matches = Vec::new();
        for device in self
            .observed_devices
            .iter()
            .filter(|device| AOA_ACCESSORY_DEVICE_IDS.contains(device.identifier()))
        {
            let location = device.required_physical_location()?;
            if location == physical_location {
                matches.push(device.clone());
            }
        }

        if matches.len() > 1 {
            return Err(UsbProbeError::AoaReenumerationAmbiguous);
        }

        let Some(device) = matches.pop() else {
            return Err(UsbProbeError::AoaReenumerationTimedOut);
        };
        self.open_attempts.push(*device.identifier());
        let opened_location = self
            .opened_handle_location
            .clone()
            .unwrap_or_else(|| physical_location.clone());
        if &opened_location != physical_location {
            return Err(UsbProbeError::AoaHandlePhysicalIdentityMismatch);
        }

        Ok(BoundAoaAccessoryHandle::metadata_only(
            *device.identifier(),
            opened_location,
        ))
    }
}

#[derive(Debug, Default)]
pub struct RusbAoaAccessoryHandleRegistry {
    _context_type: PhantomData<rusb::Context>,
}

impl AoaAccessoryHandleRegistry for RusbAoaAccessoryHandleRegistry {
    type Io = rusb::DeviceHandle<rusb::Context>;

    fn open_bound_accessory_handle(
        &mut self,
        physical_location: &UsbPhysicalLocation,
    ) -> Result<BoundAoaAccessoryHandle<Self::Io>, UsbProbeError> {
        let context = rusb::Context::new()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
        let devices = context
            .devices()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
        let mut matches = Vec::new();

        for device in devices.iter() {
            let Ok(descriptor) = device.device_descriptor() else {
                continue;
            };
            let identifier = DeviceIdentifier::VidPid {
                vendor_id: descriptor.vendor_id(),
                product_id: descriptor.product_id(),
            };
            if !AOA_ACCESSORY_DEVICE_IDS.contains(&identifier) {
                continue;
            }
            let location = physical_location_for_rusb_device(&device)?;
            if &location == physical_location {
                matches.push((device, identifier));
            }
        }

        if matches.len() > 1 {
            return Err(UsbProbeError::AoaReenumerationAmbiguous);
        }
        let Some((device, identifier)) = matches.pop() else {
            return Err(UsbProbeError::AoaReenumerationTimedOut);
        };

        let handle = device
            .open()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
        let opened_location = physical_location_for_rusb_device(&handle.device())?;
        if &opened_location != physical_location {
            return Err(UsbProbeError::AoaHandlePhysicalIdentityMismatch);
        }

        Ok(BoundAoaAccessoryHandle::new(
            handle,
            identifier,
            opened_location,
        ))
    }
}

#[derive(Debug, Clone)]
enum AoaAccessoryReenumerationPollerMode {
    Fake {
        snapshots: Vec<Vec<AoaObservedDevice>>,
        attempts: usize,
        observed_timeouts: Vec<Duration>,
    },
    Rusb,
}

#[derive(Debug, Clone)]
pub struct AoaAccessoryReenumerationPoller {
    mode: AoaAccessoryReenumerationPollerMode,
}

impl AoaAccessoryReenumerationPoller {
    pub fn fake_with_snapshots(snapshots: Vec<Vec<DeviceIdentifier>>) -> Self {
        let snapshots = snapshots
            .into_iter()
            .map(|snapshot| {
                snapshot
                    .into_iter()
                    .map(|identifier| AoaObservedDevice::new(identifier, None))
                    .collect()
            })
            .collect();
        Self::fake_with_observed_snapshots(snapshots)
    }

    pub fn fake_with_observed_snapshots(snapshots: Vec<Vec<AoaObservedDevice>>) -> Self {
        Self {
            mode: AoaAccessoryReenumerationPollerMode::Fake {
                snapshots,
                attempts: 0,
                observed_timeouts: Vec::new(),
            },
        }
    }

    pub fn rusb() -> Self {
        Self {
            mode: AoaAccessoryReenumerationPollerMode::Rusb,
        }
    }

    pub fn poll_until_observed(
        &mut self,
        wait: &ReenumerationWait,
    ) -> Result<DeviceIdentifier, UsbProbeError> {
        match &mut self.mode {
            AoaAccessoryReenumerationPollerMode::Fake {
                snapshots,
                attempts,
                observed_timeouts,
            } => {
                observed_timeouts.push(wait.timeout());
                for attempt_index in 0..wait.max_attempts() {
                    *attempts += 1;
                    let snapshot = snapshots
                        .get(attempt_index)
                        .map(Vec::as_slice)
                        .unwrap_or(&[]);
                    if let Some(device) = snapshot
                        .iter()
                        .find(|device| Self::is_aoa_accessory_device(device.identifier()))
                    {
                        return Ok(*device.identifier());
                    }
                }
                Err(UsbProbeError::AoaReenumerationTimedOut)
            }
            AoaAccessoryReenumerationPollerMode::Rusb => {
                let deadline = Instant::now() + wait.timeout();
                for attempt_index in 0..wait.max_attempts() {
                    if let Some(device) = enumerate_rusb_aoa_accessory_once()? {
                        return Ok(device);
                    }
                    if attempt_index + 1 < wait.max_attempts() {
                        let now = Instant::now();
                        if now >= deadline {
                            break;
                        }
                        thread::sleep(wait.poll_interval().min(deadline - now));
                    }
                }
                Err(UsbProbeError::AoaReenumerationTimedOut)
            }
        }
    }

    pub fn poll_until_bound_to_location(
        &mut self,
        physical_location: &UsbPhysicalLocation,
        wait: &ReenumerationWait,
    ) -> Result<AoaObservedDevice, UsbProbeError> {
        match &mut self.mode {
            AoaAccessoryReenumerationPollerMode::Fake {
                snapshots,
                attempts,
                observed_timeouts,
            } => {
                observed_timeouts.push(wait.timeout());
                for attempt_index in 0..wait.max_attempts() {
                    *attempts += 1;
                    let snapshot = snapshots
                        .get(attempt_index)
                        .map(Vec::as_slice)
                        .unwrap_or(&[]);
                    if let Some(bound) =
                        Self::bound_snapshot_to_location(snapshot, physical_location)?
                    {
                        return Ok(bound);
                    }
                }
                Err(UsbProbeError::AoaReenumerationTimedOut)
            }
            AoaAccessoryReenumerationPollerMode::Rusb => {
                let deadline = Instant::now() + wait.timeout();
                for attempt_index in 0..wait.max_attempts() {
                    let snapshot = enumerate_rusb_aoa_observations_once()?;
                    if let Some(bound) =
                        Self::bound_snapshot_to_location(&snapshot, physical_location)?
                    {
                        return Ok(bound);
                    }
                    if attempt_index + 1 < wait.max_attempts() {
                        let now = Instant::now();
                        if now >= deadline {
                            break;
                        }
                        thread::sleep(wait.poll_interval().min(deadline - now));
                    }
                }
                Err(UsbProbeError::AoaReenumerationTimedOut)
            }
        }
    }

    pub fn attempts(&self) -> usize {
        match &self.mode {
            AoaAccessoryReenumerationPollerMode::Fake { attempts, .. } => *attempts,
            AoaAccessoryReenumerationPollerMode::Rusb => 0,
        }
    }

    pub fn observed_timeouts(&self) -> &[Duration] {
        match &self.mode {
            AoaAccessoryReenumerationPollerMode::Fake {
                observed_timeouts, ..
            } => observed_timeouts,
            AoaAccessoryReenumerationPollerMode::Rusb => &[],
        }
    }

    fn is_aoa_accessory_device(device: &DeviceIdentifier) -> bool {
        AOA_ACCESSORY_DEVICE_IDS.contains(device)
    }

    fn bound_snapshot_to_location(
        snapshot: &[AoaObservedDevice],
        physical_location: &UsbPhysicalLocation,
    ) -> Result<Option<AoaObservedDevice>, UsbProbeError> {
        let mut unavailable_identity_seen = false;
        let mut matches = snapshot
            .iter()
            .filter(|device| Self::is_aoa_accessory_device(device.identifier()))
            .filter_map(|device| match &device.physical_location {
                Some(location) if location == physical_location => Some(Ok(device.clone())),
                Some(_) => None,
                None => {
                    unavailable_identity_seen = true;
                    None
                }
            })
            .collect::<Result<Vec<_>, UsbProbeError>>()?;

        if unavailable_identity_seen {
            return Err(UsbProbeError::PhysicalIdentityUnavailable);
        }
        if matches.len() > 1 {
            return Err(UsbProbeError::AoaReenumerationAmbiguous);
        }
        if let Some(device) = matches.pop() {
            return Ok(Some(device));
        }
        Ok(None)
    }
}

fn enumerate_rusb_aoa_accessory_once() -> Result<Option<DeviceIdentifier>, UsbProbeError> {
    let context = rusb::Context::new()
        .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
    let devices = context
        .devices()
        .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;

    for device in devices.iter() {
        let descriptor = device
            .device_descriptor()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
        let identifier = DeviceIdentifier::VidPid {
            vendor_id: descriptor.vendor_id(),
            product_id: descriptor.product_id(),
        };
        if AOA_ACCESSORY_DEVICE_IDS.contains(&identifier) {
            return Ok(Some(identifier));
        }
    }

    Ok(None)
}

fn enumerate_rusb_aoa_observations_once() -> Result<Vec<AoaObservedDevice>, UsbProbeError> {
    let context = rusb::Context::new()
        .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
    let devices = context
        .devices()
        .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
    let mut observations = Vec::new();

    for device in devices.iter() {
        let Ok(descriptor) = device.device_descriptor() else {
            continue;
        };
        let identifier = DeviceIdentifier::VidPid {
            vendor_id: descriptor.vendor_id(),
            product_id: descriptor.product_id(),
        };
        if AOA_ACCESSORY_DEVICE_IDS.contains(&identifier) {
            let physical_location = physical_location_for_rusb_device(&device).ok();
            observations.push(AoaObservedDevice::new(identifier, physical_location));
        }
    }

    Ok(observations)
}

fn physical_location_for_rusb_device<C>(
    device: &rusb::Device<C>,
) -> Result<UsbPhysicalLocation, UsbProbeError>
where
    C: rusb::UsbContext,
{
    let port_path = device
        .port_numbers()
        .map_err(|_| UsbProbeError::PhysicalIdentityUnavailable)?;
    UsbPhysicalLocation::new(device.bus_number(), port_path)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DryRunAoaPlan {
    selected_device: DeviceIdentifier,
    steps: Vec<String>,
}

impl DryRunAoaPlan {
    pub fn selected_device(&self) -> &DeviceIdentifier {
        &self.selected_device
    }

    pub fn steps(&self) -> &[String] {
        &self.steps
    }
}

#[derive(Debug, Default)]
pub struct DryRunAoaPlanner;

impl DryRunAoaPlanner {
    pub fn plan(
        selected_device: Option<&str>,
        identity: &AccessoryIdentity,
    ) -> Result<DryRunAoaPlan, UsbProbeError> {
        if !identity.is_valid_for_aoa_handshake() {
            return Err(UsbProbeError::InvalidAccessoryIdentity);
        }

        let selected_device = DeviceIdentifier::parse_required(selected_device)?;
        let selected_device_text = selected_device.to_string();

        Ok(DryRunAoaPlan {
            selected_device,
            steps: vec![
                format!(
                    "select explicit USB device {selected_device_text}; do not send AOA control requests to arbitrary devices"
                ),
                "AOA control read GET_PROTOCOL request=51 length=2".to_string(),
                "AOA control write SEND_STRING indexes 0..5 with NUL-terminated identity".to_string(),
                "AOA control write START_ACCESSORY request=53".to_string(),
                "wait for disconnect/reconnect re-enumeration before searching for Google AOA VID/PID or claimed bulk endpoints".to_string(),
                "dry-run only: no hardware is opened and no bulk endpoint is claimed".to_string(),
            ],
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkEndpointClaim {
    in_endpoint: u8,
    out_endpoint: u8,
}

impl BulkEndpointClaim {
    pub fn new(in_endpoint: u8, out_endpoint: u8) -> Result<Self, UsbProbeError> {
        if in_endpoint & 0x80 == 0 || out_endpoint & 0x80 != 0 || out_endpoint == 0 {
            return Err(UsbProbeError::InvalidBulkEndpointClaim);
        }

        Ok(Self {
            in_endpoint,
            out_endpoint,
        })
    }

    pub fn in_endpoint(&self) -> u8 {
        self.in_endpoint
    }

    pub fn out_endpoint(&self) -> u8 {
        self.out_endpoint
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BulkTransferKind {
    Bulk,
    Interrupt,
    Control,
    Isochronous,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BulkEndpointDescriptor {
    address: u8,
    transfer_kind: BulkTransferKind,
}

impl BulkEndpointDescriptor {
    pub fn new(address: u8, transfer_kind: BulkTransferKind) -> Result<Self, UsbProbeError> {
        if address & 0x0f == 0 {
            return Err(UsbProbeError::InvalidBulkEndpointClaim);
        }
        Ok(Self {
            address,
            transfer_kind,
        })
    }

    fn is_bulk_in(self) -> bool {
        self.transfer_kind == BulkTransferKind::Bulk && self.address & 0x80 != 0
    }

    fn is_bulk_out(self) -> bool {
        self.transfer_kind == BulkTransferKind::Bulk && self.address & 0x80 == 0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkInterfaceDescriptor {
    interface_number: u8,
    alternate_setting: u8,
    endpoints: Vec<BulkEndpointDescriptor>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkInterfaceClaim {
    interface_number: u8,
    endpoints: BulkEndpointClaim,
}

impl BulkInterfaceClaim {
    pub fn candidate_descriptor(
        interface_number: u8,
        endpoints: Vec<BulkEndpointDescriptor>,
    ) -> BulkInterfaceDescriptor {
        Self::candidate_descriptor_with_alt(interface_number, 0, endpoints)
    }

    pub fn candidate_descriptor_with_alt(
        interface_number: u8,
        alternate_setting: u8,
        endpoints: Vec<BulkEndpointDescriptor>,
    ) -> BulkInterfaceDescriptor {
        BulkInterfaceDescriptor {
            interface_number,
            alternate_setting,
            endpoints,
        }
    }

    pub fn from_accessory_descriptors(
        identifier: &DeviceIdentifier,
        active_configuration_verified: bool,
        descriptors: &[BulkInterfaceDescriptor],
    ) -> Result<Self, UsbProbeError> {
        if !active_configuration_verified {
            return Err(UsbProbeError::ActiveConfigurationUnavailable);
        }

        let valid_candidates = Self::valid_bulk_candidates(descriptors)?;
        match identifier {
            DeviceIdentifier::VidPid {
                vendor_id: 0x18d1,
                product_id: 0x2d00,
            } => Self::single_accessory_interface(valid_candidates, false),
            DeviceIdentifier::VidPid {
                vendor_id: 0x18d1,
                product_id: 0x2d01,
            } => Self::single_accessory_interface(valid_candidates, true),
            _ => Err(UsbProbeError::BulkInterfaceNotFound),
        }
    }

    pub fn from_descriptors(
        descriptors: &[BulkInterfaceDescriptor],
    ) -> Result<Self, UsbProbeError> {
        let mut candidates = Self::valid_bulk_candidates(descriptors)?;
        if candidates.len() > 1 {
            return Err(UsbProbeError::BulkInterfaceAmbiguous);
        }
        candidates.pop().ok_or(UsbProbeError::BulkInterfaceNotFound)
    }

    fn valid_bulk_candidates(
        descriptors: &[BulkInterfaceDescriptor],
    ) -> Result<Vec<Self>, UsbProbeError> {
        let mut candidates = Vec::new();
        for descriptor in descriptors
            .iter()
            .filter(|descriptor| descriptor.alternate_setting == 0)
        {
            let bulk_in = descriptor
                .endpoints
                .iter()
                .copied()
                .filter(|endpoint| endpoint.is_bulk_in())
                .collect::<Vec<_>>();
            let bulk_out = descriptor
                .endpoints
                .iter()
                .copied()
                .filter(|endpoint| endpoint.is_bulk_out())
                .collect::<Vec<_>>();

            if bulk_in.is_empty() || bulk_out.is_empty() {
                continue;
            }
            if bulk_in.len() > 1 || bulk_out.len() > 1 {
                return Err(UsbProbeError::BulkInterfaceAmbiguous);
            }

            let endpoints = BulkEndpointClaim::new(bulk_in[0].address, bulk_out[0].address)?;
            candidates.push(Self {
                interface_number: descriptor.interface_number,
                endpoints,
            });
        }
        Ok(candidates)
    }

    fn single_accessory_interface(
        candidates: Vec<Self>,
        adb_interface_allowed: bool,
    ) -> Result<Self, UsbProbeError> {
        let mut accessory_candidates = Vec::new();
        for candidate in candidates {
            match candidate.interface_number {
                0 => accessory_candidates.push(candidate),
                1 if adb_interface_allowed => {}
                _ => return Err(UsbProbeError::BulkInterfaceAmbiguous),
            }
        }

        if accessory_candidates.len() > 1 {
            return Err(UsbProbeError::BulkInterfaceAmbiguous);
        }
        accessory_candidates
            .pop()
            .ok_or(UsbProbeError::BulkInterfaceNotFound)
    }

    pub fn interface_number(&self) -> u8 {
        self.interface_number
    }

    pub fn endpoints(&self) -> &BulkEndpointClaim {
        &self.endpoints
    }
}

pub trait BulkInterfaceClaimer {
    fn claim_bulk_interface(&mut self) -> Result<BulkInterfaceClaim, UsbProbeError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkInterfaceClaimState {
    claim: BulkInterfaceClaim,
    alt_zero_attempts: Vec<u8>,
    released: bool,
    read_timeouts: Vec<Duration>,
    write_timeouts: Vec<Duration>,
}

impl BulkInterfaceClaimState {
    fn new(claim: BulkInterfaceClaim) -> Self {
        Self {
            claim,
            alt_zero_attempts: Vec::new(),
            released: false,
            read_timeouts: Vec::new(),
            write_timeouts: Vec::new(),
        }
    }

    pub fn endpoints(&self) -> &BulkEndpointClaim {
        self.claim.endpoints()
    }

    pub fn alt_zero_attempts(&self) -> &[u8] {
        &self.alt_zero_attempts
    }

    pub fn released(&self) -> bool {
        self.released
    }

    pub fn read_timeouts(&self) -> &[Duration] {
        &self.read_timeouts
    }

    pub fn write_timeouts(&self) -> &[Duration] {
        &self.write_timeouts
    }
}

#[derive(Debug, Clone)]
pub struct RecordingClaimedBulkInterface {
    state: BulkInterfaceClaimState,
    io: RecordingUsbBulkIo,
    alt_zero_result: Result<(), UsbProbeError>,
    alt_zero_selected: bool,
}

impl RecordingClaimedBulkInterface {
    pub fn new(claim: BulkInterfaceClaim, io: RecordingUsbBulkIo) -> Self {
        Self {
            state: BulkInterfaceClaimState::new(claim),
            io,
            alt_zero_result: Ok(()),
            alt_zero_selected: false,
        }
    }

    pub fn with_alt_zero_result(mut self, result: Result<(), UsbProbeError>) -> Self {
        self.alt_zero_result = result;
        self
    }

    pub fn select_alt_zero(&mut self) -> Result<(), UsbProbeError> {
        self.state
            .alt_zero_attempts
            .push(self.state.claim.interface_number());
        match self.alt_zero_result.clone() {
            Ok(()) => {
                self.alt_zero_selected = true;
                Ok(())
            }
            Err(error) => {
                self.state.released = true;
                Err(error)
            }
        }
    }

    pub fn claim_state(&self) -> &BulkInterfaceClaimState {
        &self.state
    }

    pub fn into_bulk_io(self) -> Result<RecordingClaimedBulkIo, UsbProbeError> {
        if !self.alt_zero_selected || self.state.released {
            return Err(UsbProbeError::BulkInterfaceNotClaimed);
        }
        Ok(RecordingClaimedBulkIo {
            state: self.state,
            io: self.io,
        })
    }
}

#[derive(Debug, Clone)]
pub struct RecordingClaimedBulkIo {
    state: BulkInterfaceClaimState,
    io: RecordingUsbBulkIo,
}

impl RecordingClaimedBulkIo {
    pub fn claim_state(&self) -> &BulkInterfaceClaimState {
        &self.state
    }
}

impl UsbBulkIo for RecordingClaimedBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.state.read_timeouts.push(timeout);
        self.io.read_bulk(buffer, timeout)
    }

    fn write_bulk(&mut self, bytes: &[u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.state.write_timeouts.push(timeout);
        self.io.write_bulk(bytes, timeout)
    }
}

#[derive(Debug)]
pub struct RusbClaimedBulkIo<C>
where
    C: rusb::UsbContext,
{
    handle: rusb::DeviceHandle<C>,
    claim: BulkInterfaceClaim,
}

impl<C> RusbClaimedBulkIo<C>
where
    C: rusb::UsbContext,
{
    pub fn claim_accessory(
        mut handle: rusb::DeviceHandle<C>,
        identifier: DeviceIdentifier,
    ) -> Result<Self, UsbProbeError> {
        let claim = {
            let mut claimer = RusbBulkInterfaceClaimer::for_accessory(&mut handle, identifier);
            claimer.claim_accessory_bulk_interface()?
        };
        if let Err(error) = handle.set_alternate_setting(claim.interface_number(), 0) {
            let _ = handle.release_interface(claim.interface_number());
            return Err(UsbProbeError::BulkAlternateSettingFailed(error.to_string()));
        }

        Ok(Self { handle, claim })
    }

    pub fn claim(&self) -> &BulkInterfaceClaim {
        &self.claim
    }
}

impl<C> Drop for RusbClaimedBulkIo<C>
where
    C: rusb::UsbContext,
{
    fn drop(&mut self) {
        let _ = self.handle.release_interface(self.claim.interface_number());
    }
}

impl<C> UsbBulkIo for RusbClaimedBulkIo<C>
where
    C: rusb::UsbContext,
{
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.handle
            .read_bulk(self.claim.endpoints().in_endpoint(), buffer, timeout)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))
    }

    fn write_bulk(&mut self, bytes: &[u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.handle
            .write_bulk(self.claim.endpoints().out_endpoint(), bytes, timeout)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))
    }
}

#[derive(Debug, Clone)]
pub struct RecordingBulkInterfaceClaimer {
    identifier: Option<DeviceIdentifier>,
    active_configuration_verified: bool,
    descriptors: Vec<BulkInterfaceDescriptor>,
    claim_attempts: Vec<u8>,
}

impl RecordingBulkInterfaceClaimer {
    pub fn with_descriptors(descriptors: Vec<BulkInterfaceDescriptor>) -> Self {
        Self {
            identifier: None,
            active_configuration_verified: true,
            descriptors,
            claim_attempts: Vec::new(),
        }
    }

    pub fn with_active_device_descriptors(
        identifier: DeviceIdentifier,
        descriptors: Vec<BulkInterfaceDescriptor>,
    ) -> Self {
        Self {
            identifier: Some(identifier),
            active_configuration_verified: true,
            descriptors,
            claim_attempts: Vec::new(),
        }
    }

    pub fn without_active_configuration(
        identifier: DeviceIdentifier,
        descriptors: Vec<BulkInterfaceDescriptor>,
    ) -> Self {
        Self {
            identifier: Some(identifier),
            active_configuration_verified: false,
            descriptors,
            claim_attempts: Vec::new(),
        }
    }

    pub fn claim_accessory_bulk_interface(&mut self) -> Result<BulkInterfaceClaim, UsbProbeError> {
        let identifier = self
            .identifier
            .as_ref()
            .ok_or(UsbProbeError::BulkInterfaceNotFound)?;
        let claim = BulkInterfaceClaim::from_accessory_descriptors(
            identifier,
            self.active_configuration_verified,
            &self.descriptors,
        )?;
        self.claim_attempts.push(claim.interface_number());
        Ok(claim)
    }

    pub fn claim_attempts(&self) -> &[u8] {
        &self.claim_attempts
    }
}

impl BulkInterfaceClaimer for RecordingBulkInterfaceClaimer {
    fn claim_bulk_interface(&mut self) -> Result<BulkInterfaceClaim, UsbProbeError> {
        let claim = BulkInterfaceClaim::from_descriptors(&self.descriptors)?;
        self.claim_attempts.push(claim.interface_number());
        Ok(claim)
    }
}

#[derive(Debug)]
pub struct RusbBulkInterfaceClaimer<'a, C>
where
    C: rusb::UsbContext,
{
    handle: &'a mut rusb::DeviceHandle<C>,
    identifier: Option<DeviceIdentifier>,
}

impl<'a, C> RusbBulkInterfaceClaimer<'a, C>
where
    C: rusb::UsbContext,
{
    pub fn new(handle: &'a mut rusb::DeviceHandle<C>) -> Self {
        Self {
            handle,
            identifier: None,
        }
    }

    pub fn for_accessory(
        handle: &'a mut rusb::DeviceHandle<C>,
        identifier: DeviceIdentifier,
    ) -> Self {
        Self {
            handle,
            identifier: Some(identifier),
        }
    }

    fn descriptors_from_active_configuration(
        &self,
    ) -> Result<Vec<BulkInterfaceDescriptor>, UsbProbeError> {
        let device = self.handle.device();
        let config = device
            .active_config_descriptor()
            .map_err(|_| UsbProbeError::ActiveConfigurationUnavailable)?;
        let mut descriptors = Vec::new();

        for interface in config.interfaces() {
            for descriptor in interface.descriptors() {
                let mut endpoints = Vec::new();
                for endpoint in descriptor.endpoint_descriptors() {
                    endpoints.push(BulkEndpointDescriptor::new(
                        endpoint.address(),
                        match endpoint.transfer_type() {
                            rusb::TransferType::Bulk => BulkTransferKind::Bulk,
                            rusb::TransferType::Interrupt => BulkTransferKind::Interrupt,
                            rusb::TransferType::Control => BulkTransferKind::Control,
                            rusb::TransferType::Isochronous => BulkTransferKind::Isochronous,
                        },
                    )?);
                }
                descriptors.push(BulkInterfaceClaim::candidate_descriptor_with_alt(
                    descriptor.interface_number(),
                    descriptor.setting_number(),
                    endpoints,
                ));
            }
        }

        Ok(descriptors)
    }

    pub fn claim_accessory_bulk_interface(&mut self) -> Result<BulkInterfaceClaim, UsbProbeError> {
        let identifier = self
            .identifier
            .ok_or(UsbProbeError::BulkInterfaceNotFound)?;
        let descriptors = self.descriptors_from_active_configuration()?;
        let claim =
            BulkInterfaceClaim::from_accessory_descriptors(&identifier, true, &descriptors)?;
        self.handle
            .claim_interface(claim.interface_number())
            .map_err(|error| UsbProbeError::BulkInterfaceClaimFailed(error.to_string()))?;
        Ok(claim)
    }
}

impl<C> BulkInterfaceClaimer for RusbBulkInterfaceClaimer<'_, C>
where
    C: rusb::UsbContext,
{
    fn claim_bulk_interface(&mut self) -> Result<BulkInterfaceClaim, UsbProbeError> {
        let descriptors = self.descriptors_from_active_configuration()?;
        let claim = BulkInterfaceClaim::from_descriptors(&descriptors)?;
        self.handle
            .claim_interface(claim.interface_number())
            .map_err(|error| UsbProbeError::BulkInterfaceClaimFailed(error.to_string()))?;
        Ok(claim)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkTransportBoundary {
    claimed_endpoints: BulkEndpointClaim,
    opens_hardware: bool,
}

impl BulkTransportBoundary {
    pub fn dry_run(claimed_endpoints: BulkEndpointClaim) -> Self {
        Self {
            claimed_endpoints,
            opens_hardware: false,
        }
    }

    pub fn claimed_endpoints(&self) -> &BulkEndpointClaim {
        &self.claimed_endpoints
    }

    pub fn opens_hardware(&self) -> bool {
        self.opens_hardware
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkFrame {
    stream_id: u32,
    payload: Vec<u8>,
}

impl BulkFrame {
    pub fn new(stream_id: u32, payload: Vec<u8>) -> Result<Self, UsbProbeError> {
        if payload.is_empty() {
            return Err(UsbProbeError::EmptyBulkFrame);
        }

        Ok(Self { stream_id, payload })
    }

    pub fn stream_id(&self) -> u32 {
        self.stream_id
    }

    pub fn payload(&self) -> &[u8] {
        &self.payload
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut encoded = Vec::with_capacity(8 + self.payload.len());
        encoded.extend_from_slice(&self.stream_id.to_le_bytes());
        encoded.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        encoded.extend_from_slice(&self.payload);
        encoded
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameTransferBudget {
    timeout: Duration,
    max_payload_len: usize,
    max_io_attempts: usize,
}

impl FrameTransferBudget {
    pub fn new(
        timeout: Duration,
        max_payload_len: usize,
        max_io_attempts: usize,
    ) -> Result<Self, UsbProbeError> {
        if max_payload_len == 0 || max_payload_len > u32::MAX as usize || max_io_attempts == 0 {
            return Err(UsbProbeError::InvalidBulkTransferBudget);
        }

        Ok(Self {
            timeout,
            max_payload_len,
            max_io_attempts,
        })
    }

    pub fn timeout(&self) -> Duration {
        self.timeout
    }

    pub fn max_payload_len(&self) -> usize {
        self.max_payload_len
    }

    pub fn max_io_attempts(&self) -> usize {
        self.max_io_attempts
    }
}

pub trait UsbBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError>;
    fn write_bulk(&mut self, bytes: &[u8], timeout: Duration) -> Result<usize, UsbProbeError>;
}

#[derive(Debug, Clone)]
pub struct RecordingUsbBulkIo {
    read_chunks: Vec<Result<Vec<u8>, UsbProbeError>>,
    read_residual: VecDeque<u8>,
    read_attempts: usize,
    source_read_attempts: usize,
    max_write_chunk: usize,
    write_attempts: usize,
    written_bytes: Vec<u8>,
}

impl RecordingUsbBulkIo {
    pub fn with_read_chunks(read_chunks: Vec<Result<Vec<u8>, UsbProbeError>>) -> Self {
        Self {
            read_chunks,
            read_residual: VecDeque::new(),
            read_attempts: 0,
            source_read_attempts: 0,
            max_write_chunk: usize::MAX,
            write_attempts: 0,
            written_bytes: Vec::new(),
        }
    }

    pub fn for_writes_with_max_chunk(max_write_chunk: usize) -> Self {
        Self {
            read_chunks: Vec::new(),
            read_residual: VecDeque::new(),
            read_attempts: 0,
            source_read_attempts: 0,
            max_write_chunk,
            write_attempts: 0,
            written_bytes: Vec::new(),
        }
    }

    pub fn read_attempts(&self) -> usize {
        self.read_attempts
    }

    pub fn source_read_attempts(&self) -> usize {
        self.source_read_attempts
    }

    pub fn write_attempts(&self) -> usize {
        self.write_attempts
    }

    pub fn written_bytes(&self) -> &[u8] {
        &self.written_bytes
    }
}

impl UsbBulkIo for RecordingUsbBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.read_attempts += 1;
        let mut copied = 0;

        while copied < buffer.len() {
            let Some(byte) = self.read_residual.pop_front() else {
                break;
            };
            buffer[copied] = byte;
            copied += 1;
        }
        if copied > 0 || buffer.is_empty() {
            return Ok(copied);
        }

        if self.read_chunks.is_empty() {
            return Ok(0);
        }

        self.source_read_attempts += 1;
        let chunk = self.read_chunks.remove(0)?;
        let to_copy = chunk.len().min(buffer.len());
        buffer[..to_copy].copy_from_slice(&chunk[..to_copy]);
        self.read_residual.extend(chunk[to_copy..].iter().copied());
        Ok(to_copy)
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.write_attempts += 1;
        let written = bytes.len().min(self.max_write_chunk);
        self.written_bytes.extend_from_slice(&bytes[..written]);
        Ok(written)
    }
}

#[derive(Debug, Clone)]
pub struct FramedUsbStream<I> {
    io: I,
    budget: FrameTransferBudget,
}

impl<I> FramedUsbStream<I>
where
    I: UsbBulkIo,
{
    pub fn new(io: I, budget: FrameTransferBudget) -> Self {
        Self { io, budget }
    }

    pub fn read_frame(&mut self) -> Result<BulkFrame, UsbProbeError> {
        let mut header = [0; 8];
        let header_read = self.read_exact_bounded(&mut header)?;
        if header_read < header.len() {
            return Err(UsbProbeError::BulkFrameHeaderTruncated);
        }

        let stream_id = u32::from_le_bytes(header[0..4].try_into().expect("fixed header slice"));
        let payload_len_u32 =
            u32::from_le_bytes(header[4..8].try_into().expect("fixed header slice"));
        let payload_len =
            usize::try_from(payload_len_u32).map_err(|_| UsbProbeError::OversizeBulkFrame {
                length: usize::MAX,
                max: self.budget.max_payload_len(),
            })?;
        if payload_len > self.budget.max_payload_len() {
            return Err(UsbProbeError::OversizeBulkFrame {
                length: payload_len,
                max: self.budget.max_payload_len(),
            });
        }

        let mut payload = vec![0; payload_len];
        let payload_read = self.read_exact_bounded(&mut payload)?;
        if payload_read < payload_len {
            return Err(UsbProbeError::BulkFramePayloadTruncated {
                expected: payload_len,
                actual: payload_read,
            });
        }

        BulkFrame::new(stream_id, payload)
    }

    pub fn write_frame(&mut self, frame: &BulkFrame) -> Result<(), UsbProbeError> {
        let payload_len = frame.payload().len();
        if payload_len > self.budget.max_payload_len() {
            return Err(UsbProbeError::OversizeBulkFrame {
                length: payload_len,
                max: self.budget.max_payload_len(),
            });
        }

        let encoded = frame.encode();
        let mut written = 0;
        for _ in 0..self.budget.max_io_attempts() {
            if written == encoded.len() {
                return Ok(());
            }
            let remaining = encoded.len() - written;
            let count = self
                .io
                .write_bulk(&encoded[written..], self.budget.timeout())?;
            if count > remaining {
                return Err(UsbProbeError::BulkTransferCountExceeded {
                    count,
                    limit: remaining,
                });
            }
            if count == 0 {
                return Err(UsbProbeError::BulkShortWrite);
            }
            written += count;
        }

        if written == encoded.len() {
            Ok(())
        } else {
            Err(UsbProbeError::BulkShortWrite)
        }
    }

    pub fn io(&self) -> &I {
        &self.io
    }

    fn read_exact_bounded(&mut self, buffer: &mut [u8]) -> Result<usize, UsbProbeError> {
        let mut read = 0;
        for _ in 0..self.budget.max_io_attempts() {
            if read == buffer.len() {
                return Ok(read);
            }
            let remaining = buffer.len() - read;
            let count = self
                .io
                .read_bulk(&mut buffer[read..], self.budget.timeout())?;
            if count > remaining {
                return Err(UsbProbeError::BulkTransferCountExceeded {
                    count,
                    limit: remaining,
                });
            }
            if count == 0 {
                return Ok(read);
            }
            read += count;
        }
        Ok(read)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReenumerationWait {
    timeout: Duration,
    poll_interval: Duration,
    max_attempts: usize,
}

impl ReenumerationWait {
    pub fn bounded(timeout: Duration) -> Self {
        let poll_interval = Duration::from_millis(50);
        let timeout_ms = timeout.as_millis();
        let interval_ms = poll_interval.as_millis().max(1);
        let attempts = timeout_ms
            .saturating_add(interval_ms - 1)
            .checked_div(interval_ms)
            .unwrap_or(1)
            .max(1);
        let max_attempts = attempts.min(usize::MAX as u128) as usize;
        Self {
            timeout,
            poll_interval,
            max_attempts,
        }
    }

    pub fn timeout(&self) -> Duration {
        self.timeout
    }

    pub fn poll_interval(&self) -> Duration {
        self.poll_interval
    }

    pub fn max_attempts(&self) -> usize {
        self.max_attempts
    }

    pub fn description(&self) -> String {
        format!(
            "bounded post-START poll up to {}ms across at most {} attempts; bulk I/O remains unclaimed",
            self.timeout.as_millis(),
            self.max_attempts
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostAoaControlOptions {
    selected_device: DeviceIdentifier,
    control_timeout: Duration,
    reenumeration_wait: ReenumerationWait,
}

impl HostAoaControlOptions {
    pub fn new(
        selected_device: DeviceIdentifier,
        control_timeout: Duration,
        reenumeration_wait: ReenumerationWait,
    ) -> Self {
        Self {
            selected_device,
            control_timeout,
            reenumeration_wait,
        }
    }

    pub fn selected_device(&self) -> &DeviceIdentifier {
        &self.selected_device
    }

    pub fn control_timeout(&self) -> Duration {
        self.control_timeout
    }

    pub fn reenumeration_wait(&self) -> &ReenumerationWait {
        &self.reenumeration_wait
    }
}

pub trait ControlRequestSnapshot {
    fn control_requests_snapshot(&self) -> Vec<AoaControlRequest>;
}

#[derive(Debug, Clone)]
pub struct SelectedUsbDevice<I> {
    io: I,
    physical_location: UsbPhysicalLocation,
}

impl<I> SelectedUsbDevice<I> {
    pub fn new(io: I, physical_location: UsbPhysicalLocation) -> Self {
        Self {
            io,
            physical_location,
        }
    }

    pub fn into_parts(self) -> (I, UsbPhysicalLocation) {
        (self.io, self.physical_location)
    }
}

pub trait SelectedUsbDeviceRegistry {
    type Io: UsbControlIo + ControlRequestSnapshot;

    fn open_selected_device(
        &mut self,
        selected_device: &DeviceIdentifier,
    ) -> Result<SelectedUsbDevice<Self::Io>, UsbProbeError>;
}

#[derive(Debug, Clone)]
pub struct RecordingUsbDeviceRegistry {
    available_device: Option<DeviceIdentifier>,
    physical_location: Option<UsbPhysicalLocation>,
    get_protocol_response: [u8; 2],
    open_attempts: Vec<DeviceIdentifier>,
    fallback_enumeration_attempts: usize,
}

impl RecordingUsbDeviceRegistry {
    pub fn with_device(available_device: DeviceIdentifier, get_protocol_response: [u8; 2]) -> Self {
        Self {
            available_device: Some(available_device),
            physical_location: None,
            get_protocol_response,
            open_attempts: Vec::new(),
            fallback_enumeration_attempts: 0,
        }
    }

    pub fn with_device_at_location(
        available_device: DeviceIdentifier,
        physical_location: UsbPhysicalLocation,
        get_protocol_response: [u8; 2],
    ) -> Self {
        Self {
            available_device: Some(available_device),
            physical_location: Some(physical_location),
            get_protocol_response,
            open_attempts: Vec::new(),
            fallback_enumeration_attempts: 0,
        }
    }

    pub fn without_devices() -> Self {
        Self {
            available_device: None,
            physical_location: None,
            get_protocol_response: [0, 0],
            open_attempts: Vec::new(),
            fallback_enumeration_attempts: 0,
        }
    }

    pub fn open_attempts(&self) -> &[DeviceIdentifier] {
        &self.open_attempts
    }

    pub fn fallback_enumeration_attempts(&self) -> usize {
        self.fallback_enumeration_attempts
    }
}

impl SelectedUsbDeviceRegistry for RecordingUsbDeviceRegistry {
    type Io = RecordingUsbControlIo;

    fn open_selected_device(
        &mut self,
        selected_device: &DeviceIdentifier,
    ) -> Result<SelectedUsbDevice<Self::Io>, UsbProbeError> {
        self.open_attempts.push(*selected_device);
        if self.available_device.as_ref() == Some(selected_device) {
            let physical_location = self
                .physical_location
                .clone()
                .ok_or(UsbProbeError::PhysicalIdentityUnavailable)?;
            Ok(SelectedUsbDevice::new(
                RecordingUsbControlIo::with_get_protocol_response(self.get_protocol_response),
                physical_location,
            ))
        } else {
            Err(UsbProbeError::SelectedDeviceNotFound(*selected_device))
        }
    }
}

#[derive(Debug, Default)]
pub struct RusbUsbDeviceRegistry {
    _context_type: PhantomData<rusb::Context>,
}

impl SelectedUsbDeviceRegistry for RusbUsbDeviceRegistry {
    type Io = RusbDeviceControlIo<rusb::Context>;

    fn open_selected_device(
        &mut self,
        selected_device: &DeviceIdentifier,
    ) -> Result<SelectedUsbDevice<Self::Io>, UsbProbeError> {
        let context = rusb::Context::new()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
        let devices = context
            .devices()
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;

        for device in devices.iter() {
            let descriptor = device
                .device_descriptor()
                .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))?;
            if descriptor.vendor_id() == selected_device.vendor_id()
                && descriptor.product_id() == selected_device.product_id()
            {
                let physical_location = physical_location_for_rusb_device(&device)?;
                return device
                    .open()
                    .map(RusbDeviceControlIo::new)
                    .map(|io| SelectedUsbDevice::new(io, physical_location))
                    .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()));
            }
        }

        Err(UsbProbeError::SelectedDeviceNotFound(*selected_device))
    }
}

#[derive(Debug, Clone)]
pub struct LiveAoaControlResult<R> {
    registry: R,
    selected_device: DeviceIdentifier,
    protocol: AoaProtocolVersion,
    reenumeration_wait_description: String,
    control_requests: Vec<AoaControlRequest>,
    accessory_device: Option<AoaObservedDevice>,
    reenumeration_poll_attempts: usize,
}

impl<R> LiveAoaControlResult<R> {
    pub fn registry(&self) -> &R {
        &self.registry
    }

    pub fn selected_device(&self) -> &DeviceIdentifier {
        &self.selected_device
    }

    pub fn protocol(&self) -> AoaProtocolVersion {
        self.protocol
    }

    pub fn reenumeration_wait_description(&self) -> &str {
        &self.reenumeration_wait_description
    }

    pub fn control_requests(&self) -> &[AoaControlRequest] {
        &self.control_requests
    }

    pub fn accessory_device(&self) -> Option<&AoaObservedDevice> {
        self.accessory_device.as_ref()
    }

    pub fn aoa_reenumeration_observed(&self) -> bool {
        self.accessory_device.is_some()
    }

    pub fn reenumeration_poll_attempts(&self) -> usize {
        self.reenumeration_poll_attempts
    }
}

#[derive(Debug, Clone)]
pub struct LiveAoaControlRunner<R> {
    registry: R,
}

impl<R> LiveAoaControlRunner<R>
where
    R: SelectedUsbDeviceRegistry,
{
    pub fn new(registry: R) -> Self {
        Self { registry }
    }

    pub fn start_accessory_and_poll(
        mut self,
        identity: &AccessoryIdentity,
        options: HostAoaControlOptions,
        mut reenumeration_poller: AoaAccessoryReenumerationPoller,
    ) -> Result<LiveAoaControlResult<R>, UsbProbeError> {
        let selected_device = *options.selected_device();
        let selected_usb_device = self.registry.open_selected_device(&selected_device)?;
        let (io, pre_start_physical_location) = selected_usb_device.into_parts();
        let transport = RusbAoaControlTransport::new(io, options.control_timeout());
        let mut controller = AoaHostController::new(transport);
        let protocol = controller.start_accessory_mode(identity)?;
        let control_requests = controller.transport().io.control_requests_snapshot();
        let accessory_device = reenumeration_poller.poll_until_bound_to_location(
            &pre_start_physical_location,
            options.reenumeration_wait(),
        )?;
        let reenumeration_poll_attempts = reenumeration_poller.attempts();

        Ok(LiveAoaControlResult {
            registry: self.registry,
            selected_device,
            protocol,
            reenumeration_wait_description: format!(
                "AOA re-enumeration observed as {accessory_device} after bounded post-START poll"
            ),
            control_requests,
            accessory_device: Some(accessory_device),
            reenumeration_poll_attempts,
        })
    }
}

#[derive(Debug, Default)]
pub struct UsbHostBoundary {
    _rusb_context_type: PhantomData<rusb::Context>,
}

impl UsbHostBoundary {
    pub fn backend_notice(&self) -> &'static str {
        "rusb/libusb host boundary; unit tests do not enumerate USB hardware"
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AoaOperation {
    GetProtocol,
    SendIdentityString { index: u16, value: String },
    StartAccessory,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AoaControlRequest {
    pub request_type: u8,
    pub request: u8,
    pub value: u16,
    pub index: u16,
    pub data: Vec<u8>,
    pub read_length: usize,
    pub timeout: Duration,
}

impl AoaControlRequest {
    pub fn read(
        request_type: u8,
        request: u8,
        value: u16,
        index: u16,
        read_length: usize,
        timeout: Duration,
    ) -> Self {
        Self {
            request_type,
            request,
            value,
            index,
            data: Vec::new(),
            read_length,
            timeout,
        }
    }

    pub fn write(
        request_type: u8,
        request: u8,
        value: u16,
        index: u16,
        data: &[u8],
        timeout: Duration,
    ) -> Self {
        Self {
            request_type,
            request,
            value,
            index,
            data: data.to_vec(),
            read_length: 0,
            timeout,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AoaStartOutcome {
    ExpectDeviceReenumeration {
        protocol: AoaProtocolVersion,
        next_step: &'static str,
    },
}

pub trait AoaControlTransport {
    fn get_protocol(&mut self) -> Result<[u8; 2], UsbProbeError>;
    fn send_identity_string(&mut self, index: u16, value: &str) -> Result<(), UsbProbeError>;
    fn start_accessory(&mut self) -> Result<(), UsbProbeError>;
    fn operation_log(&self) -> &[AoaOperation];
}

pub trait UsbControlIo {
    fn read_control(
        &mut self,
        request: &AoaControlRequest,
        buffer: &mut [u8],
    ) -> Result<usize, UsbProbeError>;

    fn write_control(&mut self, request: &AoaControlRequest) -> Result<usize, UsbProbeError>;
}

#[derive(Debug, Clone)]
pub struct RecordingUsbControlIo {
    get_protocol_response: [u8; 2],
    control_requests: Vec<AoaControlRequest>,
}

impl RecordingUsbControlIo {
    pub fn with_get_protocol_response(get_protocol_response: [u8; 2]) -> Self {
        Self {
            get_protocol_response,
            control_requests: Vec::new(),
        }
    }

    pub fn control_requests(&self) -> &[AoaControlRequest] {
        &self.control_requests
    }
}

impl ControlRequestSnapshot for RecordingUsbControlIo {
    fn control_requests_snapshot(&self) -> Vec<AoaControlRequest> {
        self.control_requests.clone()
    }
}

impl UsbControlIo for RecordingUsbControlIo {
    fn read_control(
        &mut self,
        request: &AoaControlRequest,
        buffer: &mut [u8],
    ) -> Result<usize, UsbProbeError> {
        self.control_requests.push(request.clone());
        buffer[..2].copy_from_slice(&self.get_protocol_response);
        Ok(2)
    }

    fn write_control(&mut self, request: &AoaControlRequest) -> Result<usize, UsbProbeError> {
        self.control_requests.push(request.clone());
        Ok(request.data.len())
    }
}

#[derive(Debug)]
pub struct RusbDeviceControlIo<C>
where
    C: rusb::UsbContext,
{
    handle: rusb::DeviceHandle<C>,
}

impl<C> RusbDeviceControlIo<C>
where
    C: rusb::UsbContext,
{
    pub fn new(handle: rusb::DeviceHandle<C>) -> Self {
        Self { handle }
    }
}

impl<C> ControlRequestSnapshot for RusbDeviceControlIo<C>
where
    C: rusb::UsbContext,
{
    fn control_requests_snapshot(&self) -> Vec<AoaControlRequest> {
        Vec::new()
    }
}

impl<C> UsbControlIo for RusbDeviceControlIo<C>
where
    C: rusb::UsbContext,
{
    fn read_control(
        &mut self,
        request: &AoaControlRequest,
        buffer: &mut [u8],
    ) -> Result<usize, UsbProbeError> {
        self.handle
            .read_control(
                request.request_type,
                request.request,
                request.value,
                request.index,
                buffer,
                request.timeout,
            )
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))
    }

    fn write_control(&mut self, request: &AoaControlRequest) -> Result<usize, UsbProbeError> {
        self.handle
            .write_control(
                request.request_type,
                request.request,
                request.value,
                request.index,
                &request.data,
                request.timeout,
            )
            .map_err(|error| UsbProbeError::UsbControlTransferFailed(error.to_string()))
    }
}

#[derive(Debug, Clone)]
pub struct RusbAoaControlTransport<I> {
    io: I,
    timeout: Duration,
    operations: Vec<AoaOperation>,
}

impl<I> RusbAoaControlTransport<I>
where
    I: UsbControlIo,
{
    pub fn new(io: I, timeout: Duration) -> Self {
        Self {
            io,
            timeout,
            operations: Vec::new(),
        }
    }

    pub fn get_protocol(&mut self) -> Result<[u8; 2], UsbProbeError> {
        <Self as AoaControlTransport>::get_protocol(self)
    }
}

impl RusbAoaControlTransport<RecordingUsbControlIo> {
    pub fn control_requests(&self) -> &[AoaControlRequest] {
        self.io.control_requests()
    }
}

impl<I> AoaControlTransport for RusbAoaControlTransport<I>
where
    I: UsbControlIo,
{
    fn get_protocol(&mut self) -> Result<[u8; 2], UsbProbeError> {
        let request = AoaControlRequest::read(0xC0, 51, 0, 0, 2, self.timeout);
        let mut buffer = [0; 2];
        self.io.read_control(&request, &mut buffer)?;
        self.operations.push(AoaOperation::GetProtocol);
        Ok(buffer)
    }

    fn send_identity_string(&mut self, index: u16, value: &str) -> Result<(), UsbProbeError> {
        let mut data = value.as_bytes().to_vec();
        data.push(0);
        let request = AoaControlRequest::write(0x40, 52, 0, index, &data, self.timeout);
        self.io.write_control(&request)?;
        self.operations.push(AoaOperation::SendIdentityString {
            index,
            value: value.to_string(),
        });
        Ok(())
    }

    fn start_accessory(&mut self) -> Result<(), UsbProbeError> {
        let request = AoaControlRequest::write(0x40, 53, 0, 0, b"", self.timeout);
        self.io.write_control(&request)?;
        self.operations.push(AoaOperation::StartAccessory);
        Ok(())
    }

    fn operation_log(&self) -> &[AoaOperation] {
        &self.operations
    }
}

#[derive(Debug, Clone)]
pub struct FakeAoaTransport {
    protocol_response: [u8; 2],
    operations: Vec<AoaOperation>,
}

impl FakeAoaTransport {
    pub fn with_protocol_response(protocol_response: [u8; 2]) -> Self {
        Self {
            protocol_response,
            operations: Vec::new(),
        }
    }
}

impl AoaControlTransport for FakeAoaTransport {
    fn get_protocol(&mut self) -> Result<[u8; 2], UsbProbeError> {
        self.operations.push(AoaOperation::GetProtocol);
        Ok(self.protocol_response)
    }

    fn send_identity_string(&mut self, index: u16, value: &str) -> Result<(), UsbProbeError> {
        self.operations.push(AoaOperation::SendIdentityString {
            index,
            value: value.to_string(),
        });
        Ok(())
    }

    fn start_accessory(&mut self) -> Result<(), UsbProbeError> {
        self.operations.push(AoaOperation::StartAccessory);
        Ok(())
    }

    fn operation_log(&self) -> &[AoaOperation] {
        &self.operations
    }
}

#[derive(Debug, Clone)]
pub struct AoaHostController<T> {
    transport: T,
}

impl<T> AoaHostController<T>
where
    T: AoaControlTransport,
{
    pub fn new(transport: T) -> Self {
        Self { transport }
    }

    pub fn start_accessory_mode(
        &mut self,
        identity: &AccessoryIdentity,
    ) -> Result<AoaProtocolVersion, UsbProbeError> {
        if !identity.is_valid_for_aoa_handshake() {
            return Err(UsbProbeError::InvalidAccessoryIdentity);
        }

        let protocol = parse_protocol_version_response(self.transport.get_protocol()?)?;
        for (value, index) in identity.aoa_identity_strings() {
            self.transport.send_identity_string(index, value)?;
        }
        self.transport.start_accessory()?;

        Ok(protocol)
    }

    pub fn start_accessory_mode_expect_reenumeration(
        &mut self,
        identity: &AccessoryIdentity,
    ) -> Result<AoaStartOutcome, UsbProbeError> {
        let protocol = self.start_accessory_mode(identity)?;

        Ok(AoaStartOutcome::ExpectDeviceReenumeration {
            protocol,
            next_step: "wait for disconnect/reconnect, then search for Google AOA VID/PID or claimed bulk endpoints",
        })
    }

    pub fn operation_log(&self) -> &[AoaOperation] {
        self.transport.operation_log()
    }

    pub fn transport(&self) -> &T {
        &self.transport
    }
}
