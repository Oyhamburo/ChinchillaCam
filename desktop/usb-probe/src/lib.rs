use std::{fmt, marker::PhantomData};

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

#[derive(Debug, Default)]
pub struct UsbHostBoundary {
    _rusb_context_type: PhantomData<rusb::Context>,
}

impl UsbHostBoundary {
    pub fn backend_notice(&self) -> &'static str {
        "rusb/libusb host boundary; unit tests do not enumerate USB hardware"
    }
}
