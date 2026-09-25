use usb_probe::{
    parse_protocol_version_response, AccessoryIdentity, AoaProtocolVersion, DeviceSummary,
    UsbHostBoundary,
};

#[test]
fn parses_little_endian_aoa_protocol_version() {
    let parsed = parse_protocol_version_response([0x02, 0x00]);

    assert_eq!(parsed, AoaProtocolVersion::new(2));
}

#[test]
fn rejects_zero_protocol_version() {
    assert!(parse_protocol_version_response([0x00, 0x00]).is_err());
}

#[test]
fn validates_accessory_identity_required_fields() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "AOA feasibility boundary",
        "0.1.0",
        "https://example.invalid/chinchillacam",
        "prototype",
    );

    assert!(identity.is_valid_for_aoa_handshake());
    assert!(!AccessoryIdentity::new("", "USB Probe", "AOA feasibility boundary", "0.1.0", "", "")
        .is_valid_for_aoa_handshake());
}

#[test]
fn formats_device_summary_without_hardware_claims() {
    let summary = DeviceSummary::new(0x18D1, 0x2D00, AoaProtocolVersion::new(2).unwrap());

    assert_eq!(
        summary.to_string(),
        "USB device 18d1:2d00 reports AOA protocol v2; hardware transport remains unvalidated"
    );
}

#[test]
fn describes_rusb_host_boundary_without_enumerating_hardware() {
    let boundary = UsbHostBoundary::default();

    assert_eq!(
        boundary.backend_notice(),
        "rusb/libusb host boundary; unit tests do not enumerate USB hardware"
    );
}
