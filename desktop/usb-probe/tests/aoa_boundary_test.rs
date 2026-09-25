use std::time::Duration;

use usb_probe::{
    parse_protocol_version_response, AccessoryIdentity, AoaAccessoryHandleRegistry,
    AoaAccessoryReenumerationPoller, AoaControlRequest, AoaHostController, AoaObservedDevice,
    AoaOperation, AoaProtocolVersion, AoaStartOutcome, BulkEndpointClaim, BulkEndpointDescriptor,
    BulkFrame, BulkInterfaceClaim, BulkInterfaceClaimer, BulkTransferKind, BulkTransportBoundary,
    DeviceIdentifier, DeviceSummary, DryRunAoaPlanner, FakeAoaTransport, FrameTransferBudget,
    FramedUsbStream, HostAoaControlOptions, LiveAoaControlRunner,
    RecordingAoaAccessoryHandleRegistry, RecordingBulkInterfaceClaimer, RecordingUsbBulkIo,
    RecordingUsbControlIo, RecordingUsbDeviceRegistry, ReenumerationWait, RusbAoaControlTransport,
    UsbBulkIo, UsbHostBoundary, UsbPhysicalLocation, AOA_ACCESSORY_DEVICE_IDS,
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

#[test]
fn live_host_control_runner_opens_only_the_explicit_selected_device() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "live AOA control runner",
        "0.5.1",
        "https://example.invalid/chinchillacam",
        "prototype-t5b",
    );
    let selected = DeviceIdentifier::parse_vid_pid("18d1:4ee7").unwrap();
    let physical_location = UsbPhysicalLocation::new(3, vec![1, 4]).unwrap();
    let registry = RecordingUsbDeviceRegistry::with_device_at_location(
        selected.clone(),
        physical_location.clone(),
        [0x02, 0x00],
    );
    let options = HostAoaControlOptions::new(
        selected.clone(),
        Duration::from_millis(250),
        ReenumerationWait::bounded(Duration::from_millis(500)),
    );

    let poller = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![vec![
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
            Some(physical_location),
        ),
    ]]);

    let result = LiveAoaControlRunner::new(registry)
        .start_accessory_and_poll(&identity, options, poller)
        .unwrap();

    assert_eq!(result.selected_device(), &selected);
    assert_eq!(result.protocol(), AoaProtocolVersion::new(2).unwrap());
    assert_eq!(
        result.reenumeration_wait_description(),
        "AOA re-enumeration observed as 18d1:2d01 at bus 3 ports 1.4 after bounded post-START poll"
    );
    assert_eq!(
        result.accessory_device().unwrap().to_string(),
        "18d1:2d01 at bus 3 ports 1.4"
    );
    assert_eq!(result.reenumeration_poll_attempts(), 1);
    assert_eq!(result.registry().open_attempts(), &[selected]);
    assert_eq!(result.registry().fallback_enumeration_attempts(), 0);
    assert_eq!(
        result.control_requests(),
        &[
            AoaControlRequest::read(0xC0, 51, 0, 0, 2, Duration::from_millis(250)),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                0,
                b"ChinchillaCam ",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(0x40, 52, 0, 1, b"USB Probe ", Duration::from_millis(250)),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                2,
                b"live AOA control runner ",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(0x40, 52, 0, 3, b"0.5.1 ", Duration::from_millis(250)),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                4,
                b"https://example.invalid/chinchillacam ",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(
                0x40,
                52,
                0,
                5,
                b"prototype-t5b ",
                Duration::from_millis(250)
            ),
            AoaControlRequest::write(0x40, 53, 0, 0, b"", Duration::from_millis(250)),
        ]
    );
}

#[test]
fn live_host_control_runner_fails_without_fallback_when_selected_device_is_absent() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "live AOA control runner",
        "0.5.1",
        "https://example.invalid/chinchillacam",
        "prototype-t5b",
    );
    let selected = DeviceIdentifier::parse_vid_pid("18d1:4ee7").unwrap();
    let registry = RecordingUsbDeviceRegistry::without_devices();
    let options = HostAoaControlOptions::new(
        selected.clone(),
        Duration::from_millis(250),
        ReenumerationWait::bounded(Duration::from_millis(500)),
    );

    let poller = AoaAccessoryReenumerationPoller::fake_with_snapshots(vec![]);

    let error = LiveAoaControlRunner::new(registry)
        .start_accessory_and_poll(&identity, options, poller)
        .unwrap_err();

    assert_eq!(
        error,
        usb_probe::UsbProbeError::SelectedDeviceNotFound(selected)
    );
}

