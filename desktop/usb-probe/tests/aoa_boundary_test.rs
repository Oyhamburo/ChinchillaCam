use usb_probe::{
    parse_protocol_version_response, AccessoryIdentity, AoaHostController, AoaOperation,
    AoaProtocolVersion, DeviceSummary, FakeAoaTransport, UsbHostBoundary,
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
    assert!(
        !AccessoryIdentity::new("", "USB Probe", "AOA feasibility boundary", "0.1.0", "", "")
            .is_valid_for_aoa_handshake()
    );
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

#[test]
fn host_control_handshake_sends_identity_in_aoa_order_then_starts_accessory() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "Host-side AOA handshake model",
        "0.2.0",
        "https://example.invalid/chinchillacam",
        "prototype-t2",
    );
    let transport = FakeAoaTransport::with_protocol_response([0x02, 0x00]);
    let mut controller = AoaHostController::new(transport);

    let protocol = controller.start_accessory_mode(&identity).unwrap();

    assert_eq!(protocol, AoaProtocolVersion::new(2).unwrap());
    assert_eq!(
        controller.operation_log(),
        &[
            AoaOperation::GetProtocol,
            AoaOperation::SendIdentityString {
                index: 0,
                value: "ChinchillaCam".to_string()
            },
            AoaOperation::SendIdentityString {
                index: 1,
                value: "USB Probe".to_string()
            },
            AoaOperation::SendIdentityString {
                index: 2,
                value: "Host-side AOA handshake model".to_string()
            },
            AoaOperation::SendIdentityString {
                index: 3,
                value: "0.2.0".to_string()
            },
            AoaOperation::SendIdentityString {
                index: 4,
                value: "https://example.invalid/chinchillacam".to_string()
            },
            AoaOperation::SendIdentityString {
                index: 5,
                value: "prototype-t2".to_string()
            },
            AoaOperation::StartAccessory,
        ]
    );
}

#[test]
fn host_control_handshake_rejects_unsupported_protocol_before_identity_strings() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "Host-side AOA handshake model",
        "0.2.0",
        "https://example.invalid/chinchillacam",
        "prototype-t2",
    );
    let transport = FakeAoaTransport::with_protocol_response([0x00, 0x00]);
    let mut controller = AoaHostController::new(transport);

    assert!(controller.start_accessory_mode(&identity).is_err());
    assert_eq!(controller.operation_log(), &[AoaOperation::GetProtocol]);
}

#[test]
fn host_control_handshake_rejects_incomplete_identity_before_usb_requests() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "",
        "Host-side AOA handshake model",
        "0.2.0",
        "https://example.invalid/chinchillacam",
        "prototype-t2",
    );
    let transport = FakeAoaTransport::with_protocol_response([0x02, 0x00]);
    let mut controller = AoaHostController::new(transport);

    assert!(controller.start_accessory_mode(&identity).is_err());
    assert!(controller.operation_log().is_empty());
}
