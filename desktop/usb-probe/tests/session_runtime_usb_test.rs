//! Task u3 (`odd/tasks/usb-session-runtime.md`): drive `SessionRuntime` over the USB stack
//! (`FramedUsbStream` -> `UsbTlsCiphertextStream` -> rustls) on a crossed bulk pipe that
//! models real transfers (`common::usb_transfer_pipe`). After the HELLO/ACCEPT handshake the
//! desktop arms a short idle read timeout (contract section 4.5), so an idle bulk read
//! surfaces as `TimedOut` without poisoning and the runtime keeps polling, sending keepalives,
//! and receiving frames. A frame that stalls mid-transfer still ends the session.

// This crate uses only the USB transfer pipe, not the duplex helper.
#[allow(dead_code)]
mod common;

use std::{
    io::{ErrorKind, Read},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant, SystemTime},
};

use common::usb_transfer_pipe::{crossed_transfer_pair, CrossedTransferBulkIo};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_reconnect_connection, phone_id_for_spki, read_session_frame, write_session_frame,
    AuthenticatedPhoneSession, BoundedEncodedVideoQueue, DesktopTlsIdentity,
    DesktopVideoSessionReceiver, FileTrustedPhoneStore, FrameTransferBudget, FramedUsbStream,
    SessionEnd, SessionFrame, SessionFramePayload, SessionRuntime, SessionRuntimeConfig,
    StaticFrameKindClassifier, TrustedPhoneIdentity, UsbBulkIo, UsbProbeError,
    UsbTlsCiphertextStream, USB_TLS_CIPHERTEXT_STREAM_ID,
};

const SESSION_ID: &str = "session-rt-usb";
const HELLO_SEQUENCE: i32 = 1;
const OP_TIMEOUT: Duration = Duration::from_millis(1500);
/// Full bulk read timeout: the handshake and the rest of a started frame use it.
const FRAME_READ_TIMEOUT: Duration = Duration::from_millis(1000);
/// Short idle read timeout armed after the handshake; below `poll_slice`.
const IDLE_READ_TIMEOUT: Duration = Duration::from_millis(20);
/// Phone silence: many idle timeouts and two keepalive intervals, below `dead_threshold`.
const PHONE_SILENCE: Duration = Duration::from_millis(300);

type UsbStream = UsbTlsCiphertextStream<CrossedTransferBulkIo>;
type PhoneStream = StreamOwned<ClientConnection, UsbStream>;
type DesktopRuntime =
    SessionRuntime<UsbStream, StaticFrameKindClassifier, BoundedEncodedVideoQueue>;

fn test_config() -> SessionRuntimeConfig {
    SessionRuntimeConfig {
        poll_slice: Duration::from_millis(30),
        frame_budget: Duration::from_millis(2000),
        keepalive_interval: Duration::from_millis(150),
        dead_threshold: Duration::from_millis(600),
    }
}

#[test]
fn transfer_pipe_models_whole_transfers_timeouts_and_overflow() {
    let (mut a, mut b) = crossed_transfer_pair();
    let mut buffer = [0u8; 8];

    assert_eq!(
        b.read_bulk(&mut buffer, Duration::from_millis(5)),
        Err(UsbProbeError::BulkReadTimeout)
    );
    assert_eq!(b.incoming().read_timeouts(), 1);

    a.write_bulk(b"abc", Duration::ZERO).unwrap();
    a.write_bulk(b"de", Duration::ZERO).unwrap();
    assert_eq!(b.read_bulk(&mut buffer, Duration::from_millis(5)), Ok(3));
    assert_eq!(&buffer[..3], b"abc");
    assert_eq!(b.read_bulk(&mut buffer, Duration::from_millis(5)), Ok(2));
    assert_eq!(&buffer[..2], b"de");

    a.write_bulk(&[7u8; 9], Duration::ZERO).unwrap();
    assert_eq!(
        b.read_bulk(&mut buffer, Duration::from_millis(5)),
        Err(UsbProbeError::UsbBulkTransferFailed(
            rusb::Error::Overflow.to_string()
        ))
    );
}