#[test]
fn aoa_accessory_ids_are_google_aoa_vid_pid_values() {
    assert_eq!(
        AOA_ACCESSORY_DEVICE_IDS,
        [
            DeviceIdentifier::VidPid {
                vendor_id: 0x18d1,
                product_id: 0x2d00
            },
            DeviceIdentifier::VidPid {
                vendor_id: 0x18d1,
                product_id: 0x2d01
            },
        ]
    );
}

#[test]
fn fake_reenumeration_poller_records_bounded_attempts_until_accessory_appears() {
    let mut poller = AoaAccessoryReenumerationPoller::fake_with_snapshots(vec![
        vec![],
        vec![DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap()],
    ]);
    let wait = ReenumerationWait::bounded(Duration::from_millis(500));

    let observed = poller.poll_until_observed(&wait).unwrap();

    assert_eq!(observed.to_string(), "18d1:2d01");
    assert_eq!(poller.attempts(), 2);
    assert_eq!(poller.observed_timeouts(), &[Duration::from_millis(500)]);
}

#[test]
fn fake_reenumeration_poller_returns_distinct_timeout_when_accessory_never_appears() {
    let mut poller = AoaAccessoryReenumerationPoller::fake_with_snapshots(vec![vec![], vec![]]);
    let wait = ReenumerationWait::bounded(Duration::from_millis(500));

    let error = poller.poll_until_observed(&wait).unwrap_err();

    assert_eq!(error, usb_probe::UsbProbeError::AoaReenumerationTimedOut);
    assert_eq!(poller.attempts(), wait.max_attempts());
}

#[test]
fn live_host_control_runner_polls_for_aoa_reenumeration_after_start_accessory() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "live AOA re-enumeration poll",
        "0.5.2",
        "https://example.invalid/chinchillacam",
        "prototype-t5c1",
    );
    let selected = DeviceIdentifier::parse_vid_pid("18d1:4ee7").unwrap();
    let physical_location = UsbPhysicalLocation::new(3, vec![1, 4]).unwrap();
    let registry = RecordingUsbDeviceRegistry::with_device_at_location(
        selected.clone(),
        physical_location.clone(),
        [0x02, 0x00],
    );
    let options = HostAoaControlOptions::new(
        selected.clone(),
        Duration::from_millis(250),
        ReenumerationWait::bounded(Duration::from_millis(500)),
    );
    let poller = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![
        vec![],
        vec![AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
            Some(physical_location.clone()),
        )],
    ]);

    let result = LiveAoaControlRunner::new(registry)
        .start_accessory_and_poll(&identity, options, poller)
        .unwrap();

    assert_eq!(result.selected_device(), &selected);
    assert_eq!(
        result.accessory_device().unwrap().to_string(),
        "18d1:2d00 at bus 3 ports 1.4"
    );
    assert_eq!(
        result.accessory_device().unwrap().physical_location(),
        Some(&physical_location)
    );
    assert!(result.aoa_reenumeration_observed());
    assert_eq!(result.reenumeration_poll_attempts(), 2);
}

#[test]
fn reenumeration_poller_binds_aoa_device_to_pre_start_physical_location() {
    let physical_location = UsbPhysicalLocation::new(7, vec![2, 1]).unwrap();
    let unrelated_location = UsbPhysicalLocation::new(7, vec![2, 3]).unwrap();
    let mut poller = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![vec![
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
            Some(unrelated_location),
        ),
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
            Some(physical_location.clone()),
        ),
    ]]);

    let observed = poller
        .poll_until_bound_to_location(
            &physical_location,
            &ReenumerationWait::bounded(Duration::ZERO),
        )
        .unwrap();

    assert_eq!(observed.identifier().to_string(), "18d1:2d01");
    assert_eq!(observed.physical_location(), Some(&physical_location));
    assert_eq!(poller.attempts(), 1);
}

