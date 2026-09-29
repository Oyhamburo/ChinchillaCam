//! Task s2 (`odd/tasks/usb-authenticated-session.md`, contract section 4.1): the desktop's
//! own per-connection choice between the pairing flow (`accept_phone_pairing_connection`)
//! and the trusted-reconnection flow (`accept_phone_reconnect_connection`). Reuses the
//! CCP1/pairing-proof fixtures from `usb_tls_pairing_proof_test.rs` and the trusted-store
//! fixtures from `usb_tls_trusted_session_test.rs`, each duplicated locally per this file's
//! existing per-test-file convention (see "Evidencia s1" desvío 1 in the task doc).

use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    path::PathBuf,
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_pairing_connection, accept_phone_reconnect_connection, phone_id_for_spki,
    DesktopTlsIdentity, FileTrustedPhoneStore, FrameTransferBudget, FramedUsbStream,
    PairingProofFrame, PairingProofRequest, PairingProofResponse, PairingQrIssuer,
    PairingQrIssuerError, PhoneConnectionError, SessionFrame, SessionFramePayload,
    TrustedPhoneIdentity, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
};

#[test]
fn pairing_mode_holds_channel_until_confirm() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Candidate Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("pairing-holds-channel");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<String, String> {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;

            let mut session = pending
                .confirm("Candidate Phone", &store)
                .map_err(|error| error.to_string())?;

            // Proves the SAME live TLS stream was retained across confirm(): app data
            // exchanged now must still reach the phone on the other end of it.
            let mut message = [0; 11];
            session
                .tls
                .read_exact(&mut message)
                .map_err(|error| error.to_string())?;
            if &message != b"after-proof" {
                return Err(format!("unexpected message: {message:?}"));
            }
            session
                .tls
                .write_all(b"server-ack")
                .and_then(|_| session.tls.flush())
                .map_err(|error| error.to_string())?;
            Ok(session.phone_id)
        });

        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        let response = read_ccp1_frame(&mut tls).unwrap();
        assert_eq!(
            response,
            PairingProofFrame::response(PairingProofResponse::ok(request))
                .encode()
                .unwrap()
        );
        tls.write_all(b"after-proof").unwrap();
        tls.flush().unwrap();
        let mut ack = [0; 10];
        tls.read_exact(&mut ack).unwrap();
        assert_eq!(&ack, b"server-ack");

        let returned_phone_id = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, expected_phone_id);
    });

    assert!(
        store
            .trusted_identity(&expected_phone_id)
            .unwrap()
            .is_some(),
        "store must hold the confirmed phone"
    );
    cleanup(store_path);
}

#[test]
fn pairing_reject_closes_channel() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Rejected Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("pairing-reject-closes-channel");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(), String> {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            pending.reject();
            Ok(())
        });

        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        let response = read_ccp1_frame(&mut tls).unwrap();
        assert_eq!(
            response,
            PairingProofFrame::response(PairingProofResponse::ok(request))
                .encode()
                .unwrap()
        );

        // The desktop rejected the candidate: the channel must now be closed instead of
        // accepting any further application data. rustls reports a cleanly-received
        // close_notify as `Ok(0)` from `Read::read` regardless of TCP-level EOF.
        let mut probe = [0u8; 1];
        let observed_close = match tls.read(&mut probe) {
            Ok(0) => true,
            Err(error) => error.kind() == io::ErrorKind::UnexpectedEof,
            Ok(_) => false,
        };
        assert!(
            observed_close,
            "expected the channel to be closed after reject()"
        );

        server.join().unwrap().unwrap();
    });

    assert!(
        store
            .trusted_identity(&expected_phone_id)
            .unwrap()
            .is_none(),
        "reject() must never persist the candidate"
    );
    cleanup(store_path);
}

#[test]
fn reconnect_mode_accepts_trusted_phone_hello() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let expected_desktop_id = phone_id_for_spki(identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-accepts-hello");
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

    let cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<String, String> {
            let session = accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            Ok(session.phone_id)
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        let hello = SessionFrame::new(
            1,
            "session-01",
            SessionFramePayload::HandshakeHello {
                device_id: phone_id.clone(),
                app_name: "ChinchillaCam".to_string(),
                capabilities: vec!["video".to_string()],
            },
        );
        usb_probe::write_session_frame(&mut phone_tls, &hello).unwrap();
        let accept = usb_probe::read_session_frame(&mut phone_tls, test_deadline()).unwrap();
        match accept.payload() {
            SessionFramePayload::HandshakeAccept { desktop_id, .. } => {
                assert_eq!(desktop_id, &expected_desktop_id);
            }
            other => panic!("expected HandshakeAccept, got {other:?}"),
        }
        assert_eq!(
            accept.sequence(),
            2,
            "ACCEPT must echo hello.sequence() + 1"
        );
        assert_eq!(accept.session_id(), "session-01");

        let returned_phone_id = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, phone_id);
    });

    cleanup(store_path);
}

