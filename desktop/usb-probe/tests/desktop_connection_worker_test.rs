//! Task d4a (`odd/tasks/desktop-production-app.md`, contract section 4.4): the
//! `DesktopConnectionWorker` drives the reconnect path end to end over the USB stack model
//! (`FramedUsbStream` -> `UsbTlsCiphertextStream` -> rustls on a crossed transfer pipe), runs the
//! session pipeline with a fake decoder, and reports typed events. The phone side is a rustls
//! client on the other end of the pipe. Task d4b adds the pairing step: QR, CCP1 proof, short
//! code, confirm/reject/deadline, HELLO rollback and QR refresh.

#[allow(dead_code)]
mod common;

use std::{
    collections::VecDeque,
    io::{ErrorKind, Read, Write},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc, Arc, Mutex,
    },
    thread,
    time::{Duration, Instant, SystemTime},
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use common::usb_transfer_pipe::{crossed_transfer_pair, CrossedTransferBulkIo};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    pairing_short_code_v1, phone_id_for_spki, read_session_frame, write_session_frame,
    DesktopCommand, DesktopConnectionFailure, DesktopConnectionWorker, DesktopEvent,
    DesktopSessionEndReason, DesktopTlsIdentity, DesktopWorkerConfig, DesktopWorkerHandle,
    DesktopWorkerSpawnError, FakeVideoDecoder, FileTrustedPhoneStore, FrameTransferBudget,
    FramedUsbStream, PairingProofFrame, PairingProofRequest, PairingQrIssuer, PhoneConnectionError,
    PhoneLink, PhoneLinkError, RecordingDecodedFrameSink, SessionFrame, SessionFramePayload,
    SessionRuntimeConfig, TrustedPhoneIdentity, UsbTlsCiphertextStream,
};

use usb_probe::quality_control::{
    encode_quality_state, parse_set_quality, CameraSelection, QualityMode, QualityState,
    QUALITY_CONTROL_CAPABILITY,
};

const SESSION_ID: &str = "desktop-worker-usb";
const HELLO_SEQUENCE: i32 = 1;
const PHONE_LABEL: &str = "Reconnecting Phone";
const PAIRED_LABEL: &str = "Teléfono de prueba";
const FRAME_READ_TIMEOUT: Duration = Duration::from_millis(1000);
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const EVENT_TIMEOUT: Duration = Duration::from_secs(8);
const PHONE_SEND_INTERVAL: Duration = Duration::from_millis(30);

type UsbStream = UsbTlsCiphertextStream<CrossedTransferBulkIo>;
type PhoneStream = StreamOwned<ClientConnection, UsbStream>;
type Receiver = mpsc::Receiver<DesktopEvent>;

/// A link whose streams the test pushes; `poll_phone` hands out one per poll.
#[derive(Clone, Default)]
struct FakeLink(Arc<Mutex<VecDeque<UsbStream>>>);

impl FakeLink {
    /// Queues a fresh crossed pipe for the worker and returns the phone's end.
    fn connect_phone(&self) -> CrossedTransferBulkIo {
        let (desktop_io, phone_io) = crossed_transfer_pair();
        self.0.lock().unwrap().push_back(usb_stream(desktop_io));
        phone_io
    }
}

impl PhoneLink for FakeLink {
    type Stream = UsbStream;

    fn poll_phone(&mut self) -> Result<Option<UsbStream>, PhoneLinkError> {
        Ok(self.0.lock().unwrap().pop_front())
    }
}

