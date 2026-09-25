use std::{fmt, marker::PhantomData, time::Duration};

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceIdentifier {
    VidPid { vendor_id: u16, product_id: u16 },
}

impl DeviceIdentifier {
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
