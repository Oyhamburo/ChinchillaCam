use std::{net::TcpStream, sync::Arc, thread, time::Duration};

use rustls::{
    pki_types::{CertificateDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore,
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
    let mut connection = ClientConnection::new(
        Arc::new(client_config(&expected_cert)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();
    while connection.is_handshaking() {
        connection.complete_io(&mut stream).unwrap();
    }
    let peer_cert = connection.peer_certificates().unwrap()[0].as_ref().to_vec();
    handle.join().unwrap();

    assert_eq!(peer_cert, expected_cert);
    assert!(peer_cert
        .windows(expected_spki.len())
        .any(|window| window == expected_spki));
}

#[test]
fn loopback_tls_handshake_rejects_wrong_trust_root() {
    let server_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let wrong_identity = DesktopTlsIdentity::generate_ephemeral("Wrong Desktop").unwrap();
    let server =
        LoopbackPairingProofServer::bind(server_identity, Duration::from_millis(1500)).unwrap();
    let addr = server.local_addr();
    let handle = thread::spawn(move || server.accept_one());
    let mut connection = ClientConnection::new(
        Arc::new(client_config(wrong_identity.certificate_der())),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    let mut stream = TcpStream::connect(addr).unwrap();

    assert!(connection.complete_io(&mut stream).is_err());
    assert!(handle.join().unwrap().is_err());
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

fn client_config(root_cert: &[u8]) -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth()
}
