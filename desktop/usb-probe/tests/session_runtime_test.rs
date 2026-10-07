//! Task s2 (`odd/tasks/session-runtime.md`, contract section 4): drive `SessionRuntime` over
//! the framing-free in-memory duplex double with a rustls phone peer thread. The desktop
//! side runs on the main thread (accept the reconnect, build the runtime, `step`); the phone
//! side runs in a scoped thread (handshake, `HandshakeHello`, read `HandshakeAccept`, then
//! the per-test traffic). Timing-sensitive decisions (keepalive / dead peer) are driven by an
//! injected `now`, so they are deterministic regardless of wall-clock scheduling; only the
//! blocking reads use real time, with generous upper bounds.

mod common;

use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{mpsc, Arc},
    time::{Duration, Instant, SystemTime},
};

use common::duplex::InMemoryDuplex;
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_reconnect_connection, phone_id_for_spki, read_session_frame, write_session_frame,
    AuthenticatedPhoneSession, BoundedEncodedVideoQueue, DecodingEncodedVideoSink,
    DesktopTlsIdentity, DesktopVideoSessionReceiver, FakeVideoDecoder, FileTrustedPhoneStore,
    KeyframeGatedSink, RecordingDecodedFrameSink, SessionEnd, SessionFrame, SessionFramePayload,
    SessionRuntime, SessionRuntimeConfig, StaticFrameKindClassifier, StepOutcome,
    TrustedPhoneIdentity, VideoDecoderError,
};

const READ_TIMEOUT: Duration = Duration::from_millis(400);
const OP_TIMEOUT: Duration = Duration::from_millis(2000);
const SESSION_ID: &str = "session-rt";
const HELLO_SEQUENCE: i32 = 1;

type DesktopRuntime =
    SessionRuntime<InMemoryDuplex, StaticFrameKindClassifier, BoundedEncodedVideoQueue>;

fn test_config() -> SessionRuntimeConfig {
    SessionRuntimeConfig {
        poll_slice: Duration::from_millis(20),
        frame_budget: Duration::from_millis(2000),
        keepalive_interval: Duration::from_millis(100),
        dead_threshold: Duration::from_millis(300),
    }
}

fn build_runtime(
    session: AuthenticatedPhoneSession<InMemoryDuplex>,
    start: Instant,
) -> DesktopRuntime {
    SessionRuntime::new(
        session,
        DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
        BoundedEncodedVideoQueue::new(8).unwrap(),
        test_config(),
        start,
    )
    .unwrap()
}

#[test]
fn video_frames_reach_sink_through_runtime() {
    let fixture = Fixture::new("video-reaches-sink");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::video_chunk_v2_key(0, 10_000, vec![0x65, 0x88, 0x84]),
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);

        let outcome = step_until_frame(&mut runtime, start);
        assert!(
            outcome.received_video,
            "expected a video frame: {outcome:?}"
        );
        assert_eq!(runtime.sink().len(), 1, "video chunk must reach the sink");
        assert_eq!(runtime.video_frames_delivered(), 1);
        assert_eq!(runtime.frames_received(), 1);
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn decoder_failure_ends_session_with_decoder_cause() {
    let fixture = Fixture::new("decoder-failure-cause");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::video_chunk_v2_key(0, 10_000, vec![0x65, 0x88, 0x84]),
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let sink = DecodingEncodedVideoSink::new(
            FakeVideoDecoder::new(RecordingDecodedFrameSink::default()).script_error(
                VideoDecoderError::Failure("synthetic decode failure".to_string()),
            ),
        );
        let mut runtime = SessionRuntime::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            sink,
            test_config(),
            start,
        )
        .unwrap();

        let end = loop {
            match runtime.step(start) {
                Ok(_) => continue,
                Err(end) => break end,
            }
        };
        assert!(
            matches!(end, SessionEnd::DecoderFailed(_)),
            "expected a decoder-caused end, got {end:?}"
        );
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn session_continues_after_saturation() {
    let fixture = Fixture::new("continues-after-saturation");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            // CodecConfig first (the decoder requires it), then a Key and a Delta.
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::video_chunk_v2_codec_config(0, 0, vec![0x67, 0x42]),
            );
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 2,
                SessionFramePayload::video_chunk_v2_key(1, 10_000, vec![0x65, 0x88, 0x84]),
            );
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 3,
                SessionFramePayload::video_chunk_v2_delta(2, 20_000, vec![0x41, 0x9a]),
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        // The decoder backpressures exactly once; the gated sink must keep the session open.
        let sink = KeyframeGatedSink::new(DecodingEncodedVideoSink::new(
            FakeVideoDecoder::new(RecordingDecodedFrameSink::default())
                .script_error(VideoDecoderError::Backpressure),
        ));
        let mut runtime = SessionRuntime::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            sink,
            test_config(),
            start,
        )
        .unwrap();

        // Drive the session at a fixed `now` (no keepalive/dead logic) until all three frames
        // are processed, failing loudly if the session ends early.
        let mut ended = None;
        for _ in 0..200 {
            match runtime.step(start) {
                Ok(_) => {
                    if runtime.frames_received() >= 3 {
                        break;
                    }
                }
                Err(end) => {
                    ended = Some(end);
                    break;
                }
            }
        }

        assert!(
            ended.is_none(),
            "session must survive saturation, but ended with {ended:?}"
        );
        assert_eq!(runtime.frames_received(), 3);

        let gated = runtime.sink();
        assert_eq!(
            gated.saturation_events(),
            1,
            "one backpressure was absorbed"
        );
        // After the Key re-pushes the pending config, later frames decode: the Key and the
        // Delta both reach the decoded-frame sink.
        let decoded = gated.inner().decoder().sink().frames();
        assert_eq!(
            decoded.len(),
            2,
            "the Key and Delta should decode after saturation, got {decoded:?}"
        );
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn sends_keepalive_after_silence() {
    let fixture = Fixture::new("sends-keepalive");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            read_session_frame(&mut phone, Instant::now() + Duration::from_secs(5)).unwrap()
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);

        // `now` is well past keepalive_interval (100ms) but below dead_threshold (300ms).
        let outcome = runtime.step(start + Duration::from_millis(200)).unwrap();
        assert!(outcome.sent_keepalive, "expected a keepalive: {outcome:?}");
        assert_eq!(runtime.keepalives_sent(), 1);

        let keepalive = phone.join().unwrap();
        assert_eq!(keepalive.payload(), &SessionFramePayload::Keepalive);
        assert_eq!(keepalive.session_id(), SESSION_ID);
        // ACCEPT used HELLO + 1 (= 2), so the desktop's next outbound frame is HELLO + 2.
        assert_eq!(keepalive.sequence(), HELLO_SEQUENCE + 2);
    });

    fixture.cleanup();
}

