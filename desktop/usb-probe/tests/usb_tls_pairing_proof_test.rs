use std::{
    collections::VecDeque,
    io,
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant},
};

use rustls::{
    pki_types::{CertificateDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore,
};
use usb_probe::{
    DesktopTlsIdentity, FrameTransferBudget, FramedUsbStream, UsbBulkIo, UsbProbeError,
    UsbTlsCiphertextStream, UsbTlsPairingProofServer,
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

fn ciphertext_stream(io: CrossedBulkIo) -> UsbTlsCiphertextStream<CrossedBulkIo> {
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