#[test]
fn reconnect_session_reports_connected_metrics_and_end() {
    let fixture = Fixture::new("reconnect-metrics");
    let (worker, events, link) = fixture.spawn();
    expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));

    let phone_io = link.connect_phone();
    let phone = fixture.phone_thread(phone_io, |phone, mut sequence| {
        for payload in scripted_phone_payloads() {
            send_phone_frame(phone, sequence, payload);
            sequence += 1;
            thread::sleep(PHONE_SEND_INTERVAL);
        }
        phone.conn.send_close_notify();
        phone.conn.complete_io(&mut phone.sock).unwrap();
    });

    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));
    phone.join().unwrap();
    let connected = seen
        .iter()
        .position(|e| {
            *e == DesktopEvent::Connected {
                phone_id: fixture.phone_id(),
                label: Some(PHONE_LABEL.to_string()),
            }
        })
        .expect("Connected with the stored label");
    let metrics = seen
        .iter()
        .position(|e| matches!(e, DesktopEvent::Metrics(s) if s.total_chunks > 0))
        .expect("Metrics with arrived chunks");
    let ended = seen
        .iter()
        .position(|e| matches!(e, DesktopEvent::SessionEnded(_)))
        .expect("SessionEnded");
    assert!(matches!(seen[0], DesktopEvent::Connecting), "{seen:?}");
    assert!(connected < metrics && metrics < ended, "{seen:?}");
    assert_ne!(
        seen[ended],
        DesktopEvent::SessionEnded(DesktopSessionEndReason::LocalClose)
    );
    assert!(worker.shutdown(), "worker must stop within its bound");
    fixture.cleanup();
}

#[test]
fn untrusted_phone_reconnect_fails_and_worker_keeps_waiting() {
    let fixture = Fixture::new("untrusted");
    let (worker, events, link) = fixture.spawn();
    expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));

    let stranger = DesktopTlsIdentity::generate_ephemeral("Stranger Phone").unwrap();
    let stranger_io = link.connect_phone();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let stranger_thread = thread::spawn(move || {
        let mut stream = usb_stream(stranger_io);
        let mut client = tls_client(&cert, &stranger);
        while client.is_handshaking() {
            if client.complete_io(&mut stream).is_err() {
                break;
            }
        }
    });
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));
    stranger_thread.join().unwrap();
    assert!(
        seen.iter().any(|e| matches!(
            e,
            DesktopEvent::ConnectionFailed(DesktopConnectionFailure::Reconnect(_))
        )),
        "{seen:?}"
    );

    // The worker keeps serving: the trusted phone connects next.
    let phone = fixture.phone_thread(link.connect_phone(), |_, _| {});
    expect_event(&events, |e| matches!(e, DesktopEvent::Connected { .. }));
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn forget_phone_emits_updated_list() {
    let fixture = Fixture::new("forget");
    let (worker, events, _link) = fixture.spawn();
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::TrustedPhones(_)));
    match &seen[0] {
        DesktopEvent::TrustedPhones(phones) => {
            assert_eq!(phones.len(), 1);
            assert_eq!(phones[0].phone_id, fixture.phone_id());
            assert_eq!(phones[0].label, PHONE_LABEL);
        }
        other => panic!("first event must be the trusted list, got {other:?}"),
    }

    worker.send(DesktopCommand::ForgetPhone {
        phone_id: fixture.phone_id(),
    });
    expect_event(&events, |e| *e == DesktopEvent::TrustedPhones(Vec::new()));
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn start_pairing_emits_qr_and_cancel_returns_to_reconnect_mode() {
    let fixture = Fixture::new("pairing-qr");
    let (worker, events, link) = fixture.spawn();
    expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));

    worker.send(DesktopCommand::StartPairing);
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::PairingQr { .. }));
    let now = epoch_seconds();
    match seen.last().unwrap() {
        DesktopEvent::PairingQr {
            text,
            expires_at_epoch_seconds,
        } => {
            assert!(text.starts_with("CHINCHILLACAM-PAIR:v1:"), "{text}");
            assert!(*expires_at_epoch_seconds > now);
        }
        other => panic!("unexpected {other:?}"),
    }

    worker.send(DesktopCommand::CancelPairing);
    expect_event(&events, |e| *e == DesktopEvent::PairingCancelled);

    // Back in reconnect mode, a trusted phone reconnects.
    let phone = fixture.phone_thread(link.connect_phone(), |_, _| {});
    expect_event(&events, |e| matches!(e, DesktopEvent::Connected { .. }));
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn disconnect_command_ends_session_locally() {
    assert_command_ends_session("disconnect", |_| DesktopCommand::Disconnect);
}

#[test]
fn forgetting_connected_phone_ends_session_locally() {
    assert_command_ends_session("forget-connected", |fixture| DesktopCommand::ForgetPhone {
        phone_id: fixture.phone_id(),
    });
}

