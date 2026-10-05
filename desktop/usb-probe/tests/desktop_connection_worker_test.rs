//! Task d4a (`odd/tasks/desktop-production-app.md`, contract section 4.4): the
//! `DesktopConnectionWorker` drives the reconnect path end to end over the USB stack model
//! (`FramedUsbStream` -> `UsbTlsCiphertextStream` -> rustls on a crossed transfer pipe), runs the
//! session pipeline with a fake decoder, and reports typed events. The phone side is a rustls
//! client on the other end of the pipe.

#[allow(dead_code)]
mod common;

use std::{
    collections::VecDeque,
    io::{ErrorKind, Read},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    thread,
    time::{Duration, Instant, SystemTime},
};

use common::usb_transfer_pipe::{crossed_transfer_pair, CrossedTransferBulkIo};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    phone_id_for_spki, read_session_frame, write_session_frame, DesktopCommand,
    DesktopConnectionFailure, DesktopConnectionWorker, DesktopEvent, DesktopSessionEndReason,
    DesktopTlsIdentity, DesktopWorkerConfig, DesktopWorkerHandle, DesktopWorkerSpawnError,
    FakeVideoDecoder, FileTrustedPhoneStore, FrameTransferBudget, FramedUsbStream, PairingQrIssuer,
    PhoneLink, PhoneLinkError, RecordingDecodedFrameSink, SessionFrame, SessionFramePayload,
    SessionRuntimeConfig, TrustedPhoneIdentity, UsbTlsCiphertextStream,
};

const SESSION_ID: &str = "desktop-worker-usb";
const HELLO_SEQUENCE: i32 = 1;
const PHONE_LABEL: &str = "Reconnecting Phone";
const FRAME_READ_TIMEOUT: Duration = Duration::from_millis(1000);
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const EVENT_TIMEOUT: Duration = Duration::from_secs(8);
const PHONE_SEND_INTERVAL: Duration = Duration::from_millis(30);

type UsbStream = UsbTlsCiphertextStream<CrossedTransferBulkIo>;
type PhoneStream = StreamOwned<ClientConnection, UsbStream>;

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

    fn spawn(&self) -> (DesktopWorkerHandle, mpsc::Receiver<DesktopEvent>, FakeLink) {
        let (sender, events) = mpsc::channel();
        let link = FakeLink::default();
        let worker = self
            .spawn_on(test_config(), link.clone(), sender)
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
        let cert = self.desktop_identity.certificate_der().to_vec();
        let phone_identity = self.phone_identity.clone();
        thread::spawn(move || {
            let mut stream = usb_stream(io);
            let mut client = tls_client(&cert, &phone_identity);
            while client.is_handshaking() {
                client.complete_io(&mut stream).unwrap();
            }
            let mut phone = StreamOwned::new(client, stream);
            let hello = SessionFrame::new(
                HELLO_SEQUENCE,
                SESSION_ID,
                SessionFramePayload::HandshakeHello {
                    device_id: phone_id_for_spki(phone_identity.spki_der_p256()),
                    app_name: "ChinchillaCam".to_string(),
                    capabilities: vec!["video".to_string()],
                },
            );
            write_session_frame(&mut phone, &hello).unwrap();
            let accept = read_session_frame(&mut phone, Instant::now() + OP_TIMEOUT).unwrap();
            assert!(matches!(
                accept.payload(),
                SessionFramePayload::HandshakeAccept { .. }
            ));
            script(&mut phone, HELLO_SEQUENCE + 1);
        })
    }

    fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.store_path);
        let _ = std::fs::remove_file(self.store_path.with_extension("lock"));
    }
}

/// Collects events until `matches` accepts one (returned last), panicking after a bound.
fn expect_event(
    events: &mpsc::Receiver<DesktopEvent>,
    matches: impl Fn(&DesktopEvent) -> bool,
) -> Vec<DesktopEvent> {
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
