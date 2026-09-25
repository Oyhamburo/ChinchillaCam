use std::time::Duration;

use usb_probe::{
    parse_protocol_version_response, AccessoryIdentity, AoaControlRequest, AoaHostController,
    AoaOperation, AoaProtocolVersion, AoaStartOutcome, BulkEndpointClaim, BulkFrame,
    BulkTransportBoundary, DeviceIdentifier, DeviceSummary, DryRunAoaPlanner, FakeAoaTransport,
    RecordingUsbControlIo, RusbAoaControlTransport, UsbHostBoundary,
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

#[test]
fn rusb_adapter_maps_get_protocol_to_vendor_device_in_control_request() {
    let io = RecordingUsbControlIo::with_get_protocol_response([0x02, 0x00]);
    let mut transport = RusbAoaControlTransport::new(io, Duration::from_millis(250));

    assert_eq!(transport.get_protocol().unwrap(), [0x02, 0x00]);

    assert_eq!(
        transport.control_requests(),
        &[AoaControlRequest::read(
            0xC0,
            51,
            0,
            0,
            2,
            Duration::from_millis(250)
        )]
    );
}

#[test]
fn rusb_adapter_maps_identity_indexes_zero_through_five_to_send_string_requests() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "rusb AOA adapter",
        "0.3.0",
        "https://example.invalid/chinchillacam",
        "prototype-t3",
    );
    let io = RecordingUsbControlIo::with_get_protocol_response([0x02, 0x00]);
    let transport = RusbAoaControlTransport::new(io, Duration::from_millis(250));
    let mut controller = AoaHostController::new(transport);

    controller.start_accessory_mode(&identity).unwrap();

    let send_string_requests: Vec<_> = controller
        .transport()
        .control_requests()
        .iter()
        .filter(|request| request.request == 52)
        .cloned()
        .collect();

    assert_eq!(
        send_string_requests,
        vec![
            AoaControlRequest::write(
                0x40,
                52,
                0,
                0,
                b"ChinchillaCam\0",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(0x40, 52, 0, 1, b"USB Probe\0", Duration::from_millis(250)),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                2,
                b"rusb AOA adapter\0",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(0x40, 52, 0, 3, b"0.3.0\0", Duration::from_millis(250)),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                4,
                b"https://example.invalid/chinchillacam\0",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                5,
                b"prototype-t3\0",
                Duration::from_millis(250)
            ),
        ]
    );
}

#[test]
fn rusb_adapter_maps_start_accessory_and_returns_expected_reenumeration_boundary() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "rusb AOA adapter",
        "0.3.0",
        "https://example.invalid/chinchillacam",
        "prototype-t3",
    );
    let io = RecordingUsbControlIo::with_get_protocol_response([0x02, 0x00]);
    let transport = RusbAoaControlTransport::new(io, Duration::from_millis(250));
    let mut controller = AoaHostController::new(transport);

    let outcome = controller
        .start_accessory_mode_expect_reenumeration(&identity)
        .unwrap();

    assert_eq!(
        controller.transport().control_requests().last(),
        Some(&AoaControlRequest::write(
            0x40,
            53,
            0,
            0,
            b"",
            Duration::from_millis(250)
        ))
    );
    assert_eq!(
        outcome,
        AoaStartOutcome::ExpectDeviceReenumeration {
            protocol: AoaProtocolVersion::new(2).unwrap(),
            next_step: "wait for disconnect/reconnect, then search for Google AOA VID/PID or claimed bulk endpoints"
        }
    );
}

#[test]
fn parses_explicit_vid_pid_device_identifier_and_rejects_missing_or_invalid_input() {
    assert_eq!(
        DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
        DeviceIdentifier::VidPid {
            vendor_id: 0x18d1,
            product_id: 0x2d00
        }
    );
    assert_eq!(
        DeviceIdentifier::parse_vid_pid("18D1:2D01")
            .unwrap()
            .to_string(),
        "18d1:2d01"
    );

    assert!(DeviceIdentifier::parse_required(None).is_err());
    assert!(DeviceIdentifier::parse_vid_pid("18d1").is_err());
    assert!(DeviceIdentifier::parse_vid_pid("zzzz:2d00").is_err());
}

#[test]
fn dry_run_aoa_planner_requires_explicit_device_before_control_plan() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "safe host CLI boundary",
        "0.5.0",
        "https://example.invalid/chinchillacam",
        "prototype-t5a",
    );

    assert!(DryRunAoaPlanner::plan(None, &identity).is_err());

    let plan = DryRunAoaPlanner::plan(Some("18d1:4ee7"), &identity).unwrap();

    assert_eq!(plan.selected_device().to_string(), "18d1:4ee7");
    assert_eq!(
        plan.steps(),
        &[
            "select explicit USB device 18d1:4ee7; do not send AOA control requests to arbitrary devices",
            "AOA control read GET_PROTOCOL request=51 length=2",
            "AOA control write SEND_STRING indexes 0..5 with NUL-terminated identity",
            "AOA control write START_ACCESSORY request=53",
            "wait for disconnect/reconnect re-enumeration before searching for Google AOA VID/PID or claimed bulk endpoints",
            "dry-run only: no hardware is opened and no bulk endpoint is claimed",
        ]
    );
}

#[test]
fn bulk_transport_boundary_models_claimed_endpoints_and_minimal_frame_bytes() {
    let endpoints = BulkEndpointClaim::new(0x81, 0x02).unwrap();
    let boundary = BulkTransportBoundary::dry_run(endpoints.clone());
    let frame = BulkFrame::new(7, vec![0xde, 0xad, 0xbe, 0xef]).unwrap();

    assert_eq!(boundary.claimed_endpoints(), &endpoints);
    assert_eq!(frame.stream_id(), 7);
    assert_eq!(frame.payload(), &[0xde, 0xad, 0xbe, 0xef]);
    assert_eq!(
        frame.encode(),
        vec![7, 0, 0, 0, 4, 0, 0, 0, 0xde, 0xad, 0xbe, 0xef]
    );

    assert!(BulkEndpointClaim::new(0x01, 0x02).is_err());
    assert!(BulkFrame::new(7, Vec::new()).is_err());
}