#[test]
fn reenumeration_poller_fails_closed_when_matching_aoa_identity_is_missing_or_ambiguous() {
    let physical_location = UsbPhysicalLocation::new(7, vec![2, 1]).unwrap();
    let other_location = UsbPhysicalLocation::new(7, vec![2, 3]).unwrap();
    let mut missing = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![vec![
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
            Some(other_location),
        ),
    ]]);

    assert_eq!(
        missing
            .poll_until_bound_to_location(
                &physical_location,
                &ReenumerationWait::bounded(Duration::ZERO)
            )
            .unwrap_err(),
        usb_probe::UsbProbeError::AoaReenumerationTimedOut
    );

    let mut ambiguous = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![vec![
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
            Some(physical_location.clone()),
        ),
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
            Some(physical_location.clone()),
        ),
    ]]);

    assert_eq!(
        ambiguous
            .poll_until_bound_to_location(
                &physical_location,
                &ReenumerationWait::bounded(Duration::ZERO)
            )
            .unwrap_err(),
        usb_probe::UsbProbeError::AoaReenumerationAmbiguous
    );
}

#[test]
fn live_runner_fails_closed_when_pre_start_physical_identity_is_unavailable() {
    let identity = AccessoryIdentity::new(
        "ChinchillaCam",
        "USB Probe",
        "live AOA identity guard",
        "0.5.3",
        "https://example.invalid/chinchillacam",
        "prototype-t5c1b",
    );
    let selected = DeviceIdentifier::parse_vid_pid("18d1:4ee7").unwrap();
    let registry = RecordingUsbDeviceRegistry::with_device(selected.clone(), [0x02, 0x00]);
    let options = HostAoaControlOptions::new(
        selected,
        Duration::from_millis(250),
        ReenumerationWait::bounded(Duration::from_millis(500)),
    );
    let poller = AoaAccessoryReenumerationPoller::fake_with_observed_snapshots(vec![vec![]]);

    let error = LiveAoaControlRunner::new(registry)
        .start_accessory_and_poll(&identity, options, poller)
        .unwrap_err();

    assert_eq!(error, usb_probe::UsbProbeError::PhysicalIdentityUnavailable);
}

#[test]
fn reenumeration_wait_zero_timeout_is_bounded_to_one_attempt_without_overflow() {
    let wait = ReenumerationWait::bounded(Duration::ZERO);

    assert_eq!(wait.max_attempts(), 1);
}

#[test]
fn aoa_accessory_handle_registry_opens_only_single_aoa_device_at_bound_physical_location() {
    let bound_location = UsbPhysicalLocation::new(3, vec![1, 4]).unwrap();
    let observed = AoaObservedDevice::new(
        DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
        Some(bound_location.clone()),
    );
    let mut registry = RecordingAoaAccessoryHandleRegistry::with_observed_devices(vec![observed]);

    let handle = registry
        .open_bound_accessory_handle(&bound_location)
        .unwrap();

    assert_eq!(handle.identifier().to_string(), "18d1:2d01");
    assert_eq!(handle.physical_location(), &bound_location);
    assert!(!handle.bulk_interface_claimed());
    assert_eq!(
        registry.open_attempts(),
        &[DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap()]
    );
}

#[test]
fn aoa_accessory_handle_registry_fails_closed_on_missing_or_ambiguous_handle_identity() {
    let bound_location = UsbPhysicalLocation::new(3, vec![1, 4]).unwrap();
    let mut missing_identity =
        RecordingAoaAccessoryHandleRegistry::with_observed_devices(vec![AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
            None,
        )]);

    assert_eq!(
        missing_identity
            .open_bound_accessory_handle(&bound_location)
            .unwrap_err(),
        usb_probe::UsbProbeError::PhysicalIdentityUnavailable
    );

    let mut ambiguous = RecordingAoaAccessoryHandleRegistry::with_observed_devices(vec![
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d00").unwrap(),
            Some(bound_location.clone()),
        ),
        AoaObservedDevice::new(
            DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
            Some(bound_location.clone()),
        ),
    ]);

    assert_eq!(
        ambiguous
            .open_bound_accessory_handle(&bound_location)
            .unwrap_err(),
        usb_probe::UsbProbeError::AoaReenumerationAmbiguous
    );
}

