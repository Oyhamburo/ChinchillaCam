//! Task q3b (`odd/tasks/session-pipeline-wiring.md`, contract section 4): drives a
//! `DesktopSessionPipeline` end-to-end over a REAL loopback TCP socket (same fixture approach as
//! `tests/session_runtime_loopback_test.rs`, with a socket read timeout shorter than the poll
//! slice so a single step is bounded by the socket timeout). The phone completes HELLO/ACCEPT
//! then sends a `CodecConfig`, a `Key`, a few `Delta`s, one `MetricsSnapshot`, and keepalives on
//! its strict +1 sequence; the desktop decodes the frames with a `FakeVideoDecoder`, measures
//! arrival/decoded rates locally, keeps the phone-reported metrics labelled, and shuts down with
//! `LocalClose`.

use std::{
    collections::BTreeMap,
    io::{ErrorKind, Read},
    net::TcpStream,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant, SystemTime},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_reconnect_connection, phone_id_for_spki, read_session_frame, write_session_frame,
    AuthenticatedPhoneSession, DecodedFrameCounter, DesktopSessionPipeline, DesktopTlsIdentity,
    DesktopVideoSessionReceiver, FakeVideoDecoder, FileTrustedPhoneStore, LoopbackLanListener,
    LoopbackLanOptions, RecordingDecodedFrameSink, SessionEnd, SessionFrame, SessionFramePayload,
    SessionRuntimeConfig, StaticFrameKindClassifier, TrustedPhoneIdentity, VideoDecoderError,
};

const SESSION_ID: &str = "session-pipeline-loopback";
const HELLO_SEQUENCE: i32 = 1;
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const ACCEPT_DEADLINE: Duration = Duration::from_millis(1500);

/// Deliberately shorter than `poll_slice`: the socket read timeout bounds one blocking read.
const SOCKET_READ_TIMEOUT: Duration = Duration::from_millis(25);
const SOCKET_WRITE_TIMEOUT: Duration = Duration::from_millis(1000);
const PHONE_READ_TIMEOUT: Duration = Duration::from_millis(100);
const PHONE_SEND_INTERVAL: Duration = Duration::from_millis(30);
const METRICS_WINDOW: Duration = Duration::from_secs(5);

type DesktopPipeline = DesktopSessionPipeline<
    TcpStream,
    StaticFrameKindClassifier,
    FakeVideoDecoder<RecordingDecodedFrameSink>,
>;

fn test_config() -> SessionRuntimeConfig {
    SessionRuntimeConfig {
        poll_slice: Duration::from_millis(30),
        frame_budget: Duration::from_millis(2000),
        keepalive_interval: Duration::from_millis(150),
        dead_threshold: Duration::from_millis(600),
    }
}

#[test]
fn pipeline_decodes_over_loopback_and_shuts_down() {
    let fixture = Fixture::new("decodes-and-shuts-down");
    let config = test_config();
    assert!(SOCKET_READ_TIMEOUT < config.poll_slice);

    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    let run = Arc::new(AtomicBool::new(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let phone_run = Arc::clone(&run);
    let phone_stopped = Arc::clone(&stopped);

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);

            // A codec config, a key, three deltas, one metrics snapshot, then keepalives.
            let mut sequence = HELLO_SEQUENCE + 1;
            let scripted = scripted_phone_payloads();
            for payload in scripted {
                send_phone_frame(&mut phone, sequence, payload);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }
            while phone_run.load(Ordering::SeqCst) {
                send_phone_frame(&mut phone, sequence, SessionFramePayload::Keepalive);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }

            phone_stopped.store(true, Ordering::SeqCst);
            drain_until_close(&mut phone)
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        let mut pipeline = build_pipeline(session, start, config);

        // Drive until the four video frames (key + 3 deltas) decode AND the arrival rate and
        // phone-reported metrics are available, or fail after a generous deadline.
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let now = Instant::now();
            if let Err(end) = pipeline.step(now) {
                panic!("pipeline ended unexpectedly while the peer was alive: {end:?}");
            }
            let snapshot = pipeline.metrics(now);
            let decoded = pipeline.decoder().frames_emitted();
            if decoded >= 4 && snapshot.arrival_fps.is_some() && snapshot.phone_reported.is_some() {
                break;
            }
            assert!(
                now < deadline,
                "pipeline did not reach the expected decoded/metrics state in time"
            );
        }

        let now = Instant::now();
        let snapshot = pipeline.metrics(now);
        assert_eq!(
            pipeline.decoder().sink().frames().len(),
            4,
            "decoder must have emitted one frame per non-config chunk"
        );
        assert!(
            snapshot.arrival_fps.is_some(),
            "arrival fps must be measured once two chunks arrived"
        );
        assert!(
            snapshot.decoded_fps.is_some(),
            "decoded fps must be measured once two frames decoded"
        );
        let phone_reported = snapshot
            .phone_reported
            .expect("phone-reported metrics must be present");
        assert_eq!(
            phone_reported.frame_rate, 30,
            "phone frame rate kept verbatim"
        );
        assert_eq!(
            snapshot.dropped_chunks, 0,
            "no saturation in the happy path"
        );

        run.store(false, Ordering::SeqCst);
        wait_for(&stopped, Duration::from_secs(2));

        let (end, final_snapshot) = pipeline.shutdown();
        assert_eq!(end, SessionEnd::LocalClose);
        assert_eq!(
            final_snapshot.total_chunks, 5,
            "five video chunks arrived in all (codec config + key + three deltas)"
        );

        assert!(
            phone.join().unwrap(),
            "phone must observe close_notify / EOF after shutdown"
        );
    });

    fixture.cleanup();
}

