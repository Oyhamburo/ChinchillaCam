//! Task m4: explicit confirmation. A completed pairing handshake only ever produces a
//! `PairedPhoneCandidate` -- nothing is persisted to any `FileTrustedPhoneStore` until the
//! caller explicitly calls `confirm` (contract section 4.6). Confirming a currently-revoked
//! phone_id must fail closed and must never un-revoke it.

use std::{
    collections::VecDeque,
    io::{Read, Write},
    path::PathBuf,
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant, SystemTime},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    complete_trusted_phone_handshake, DesktopTlsIdentity, FileTrustedPhoneStore,
    FrameTransferBudget, FramedUsbStream, PairedPhoneCandidate, PairedPhoneCandidateConfirmError,
    PairingProofFrame, PairingProofRequest, PairingQrIssuer, PairingQrIssuerError,
    PairingQrNonceGenerator, TrustedPhoneIdentity, UsbBulkIo, UsbProbeError,
    UsbTlsCiphertextStream, UsbTlsPairingProofError, UsbTlsPairingProofServer,
};

#[test]
fn pairing_candidate_is_not_persisted_without_confirmation() {
    let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Never Confirmed Phone").unwrap();
    let candidate = pair_phone(&desktop_identity, &phone_identity);

    let path = unique_store_path("not-persisted");
    let store = FileTrustedPhoneStore::new(&path);

    assert_eq!(store.trusted_identity(&candidate.phone_id).unwrap(), None);
    assert!(
        !path.exists(),
        "pairing alone must never create or write a trust store file"
    );
}

#[test]
fn confirm_persists_trusted_phone_identity() {
    let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Confirmed Phone").unwrap();
    let candidate = pair_phone(&desktop_identity, &phone_identity);

    let path = unique_store_path("confirm-persists");
    let store = FileTrustedPhoneStore::new(&path);

    let confirmed = candidate.confirm("My Phone", &store).unwrap();

    let expected = TrustedPhoneIdentity::new(
        candidate.phone_id.clone(),
        "My Phone",
        candidate.spki.clone(),
    )
    .unwrap();
    assert_eq!(confirmed, expected);
    assert_eq!(
        store.trusted_identity(&candidate.phone_id).unwrap(),
        Some(expected)
    );
    cleanup(path);
}

#[test]
fn confirm_refuses_revoked_phone() {
    let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity =
        DesktopTlsIdentity::generate_ephemeral("Revoked Then Repaired Phone").unwrap();
    let candidate = pair_phone(&desktop_identity, &phone_identity);

    let path = unique_store_path("confirm-refuses-revoked");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(
            TrustedPhoneIdentity::new(
                candidate.phone_id.clone(),
                "Old Label",
                candidate.spki.clone(),
            )
            .unwrap(),
        )
        .unwrap();
    store.revoke(&candidate.phone_id).unwrap();

    let result = candidate.confirm("New Label", &store);

    assert!(
        matches!(result, Err(PairedPhoneCandidateConfirmError::PhoneRevoked)),
        "expected Err(PhoneRevoked), got {result:?}"
    );
    assert!(
        store.is_revoked(&candidate.phone_id).unwrap(),
        "confirm must never un-revoke a currently revoked phone_id"
    );
    assert_eq!(store.trusted_identity(&candidate.phone_id).unwrap(), None);
    cleanup(path);
}

#[test]
fn confirmed_phone_reconnects_and_revoked_phone_is_rejected() {
    let desktop_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("End To End Phone").unwrap();
    let candidate = pair_phone(&desktop_identity, &phone_identity);

    let path = unique_store_path("end-to-end");
    let store = FileTrustedPhoneStore::new(&path);
    candidate.confirm("E2E Phone", &store).unwrap();

    match attempt_trusted_handshake(
        &desktop_identity,
        Arc::new(FileTrustedPhoneStore::new(&path)),
        &phone_identity,
    ) {
        Ok(_) => {}
        Err(error) => panic!("expected the confirmed phone to reconnect, got Err({error:?})"),
    }

    store.revoke(&candidate.phone_id).unwrap();

    match attempt_trusted_handshake(
        &desktop_identity,
        Arc::new(FileTrustedPhoneStore::new(&path)),
        &phone_identity,
    ) {
        Err(UsbTlsPairingProofError::Tls(_)) => {}
        Err(other) => panic!("expected Err(Tls(_)) after revocation, got Err({other:?})"),
        Ok(_) => panic!("expected the revoked phone to be rejected at the handshake, got Ok"),
    }

    cleanup(path);
}

