//! Task m3: reconnection without a QR. Unlike the pairing flow (`usb_tls_pairing_proof_test.rs`),
//! there is no `PairingQrIssuer` and no `CCP1` exchange here -- the desktop must accept only
//! phones a `FileTrustedPhoneStore` already reports as trusted and not revoked, rejecting
//! everyone else at the TLS handshake itself (contract section 4.5).

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
    ClientConfig, ClientConnection, RootCertStore,
};
use usb_probe::{
    complete_trusted_phone_handshake, phone_id_for_spki, DesktopTlsIdentity, FileTrustedPhoneStore,
    FrameTransferBudget, FramedUsbStream, TrustedPhoneIdentity, UsbBulkIo, UsbProbeError,
    UsbTlsCiphertextStream, UsbTlsPairingProofError,
};

#[test]
fn trusted_handshake_accepts_trusted_phone_without_qr() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let path = unique_store_path("accepts-trusted");
    let store = FileTrustedPhoneStore::new(&path);
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
        let server_identity = identity.clone();
        // The server-side app-data exchange must happen INSIDE this spawned closure,
        // concurrently with the phone's blocking write/read below -- not after
        // `server.join()`, which would only run once the phone side has already
        // finished (and nobody would ever have read the phone's message).
        let server = scope.spawn(move || -> Result<String, UsbTlsPairingProofError> {
            let outcome = complete_trusted_phone_handshake(
                &server_identity,
                Arc::new(store),
                ciphertext_stream(desktop_io),
                Duration::from_millis(1500),
            )?;
            let mut tls = outcome.tls;
            let mut message = [0; 13];
            tls.read_exact(&mut message)
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
            assert_eq!(&message, b"hello-desktop");
            tls.write_all(&message)
                .and_then(|_| tls.flush())
                .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
            Ok(outcome.phone_id)
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        phone_tls.write_all(b"hello-desktop").unwrap();
        phone_tls.flush().unwrap();
        let mut echo = [0; 13];
        phone_tls.read_exact(&mut echo).unwrap();
        assert_eq!(&echo, b"hello-desktop");

        let returned_phone_id = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, phone_id);
    });

    cleanup(path);
}

#[test]
fn trusted_handshake_rejects_unknown_phone() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Unknown Phone").unwrap();
    let path = unique_store_path("rejects-unknown");
    let store = FileTrustedPhoneStore::new(&path);

    let result = attempt_trusted_handshake(&identity, Arc::new(store), &phone_identity);

    assert_rejected_at_handshake(result, "an unknown phone");
    cleanup(path);
}

#[test]
fn trusted_handshake_rejects_revoked_phone() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Revoked Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let path = unique_store_path("rejects-revoked");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(
            TrustedPhoneIdentity::new(
                phone_id.clone(),
                "Revoked Phone",
                phone_identity.spki_der_p256().to_vec(),
            )
            .unwrap(),
        )
        .unwrap();
    store.revoke(&phone_id).unwrap();

    let result = attempt_trusted_handshake(&identity, Arc::new(store), &phone_identity);

    assert_rejected_at_handshake(result, "a revoked phone");
    cleanup(path);
}

#[test]
fn trusted_handshake_rejects_trusted_phone_id_with_different_key() {
    // The phone_id is trusted, but the store's stored public key does not match the
    // certificate the phone actually presents (only reachable through the public
    // `TrustedPhoneIdentity::new`/`store.trust` API, since the store itself never
    // verifies that phone_id was really derived from public_key -- that binding is the
    // verifier's job at handshake time, not the store's).
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Mismatched Key Phone").unwrap();
    let other_spki = DesktopTlsIdentity::generate_ephemeral("Other Phone")
        .unwrap()
        .spki_der_p256()
        .to_vec();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let path = unique_store_path("rejects-key-mismatch");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(
            TrustedPhoneIdentity::new(phone_id.clone(), "Mismatched Key Phone", other_spki)
                .unwrap(),
        )
        .unwrap();

    let result = attempt_trusted_handshake(&identity, Arc::new(store), &phone_identity);

    assert_rejected_at_handshake(result, "a trusted phone_id with a mismatched key");
    cleanup(path);
}

/// `CompletedTrustedHandshake` does not implement `Debug` (it owns a live `StreamOwned`,
/// same as `CompletedPairingProof`), so this matches on the `Err` arm directly instead of
/// formatting the whole `Result` with `{:?}`.
fn assert_rejected_at_handshake(
    result: Result<
        usb_probe::CompletedTrustedHandshake<UsbTlsCiphertextStream<CrossedBulkIo>>,
        UsbTlsPairingProofError,
    >,
    context: &str,
) {
    match result {
        Err(UsbTlsPairingProofError::Tls(_)) => {}
        Err(other) => panic!("expected Err(Tls(_)) rejecting {context}, got Err({other:?})"),
        Ok(_) => panic!("expected Err(Tls(_)) rejecting {context}, got Ok"),
    }
}

fn attempt_trusted_handshake(
    identity: &DesktopTlsIdentity,
    store: Arc<FileTrustedPhoneStore>,
    phone_identity: &DesktopTlsIdentity,
) -> Result<
    usb_probe::CompletedTrustedHandshake<UsbTlsCiphertextStream<CrossedBulkIo>>,
    UsbTlsPairingProofError,
> {
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
) -> rustls::StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>> {
    let mut phone_stream = ciphertext_stream(phone_io);
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut phone_stream).unwrap();
    }
    rustls::StreamOwned::new(client, phone_stream)
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

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-trusted-session-{name}-{}-{nanos}.txt",
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