#[test]
fn session_runtime_survives_usb_idle_and_receives_frames() {
    let fixture = Fixture::new("survives-idle");
    let config = test_config();
    assert!(IDLE_READ_TIMEOUT < config.poll_slice);
    assert!(PHONE_SILENCE > config.keepalive_interval && PHONE_SILENCE < config.dead_threshold);

    let (desktop_io, phone_io) = crossed_transfer_pair();
    let phone_to_desktop = desktop_io.incoming();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || -> (usize, bool) {
            let mut phone = phone_hello_and_accept(phone_io, &cert, &phone_identity);
            std::thread::sleep(PHONE_SILENCE);

            // The desktop kept the session alive during the silence with its own keepalives.
            let keepalive = read_session_frame(&mut phone, Instant::now() + OP_TIMEOUT).unwrap();
            assert_eq!(keepalive.payload(), &SessionFramePayload::Keepalive);
            assert_eq!(keepalive.sequence(), HELLO_SEQUENCE + 2);

            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 1,
                SessionFramePayload::Keepalive,
            );
            send_phone_frame(
                &mut phone,
                HELLO_SEQUENCE + 2,
                SessionFramePayload::video_chunk_v2_key(0, 10_000, vec![0x65, 0x88, 0x84]),
            );
            let (extra_frames, observed_close) = drain_until_close(&mut phone);
            (extra_frames, observed_close)
        });

        let mut session = accept_desktop_session(desktop_io, &fixture);
        session
            .tls
            .sock
            .set_idle_read_timeout(Some(IDLE_READ_TIMEOUT))
            .unwrap();
        let timeouts_before = phone_to_desktop.read_timeouts();
        let start = Instant::now();
        let mut runtime = build_runtime(session, start, config);

        let mut keepalives_sent_before_first_frame = None;
        let deadline = start + Duration::from_secs(3);
        while runtime.video_frames_delivered() == 0 {
            assert!(Instant::now() < deadline, "video never reached the runtime");
            let outcome = match runtime.step(Instant::now()) {
                Ok(outcome) => outcome,
                Err(end) => panic!("runtime ended during USB idle: {end:?}"),
            };
            if outcome.received_frame && keepalives_sent_before_first_frame.is_none() {
                keepalives_sent_before_first_frame = Some(runtime.keepalives_sent());
            }
        }

        assert!(
            phone_to_desktop.read_timeouts() - timeouts_before >= 5,
            "idle bulk reads must have timed out repeatedly without ending the session"
        );
        assert!(
            keepalives_sent_before_first_frame.unwrap() >= 1,
            "desktop must send keepalives while the phone is silent"
        );
        assert_eq!(runtime.keepalives_received(), 1);
        assert_eq!(runtime.sink().len(), 1, "video chunk must reach the sink");

        assert_eq!(runtime.shutdown(), SessionEnd::LocalClose);
        let (_extra_frames, observed_close) = phone.join().unwrap();
        assert!(observed_close, "phone must observe the TLS close");
    });

    fixture.cleanup();
}

