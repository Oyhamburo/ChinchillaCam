use std::{net::TcpStream, sync::Arc, thread, time::Duration};

use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, ServerName, UnixTime},
    ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme,
};
use usb_probe::{DesktopTlsIdentity, LoopbackPairingProofServer, LoopbackPairingProofServerError};

#[test]
fn loopback_tls_handshake_presents_identity_spki() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let expected_cert = identity.certificate_der().to_vec();
    let expected_spki = identity.spki_der_p256().to_vec();
    let server = LoopbackPairingProofServer::bind(identity, Duration::from_millis(1500)).unwrap();
    assert_eq!(server.endpoint().host(), "127.0.0.1");
    assert!(server.endpoint().port() > 0);

    let addr = server.local_addr();
    let handle = thread::spawn(move || server.accept_one().unwrap());
    let verifier = Arc::new(CaptureVerifier::new(expected_cert.clone()));
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier.clone())
        .with_no_client_auth();
    let mut connection =
        ClientConnection::new(Arc::new(config), ServerName::try_from("localhost").unwrap())
            .unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();
    while connection.is_handshaking() {
        connection.complete_io(&mut stream).unwrap();
    }
    handle.join().unwrap();

    let cert = verifier.observed.lock().unwrap().clone().unwrap();
    assert_eq!(cert, expected_cert);
    assert!(cert
        .windows(expected_spki.len())
        .any(|window| window == expected_spki));
}

#[test]
fn accept_one_times_out_without_client() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let server = LoopbackPairingProofServer::bind(identity, Duration::from_millis(250)).unwrap();

    assert!(matches!(
        server.accept_one(),
        Err(LoopbackPairingProofServerError::Timeout)
    ));
}

#[derive(Debug)]
struct CaptureVerifier {
    expected: Vec<u8>,
    observed: std::sync::Mutex<Option<Vec<u8>>>,
}

impl CaptureVerifier {
    fn new(expected: Vec<u8>) -> Self {
        Self {
            expected,
            observed: std::sync::Mutex::new(None),
        }
    }
}

impl ServerCertVerifier for CaptureVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let cert = end_entity.as_ref().to_vec();
        assert_eq!(cert, self.expected);
        *self.observed.lock().unwrap() = Some(cert);
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}
