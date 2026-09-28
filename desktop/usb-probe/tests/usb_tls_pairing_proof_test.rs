use std::{
    collections::VecDeque,
    io,
    io::{Read, Write},
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    phone_id_for_spki, DesktopTlsIdentity, FrameTransferBudget, FramedUsbStream, PairingProofFrame,
    PairingProofRequest, PairingProofResponse, PairingQrIssuer, PairingQrIssuerError,
    RecordingUsbBulkIo, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream, UsbTlsPairingProofError,
    UsbTlsPairingProofServer,
};

#[test]
fn desktop_rustls_handshake_runs_over_crossed_usb_ciphertext_streams() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let expected_cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let mut desktop_stream = ciphertext_stream(desktop_io);
    let mut phone_stream = ciphertext_stream(phone_io);
    let server_identity = identity.clone();

    let server = thread::spawn(move || {
        let mut server = UsbTlsPairingProofServer::new(&server_identity).unwrap();
        server.complete_handshake(&mut desktop_stream).unwrap();
    });
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    let mut client = tls_client(identity.certificate_der(), &phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut phone_stream).unwrap();
    }
    server.join().unwrap();

    let peer_cert = client.peer_certificates().unwrap()[0].as_ref().to_vec();
    assert_eq!(peer_cert, expected_cert);
}

#[test]
fn desktop_usb_tls_pairing_proof_echoes_request_and_denies_replay_expired_wrong_nonce() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let expected = PairingProofFrame::response(PairingProofResponse::ok(request.clone()))
        .encode()
        .unwrap();
    assert_eq!(
        exchange_proof(identity.clone(), &mut issuer, request.clone()),
        Ok(expected)
    );
    assert!(matches!(
        exchange_proof(identity.clone(), &mut issuer, request),
        Err(UsbTlsPairingProofError::Issuer(_))
    ));

    let (expired_identity, mut expired_issuer, expired_request) =
        proof_fixture(now.saturating_sub(61), "desktop-01", vec![7; 32]);
    assert!(matches!(
        exchange_proof(expired_identity, &mut expired_issuer, expired_request),
        Err(UsbTlsPairingProofError::Issuer(_))
    ));

    let (wrong_identity, mut wrong_issuer, wrong_request) =
        proof_fixture(now, "desktop-01", vec![8; 32]);
    assert!(matches!(
        exchange_proof(wrong_identity, &mut wrong_issuer, wrong_request),
        Err(UsbTlsPairingProofError::Issuer(_))
    ));
}

#[test]
fn completed_pairing_proof_returns_live_tls_stream_for_same_connection_messages() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(), UsbTlsPairingProofError> {
            let mut tls = UsbTlsPairingProofServer::new(&identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    &mut issuer,
                    Duration::from_millis(1500),
                )?
                .tls;
            let mut message = [0; 11];
            tls.read_exact(&mut message)
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
            assert_eq!(&message, b"after-proof");
            tls.write_all(b"server-ack")
                .and_then(|_| tls.flush())
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))
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

        server.join().unwrap().unwrap();
    });
}

#[test]
fn pairing_proof_returns_client_spki_with_live_stream() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Candidate Phone").unwrap();
    let expected_spki = phone_identity.spki_der_p256().to_vec();
    let expected_phone_id = phone_id_for_spki(&expected_spki);
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(), UsbTlsPairingProofError> {
            let outcome = UsbTlsPairingProofServer::new(&identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    &mut issuer,
                    Duration::from_millis(1500),
                )?;
            assert_eq!(outcome.candidate.spki, expected_spki);
            assert_eq!(outcome.candidate.phone_id, expected_phone_id);

            let mut tls = outcome.tls;
            let mut message = [0; 11];
            tls.read_exact(&mut message)
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
            assert_eq!(&message, b"after-proof");
            tls.write_all(b"server-ack")
                .and_then(|_| tls.flush())
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))
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

        server.join().unwrap().unwrap();
    });
}

#[test]
fn pairing_handshake_without_client_certificate_does_not_consume_qr_nonce() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();

    let server_result = thread::scope(|scope| {
        let (desktop_io, phone_io) = crossed_bulk_pair();
        let server = scope.spawn(|| {
            UsbTlsPairingProofServer::new(&identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    &mut issuer,
                    Duration::from_millis(500),
                )
        });

        let mut phone_stream = ciphertext_stream(phone_io);
        let mut client = tls_client_without_certificate(&cert);
        while client.is_handshaking() {
            if client.complete_io(&mut phone_stream).is_err() {
                break;
            }
        }

        server.join().unwrap()
    });

    assert!(
        server_result.is_err(),
        "expected the handshake to fail without a phone client certificate, got Ok"
    );

    // The QR nonce must still be consumable afterwards by a proper client, proving the
    // rejected handshake above never consumed it.
    assert!(exchange_proof(identity, &mut issuer, request).is_ok());
}