#[test]
fn pipeline_sends_commands_and_drains_inbound_controls() {
    let fixture = Fixture::new("control-passthrough");
    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();
    let arguments = BTreeMap::from([("v".to_string(), "1".to_string())]);

    std::thread::scope(|scope| {
        let (done_tx, done_rx) = std::sync::mpsc::channel::<()>();
        let expected = arguments.clone();
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);
            let outbound = read_session_frame(&mut phone, Instant::now() + OP_TIMEOUT).unwrap();
            assert_eq!(outbound.sequence(), HELLO_SEQUENCE + 2);
            assert_eq!(
                outbound.payload(),
                &SessionFramePayload::CameraControlCommand {
                    command: "quality_subscribe".into(),
                    arguments: expected.clone(),
                }
            );
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::CameraControlCommand {
                    command: "quality_state".into(),
                    arguments: expected,
                },
            );
            let _ = done_rx.recv();
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        let mut pipeline = build_pipeline(session, start, test_config());
        pipeline
            .send_command("quality_subscribe".into(), arguments.clone(), start)
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let outcome = pipeline
                .step(Instant::now())
                .expect("control must not end session");
            if outcome.outcome.received_frame {
                break;
            }
            assert!(Instant::now() < deadline, "control frame did not arrive");
        }
        assert_eq!(
            pipeline.take_controls(),
            vec![("quality_state".into(), arguments)]
        );
        assert!(pipeline.take_controls().is_empty());
        drop(done_tx);
        phone.join().unwrap();
    });
    fixture.cleanup();
}

#[test]
fn pipeline_survives_decoder_backpressure() {
    let fixture = Fixture::new("survives-backpressure");
    let config = test_config();

    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    let run = Arc::new(AtomicBool::new(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let phone_run = Arc::clone(&run);
    let phone_stopped = Arc::clone(&stopped);

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);
            let mut sequence = HELLO_SEQUENCE + 1;
            for payload in scripted_phone_payloads() {
                send_phone_frame(&mut phone, sequence, payload);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }
            while phone_run.load(Ordering::SeqCst) {
                send_phone_frame(&mut phone, sequence, SessionFramePayload::Keepalive);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }
            phone_stopped.store(true, Ordering::SeqCst);
            drain_until_close(&mut phone)
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        // The decoder backpressures on the codec config and again on its re-push before the
        // keyframe, so the keyframe and the following deltas are dropped until a fresh keyframe
        // (none arrives), while the session stays alive and the drops are counted.
        let decoder = FakeVideoDecoder::new(RecordingDecodedFrameSink::default())
            .script_error(VideoDecoderError::Backpressure)
            .script_error(VideoDecoderError::Backpressure);
        let mut pipeline: DesktopPipeline = DesktopSessionPipeline::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            decoder,
            config,
            METRICS_WINDOW,
            start,
        )
        .expect("pipeline builds");

        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let now = Instant::now();
            if let Err(end) = pipeline.step(now) {
                panic!("pipeline ended unexpectedly under backpressure: {end:?}");
            }
            if pipeline.metrics(now).dropped_chunks > 0 {
                break;
            }
            assert!(
                now < deadline,
                "expected a dropped chunk under backpressure"
            );
        }

        let now = Instant::now();
        assert!(
            pipeline.metrics(now).dropped_chunks > 0,
            "saturation must be counted as dropped chunks"
        );

        run.store(false, Ordering::SeqCst);
        wait_for(&stopped, Duration::from_secs(2));

        let (end, _snapshot) = pipeline.shutdown();
        assert_eq!(end, SessionEnd::LocalClose);
        assert!(
            phone.join().unwrap(),
            "phone must observe close after shutdown"
        );
    });

    fixture.cleanup();
}