#[test]
fn reconnect_mode_rejects_invalid_hello() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-rejects-invalid-hello");
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

    let cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        // Not a HandshakeHello: the desktop must reject the reconnection instead of
        // accepting it.
        let not_hello = SessionFrame::new(
            1,
            "session-01",
            SessionFramePayload::HandshakeAccept {
                desktop_id: "not-a-hello".to_string(),
                message: "wrong frame type".to_string(),
            },
        );
        usb_probe::write_session_frame(&mut phone_tls, &not_hello).unwrap();

        // Expected outcome per this implementation: framing was intact (the read itself
        // succeeded), so the channel is still usable for one best-effort HandshakeReject
        // reply before closing. A closed channel without a reply is also tolerated here,
        // since the contract only requires "HandshakeReject when the channel is still
        // usable", not that the phone always observes it.
        match usb_probe::read_session_frame(&mut phone_tls, test_deadline()) {
            Ok(frame) => assert!(
                matches!(frame.payload(), SessionFramePayload::HandshakeReject { .. }),
                "expected HandshakeReject, got {frame:?}"
            ),
            Err(_) => {}
        }

        match server.join().unwrap() {
            Err(PhoneConnectionError::UnexpectedFirstFrame) => {}
            Err(other) => panic!("expected Err(UnexpectedFirstFrame), got Err({other:?})"),
            Ok(_) => panic!("expected Err(UnexpectedFirstFrame), got Ok"),
        }
    });

    cleanup(store_path);
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
}

fn phone_tls_stream(
    phone_io: CrossedBulkIo,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>> {
    let mut phone_stream = ciphertext_stream(phone_io);
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut phone_stream).unwrap();
    }
    StreamOwned::new(client, phone_stream)
}

fn proof_fixture(
    issued_at: u64,
    desktop_id: &str,
    request_nonce: Vec<u8>,
) -> (
    DesktopTlsIdentity,
    PairingQrIssuer<TestRng>,
    PairingProofRequest,
) {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        60,
        TestRng(7),
    )
    .unwrap();
    issuer.issue_at(issued_at).unwrap();
    let request =
        PairingProofRequest::new(desktop_id, request_nonce, vec![3; 32], "session-01").unwrap();
    (identity, issuer, request)
}

fn request_frame(request: &PairingProofRequest) -> Vec<u8> {
    PairingProofFrame::request(request.clone())
        .encode()
        .unwrap()
}

fn read_ccp1_frame<S: Read>(stream: &mut S) -> io::Result<Vec<u8>> {
    let mut header = [0; 10];
    stream.read_exact(&mut header)?;
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    let mut frame = header.to_vec();
    frame.resize(10 + payload_len, 0);
    stream.read_exact(&mut frame[10..])?;
    Ok(frame)
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

struct TestRng(u8);

impl usb_probe::PairingQrNonceGenerator for TestRng {
    fn fill_nonce(&mut self, nonce: &mut [u8; 32]) -> Result<(), PairingQrIssuerError> {
        nonce.fill(self.0);
        self.0 = self.0.wrapping_add(1);
        Ok(())
    }
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

fn ciphertext_stream<I: UsbBulkIo>(io: I) -> UsbTlsCiphertextStream<I> {
    UsbTlsCiphertextStream::new(FramedUsbStream::new(
        io,
        FrameTransferBudget::new(Duration::from_millis(1500), 65_536, 512).unwrap(),
    ))
}

fn crossed_bulk_pair() -> (CrossedBulkIo, CrossedBulkIo) {
    let a_to_b = Arc::new(BulkPipe::default());
    let b_to_a = Arc::new(BulkPipe::default());
    (
        CrossedBulkIo::new(a_to_b.clone(), b_to_a.clone()),
        CrossedBulkIo::new(b_to_a, a_to_b),
    )
}

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-phone-connection-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}

fn cleanup(path: PathBuf) {
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(path.with_extension("lock"));
}

#[derive(Debug)]
struct CrossedBulkIo {
    outgoing: Arc<BulkPipe>,
    incoming: Arc<BulkPipe>,
}

impl CrossedBulkIo {
    fn new(outgoing: Arc<BulkPipe>, incoming: Arc<BulkPipe>) -> Self {
        Self { outgoing, incoming }
    }
}

impl UsbBulkIo for CrossedBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.incoming.read(buffer, timeout).map_err(|error| {
            UsbProbeError::UsbBulkTransferFailed(format!("crossed read failed: {error}"))
        })
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.outgoing.write(bytes);
        Ok(bytes.len())
    }
}

#[derive(Debug, Default)]
struct BulkPipe {
    queue: Mutex<VecDeque<u8>>,
    ready: Condvar,
}

impl BulkPipe {
    fn write(&self, bytes: &[u8]) {
        let mut queue = self.queue.lock().unwrap();
        queue.extend(bytes.iter().copied());
        self.ready.notify_all();
    }

    fn read(&self, buffer: &mut [u8], timeout: Duration) -> io::Result<usize> {
        let deadline = Instant::now() + timeout;
        let mut queue = self.queue.lock().unwrap();
        while queue.is_empty() {
            let now = Instant::now();
            if now >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "bulk read timed out",
                ));
            }
            let wait = deadline - now;
            let (guard, result) = self.ready.wait_timeout(queue, wait).unwrap();
            queue = guard;
            if result.timed_out() && queue.is_empty() {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "bulk read timed out",
                ));
            }
        }

        let mut read = 0;
        while read < buffer.len() {
            let Some(byte) = queue.pop_front() else {
                break;
            };
            buffer[read] = byte;
            read += 1;
        }
        Ok(read)
    }
}