#[test]
fn detects_dead_peer() {
    let fixture = Fixture::new("detects-dead-peer");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            // Stay silent but keep the TLS stream alive so the desktop sees silence, not EOF.
            let _phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);

        // `now` is past dead_threshold (300ms) with no frame received since construction.
        let end = loop {
            match runtime.step(start + Duration::from_millis(600)) {
                Ok(_) => continue,
                Err(end) => break end,
            }
        };
        assert_eq!(end, SessionEnd::PeerDead);
        // The runtime is unusable after an end: the same cause comes back.
        assert_eq!(
            runtime
                .step(start + Duration::from_millis(600))
                .unwrap_err(),
            SessionEnd::PeerDead
        );
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn rejects_out_of_order_sequence() {
    let fixture = Fixture::new("rejects-out-of-order");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            // Skips the expected HELLO + 1, jumping to HELLO + 4.
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 4,
                SessionFramePayload::video_chunk_v2_key(0, 10_000, vec![0x65, 0x88, 0x84]),
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);

        let end = step_until_end(&mut runtime, start);
        assert!(
            matches!(end, SessionEnd::ProtocolViolation(_)),
            "expected a protocol violation, got {end:?}"
        );
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn inbound_camera_control_is_queued_not_a_violation() {
    let fixture = Fixture::new("inbound-control");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();
    let arguments = BTreeMap::from([("v".to_string(), "1".to_string())]);

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        let (continue_tx, continue_rx) = mpsc::channel::<()>();
        let sent = arguments.clone();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::CameraControlCommand {
                    command: "quality_state".into(),
                    arguments: sent,
                },
            );
            let _ = continue_rx.recv();
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 2,
                SessionFramePayload::Keepalive,
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);
        assert!(step_until_frame(&mut runtime, start + Duration::from_millis(250)).received_frame);
        assert_eq!(runtime.frames_received(), 1);
        assert_eq!(
            runtime.take_controls(),
            vec![("quality_state".into(), arguments)]
        );
        assert!(runtime.take_controls().is_empty());
        // No further inbound traffic: the control at 250 ms resets the 300 ms peer deadline.
        assert!(runtime.step(start + Duration::from_millis(500)).is_ok());
        continue_tx.send(()).unwrap();
        assert!(
            step_until_frame(&mut runtime, start + Duration::from_millis(500)).received_keepalive
        );
        assert_eq!(runtime.frames_received(), 2);
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn control_inbox_drops_oldest_without_ending_session() {
    let fixture = Fixture::new("control-overflow");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            for i in 0..10 {
                send_phone_frame(
                    &mut phone,
                    HELLO_SEQUENCE + 1 + i,
                    SessionFramePayload::CameraControlCommand {
                        command: i.to_string(),
                        arguments: BTreeMap::new(),
                    },
                );
            }
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 11,
                SessionFramePayload::Keepalive,
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);
        for _ in 0..10 {
            assert!(step_until_frame(&mut runtime, start).received_frame);
        }
        assert_eq!(runtime.controls_dropped(), 2);
        assert_eq!(
            runtime
                .take_controls()
                .into_iter()
                .map(|(command, _)| command)
                .collect::<Vec<_>>(),
            (2..10).map(|i| i.to_string()).collect::<Vec<_>>()
        );
        assert!(step_until_frame(&mut runtime, start).received_keepalive);
        assert_eq!(runtime.frames_received(), 11);
        drop(done_tx);
    });

    fixture.cleanup();
}