/// Task f2 (`odd/tasks/review-followups.md`, contract section 4.2): arrivals count whole encoded
/// chunks reaching the sink, not wire fragments. The phone sends three whole chunks (codec config,
/// key, delta), each split into three `VIDEO_CHUNK_FRAGMENT_V1` frames, so nine video frames cross
/// the wire but only three reassembled chunks reach the sink.
#[test]
fn arrival_fps_counts_reassembled_chunks_not_fragments() {
    let fixture = Fixture::new("counts-reassembled-chunks");
    let config = test_config();

    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    let run = Arc::new(AtomicBool::new(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let phone_run = Arc::clone(&run);
    let phone_stopped = Arc::clone(&stopped);

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);
            let mut sequence = HELLO_SEQUENCE + 1;
            for payload in fragmented_phone_payloads() {
                send_phone_frame(&mut phone, sequence, payload);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }
            while phone_run.load(Ordering::SeqCst) {
                send_phone_frame(&mut phone, sequence, SessionFramePayload::Keepalive);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }
            phone_stopped.store(true, Ordering::SeqCst);
            drain_until_close(&mut phone)
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        let mut pipeline = build_pipeline(session, start, config);

        // Drive until the key and the delta decode (the codec config emits no frame), which
        // happens only after all nine fragments were received and reassembled.
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let now = Instant::now();
            if let Err(end) = pipeline.step(now) {
                panic!("pipeline ended unexpectedly while the peer was alive: {end:?}");
            }
            if pipeline.decoder().frames_emitted() >= 2 {
                break;
            }
            assert!(
                now < deadline,
                "pipeline did not decode the reassembled chunks in time"
            );
        }

        let snapshot = pipeline.metrics(Instant::now());
        assert_eq!(
            snapshot.total_chunks, FRAGMENTED_CHUNKS,
            "arrivals must count whole reassembled chunks, not fragments"
        );
        assert_eq!(
            snapshot.total_bytes, FRAGMENTED_CHUNK_BYTES,
            "arrival bytes must be the whole reassembled chunk payloads"
        );
        assert!(
            snapshot.arrival_fps.is_some(),
            "arrival fps must be measured once two whole chunks arrived"
        );
        assert_eq!(
            pipeline.video_fragments_received(),
            FRAGMENTED_CHUNKS * FRAGMENTS_PER_CHUNK as u64,
            "fragments are counted apart from whole-chunk arrivals"
        );

        run.store(false, Ordering::SeqCst);
        wait_for(&stopped, Duration::from_secs(2));

        let (end, final_snapshot) = pipeline.shutdown();
        assert_eq!(end, SessionEnd::LocalClose);
        assert_eq!(final_snapshot.total_chunks, FRAGMENTED_CHUNKS);
        assert!(
            phone.join().unwrap(),
            "phone must observe close after shutdown"
        );
    });

    fixture.cleanup();
}

/// Whole chunks in [`fragmented_phone_payloads`].
const FRAGMENTED_CHUNKS: u64 = 3;
/// Fragments per whole chunk in [`fragmented_phone_payloads`].
const FRAGMENTS_PER_CHUNK: i32 = 3;
/// Total reassembled H.264 bytes across the whole chunks (three chunks of six bytes).
const FRAGMENTED_CHUNK_BYTES: u64 = 18;

/// A codec config, a key and a delta, each six bytes split into three two-byte fragments: nine
/// `VIDEO_CHUNK_FRAGMENT_V1` frames that reassemble into three whole chunks.
fn fragmented_phone_payloads() -> Vec<SessionFramePayload> {
    type FragmentCtor = fn(i32, i64, i32, i32, i32, Vec<u8>) -> SessionFramePayload;
    let chunks: [(FragmentCtor, i64, [u8; 6]); 3] = [
        (
            SessionFramePayload::video_chunk_fragment_v1_codec_config,
            0,
            [0x00, 0x00, 0x01, 0x67, 0x42, 0x00],
        ),
        (
            SessionFramePayload::video_chunk_fragment_v1_key,
            10_000,
            [0x00, 0x00, 0x01, 0x65, 0x88, 0x84],
        ),
        (
            SessionFramePayload::video_chunk_fragment_v1_delta,
            20_000,
            [0x00, 0x00, 0x01, 0x41, 0x9a, 0x01],
        ),
    ];
    let mut payloads = Vec::new();
    for (chunk_index, (ctor, pts, bytes)) in chunks.into_iter().enumerate() {
        for (fragment_index, fragment) in bytes.chunks(2).enumerate() {
            payloads.push(ctor(
                chunk_index as i32,
                pts,
                fragment_index as i32,
                FRAGMENTS_PER_CHUNK,
                bytes.len() as i32,
                fragment.to_vec(),
            ));
        }
    }
    payloads
}