#[test]
fn aoa_accessory_handle_registry_rejects_wrong_opened_handle_physical_location() {
    let bound_location = UsbPhysicalLocation::new(3, vec![1, 4]).unwrap();
    let wrong_open_location = UsbPhysicalLocation::new(3, vec![1, 5]).unwrap();
    let observed = AoaObservedDevice::new(
        DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap(),
        Some(bound_location.clone()),
    );
    let mut registry = RecordingAoaAccessoryHandleRegistry::with_observed_devices(vec![observed])
        .with_opened_handle_location(wrong_open_location);

    let error = registry
        .open_bound_accessory_handle(&bound_location)
        .unwrap_err();

    assert_eq!(
        error,
        usb_probe::UsbProbeError::AoaHandlePhysicalIdentityMismatch
    );
    assert_eq!(
        registry.open_attempts(),
        &[DeviceIdentifier::parse_vid_pid("18d1:2d01").unwrap()]
    );
}

#[test]
fn frame_transfer_budget_rejects_unrepresentable_or_unbounded_limits() {
    assert_eq!(
        FrameTransferBudget::new(Duration::from_millis(250), (u32::MAX as usize) + 1, 1)
            .unwrap_err(),
        usb_probe::UsbProbeError::InvalidBulkTransferBudget
    );
    assert!(FrameTransferBudget::new(Duration::from_millis(250), 16, 0).is_err());
}

#[test]
fn framed_usb_stream_reads_segmented_header_and_payload_until_complete() {
    let frame = BulkFrame::new(9, b"hello".to_vec()).unwrap();
    let encoded = frame.encode();
    let io = RecordingUsbBulkIo::with_read_chunks(vec![
        Ok(encoded[0..3].to_vec()),
        Ok(encoded[3..8].to_vec()),
        Ok(encoded[8..10].to_vec()),
        Ok(encoded[10..].to_vec()),
    ]);
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 8).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    let decoded = stream.read_frame().unwrap();

    assert_eq!(decoded.stream_id(), 9);
    assert_eq!(decoded.payload(), b"hello");
    assert_eq!(stream.io().read_attempts(), 4);
}

#[test]
fn framed_usb_stream_rejects_oversize_length_before_reading_payload() {
    let mut encoded_header = Vec::new();
    encoded_header.extend_from_slice(&7u32.to_le_bytes());
    encoded_header.extend_from_slice(&17u32.to_le_bytes());
    let io = RecordingUsbBulkIo::with_read_chunks(vec![Ok(encoded_header)]);
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 4).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    let error = stream.read_frame().unwrap_err();

    assert_eq!(
        error,
        usb_probe::UsbProbeError::OversizeBulkFrame {
            length: 17,
            max: 16
        }
    );
    assert_eq!(stream.io().read_attempts(), 1);
}

#[test]
fn framed_usb_stream_rejects_truncated_header_and_payload_without_panics() {
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 4).unwrap();
    let mut truncated_header = FramedUsbStream::new(
        RecordingUsbBulkIo::with_read_chunks(vec![Ok(vec![1, 0, 0]), Ok(Vec::new())]),
        budget.clone(),
    );

    assert_eq!(
        truncated_header.read_frame().unwrap_err(),
        usb_probe::UsbProbeError::BulkFrameHeaderTruncated
    );

    let frame = BulkFrame::new(1, b"hello".to_vec()).unwrap();
    let encoded = frame.encode();
    let mut truncated_payload = FramedUsbStream::new(
        RecordingUsbBulkIo::with_read_chunks(vec![
            Ok(encoded[0..8].to_vec()),
            Ok(b"he".to_vec()),
            Ok(Vec::new()),
        ]),
        budget,
    );

    assert_eq!(
        truncated_payload.read_frame().unwrap_err(),
        usb_probe::UsbProbeError::BulkFramePayloadTruncated {
            expected: 5,
            actual: 2
        }
    );
}

