//! Task d1 (`odd/tasks/wifi-loopback-transport.md`, contract section 4): the TLS
//! pairing/reconnection stack must run, with no logic change, over any `Read + Write`
//! transport -- not only `UsbTlsCiphertextStream<I: UsbBulkIo>`. These tests drive the same
//! `accept_phone_pairing_connection` / `accept_phone_reconnect_connection` entry points over
//! the framing-free in-memory duplex double (`common::duplex::InMemoryDuplex`), proving the
//! seam is generic. They are modelled on `phone_connection_test.rs` (thread::scope, a rustls
//! `ClientConnection` presenting a phone client cert, `TestRng`, temp trusted-phone store).

mod common;

use std::{
    io::{Read, Write},
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use common::duplex::InMemoryDuplex;
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_pairing_connection, accept_phone_reconnect_connection, phone_id_for_spki,
    DesktopTlsIdentity, FileTrustedPhoneStore, PairingProofFrame, PairingProofRequest,
    PairingProofResponse, PairingQrIssuer, PairingQrIssuerError, SessionFrame, SessionFramePayload,
    TrustedPhoneIdentity,
};

const READ_TIMEOUT: Duration = Duration::from_millis(1500);
const OP_TIMEOUT: Duration = Duration::from_millis(1500);

#[test]
fn trusted_reconnect_over_in_memory_duplex() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let expected_desktop_id = phone_id_for_spki(identity.spki_der_p256());

    let store_path = unique_store_path("duplex-reconnect-accepts-hello");
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
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);

    std::thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<String, String> {
            let session = accept_phone_reconnect_connection(
                desktop_duplex,
                &identity,
                Arc::new(store),
                OP_TIMEOUT,
            )
            .map_err(|error| error.to_string())?;
            Ok(session.phone_id)
        });

        let mut phone_tls = phone_tls_stream(phone_duplex, &cert, &phone_identity);
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
fn pairing_over_in_memory_duplex() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Candidate Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_duplex, phone_duplex) = InMemoryDuplex::pair(READ_TIMEOUT);

    std::thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<String, String> {
            let pending =
                accept_phone_pairing_connection(desktop_duplex, &identity, &mut issuer, OP_TIMEOUT)
                    .map_err(|error| error.to_string())?;
            Ok(pending.candidate.phone_id)
        });

        let mut phone_tls = phone_tls_stream(phone_duplex, &cert, &phone_identity);
        phone_tls.write_all(&request_frame(&request)).unwrap();
        phone_tls.flush().unwrap();
        let response = read_ccp1_frame(&mut phone_tls).unwrap();
        assert_eq!(
            response,
            PairingProofFrame::response(PairingProofResponse::ok(request))
                .encode()
                .unwrap()
        );

        let returned_phone_id = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, expected_phone_id);
    });
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
}

fn phone_tls_stream(
    phone_duplex: InMemoryDuplex,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, InMemoryDuplex> {
    let mut phone_duplex = phone_duplex;
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut phone_duplex).unwrap();
    }
    StreamOwned::new(client, phone_duplex)
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

fn read_ccp1_frame<S: Read>(stream: &mut S) -> std::io::Result<Vec<u8>> {
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

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-duplex-session-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}

fn cleanup(path: PathBuf) {
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(path.with_extension("lock"));
}
