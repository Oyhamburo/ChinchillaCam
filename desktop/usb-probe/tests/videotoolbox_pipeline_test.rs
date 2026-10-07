#![cfg(target_os = "macos")]
//! Task v3 (`odd/tasks/videotoolbox-decoder.md`): drives a `DesktopSessionPipeline` with the real
//! `VideoToolboxDecoder` over a REAL loopback TCP socket. The phone sends the H.264 fixture as a
//! `CodecConfig` (SPS+PPS) and a `Key` (SEI+IDR), then keepalives; the desktop steps until one
//! decoded NV12 frame arrives and measures the wall time of the step that decoded it, since
//! decoding runs synchronously inside the single-thread session loop (section 5 risk).
//!
//! `config_step_does_not_wait_for_session_creation` (`odd/tasks/threaded-decoder.md`) runs the
//! same session with the decoder behind a `ThreadedVideoDecoder`: the step that receives the
//! codec config only enqueues, and the frame reaches a `ChannelDecodedFrameSink` consumer.
//!
//! The loopback/phone harness is copied from `tests/desktop_session_pipeline_test.rs`.

use std::{
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
    AuthenticatedPhoneSession, ChannelDecodedFrameSink, DecodedFrameCounter,
    DesktopSessionPipeline, DesktopTlsIdentity, DesktopVideoSessionReceiver, FileTrustedPhoneStore,
    LoopbackLanListener, LoopbackLanOptions, PixelFormat, RecordingDecodedFrameSink, SessionEnd,
    SessionFrame, SessionFramePayload, SessionRuntimeConfig, StaticFrameKindClassifier,
    ThreadedVideoDecoder, ThreadedVideoDecoderConfig, TrustedPhoneIdentity, VideoToolboxDecoder,
};

const FIXTURE: &[u8] = include_bytes!("fixtures/t21b1-16x16-idr.h264");
const KEY_PTS_US: i64 = 33_333;
const SESSION_ID: &str = "videotoolbox-pipeline-loopback";
const HELLO_SEQUENCE: i32 = 1;
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const SOCKET_READ_TIMEOUT: Duration = Duration::from_millis(25);
const SOCKET_WRITE_TIMEOUT: Duration = Duration::from_millis(1000);
const PHONE_READ_TIMEOUT: Duration = Duration::from_millis(100);
const PHONE_SEND_INTERVAL: Duration = Duration::from_millis(30);
/// Generous budget for the step that runs the synchronous VideoToolbox decode.
const DECODE_STEP_MARGIN: Duration = Duration::from_millis(200);
/// Budget for the step that receives the codec config when the decoder only enqueues; inline
/// session creation was measured at 375-710 ms.
const CONFIG_STEP_MARGIN: Duration = Duration::from_millis(100);
const RUNTIME_CONFIG: SessionRuntimeConfig = SessionRuntimeConfig {
    poll_slice: Duration::from_millis(30),
    frame_budget: Duration::from_millis(2000),
    keepalive_interval: Duration::from_millis(150),
    dead_threshold: Duration::from_millis(600),
};

#[test]
fn pipeline_decodes_real_h264_over_loopback() {
    let config = RUNTIME_CONFIG;
    with_fixture_session(|session, stop_phone| {
        let mut pipeline = DesktopSessionPipeline::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            VideoToolboxDecoder::new(RecordingDecodedFrameSink::default()),
            config,
            Duration::from_secs(5),
            Instant::now(),
        )
        .expect("pipeline builds");

        // Step until the decoder emits one frame, timing the step that produced it and the step
        // that received the codec config (it creates the VideoToolbox session synchronously).
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut config_step = None;
        let decode_step = loop {
            let now = Instant::now();
            let step = pipeline
                .step(now)
                .unwrap_or_else(|end| panic!("pipeline ended while the peer was alive: {end:?}"));
            let elapsed = now.elapsed();
            if config_step.is_none() && pipeline.metrics(now).total_chunks > 0 {
                config_step = Some(elapsed);
            }
            if step.frames_decoded > 0 {
                break elapsed;
            }
            assert!(now < deadline, "no decoded frame before the deadline");
        };
        // Only the decode step is bounded: session creation was observed at 375-710 ms on this
        // machine (cold vs warm), above `keepalive_interval`, so it is recorded, not asserted.
        eprintln!("v3 config step: {config_step:?}; decode step: {decode_step:?}");
        assert!(
            decode_step < config.poll_slice + DECODE_STEP_MARGIN,
            "decode step took {decode_step:?}"
        );

        let frames = pipeline.decoder().sink().frames();
        assert_eq!(frames.len(), 1);
        assert_fixture_frame(&frames[0]);
        assert_eq!(pipeline.decoder().frames_emitted(), 1);

        let snapshot = pipeline.metrics(Instant::now());
        assert_eq!(snapshot.total_chunks, 2, "codec config + key arrived");
        assert_eq!(snapshot.decoded_fps, None, "one frame cannot measure fps");
        assert_eq!(snapshot.dropped_chunks, 0);

        stop_phone();
        let (end, final_snapshot) = pipeline.shutdown();
        assert_eq!(end, SessionEnd::LocalClose);
        assert_eq!(final_snapshot.total_chunks, 2);
    });
}