/// A codec config, a key, three deltas and one metrics snapshot: the decoder emits one frame per
/// non-config chunk, so this scripts exactly four decoded frames in the happy path.
fn scripted_phone_payloads() -> Vec<SessionFramePayload> {
    vec![
        SessionFramePayload::video_chunk_v2_codec_config(0, 0, vec![0x00, 0x00, 0x01]),
        SessionFramePayload::video_chunk_v2_key(1, 10_000, vec![0x65, 0x88, 0x84]),
        SessionFramePayload::video_chunk_v2_delta(2, 20_000, vec![0x41, 0x9a]),
        SessionFramePayload::video_chunk_v2_delta(3, 30_000, vec![0x41, 0x9b]),
        SessionFramePayload::video_chunk_v2_delta(4, 40_000, vec![0x41, 0x9c]),
        SessionFramePayload::MetricsSnapshot {
            captured_at_us: 123_456,
            dropped_frames: 2,
            latency_ms: 33,
            frame_rate: 30,
        },
    ]
}

// --- Harness ---------------------------------------------------------------------------

struct Fixture {
    desktop_identity: DesktopTlsIdentity,
    phone_identity: DesktopTlsIdentity,
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
                    phone_id,
                    "Reconnecting Phone",
                    phone_identity.spki_der_p256().to_vec(),
                )
                .unwrap(),
            )
            .unwrap();

        Self {
            desktop_identity,
            phone_identity,
            store_path,
        }
    }

    fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.store_path);
        let _ = std::fs::remove_file(self.store_path.with_extension("lock"));
    }
}

fn bind_listener() -> LoopbackLanListener {
    LoopbackLanListener::bind_with_options(LoopbackLanOptions {
        read_timeout: SOCKET_READ_TIMEOUT,
        write_timeout: SOCKET_WRITE_TIMEOUT,
    })
    .expect("bind loopback listener")
}

fn connect(addr: std::net::SocketAddr) -> TcpStream {
    let tcp = TcpStream::connect(addr).expect("connect phone over loopback");
    tcp.set_read_timeout(Some(PHONE_READ_TIMEOUT)).unwrap();
    tcp.set_write_timeout(Some(SOCKET_WRITE_TIMEOUT)).unwrap();
    tcp
}

fn accept_desktop_session(
    listener: &LoopbackLanListener,
    fixture: &Fixture,
) -> AuthenticatedPhoneSession<TcpStream> {
    let tcp = listener
        .accept(ACCEPT_DEADLINE)
        .expect("accept reconnect connection");
    let lookup = Arc::new(FileTrustedPhoneStore::new(&fixture.store_path));
    accept_phone_reconnect_connection(tcp, &fixture.desktop_identity, lookup, OP_TIMEOUT)
        .expect("reconnect handshake should succeed")
}

fn build_pipeline(
    session: AuthenticatedPhoneSession<TcpStream>,
    start: Instant,
    config: SessionRuntimeConfig,
) -> DesktopPipeline {
    DesktopSessionPipeline::new(
        session,
        DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
        FakeVideoDecoder::new(RecordingDecodedFrameSink::default()),
        config,
        METRICS_WINDOW,
        start,
    )
    .expect("pipeline builds")
}

fn phone_hello_and_accept(
    tcp: TcpStream,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, TcpStream> {
    let mut phone = phone_tls_stream(tcp, root_cert, phone_identity);
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
    phone: &mut StreamOwned<ClientConnection, TcpStream>,
    sequence: i32,
    payload: SessionFramePayload,
) {
    let frame = SessionFrame::new(sequence, SESSION_ID, payload);
    write_session_frame(phone, &frame).unwrap();
}

fn drain_until_close(phone: &mut StreamOwned<ClientConnection, TcpStream>) -> bool {
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut buf = [0u8; 4096];
    while Instant::now() < deadline {
        match phone.read(&mut buf) {
            Ok(0) => return true,
            Ok(_) => continue,
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                continue
            }
            Err(error) if error.kind() == ErrorKind::UnexpectedEof => return true,
            Err(_) => return false,
        }
    }
    false
}

fn wait_for(flag: &AtomicBool, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while !flag.load(Ordering::SeqCst) && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn phone_tls_stream(
    tcp: TcpStream,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, TcpStream> {
    let mut tcp = tcp;
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut tcp).unwrap();
    }
    StreamOwned::new(client, tcp)
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
        "chinchillacam-desktop-session-pipeline-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}