#[test]
fn idle_read_timeout_must_be_below_poll_slice() {
    let fixture = Fixture::new("invalid-config");
    let mut config = test_config();
    config.idle_read_timeout = config.session.poll_slice;
    let spawned = fixture.spawn_with(config);
    assert!(matches!(
        spawned,
        Err(DesktopWorkerSpawnError::IdleReadTimeoutNotBelowPollSlice { .. })
    ));
    fixture.cleanup();
}

fn assert_command_ends_session(name: &str, command: impl Fn(&Fixture) -> DesktopCommand) {
    let fixture = Fixture::new(name);
    let (worker, events, link) = fixture.spawn();
    let keep_sending = Arc::new(AtomicBool::new(true));
    let phone_keep_sending = Arc::clone(&keep_sending);

    let phone = fixture.phone_thread(link.connect_phone(), move |phone, mut sequence| {
        while phone_keep_sending.load(Ordering::SeqCst) {
            send_phone_frame(phone, sequence, SessionFramePayload::Keepalive);
            sequence += 1;
            thread::sleep(PHONE_SEND_INTERVAL);
        }
        assert!(drain_until_close(phone), "phone must observe the close");
    });
    expect_event(&events, |e| matches!(e, DesktopEvent::Connected { .. }));

    worker.send(command(&fixture));
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::SessionEnded(_)));
    assert_eq!(
        seen.last().unwrap(),
        &DesktopEvent::SessionEnded(DesktopSessionEndReason::LocalClose)
    );
    expect_event(&events, |e| matches!(e, DesktopEvent::WaitingForPhone));
    keep_sending.store(false, Ordering::SeqCst);
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn pairing_confirm_starts_session_and_trusts_phone() {
    let fixture = Fixture::new("pairing-confirm");
    let (worker, events, link) = fixture.spawn();
    let request = start_pairing(&worker, &events);
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Pairing Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let code = pairing_short_code_v1(
        fixture.desktop_identity.spki_der_p256(),
        phone_identity.spki_der_p256(),
        request.qr_nonce(),
        request.challenge_nonce(),
    )
    .unwrap();
    let hello_id = phone_id.clone();
    let phone = fixture.spawn_phone(
        link.connect_phone(),
        phone_identity,
        Some(request),
        move |phone| {
            let accept = phone_hello(phone, &hello_id);
            assert!(matches!(
                accept.payload(),
                SessionFramePayload::HandshakeAccept { .. }
            ));
            for (sequence, payload) in (HELLO_SEQUENCE + 1..).zip(scripted_phone_payloads()) {
                send_phone_frame(phone, sequence, payload);
                thread::sleep(PHONE_SEND_INTERVAL);
            }
            phone.conn.send_close_notify();
            phone.conn.complete_io(&mut phone.sock).unwrap();
        },
    );

    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::ConfirmCode { .. }));
    let expected = DesktopEvent::ConfirmCode {
        phone_id: phone_id.clone(),
        code,
    };
    assert_eq!(seen.last(), Some(&expected));
    worker.send(DesktopCommand::ConfirmPairing {
        label: PAIRED_LABEL.to_string(),
    });
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::SessionEnded(_)));
    phone.join().unwrap();
    assert!(seen
        .iter()
        .any(|e| matches!(e, DesktopEvent::TrustedPhones(phones)
        if phones.iter().any(|p| p.phone_id == phone_id && p.label == PAIRED_LABEL))));
    let connected = DesktopEvent::Connected {
        phone_id,
        label: Some(PAIRED_LABEL.to_string()),
    };
    assert!(seen.contains(&connected), "{seen:?}");
    assert!(seen
        .iter()
        .any(|e| matches!(e, DesktopEvent::Metrics(m) if m.total_chunks > 0)));
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn pairing_reject_closes_channel_and_leaves_pairing_mode() {
    assert_pairing_abandoned(
        "pairing-reject",
        test_config(),
        Some(DesktopCommand::RejectPairing),
    );
}