#[test]
fn delayed_final_pairing_proof_bytes_after_deadline_reject_without_consuming_nonce() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let first_attempt = exchange_proof_with_split_final_bytes(
        identity.clone(),
        &mut issuer,
        request.clone(),
        Duration::from_millis(80),
        Duration::from_millis(20),
    );

    assert!(matches!(
        first_attempt,
        Err(UsbTlsPairingProofError::Timeout)
    ));
    assert!(exchange_proof(identity, &mut issuer, request).is_ok());
}

#[test]
fn raw_ccp1_without_tls_is_denied_by_usb_pairing_proof_api() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        60,
        TestRng(7),
    )
    .unwrap();
    issuer.issue_at(now_seconds()).unwrap();
    let request =
        PairingProofRequest::new("desktop-01", vec![7; 32], vec![3; 32], "session-01").unwrap();
    let raw_io = RecordingUsbBulkIo::with_read_chunks(vec![Ok(request_frame(&request))]);

    let result = UsbTlsPairingProofServer::new(&identity)
        .unwrap()
        .complete_handshake_and_pairing_proof(
            ciphertext_stream(raw_io),
            &mut issuer,
            Duration::from_millis(350),
        );

    assert!(matches!(result, Err(UsbTlsPairingProofError::Tls(_))));
}

#[test]
fn desktop_usb_tls_handshake_rejects_wrong_root() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let wrong_identity = DesktopTlsIdentity::generate_ephemeral("Wrong Desktop").unwrap();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let mut desktop_stream = ciphertext_stream(desktop_io);
    let mut phone_stream = ciphertext_stream(phone_io);

    let server = thread::spawn(move || {
        let mut server = UsbTlsPairingProofServer::new(&identity).unwrap();
        server.complete_handshake(&mut desktop_stream)
    });
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    let mut client = tls_client(wrong_identity.certificate_der(), &phone_identity);

    let error = client.complete_io(&mut phone_stream).unwrap_err();
    assert!(
        error.to_string().contains("certificate") || format!("{error:?}").contains("UnknownIssuer"),
        "expected client certificate verification error, got {error:?}"
    );
    let _ = server.join().unwrap();
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

fn exchange_proof(
    identity: DesktopTlsIdentity,
    issuer: &mut PairingQrIssuer<TestRng>,
    request: PairingProofRequest,
) -> Result<Vec<u8>, UsbTlsPairingProofError> {
    exchange_proof_with_request_writer(
        identity,
        issuer,
        request,
        Duration::from_millis(1500),
        |tls, frame| tls.write_all(&frame).and_then(|_| tls.flush()),
    )
}

fn exchange_proof_with_split_final_bytes(
    identity: DesktopTlsIdentity,
    issuer: &mut PairingQrIssuer<TestRng>,
    request: PairingProofRequest,
    delay: Duration,
    server_timeout: Duration,
) -> Result<Vec<u8>, UsbTlsPairingProofError> {
    exchange_proof_with_request_writer(identity, issuer, request, server_timeout, |tls, frame| {
        let split = frame.len() - 1;
        tls.write_all(&frame[..split]).and_then(|_| tls.flush())?;
        thread::sleep(delay);
        tls.write_all(&frame[split..]).and_then(|_| tls.flush())
    })
}

fn exchange_proof_with_request_writer<F>(
    identity: DesktopTlsIdentity,
    issuer: &mut PairingQrIssuer<TestRng>,
    request: PairingProofRequest,
    server_timeout: Duration,
    write_request: F,
) -> Result<Vec<u8>, UsbTlsPairingProofError>
where
    F: FnOnce(
        &mut StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>>,
        Vec<u8>,
    ) -> io::Result<()>,
{
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let server = thread::scope(|scope| {
        let server = scope.spawn(|| {
            UsbTlsPairingProofServer::new(&identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    issuer,
                    server_timeout,
                )
        });
        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        let response = write_request(&mut tls, request_frame(&request))
            .and_then(|_| read_ccp1_frame(&mut tls));
        let server_tls = server.join().unwrap()?;
        drop(server_tls);
        response.map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))
    });
    server
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

/// Builds a phone-side TLS client that presents `phone_identity`'s certificate as its
/// client credential -- the desktop server now requires phone client auth (task m2).
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

/// A phone-side TLS client presenting no client certificate at all, to exercise the
/// desktop server's mandatory phone client auth rejecting it.
fn tls_client_without_certificate(root_cert: &[u8]) -> ClientConnection {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    let config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    ClientConnection::new(Arc::new(config), ServerName::try_from("localhost").unwrap()).unwrap()
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