#[test]
fn usb_peer_stall_mid_frame_ends_session() {
    let fixture = Fixture::new("stall-mid-frame");
    let config = test_config();
    let (desktop_io, phone_io) = crossed_transfer_pair();
    let cert = fixture.desktop_identity.certificate_der().to_vec();
    let phone_identity = fixture.phone_identity.clone();

    std::thread::scope(|scope| {
        let phone = scope.spawn(move || -> (Instant, PhoneStream) {
            let raw_to_desktop = phone_io.outgoing();
            let phone = phone_hello_and_accept(phone_io, &cert, &phone_identity);
            std::thread::sleep(Duration::from_millis(50));
            // A bulk frame header announcing 64 payload bytes plus only 10 of them, in one
            // transfer, and then nothing.
            let mut partial = USB_TLS_CIPHERTEXT_STREAM_ID.to_le_bytes().to_vec();
            partial.extend_from_slice(&64u32.to_le_bytes());
            partial.extend_from_slice(&[0x17; 10]);
            raw_to_desktop.push_transfer(&partial);
            (Instant::now(), phone)
        });

        let mut session = accept_desktop_session(desktop_io, &fixture);
        session
            .tls
            .sock
            .set_idle_read_timeout(Some(IDLE_READ_TIMEOUT))
            .unwrap();
        let start = Instant::now();
        let mut runtime = build_runtime(session, start, config);

        let deadline = start + Duration::from_secs(4);
        let end = loop {
            match runtime.step(Instant::now()) {
                Ok(_) => assert!(Instant::now() < deadline, "stalled frame never ended"),
                Err(end) => break end,
            }
        };
        let ended_at = Instant::now();
        let (stall_started, _phone) = phone.join().unwrap();

        match &end {
            SessionEnd::ReadFailed(detail) => assert!(
                detail.contains("BulkFrameStalled"),
                "expected a bulk frame stall, got {detail}"
            ),
            other => panic!("expected ReadFailed, got {other:?}"),
        }
        let elapsed = ended_at.duration_since(stall_started);
        assert!(
            elapsed >= FRAME_READ_TIMEOUT && elapsed < FRAME_READ_TIMEOUT * 3,
            "stall must end after the frame read timeout, took {elapsed:?}"
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
        let store_path = unique_store_path(name);
        FileTrustedPhoneStore::new(&store_path)
            .trust(
                TrustedPhoneIdentity::new(
                    phone_id_for_spki(phone_identity.spki_der_p256()),
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

fn usb_stream(io: CrossedTransferBulkIo) -> UsbStream {
    let budget = FrameTransferBudget::new(FRAME_READ_TIMEOUT, 65_536, 512).unwrap();
    UsbTlsCiphertextStream::new(FramedUsbStream::new(io, budget))
}

fn accept_desktop_session(
    io: CrossedTransferBulkIo,
    fixture: &Fixture,
) -> AuthenticatedPhoneSession<UsbStream> {
    let lookup = Arc::new(FileTrustedPhoneStore::new(&fixture.store_path));
    accept_phone_reconnect_connection(
        usb_stream(io),
        &fixture.desktop_identity,
        lookup,
        OP_TIMEOUT,
    )
    .expect("reconnect handshake should succeed")
}

fn build_runtime(
    session: AuthenticatedPhoneSession<UsbStream>,
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
    io: CrossedTransferBulkIo,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> PhoneStream {
    let mut stream = usb_stream(io);
    let mut client = ClientConnection::new(
        Arc::new(client_config(root_cert, phone_identity)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
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
    phone
}

fn send_phone_frame(phone: &mut PhoneStream, sequence: i32, payload: SessionFramePayload) {
    let frame = SessionFrame::new(sequence, SESSION_ID, payload);
    write_session_frame(phone, &frame).unwrap();
}

/// Reads inbound bytes (later desktop keepalives) until the desktop's `close_notify`
/// surfaces as EOF, or a bounded deadline passes. Returns the read count and whether the
/// close was observed.
fn drain_until_close(phone: &mut PhoneStream) -> (usize, bool) {
    let deadline = Instant::now() + Duration::from_secs(4);
    let mut buf = [0u8; 4096];
    let mut reads = 0;
    while Instant::now() < deadline {
        match phone.read(&mut buf) {
            Ok(0) => return (reads, true),
            Ok(_) => reads += 1,
            Err(error) if error.kind() == ErrorKind::TimedOut => {}
            Err(error) if error.kind() == ErrorKind::UnexpectedEof => return (reads, true),
            Err(_) => return (reads, false),
        }
    }
    (reads, false)
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
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!(
        "chinchillacam-session-runtime-usb-{name}-{}-{nanos}.txt",
        std::process::id()
    ))
}
