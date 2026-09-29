//! Task s1 (`odd/tasks/usb-authenticated-session.md`, contract section 4.3): framing of
//! `SessionFrame` over the live TLS application-data stream. Unlike
//! `usb_tls_pairing_proof_test.rs` (which exercises the CCP1 pairing-proof handshake) this
//! file only exercises the framing layer that sits on top of an already-completed TLS
//! connection, so the handshake helpers below deliberately skip the CCP1/pairing-proof and
//! trusted-store machinery and only set up a plain mutually authenticated TLS pair.

use std::{
    collections::VecDeque,
    io::Cursor,
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned,
};
use usb_probe::{
    read_session_frame, write_session_frame, DesktopTlsIdentity, FrameTransferBudget,
    FramedUsbStream, PhoneClientCertVerifier, SessionFrame, SessionFrameCodec, SessionFramePayload,
    TlsSessionFrameError, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
};

#[test]
fn round_trips_session_frame_over_tls() {
    let (mut server_tls, mut client_tls) = connected_tls_pair();

    let server_frame = SessionFrame::new(
        1,
        "session-01",
        SessionFramePayload::HandshakeAccept {
            desktop_id: "desktop-01".to_string(),
            message: "welcome".to_string(),
        },
    );
    write_session_frame(&mut server_tls, &server_frame).unwrap();
    let received = read_session_frame(&mut client_tls, test_deadline()).unwrap();
    assert_eq!(received, server_frame);

    let client_frame = SessionFrame::new(
        2,
        "session-01",
        SessionFramePayload::HandshakeHello {
            device_id: "phone-01".to_string(),
            app_name: "ChinchillaCam".to_string(),
            capabilities: vec!["video".to_string()],
        },
    );
    write_session_frame(&mut client_tls, &client_frame).unwrap();
    let received = read_session_frame(&mut server_tls, test_deadline()).unwrap();
    assert_eq!(received, client_frame);
}

#[test]
fn rejects_oversized_length_before_allocating() {
    let oversized_length = SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE as u32 + 1;
    // Only the 4-byte length prefix is provided: if the implementation allocated (or
    // tried to read) `oversized_length` bytes before validating it, this would fail with
    // a truncation/IO error instead of the expected `InvalidLength`.
    let mut source = Cursor::new(oversized_length.to_be_bytes().to_vec());

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(
        result,
        Err(TlsSessionFrameError::InvalidLength(oversized_length))
    );
}

#[test]
fn rejects_zero_length() {
    let mut source = Cursor::new(0u32.to_be_bytes().to_vec());

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(result, Err(TlsSessionFrameError::InvalidLength(0)));
}

#[test]
fn truncated_frame_fails_closed() {
    let mut bytes = 20u32.to_be_bytes().to_vec();
    bytes.extend_from_slice(&[0u8; 5]); // declares 20 payload bytes, provides only 5
    let mut source = Cursor::new(bytes);

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(result, Err(TlsSessionFrameError::TruncatedFrame("payload")));
}

#[test]
fn read_times_out_when_deadline_already_passed() {
    let mut source: &[u8] = &[];
    let already_passed_deadline = Instant::now() - Duration::from_millis(1);

    let result = read_session_frame(&mut source, already_passed_deadline);

    assert_eq!(result, Err(TlsSessionFrameError::Timeout));
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
}

/// Builds a plain (non-pairing, non-trusted-reconnect) mutually authenticated TLS pair
/// over an in-memory `CrossedBulkIo`, so this file can exercise the framing layer without
/// pulling in the CCP1 pairing-proof exchange or the trusted-phone-store machinery those
/// other flows need.
fn connected_tls_pair() -> (
    StreamOwned<ServerConnection, UsbTlsCiphertextStream<CrossedBulkIo>>,
    StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>>,
) {
    let server_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    let server_cert = server_identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let mut desktop_stream = ciphertext_stream(desktop_io);
    let mut phone_stream = ciphertext_stream(phone_io);

    let mut server_connection = ServerConnection::new(server_config(&server_identity)).unwrap();
    let mut client_connection = ClientConnection::new(
        Arc::new(client_config(&server_cert, &phone_identity)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();

    thread::scope(|scope| {
        let server_handshake = scope.spawn(|| {
            while server_connection.is_handshaking() {
                server_connection.complete_io(&mut desktop_stream).unwrap();
            }
        });
        while client_connection.is_handshaking() {
            client_connection.complete_io(&mut phone_stream).unwrap();
        }
        server_handshake.join().unwrap();
    });

    (
        StreamOwned::new(server_connection, desktop_stream),
        StreamOwned::new(client_connection, phone_stream),
    )
}

fn server_config(identity: &DesktopTlsIdentity) -> Arc<ServerConfig> {
    let provider = rustls::crypto::ring::default_provider();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let cert = CertificateDer::from(identity.certificate_der().to_vec());
    Arc::new(
        ServerConfig::builder_with_provider(provider.into())
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .unwrap()
            .with_client_cert_verifier(PhoneClientCertVerifier::pairing())
            .with_single_cert(vec![cert], key)
            .unwrap(),
    )
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