#[test]
fn pairing_confirm_deadline_rejects() {
    let config = DesktopWorkerConfig {
        pairing_confirm_timeout: Duration::from_millis(300),
        ..test_config()
    };
    assert_pairing_abandoned("pairing-deadline", config, None);
}

/// Without `command` the confirmation deadline must expire on its own.
fn assert_pairing_abandoned(
    name: &str,
    config: DesktopWorkerConfig,
    command: Option<DesktopCommand>,
) {
    let fixture = Fixture::new(name);
    let (worker, events, link) = fixture.spawn_config(config);
    let request = start_pairing(&worker, &events);
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Pairing Phone").unwrap();
    let phone = fixture.spawn_phone(
        link.connect_phone(),
        phone_identity,
        Some(request),
        |phone| {
            assert!(drain_until_close(phone), "phone must observe the close");
        },
    );
    expect_event(&events, |e| matches!(e, DesktopEvent::ConfirmCode { .. }));
    let expected = match command {
        Some(command) => {
            worker.send(command);
            DesktopEvent::PairingCancelled
        }
        None => DesktopEvent::ConnectionFailed(DesktopConnectionFailure::PairingConfirmTimedOut),
    };
    let seen = expect_event(&events, |e| *e == expected);
    phone.join().unwrap();
    assert!(
        !seen
            .iter()
            .any(|e| matches!(e, DesktopEvent::Connected { .. })),
        "{seen:?}"
    );
    assert_reconnects(&fixture, &link, &events);
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn hello_failure_after_confirm_forgets_phone() {
    let fixture = Fixture::new("pairing-hello-failure");
    let (worker, events, link) = fixture.spawn();
    let request = start_pairing(&worker, &events);
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Pairing Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let phone = fixture.spawn_phone(
        link.connect_phone(),
        phone_identity,
        Some(request),
        |phone| {
            let reply = phone_hello(phone, "not-this-phone");
            assert!(matches!(
                reply.payload(),
                SessionFramePayload::HandshakeReject { .. }
            ));
        },
    );
    expect_event(&events, |e| matches!(e, DesktopEvent::ConfirmCode { .. }));
    worker.send(DesktopCommand::ConfirmPairing {
        label: PAIRED_LABEL.to_string(),
    });
    let failed = DesktopEvent::ConnectionFailed(DesktopConnectionFailure::Pairing(
        PhoneConnectionError::HelloDeviceIdMismatch,
    ));
    expect_event(&events, |e| *e == failed);
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::TrustedPhones(_)));
    phone.join().unwrap();
    assert!(
        matches!(seen.last(), Some(DesktopEvent::TrustedPhones(phones))
        if phones.iter().all(|p| p.phone_id != phone_id))
    );
    let store = FileTrustedPhoneStore::new(&fixture.store_path);
    assert_eq!(store.trusted_identity(&phone_id).unwrap(), None);
    assert_reconnects(&fixture, &link, &events);
    assert!(worker.shutdown());
    fixture.cleanup();
}

static TEST_EPOCH: AtomicU64 = AtomicU64::new(1_000_000);

fn test_epoch() -> u64 {
    TEST_EPOCH.load(Ordering::SeqCst)
}