#[test]
fn config_step_does_not_wait_for_session_creation() {
    let config = RUNTIME_CONFIG;
    with_fixture_session(|session, stop_phone| {
        let (sink, frames) = ChannelDecodedFrameSink::bounded(4).expect("channel sink");
        let decoder = ThreadedVideoDecoder::spawn(
            "videotoolbox-decoder",
            ThreadedVideoDecoderConfig::new(16, Duration::from_secs(2)).expect("decoder config"),
            move || VideoToolboxDecoder::new(sink),
        )
        .expect("spawn decoder worker");
        let mut pipeline = DesktopSessionPipeline::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            decoder,
            config,
            Duration::from_secs(5),
            Instant::now(),
        )
        .expect("pipeline builds");
        let consumer = std::thread::spawn(move || {
            let frame = frames.recv_timeout(Duration::from_secs(5));
            (frame, Instant::now())
        });

        // `frames_emitted` is published by the worker after the decode returns, so the frame is
        // credited to whichever later step observes it; keep stepping (keepalives) until then.
        let deadline = Instant::now() + Duration::from_secs(5);
        let (mut config_step, mut config_at, mut key_at) = (None, None, None);
        let mut frames_credited = 0;
        while !(consumer.is_finished() && frames_credited > 0) {
            let now = Instant::now();
            let step = pipeline
                .step(now)
                .unwrap_or_else(|end| panic!("pipeline ended while the peer was alive: {end:?}"));
            let elapsed = now.elapsed();
            let total_chunks = pipeline.metrics(now).total_chunks;
            if config_step.is_none() && total_chunks > 0 {
                (config_step, config_at) = (Some(elapsed), Some(now));
            }
            if key_at.is_none() && total_chunks > 1 {
                key_at = Some(Instant::now());
            }
            frames_credited += step.frames_decoded;
            assert!(
                now < deadline,
                "no decoded frame credited before the deadline"
            );
        }

        let (frame, received_at) = consumer.join().expect("consumer thread");
        let frame = frame.expect("decoded frame reaches the channel consumer");
        let config_step = config_step.expect("codec config step observed");
        let from_config = received_at - config_at.expect("codec config instant");
        let from_key = received_at.saturating_duration_since(key_at.expect("key instant"));
        eprintln!(
            "threaded config step: {config_step:?}; config->frame: {from_config:?}; \
             key->frame: {from_key:?}"
        );
        assert!(
            config_step < config.poll_slice + CONFIG_STEP_MARGIN,
            "config step took {config_step:?}"
        );
        assert_fixture_frame(&frame);
        assert_eq!(frames_credited, 1);
        assert_eq!(pipeline.decoder().frames_emitted(), 1);
        assert_eq!(pipeline.decoder().dropped_decodes(), 0);

        let snapshot = pipeline.metrics(Instant::now());
        assert_eq!(snapshot.total_chunks, 2, "codec config + key arrived");
        assert_eq!(snapshot.decoded_fps, None, "one frame cannot measure fps");
        assert_eq!(snapshot.dropped_chunks, 0);

        stop_phone();
        let (end, final_snapshot) = pipeline.shutdown();
        assert_eq!(end, SessionEnd::LocalClose);
        assert_eq!(final_snapshot.total_chunks, 2);
    });
}

fn assert_fixture_frame(frame: &usb_probe::DecodedVideoFrame) {
    assert_eq!(frame.pts_us(), KEY_PTS_US as u64);
    assert_eq!((frame.width(), frame.height()), (16, 16));
    assert_eq!(frame.pixel_format(), PixelFormat::Nv12);
    assert_eq!(frame.data().len(), 16 * 16 * 3 / 2);
}