/// Runs a full pairing handshake (m1-m2) and returns only the resulting candidate -- this
/// file never persists anything itself except through the `confirm` API under test.
fn pair_phone(
    desktop_identity: &DesktopTlsIdentity,
    phone_identity: &DesktopTlsIdentity,
) -> PairedPhoneCandidate {
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        desktop_identity.clone(),
        60,
        TestRng(7),
    )
    .unwrap();
    issuer.issue_at(now_seconds()).unwrap();
    let request =
        PairingProofRequest::new("desktop-01", vec![7; 32], vec![3; 32], "session-01").unwrap();
    let cert = desktop_identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server_identity = desktop_identity.clone();
        let server = scope.spawn(move || {
            UsbTlsPairingProofServer::new(&server_identity)
                .unwrap()
                .complete_handshake_and_pairing_proof(
                    ciphertext_stream(desktop_io),
                    &mut issuer,
                    Duration::from_millis(1500),
                )
        });

        let mut tls = phone_tls_stream(phone_io, &cert, phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        let _response = read_ccp1_frame(&mut tls);

        server.join().unwrap().unwrap().candidate
    })
}

fn attempt_trusted_handshake(
    identity: &DesktopTlsIdentity,
    store: Arc<FileTrustedPhoneStore>,
    phone_identity: &DesktopTlsIdentity,
) -> Result<usb_probe::CompletedTrustedHandshake<CrossedBulkIo>, UsbTlsPairingProofError> {
    let cert = identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            complete_trusted_phone_handshake(
                identity,
                store,
                ciphertext_stream(desktop_io),
                Duration::from_millis(800),
            )
        });

        let mut phone_stream = ciphertext_stream(phone_io);
        let mut client = tls_client(&cert, phone_identity);
        while client.is_handshaking() {
            if client.complete_io(&mut phone_stream).is_err() {
                break;
            }
        }

        server.join().unwrap()
    })
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

fn request_frame(request: &PairingProofRequest) -> Vec<u8> {
    PairingProofFrame::request(request.clone())
        .encode()
        .unwrap()
}

/// Drains and discards the CCP1 response frame. This file never asserts on its content --
/// that contract is already covered by `usb_tls_pairing_proof_test.rs` -- but the phone
/// side must still read it off the stream so the exchange completes cleanly.
fn read_ccp1_frame<S: Read>(stream: &mut S) -> Vec<u8> {
    let mut header = [0u8; 10];
    stream.read_exact(&mut header).unwrap();
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    let mut frame = header.to_vec();
    frame.resize(10 + payload_len, 0);
    stream.read_exact(&mut frame[10..]).unwrap();
    frame
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

struct TestRng(u8);

impl PairingQrNonceGenerator for TestRng {
    fn fill_nonce(&mut self, nonce: &mut [u8; 32]) -> Result<(), PairingQrIssuerError> {
        nonce.fill(self.0);
        self.0 = self.0.wrapping_add(1);
        Ok(())
    }
}

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-confirm-{name}-{}-{nanos}.txt",
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

    fn read(&self, buffer: &mut [u8], timeout: Duration) -> std::io::Result<usize> {
        let deadline = Instant::now() + timeout;
        let mut queue = self.queue.lock().unwrap();
        while queue.is_empty() {
            let now = Instant::now();
            if now >= deadline {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "bulk read timed out",
                ));
            }
            let wait = deadline - now;
            let (guard, result) = self.ready.wait_timeout(queue, wait).unwrap();
            queue = guard;
            if result.timed_out() && queue.is_empty() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
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
