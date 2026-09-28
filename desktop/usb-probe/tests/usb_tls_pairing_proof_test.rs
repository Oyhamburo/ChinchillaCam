use std::{
    collections::VecDeque,
    io,
    io::{Read, Write},
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    DesktopTlsIdentity, FrameTransferBudget, FramedUsbStream, PairingProofFrame,
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
    let mut client = tls_client(identity.certificate_der());
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
    let mut client = tls_client(wrong_identity.certificate_der());

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
) -> StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>> {
    let mut phone_stream = ciphertext_stream(phone_io);
    let mut client = tls_client(root_cert);
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
    let cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let server = thread::scope(|scope| {
        let server = scope.spawn(|| {
            UsbTlsPairingProofServer::new(&identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    issuer,
                    Duration::from_millis(1500),
                )
        });
        let mut tls = phone_tls_stream(phone_io, &cert);
        let response = tls
            .write_all(&request_frame(&request))
            .and_then(|_| tls.flush())
            .and_then(|_| read_ccp1_frame(&mut tls));
        server.join().unwrap()?;
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

fn tls_client(root_cert: &[u8]) -> ClientConnection {
    ClientConnection::new(
        Arc::new(client_config(root_cert)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap()
}

fn client_config(root_cert: &[u8]) -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth()
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