#[test]
fn framed_usb_stream_writes_frame_with_partial_writes_until_complete() {
    let frame = BulkFrame::new(11, b"abc".to_vec()).unwrap();
    let io = RecordingUsbBulkIo::for_writes_with_max_chunk(4);
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 8).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    stream.write_frame(&frame).unwrap();

    assert_eq!(stream.io().write_attempts(), 3);
    assert_eq!(stream.io().written_bytes(), frame.encode());
}

#[test]
fn framed_usb_stream_propagates_usb_errors_and_zero_progress_short_writes() {
    let frame = BulkFrame::new(2, b"abc".to_vec()).unwrap();
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 2).unwrap();
    let mut read_error = FramedUsbStream::new(
        RecordingUsbBulkIo::with_read_chunks(vec![Err(
            usb_probe::UsbProbeError::UsbBulkTransferFailed("read boom".to_string()),
        )]),
        budget.clone(),
    );

    assert_eq!(
        read_error.read_frame().unwrap_err(),
        usb_probe::UsbProbeError::UsbBulkTransferFailed("read boom".to_string())
    );

    let mut zero_write =
        FramedUsbStream::new(RecordingUsbBulkIo::for_writes_with_max_chunk(0), budget);

    assert_eq!(
        zero_write.write_frame(&frame).unwrap_err(),
        usb_probe::UsbProbeError::BulkShortWrite
    );
}

#[derive(Debug, Clone)]
struct ContractViolatingBulkIo {
    read_count: usize,
    write_count: usize,
}

impl UsbBulkIo for ContractViolatingBulkIo {
    fn read_bulk(
        &mut self,
        buffer: &mut [u8],
        _timeout: Duration,
    ) -> Result<usize, usb_probe::UsbProbeError> {
        let copy_len = buffer.len().min(1);
        if copy_len > 0 {
            buffer[0] = 0;
        }
        Ok(self.read_count)
    }

    fn write_bulk(
        &mut self,
        _bytes: &[u8],
        _timeout: Duration,
    ) -> Result<usize, usb_probe::UsbProbeError> {
        Ok(self.write_count)
    }
}

#[test]
fn framed_usb_stream_preserves_coalesced_read_residual_for_next_frame() {
    let first = BulkFrame::new(1, b"one".to_vec()).unwrap();
    let second = BulkFrame::new(2, b"two".to_vec()).unwrap();
    let mut coalesced = first.encode();
    coalesced.extend_from_slice(&second.encode());
    let io = RecordingUsbBulkIo::with_read_chunks(vec![Ok(coalesced)]);
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 8).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    let decoded_first = stream.read_frame().unwrap();
    let decoded_second = stream.read_frame().unwrap();

    assert_eq!(decoded_first.stream_id(), 1);
    assert_eq!(decoded_first.payload(), b"one");
    assert_eq!(decoded_second.stream_id(), 2);
    assert_eq!(decoded_second.payload(), b"two");
    assert_eq!(stream.io().source_read_attempts(), 1);
}

#[test]
fn framed_usb_stream_rejects_backend_read_count_larger_than_buffer_without_panic() {
    let io = ContractViolatingBulkIo {
        read_count: 9,
        write_count: 0,
    };
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 8).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    assert_eq!(
        stream.read_frame().unwrap_err(),
        usb_probe::UsbProbeError::BulkTransferCountExceeded { count: 9, limit: 8 }
    );
}

#[test]
fn framed_usb_stream_rejects_backend_write_count_larger_than_remaining_slice_without_panic() {
    let frame = BulkFrame::new(3, b"abc".to_vec()).unwrap();
    let io = ContractViolatingBulkIo {
        read_count: 0,
        write_count: frame.encode().len() + 1,
    };
    let budget = FrameTransferBudget::new(Duration::from_millis(250), 16, 8).unwrap();
    let mut stream = FramedUsbStream::new(io, budget);

    assert_eq!(
        stream.write_frame(&frame).unwrap_err(),
        usb_probe::UsbProbeError::BulkTransferCountExceeded {
            count: frame.encode().len() + 1,
            limit: frame.encode().len()
        }
    );
}

