use std::{
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
    EmptyBulkFrame,
    SelectedDeviceNotFound(DeviceIdentifier),
    InvalidReenumerationWait,
    AoaReenumerationTimedOut,
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

#[derive(Debug, Clone)]
enum AoaAccessoryReenumerationPollerMode {
    Fake {
        snapshots: Vec<Vec<DeviceIdentifier>>,
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
                    if let Some(device) =
                        snapshot.iter().copied().find(Self::is_aoa_accessory_device)
                    {
                        return Ok(device);
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
        let max_attempts = ((timeout_ms + interval_ms - 1) / interval_ms).max(1) as usize;
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

pub trait SelectedUsbDeviceRegistry {
    type Io: UsbControlIo + ControlRequestSnapshot;

    fn open_selected_device(
        &mut self,
        selected_device: &DeviceIdentifier,
    ) -> Result<Self::Io, UsbProbeError>;
}

#[derive(Debug, Clone)]
pub struct RecordingUsbDeviceRegistry {
    available_device: Option<DeviceIdentifier>,
    get_protocol_response: [u8; 2],
    open_attempts: Vec<DeviceIdentifier>,
    fallback_enumeration_attempts: usize,
}

impl RecordingUsbDeviceRegistry {
    pub fn with_device(available_device: DeviceIdentifier, get_protocol_response: [u8; 2]) -> Self {
        Self {
            available_device: Some(available_device),
            get_protocol_response,
            open_attempts: Vec::new(),
            fallback_enumeration_attempts: 0,
        }
    }

    pub fn without_devices() -> Self {
        Self {
            available_device: None,
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
    ) -> Result<Self::Io, UsbProbeError> {
        self.open_attempts.push(selected_device.clone());
        if self.available_device.as_ref() == Some(selected_device) {
            Ok(RecordingUsbControlIo::with_get_protocol_response(
                self.get_protocol_response,
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
    ) -> Result<Self::Io, UsbProbeError> {
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
                return device
                    .open()
                    .map(RusbDeviceControlIo::new)
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
    accessory_device: Option<DeviceIdentifier>,
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

    pub fn accessory_device(&self) -> Option<&DeviceIdentifier> {
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
        let io = self.registry.open_selected_device(&selected_device)?;
        let transport = RusbAoaControlTransport::new(io, options.control_timeout());
        let mut controller = AoaHostController::new(transport);
        let protocol = controller.start_accessory_mode(identity)?;
        let control_requests = controller.transport().io.control_requests_snapshot();
        let accessory_device =
            reenumeration_poller.poll_until_observed(options.reenumeration_wait())?;
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