#[test]
fn pairing_qr_refreshes_before_expiry() {
    let fixture = Fixture::new("pairing-qr-refresh");
    let config = DesktopWorkerConfig {
        epoch_clock: test_epoch,
        ..test_config()
    };
    let (worker, events, _link) = fixture.spawn_config(config);
    worker.send(DesktopCommand::StartPairing);
    assert_eq!(next_qr_expiry(&events), 1_000_120);

    // One second before the refresh margin nothing is reissued (that QR would expire at
    // 1_000_234); at the margin the next QR expires at 1_000_235.
    TEST_EPOCH.store(1_000_114, Ordering::SeqCst);
    thread::sleep(Duration::from_millis(150));
    TEST_EPOCH.store(1_000_115, Ordering::SeqCst);
    assert_eq!(next_qr_expiry(&events), 1_000_235);
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn worker_subscribes_and_reports_quality_state() {
    let fixture = Fixture::new("quality-state");
    let (worker, events, link) = fixture.spawn();
    let phone_id = fixture.phone_id();
    let phone = fixture.spawn_phone(
        link.connect_phone(),
        fixture.phone_identity.clone(),
        None,
        move |phone| {
            phone_hello_with_capabilities(
                phone,
                &phone_id,
                vec![QUALITY_CONTROL_CAPABILITY.into()],
            );
            let frame = read_session_frame(phone, Instant::now() + OP_TIMEOUT).unwrap();
            assert_eq!(
                frame.payload(),
                &SessionFramePayload::CameraControlCommand {
                    command: "quality_subscribe".into(),
                    arguments: [("v".into(), "1".into())].into(),
                }
            );
            let state = sample_quality_state();
            let (command, arguments) = encode_quality_state(&state);
            send_phone_frame(
                phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::CameraControlCommand { command, arguments },
            );
            // The worker must remain active after consuming the inbound control.
            thread::sleep(Duration::from_millis(150));
        },
    );
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::QualityState(_)));
    assert!(seen
        .iter()
        .any(|e| matches!(e, DesktopEvent::Connected { .. })));
    assert_eq!(
        seen.last(),
        Some(&DesktopEvent::QualityState(sample_quality_state()))
    );
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn worker_sends_increasing_set_quality_requests() {
    let fixture = Fixture::new("set-quality");
    let (worker, events, link) = fixture.spawn();
    let (ready_tx, ready_rx) = mpsc::channel();
    let phone_id = fixture.phone_id();
    let phone = fixture.spawn_phone(link.connect_phone(), fixture.phone_identity.clone(), None, move |phone| {
        phone_hello_with_capabilities(phone, &phone_id, vec![QUALITY_CONTROL_CAPABILITY.into()]);
        assert!(matches!(read_session_frame(phone, Instant::now() + OP_TIMEOUT).unwrap().payload(),
            SessionFramePayload::CameraControlCommand { command, .. } if command == "quality_subscribe"));
        ready_tx.send(()).unwrap();
        for req in 1..=2 {
            let frame = read_session_frame(phone, Instant::now() + OP_TIMEOUT).unwrap();
            let SessionFramePayload::CameraControlCommand { command, arguments } = frame.payload() else {
                panic!("expected set_quality: {frame:?}");
            };
            let set = parse_set_quality(command, arguments).unwrap();
            assert_eq!(set.req, req);
            assert_eq!(set.camera, Some(CameraSelection::Id("back".into())));
            assert_eq!(set.mode, QualityMode::Manual);
            assert_eq!(set.manual, Some((1280, 720, 30)));
        }
    });
    expect_event(&events, |e| matches!(e, DesktopEvent::Connected { .. }));
    ready_rx.recv_timeout(OP_TIMEOUT).unwrap();
    for _ in 0..2 {
        worker.send(DesktopCommand::SetQuality {
            camera: Some(CameraSelection::Id("back".into())),
            mode: QualityMode::Manual,
            manual: Some((1280, 720, 30)),
        });
    }
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn worker_ignores_quality_commands_for_incapable_phone() {
    let fixture = Fixture::new("no-quality-capability");
    let (worker, events, link) = fixture.spawn();
    let (ready_tx, ready_rx) = mpsc::channel();
    let (sent_tx, sent_rx) = mpsc::channel();
    let phone = fixture.phone_thread(link.connect_phone(), move |phone, _| {
        ready_tx.send(()).unwrap();
        sent_rx.recv_timeout(OP_TIMEOUT).unwrap();
        let deadline = Instant::now() + Duration::from_millis(350);
        while Instant::now() < deadline {
            match read_session_frame(phone, Instant::now() + Duration::from_millis(100)) {
                Ok(frame) => assert!(
                    !matches!(
                        frame.payload(),
                        SessionFramePayload::CameraControlCommand { .. }
                    ),
                    "unexpected frame 7: {frame:?}"
                ),
                Err(_) => break,
            }
        }
    });
    expect_event(&events, |e| matches!(e, DesktopEvent::Connected { .. }));
    ready_rx.recv_timeout(OP_TIMEOUT).unwrap();
    worker.send(DesktopCommand::SetQuality {
        camera: None,
        mode: QualityMode::Auto,
        manual: None,
    });
    sent_tx.send(()).unwrap();
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

#[test]
fn malformed_quality_state_is_ignored_without_ending_session() {
    let fixture = Fixture::new("malformed-quality");
    let (worker, events, link) = fixture.spawn();
    let phone_id = fixture.phone_id();
    let phone = fixture.spawn_phone(link.connect_phone(), fixture.phone_identity.clone(), None, move |phone| {
        phone_hello_with_capabilities(phone, &phone_id, vec![QUALITY_CONTROL_CAPABILITY.into()]);
        assert!(matches!(read_session_frame(phone, Instant::now() + OP_TIMEOUT).unwrap().payload(),
            SessionFramePayload::CameraControlCommand { command, .. } if command == "quality_subscribe"));
        send_phone_frame(phone, HELLO_SEQUENCE + 1, SessionFramePayload::CameraControlCommand {
            command: "quality_state".into(), arguments: [("v".into(), "1".into())].into(),
        });
        send_phone_frame(phone, HELLO_SEQUENCE + 2, SessionFramePayload::CameraControlCommand {
            command: "unknown_command".into(), arguments: Default::default(),
        });
        let (command, arguments) = encode_quality_state(&sample_quality_state());
        send_phone_frame(phone, HELLO_SEQUENCE + 3, SessionFramePayload::CameraControlCommand { command, arguments });
        thread::sleep(Duration::from_millis(150));
    });
    let seen = expect_event(&events, |e| matches!(e, DesktopEvent::QualityState(_)));
    assert_eq!(
        seen.iter()
            .filter(|e| matches!(e, DesktopEvent::QualityState(_)))
            .count(),
        1
    );
    assert!(!seen
        .iter()
        .any(|e| matches!(e, DesktopEvent::SessionEnded(_))));
    phone.join().unwrap();
    assert!(worker.shutdown());
    fixture.cleanup();
}

fn sample_quality_state() -> QualityState {
    QualityState {
        req: None,
        error: None,
        mode: QualityMode::Auto,
        selected_camera: CameraSelection::Auto,
        cameras: vec![],
        resolutions: vec![],
        frame_rates: vec![],
        applied_width: 640,
        applied_height: 480,
        applied_fps: 30,
        summary: "Auto".into(),
    }
}

// --- Harness ---------------------------------------------------------------------------

fn test_config() -> DesktopWorkerConfig {
    DesktopWorkerConfig {
        poll_interval: Duration::from_millis(20),
        handshake_timeout: OP_TIMEOUT,
        idle_read_timeout: Duration::from_millis(20),
        session: SessionRuntimeConfig {
            poll_slice: Duration::from_millis(30),
            frame_budget: Duration::from_millis(2000),
            keepalive_interval: Duration::from_millis(150),
            dead_threshold: Duration::from_millis(600),
        },
        metrics_window: Duration::from_secs(5),
        metrics_interval: Duration::from_millis(50),
        ..DesktopWorkerConfig::default()
    }
}

struct Fixture {
    desktop_identity: DesktopTlsIdentity,
    phone_identity: DesktopTlsIdentity,
    store_path: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Self {
        let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
        let phone_identity = DesktopTlsIdentity::generate_ephemeral(PHONE_LABEL).unwrap();
        let store_path = std::env::temp_dir().join(format!(
            "chinchillacam-desktop-worker-{name}-{}-{}.txt",
            std::process::id(),
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let spki = phone_identity.spki_der_p256().to_vec();
        let trusted = TrustedPhoneIdentity::new(phone_id_for_spki(&spki), PHONE_LABEL, spki);
        FileTrustedPhoneStore::new(&store_path)
            .trust(trusted.unwrap())
            .unwrap();
        Self {
            desktop_identity,
            phone_identity,
            store_path,
        }
    }

    fn phone_id(&self) -> String {
        phone_id_for_spki(self.phone_identity.spki_der_p256())
    }

    fn spawn(&self) -> (DesktopWorkerHandle, Receiver, FakeLink) {
        self.spawn_config(test_config())
    }

    fn spawn_config(
        &self,
        config: DesktopWorkerConfig,
    ) -> (DesktopWorkerHandle, Receiver, FakeLink) {
        let (sender, events) = mpsc::channel();
        let link = FakeLink::default();
        let worker = self
            .spawn_on(config, link.clone(), sender)
            .expect("worker spawns");
        (worker, events, link)
    }

    fn spawn_with(
        &self,
        config: DesktopWorkerConfig,
    ) -> Result<DesktopWorkerHandle, DesktopWorkerSpawnError> {
        self.spawn_on(config, FakeLink::default(), mpsc::channel().0)
    }

    fn spawn_on(
        &self,
        config: DesktopWorkerConfig,
        link: FakeLink,
        sender: mpsc::Sender<DesktopEvent>,
    ) -> Result<DesktopWorkerHandle, DesktopWorkerSpawnError> {
        let issuer = PairingQrIssuer::new(
            "studio-desktop",
            "Studio Desktop",
            self.desktop_identity.clone(),
            120,
        )
        .unwrap();
        DesktopConnectionWorker::spawn(
            config,
            link,
            self.desktop_identity.clone(),
            Arc::new(FileTrustedPhoneStore::new(&self.store_path)),
            issuer,
            || FakeVideoDecoder::new(RecordingDecodedFrameSink::default()),
            move |event| {
                let _ = sender.send(event);
            },
        )
    }

    /// Runs the phone side: TLS, HELLO, ACCEPT, then `script` with the next sequence.
    fn phone_thread(
        &self,
        io: CrossedTransferBulkIo,
        script: impl FnOnce(&mut PhoneStream, i32) + Send + 'static,
    ) -> thread::JoinHandle<()> {
        let phone_id = self.phone_id();
        self.spawn_phone(io, self.phone_identity.clone(), None, move |phone| {
            let accept = phone_hello(phone, &phone_id);
            assert!(matches!(
                accept.payload(),
                SessionFramePayload::HandshakeAccept { .. }
            ));
            script(phone, HELLO_SEQUENCE + 1);
        })
    }

    /// Runs the phone side: TLS, the CCP1 pairing proof when `pairing` is set, then `script`.
    fn spawn_phone(
        &self,
        io: CrossedTransferBulkIo,
        identity: DesktopTlsIdentity,
        pairing: Option<PairingProofRequest>,
        script: impl FnOnce(&mut PhoneStream) + Send + 'static,
    ) -> thread::JoinHandle<()> {
        let cert = self.desktop_identity.certificate_der().to_vec();
        thread::spawn(move || {
            let mut stream = usb_stream(io);
            let mut client = tls_client(&cert, &identity);
            while client.is_handshaking() {
                client.complete_io(&mut stream).unwrap();
            }
            let mut phone = StreamOwned::new(client, stream);
            if let Some(request) = pairing {
                let frame = PairingProofFrame::request(request).encode().unwrap();
                phone.write_all(&frame).unwrap();
                phone.flush().unwrap();
                let mut header = [0; 10];
                phone.read_exact(&mut header).unwrap();
                let len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
                phone.read_exact(&mut vec![0; len]).unwrap();
            }
            script(&mut phone);
        })
    }

    fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.store_path);
        let _ = std::fs::remove_file(self.store_path.with_extension("lock"));
    }
}

/// Collects events until `matches` accepts one (returned last), panicking after a bound.
fn expect_event(events: &Receiver, matches: impl Fn(&DesktopEvent) -> bool) -> Vec<DesktopEvent> {
    let deadline = Instant::now() + EVENT_TIMEOUT;
    let mut seen = Vec::new();
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        match events.recv_timeout(left) {
            Ok(event) => {
                let done = matches(&event);
                seen.push(event);
                if done {
                    return seen;
                }
            }
            Err(error) => panic!("expected event not seen ({error:?}); saw {seen:?}"),
        }
    }
}

fn phone_hello(phone: &mut PhoneStream, device_id: &str) -> SessionFrame {
    phone_hello_with_capabilities(phone, device_id, vec!["video".into()])
}

fn phone_hello_with_capabilities(
    phone: &mut PhoneStream,
    device_id: &str,
    capabilities: Vec<String>,
) -> SessionFrame {
    send_phone_frame(
        phone,
        HELLO_SEQUENCE,
        SessionFramePayload::HandshakeHello {
            device_id: device_id.to_string(),
            app_name: "ChinchillaCam".to_string(),
            capabilities,
        },
    );
    read_session_frame(phone, Instant::now() + OP_TIMEOUT).unwrap()
}

/// Sends `StartPairing` and builds the phone's CCP1 request from the emitted QR text.
fn start_pairing(worker: &DesktopWorkerHandle, events: &Receiver) -> PairingProofRequest {
    worker.send(DesktopCommand::StartPairing);
    let seen = expect_event(events, |e| matches!(e, DesktopEvent::PairingQr { .. }));
    let Some(DesktopEvent::PairingQr { text, .. }) = seen.last() else {
        unreachable!()
    };
    let field = |name: &str| {
        let body = text.strip_prefix("CHINCHILLACAM-PAIR:v1:").unwrap();
        let prefix = format!("{name}=");
        body.split('&')
            .find_map(|pair| pair.strip_prefix(prefix.as_str()))
            .unwrap()
            .to_string()
    };
    let nonce = URL_SAFE_NO_PAD.decode(field("nonce")).unwrap();
    PairingProofRequest::new(&field("desktopId"), nonce, vec![3; 32], "pair-session").unwrap()
}

fn next_qr_expiry(events: &Receiver) -> u64 {
    let seen = expect_event(events, |e| matches!(e, DesktopEvent::PairingQr { .. }));
    match seen.last() {
        Some(DesktopEvent::PairingQr {
            expires_at_epoch_seconds,
            ..
        }) => *expires_at_epoch_seconds,
        _ => unreachable!(),
    }
}

/// The worker is back in reconnect mode: the trusted phone reconnects.
fn assert_reconnects(fixture: &Fixture, link: &FakeLink, events: &Receiver) {
    let phone = fixture.phone_thread(link.connect_phone(), |_, _| {});
    expect_event(events, |e| matches!(e, DesktopEvent::Connected { .. }));
    phone.join().unwrap();
}

fn scripted_phone_payloads() -> Vec<SessionFramePayload> {
    vec![
        SessionFramePayload::video_chunk_v2_codec_config(0, 0, vec![0x00, 0x00, 0x01]),
        SessionFramePayload::video_chunk_v2_key(1, 10_000, vec![0x65, 0x88, 0x84]),
        SessionFramePayload::video_chunk_v2_delta(2, 20_000, vec![0x41, 0x9a]),
        SessionFramePayload::Keepalive,
        SessionFramePayload::video_chunk_v2_delta(3, 30_000, vec![0x41, 0x9b]),
        SessionFramePayload::Keepalive,
    ]
}

fn usb_stream(io: CrossedTransferBulkIo) -> UsbStream {
    let budget = FrameTransferBudget::new(FRAME_READ_TIMEOUT, 65_536, 512).unwrap();
    UsbTlsCiphertextStream::new(FramedUsbStream::new(io, budget))
}

fn send_phone_frame(phone: &mut PhoneStream, sequence: i32, payload: SessionFramePayload) {
    write_session_frame(phone, &SessionFrame::new(sequence, SESSION_ID, payload)).unwrap();
}

fn drain_until_close(phone: &mut PhoneStream) -> bool {
    let deadline = Instant::now() + Duration::from_secs(4);
    let mut buf = [0u8; 4096];
    while Instant::now() < deadline {
        match phone.read(&mut buf) {
            Ok(0) => return true,
            Ok(_) => {}
            Err(error) if error.kind() == ErrorKind::TimedOut => {}
            Err(error) => return error.kind() == ErrorKind::UnexpectedEof,
        }
    }
    false
}

fn tls_client(root_cert: &[u8], phone_identity: &DesktopTlsIdentity) -> ClientConnection {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    let client_cert = CertificateDer::from(phone_identity.certificate_der().to_vec());
    let client_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        phone_identity.private_key_pkcs8_der().to_vec(),
    ));
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_client_auth_cert(vec![client_cert], client_key)
        .unwrap();
    ClientConnection::new(Arc::new(config), ServerName::try_from("localhost").unwrap()).unwrap()
}

fn epoch_seconds() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}