#[test]
fn bulk_interface_claim_selects_single_interface_with_bulk_in_and_out_endpoints() {
    let descriptors = vec![BulkInterfaceClaim::candidate_descriptor(
        2,
        vec![
            BulkEndpointDescriptor::new(0x81, BulkTransferKind::Bulk).unwrap(),
            BulkEndpointDescriptor::new(0x02, BulkTransferKind::Bulk).unwrap(),
        ],
    )];
    let mut claimer = RecordingBulkInterfaceClaimer::with_descriptors(descriptors);

    let claim = claimer.claim_bulk_interface().unwrap();

    assert_eq!(claim.interface_number(), 2);
    assert_eq!(claim.endpoints().in_endpoint(), 0x81);
    assert_eq!(claim.endpoints().out_endpoint(), 0x02);
    assert_eq!(claimer.claim_attempts(), &[2]);
}

#[test]
fn bulk_interface_claim_rejects_missing_wrong_kind_or_ambiguous_bulk_pairs() {
    let mut missing_out = RecordingBulkInterfaceClaimer::with_descriptors(vec![
        BulkInterfaceClaim::candidate_descriptor(
            1,
            vec![BulkEndpointDescriptor::new(0x81, BulkTransferKind::Bulk).unwrap()],
        ),
    ]);
    assert_eq!(
        missing_out.claim_bulk_interface().unwrap_err(),
        usb_probe::UsbProbeError::BulkInterfaceNotFound
    );

    let mut wrong_kind = RecordingBulkInterfaceClaimer::with_descriptors(vec![
        BulkInterfaceClaim::candidate_descriptor(
            1,
            vec![
                BulkEndpointDescriptor::new(0x81, BulkTransferKind::Interrupt).unwrap(),
                BulkEndpointDescriptor::new(0x02, BulkTransferKind::Bulk).unwrap(),
            ],
        ),
    ]);
    assert_eq!(
        wrong_kind.claim_bulk_interface().unwrap_err(),
        usb_probe::UsbProbeError::BulkInterfaceNotFound
    );

    let mut ambiguous = RecordingBulkInterfaceClaimer::with_descriptors(vec![
        BulkInterfaceClaim::candidate_descriptor(
            1,
            vec![
                BulkEndpointDescriptor::new(0x81, BulkTransferKind::Bulk).unwrap(),
                BulkEndpointDescriptor::new(0x02, BulkTransferKind::Bulk).unwrap(),
            ],
        ),
        BulkInterfaceClaim::candidate_descriptor(
            2,
            vec![
                BulkEndpointDescriptor::new(0x83, BulkTransferKind::Bulk).unwrap(),
                BulkEndpointDescriptor::new(0x04, BulkTransferKind::Bulk).unwrap(),
            ],
        ),
    ]);
    assert_eq!(
        ambiguous.claim_bulk_interface().unwrap_err(),
        usb_probe::UsbProbeError::BulkInterfaceAmbiguous
    );

    let mut ambiguous_same_interface = RecordingBulkInterfaceClaimer::with_descriptors(vec![
        BulkInterfaceClaim::candidate_descriptor(
            1,
            vec![
                BulkEndpointDescriptor::new(0x81, BulkTransferKind::Bulk).unwrap(),
                BulkEndpointDescriptor::new(0x82, BulkTransferKind::Bulk).unwrap(),
                BulkEndpointDescriptor::new(0x02, BulkTransferKind::Bulk).unwrap(),
            ],
        ),
    ]);
    assert_eq!(
        ambiguous_same_interface.claim_bulk_interface().unwrap_err(),
        usb_probe::UsbProbeError::BulkInterfaceAmbiguous
    );
}

#[test]
fn bulk_endpoint_descriptor_rejects_endpoint_zero_and_invalid_directions() {
    assert!(BulkEndpointDescriptor::new(0x00, BulkTransferKind::Bulk).is_err());
    assert!(BulkEndpointClaim::new(0x01, 0x02).is_err());
    assert!(BulkEndpointClaim::new(0x81, 0x82).is_err());
}
