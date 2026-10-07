//! Task s3 (`odd/tasks/session-runtime.md`, contract section 4): drive `SessionRuntime`
//! end-to-end over a REAL loopback TCP socket (unlike s2, which used the in-memory duplex
//! double). The desktop binds `LoopbackLanListener::bind_with_options` with a SHORT per-stream
//! read timeout and accepts the trusted reconnection; the phone connects with
//! `TcpStream::connect`, presents a client certificate over rustls, completes HELLO/ACCEPT,
//! then keeps the session alive by sending `Keepalive` and `VideoChunkV2` frames on its own
//! strict +1 sequence (same session id). The desktop drives `SessionRuntime::step` on the
//! real wall clock for well over `dead_threshold` and asserts the session stays alive
//! (keepalives flow both ways, video reaches the sink), then `shutdown()` returns `LocalClose`
//! and the phone observes the TLS close.
//!
//! # Why the socket read timeout is shorter than the poll slice
//!
//! A single `SessionRuntime::step` is bounded by the socket's read timeout, because
//! `read_session_frame_with_budgets` blocks inside one `Read::read` for up to that timeout
//! before it can consult its `poll_slice` idle deadline (a `WouldBlock`/`TimedOut` return is
//! the only thing that lets the idle deadline fire). So the socket read timeout is chosen
//! SHORTER than `poll_slice`/`keepalive_interval` on purpose: if it were longer than
//! `dead_threshold`, a single idle read could overrun the entire liveness budget and the loop
//! could never send a keepalive in time. These tests exercise that real timeout/poll-slice
//! interaction over the wall clock, so their timing bounds are deliberately generous.

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
    AuthenticatedPhoneSession, BoundedEncodedVideoQueue, DesktopTlsIdentity,
    DesktopVideoSessionReceiver, FileTrustedPhoneStore, LoopbackLanListener, LoopbackLanOptions,
    SessionEnd, SessionFrame, SessionFramePayload, SessionRuntime, SessionRuntimeConfig,
    StaticFrameKindClassifier, TrustedPhoneIdentity,
};

const SESSION_ID: &str = "session-rt-loopback";
const HELLO_SEQUENCE: i32 = 1;
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const ACCEPT_DEADLINE: Duration = Duration::from_millis(1500);

/// Deliberately shorter than `poll_slice` (see the module docs): the socket read timeout is
/// what actually bounds a single blocking read inside a `step`.
const SOCKET_READ_TIMEOUT: Duration = Duration::from_millis(25);
const SOCKET_WRITE_TIMEOUT: Duration = Duration::from_millis(1000);
const PHONE_READ_TIMEOUT: Duration = Duration::from_millis(100);
const PHONE_SEND_INTERVAL: Duration = Duration::from_millis(50);

type DesktopRuntime =
    SessionRuntime<TcpStream, StaticFrameKindClassifier, BoundedEncodedVideoQueue>;

fn test_config() -> SessionRuntimeConfig {
    SessionRuntimeConfig {
        poll_slice: Duration::from_millis(30),
        frame_budget: Duration::from_millis(2000),
        keepalive_interval: Duration::from_millis(150),
        dead_threshold: Duration::from_millis(600),
    }
}

struct PhoneOutcome {
    video_sent: u32,
    keepalives_sent: u32,
    observed_close: bool,
}