#[test]
fn handshake_frame_after_start_is_protocol_violation() {
    let fixture = Fixture::new("handshake-after-start");
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();
    let phone_id = fixture.phone_id.clone();

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = mpsc::channel::<()>();
        scope.spawn(move || {
            let mut phone = phone_hello_and_accept(phone_duplex, &cert, &phone_identity);
            // A second HELLO at the correctly expected sequence is still an illegal frame type.
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::HandshakeHello {
                    device_id: phone_id,
                    app_name: "ChinchillaCam".to_string(),
                    capabilities: vec!["video".to_string()],
                },
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(desktop_duplex, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start);

        let end = step_until_end(&mut runtime, start);
        assert!(
            matches!(end, SessionEnd::ProtocolViolation(_)),
            "expected a protocol violation, got {end:?}"
        );
        drop(done_tx);
    });

    fixture.cleanup();
}

// --- Harness ---------------------------------------------------------------------------

struct Fixture {
    desktop_identity: DesktopTlsIdentity,
    phone_identity: DesktopTlsIdentity,
    phone_id: String,
    store_path: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Self {
        let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
        let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
        let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

        let store_path = unique_store_path(name);
        let store = FileTrustedPhoneStore::new(&store_path);
        store
            .trust(
                TrustedPhoneIdentity::new(
                    phone_id.clone(),
                    "Reconnecting Phone",
                    phone_identity.spki_der_p256().to_vec(),
                )
                .unwrap(),
            )
            .unwrap();

        Self {
            desktop_identity,
            phone_identity,
            phone_id,
            store_path,
        }
    }

    fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.store_path);
        let _ = std::fs::remove_file(self.store_path.with_extension("lock"));
    }
}

fn accept_desktop_session(
    desktop_duplex: InMemoryDuplex,
    fixture: &Fixture,
) -> AuthenticatedPhoneSession<InMemoryDuplex> {
    let lookup = Arc::new(FileTrustedPhoneStore::new(&fixture.store_path));
    accept_phone_reconnect_connection(
        desktop_duplex,
        &fixture.desktop_identity,
        lookup,
        OP_TIMEOUT,
    )
    .expect("reconnect handshake should succeed")
}

fn step_until_frame(runtime: &mut DesktopRuntime, start: Instant) -> StepOutcome {
    for _ in 0..200 {
        let outcome = runtime.step(start).expect("runtime ended unexpectedly");
        if outcome.received_frame {
            return outcome;
        }
    }
    panic!("no frame received within the step budget");
}

fn step_until_end(runtime: &mut DesktopRuntime, start: Instant) -> SessionEnd {
    for _ in 0..200 {
        if let Err(end) = runtime.step(start) {
            return end;
        }
    }
    panic!("runtime did not end within the step budget");
}

fn phone_hello_and_accept(
    phone_duplex: InMemoryDuplex,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, InMemoryDuplex> {
    let mut phone = phone_tls_stream(phone_duplex, root_cert, phone_identity);
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let hello = SessionFrame::new(
        HELLO_SEQUENCE,
        SESSION_ID,
        SessionFramePayload::HandshakeHello {
            device_id: phone_id,
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
    phone
}

fn send_phone_frame(
    phone: &mut StreamOwned<ClientConnection, InMemoryDuplex>,
    sequence: i32,
    payload: SessionFramePayload,
) {
    let frame = SessionFrame::new(sequence, SESSION_ID, payload);
    write_session_frame(phone, &frame).unwrap();
}

fn phone_tls_stream(
    phone_duplex: InMemoryDuplex,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, InMemoryDuplex> {
    let mut phone_duplex = phone_duplex;
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut phone_duplex).unwrap();
    }
    StreamOwned::new(client, phone_duplex)
}

fn tls_client(root_cert: &[u8], phone_identity: &DesktopTlsIdentity) -> ClientConnection {
    ClientConnection::new(
        Arc::new(client_config(root_cert, phone_identity)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap()
}

fn client_config(root_cert: &[u8], phone_identity: &DesktopTlsIdentity) -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    let client_cert = CertificateDer::from(phone_identity.certificate_der().to_vec());
    let client_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        phone_identity.private_key_pkcs8_der().to_vec(),
    ));
    ClientConfig::builder()
        .with_root_certificates(roots)
        .with_client_auth_cert(vec![client_cert], client_key)
        .unwrap()
}

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-session-runtime-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}