/// Opens a loopback session in which the phone sends the fixture (codec config, then key) and
/// keepalives until `stop_phone` is called; `drive` must call it before shutting down, and the
/// phone must then observe the close.
fn with_fixture_session(drive: impl FnOnce(AuthenticatedPhoneSession<TcpStream>, &dyn Fn())) {
    let fixture = Fixture::new();
    let listener = LoopbackLanListener::bind_with_options(LoopbackLanOptions {
        read_timeout: SOCKET_READ_TIMEOUT,
        write_timeout: SOCKET_WRITE_TIMEOUT,
    })
    .expect("bind loopback listener");
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();
    let run = Arc::new(AtomicBool::new(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let (phone_run, phone_stopped) = (Arc::clone(&run), Arc::clone(&stopped));

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);
            let (config_bytes, access_unit) = split_fixture();
            let scripted = [
                SessionFramePayload::video_chunk_v2_codec_config(0, 0, config_bytes),
                SessionFramePayload::video_chunk_v2_key(1, KEY_PTS_US, access_unit),
            ];
            let mut sequence = HELLO_SEQUENCE + 1;
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

        let tcp = listener.accept(OP_TIMEOUT).expect("accept reconnect");
        let lookup = Arc::new(FileTrustedPhoneStore::new(&fixture.store_path));
        let session: AuthenticatedPhoneSession<TcpStream> =
            accept_phone_reconnect_connection(tcp, &fixture.desktop_identity, lookup, OP_TIMEOUT)
                .expect("reconnect handshake should succeed");
        let stop_phone = || {
            run.store(false, Ordering::SeqCst);
            let stop_deadline = Instant::now() + Duration::from_secs(2);
            while !stopped.load(Ordering::SeqCst) && Instant::now() < stop_deadline {
                std::thread::sleep(Duration::from_millis(5));
            }
        };
        drive(session, &stop_phone);
        assert!(
            phone.join().unwrap(),
            "phone must observe close after shutdown"
        );
    });

    fixture.cleanup();
}

/// Splits the fixture into SPS+PPS and the access unit (SEI + IDR), both Annex-B, exactly as
/// `tests/videotoolbox_decoder_test.rs` does.
fn split_fixture() -> (Vec<u8>, Vec<u8>) {
    let starts: Vec<usize> = (0..FIXTURE.len() - 3)
        .filter(|&index| FIXTURE[index..index + 3] == [0, 0, 1])
        .map(|index| {
            if index > 0 && FIXTURE[index - 1] == 0 {
                index - 1
            } else {
                index
            }
        })
        .collect();
    let access_unit_start = starts[2];
    (
        FIXTURE[..access_unit_start].to_vec(),
        FIXTURE[access_unit_start..].to_vec(),
    )
}

// --- Harness (copied from tests/desktop_session_pipeline_test.rs) ------------------------

struct Fixture {
    desktop_identity: DesktopTlsIdentity,
    phone_identity: DesktopTlsIdentity,
    store_path: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
        let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
        let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
        let nanos = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let store_path = std::env::temp_dir().join(format!(
            "chinchillacam-videotoolbox-pipeline-{}-{nanos}.txt",
            std::process::id()
        ));
        FileTrustedPhoneStore::new(&store_path)
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

fn connect(addr: std::net::SocketAddr) -> TcpStream {
    let tcp = TcpStream::connect(addr).expect("connect phone over loopback");
    tcp.set_read_timeout(Some(PHONE_READ_TIMEOUT)).unwrap();
    tcp.set_write_timeout(Some(SOCKET_WRITE_TIMEOUT)).unwrap();
    tcp
}

fn phone_hello_and_accept(
    mut tcp: TcpStream,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, TcpStream> {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    let client_cert = CertificateDer::from(phone_identity.certificate_der().to_vec());
    let client_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        phone_identity.private_key_pkcs8_der().to_vec(),
    ));
    let client_config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_client_auth_cert(vec![client_cert], client_key)
        .unwrap();
    let mut client = ClientConnection::new(
        Arc::new(client_config),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    while client.is_handshaking() {
        client.complete_io(&mut tcp).unwrap();
    }
    let mut phone = StreamOwned::new(client, tcp);

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