#[test]
fn runtime_keeps_loopback_session_alive_with_keepalives() {
    let fixture = Fixture::new("alive-with-keepalives");
    let config = test_config();
    // The socket read timeout is shorter than the poll slice, and the config honours
    // poll_slice < keepalive_interval < dead_threshold (the runtime also validates the first
    // two at construction; this documents the third).
    assert!(SOCKET_READ_TIMEOUT < config.poll_slice);
    assert!(config.poll_slice < config.keepalive_interval);
    assert!(config.dead_threshold > config.keepalive_interval);

    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    let run = Arc::new(AtomicBool::new(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let phone_run = Arc::clone(&run);
    let phone_stopped = Arc::clone(&stopped);

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || -> PhoneOutcome {
            let mut phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);

            // Keep the session alive: send on a strict +1 sequence (HELLO + 1, +2, ...), a few
            // video chunks first and keepalives afterwards, well within the dead threshold.
            let mut sequence = HELLO_SEQUENCE + 1;
            let mut video_sent = 0u32;
            let mut keepalives_sent = 0u32;
            while phone_run.load(Ordering::SeqCst) {
                let payload = if video_sent < 3 {
                    let pts = 10_000 * i64::from(video_sent + 1);
                    video_sent += 1;
                    SessionFramePayload::video_chunk_v2_key(
                        video_sent as i32 - 1,
                        pts,
                        vec![0x65, 0x88, 0x84],
                    )
                } else {
                    keepalives_sent += 1;
                    SessionFramePayload::Keepalive
                };
                send_phone_frame(&mut phone, sequence, payload);
                sequence += 1;
                std::thread::sleep(PHONE_SEND_INTERVAL);
            }

            // The desktop is shutting down: announce we stopped sending, then drain inbound
            // (desktop keepalives) until the TLS close arrives.
            phone_stopped.store(true, Ordering::SeqCst);
            let observed_close = drain_until_close(&mut phone);
            PhoneOutcome {
                video_sent,
                keepalives_sent,
                observed_close,
            }
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start, config);

        // Drive the loop for well over the dead threshold (3x). A `SessionEnd` here is a
        // failure: a live, keepalive-answering peer must keep the session alive.
        let run_for = config.dead_threshold * 3;
        while start.elapsed() < run_for {
            if let Err(end) = runtime.step(Instant::now()) {
                panic!("runtime ended unexpectedly while the peer was alive: {end:?}");
            }
        }

        assert!(
            runtime.keepalives_sent() > 0,
            "desktop must have sent keepalives"
        );
        assert!(
            runtime.keepalives_received() > 0,
            "desktop must have received keepalives from the phone"
        );
        assert!(
            runtime.video_frames_delivered() >= 1,
            "video must reach the sink"
        );
        assert!(
            !runtime.sink().is_empty(),
            "video chunk must reach the sink"
        );

        // Let the phone stop sending before we close, so no phone write races our close_notify.
        run.store(false, Ordering::SeqCst);
        wait_for(&stopped, Duration::from_secs(2));

        assert_eq!(runtime.shutdown(), SessionEnd::LocalClose);

        let phone = phone.join().unwrap();
        assert!(
            phone.observed_close,
            "phone must observe close_notify / EOF after shutdown"
        );
        assert!(phone.keepalives_sent > 0, "phone must have sent keepalives");
        assert_eq!(phone.video_sent, 3, "phone must have sent its video frames");
    });

    fixture.cleanup();
}

#[test]
fn loopback_peer_disconnect_ends_runtime() {
    let fixture = Fixture::new("peer-disconnect");
    let config = test_config();
    let listener = bind_listener();
    let addr = listener.local_addr();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        scope.spawn(move || {
            let phone = phone_hello_and_accept(connect(addr), &cert, &phone_identity);
            // Drop the stream right after the handshake (no further frames, no clean close):
            // the desktop must end the session in bounded time.
            drop(phone);
        });

        let session = accept_desktop_session(&listener, &fixture);
        let start = Instant::now();
        let mut runtime = build_runtime(session, start, config);

        let deadline = start + config.dead_threshold * 3;
        let end = loop {
            match runtime.step(Instant::now()) {
                Ok(_) => assert!(
                    Instant::now() < deadline,
                    "runtime did not end after the peer disconnected"
                ),
                Err(end) => break end,
            }
        };
        assert!(
            matches!(end, SessionEnd::ReadFailed(_) | SessionEnd::PeerDead),
            "peer disconnect must end with ReadFailed or PeerDead, got {end:?}"
        );
    });

    fixture.cleanup();
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

fn build_runtime(
    session: AuthenticatedPhoneSession<TcpStream>,
    start: Instant,
    config: SessionRuntimeConfig,
) -> DesktopRuntime {
    SessionRuntime::new(
        session,
        DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
        BoundedEncodedVideoQueue::new(8).unwrap(),
        config,
        start,
    )
    .unwrap()
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

/// Reads and discards inbound bytes (buffered desktop keepalives) until the desktop's
/// best-effort `close_notify` surfaces as a clean EOF, or the bounded deadline passes.
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
        "chinchillacam-session-runtime-loopback-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}
