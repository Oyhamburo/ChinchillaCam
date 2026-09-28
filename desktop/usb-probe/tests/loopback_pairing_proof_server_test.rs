use std::{
    io::{Read, Write},
    net::TcpStream,
    sync::Arc,
    thread,
    time::Duration,
};

use rustls::{
    pki_types::{CertificateDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    DesktopTlsIdentity, LoopbackPairingProofServer, LoopbackPairingProofServerError,
    PairingProofFrame, PairingProofRequest, PairingProofResponse,
};

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
    let mut connection = tls_connection(&expected_cert);
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
fn proof_reader_accepts_valid_request_without_ok_response() {
    let request = proof_request("desktop-01", 32, "session-01");
    let (server, cert) = proof_reader();

    let response = exchange(server, &cert, request_frame(&request));
    assert!(response.unwrap().is_empty());
}

#[test]
fn proof_reader_rejects_malformed_type_challenge_session_oversize_and_timeout() {
    let invalid_short_challenge = request_frame(&proof_request("desktop-01", 31, "session-01"));
    let response_type = PairingProofFrame::response(PairingProofResponse::ok(proof_request(
        "desktop-01",
        32,
        "session-01",
    )))
    .encode()
    .unwrap();
    let mut oversize = b"CCP1".to_vec();
    oversize.extend_from_slice(&[1, 1]);
    oversize.extend_from_slice(&1025u32.to_be_bytes());

    for frame in [
        b"bad".to_vec(),
        response_type,
        invalid_short_challenge,
        request_frame(&proof_request("desktop-01", 32, "bad session")),
        oversize,
    ] {
        let (server, cert) = proof_reader();
        assert!(exchange(server, &cert, frame).is_err());
    }

    let (server, cert) = proof_reader();
    assert!(connect_without_proof(server, &cert).is_err());
}

#[test]
fn loopback_tls_handshake_rejects_wrong_trust_root() {
    let server_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let wrong_identity = DesktopTlsIdentity::generate_ephemeral("Wrong Desktop").unwrap();
    let server =
        LoopbackPairingProofServer::bind(server_identity, Duration::from_millis(1500)).unwrap();
    let addr = server.local_addr();
    let handle = thread::spawn(move || server.accept_one());
    let mut connection = tls_connection(wrong_identity.certificate_der());
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

fn proof_reader() -> (LoopbackPairingProofServer, Vec<u8>) {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let cert = identity.certificate_der().to_vec();
    let server =
        LoopbackPairingProofServer::bind_pairing_proof_reader(identity, Duration::from_millis(350))
            .unwrap();
    (server, cert)
}

fn proof_request(desktop_id: &str, challenge_len: usize, session_id: &str) -> PairingProofRequest {
    PairingProofRequest::new(desktop_id, vec![7; 32], vec![3; challenge_len], session_id).unwrap()
}

fn request_frame(request: &PairingProofRequest) -> Vec<u8> {
    PairingProofFrame::request(request.clone())
        .encode()
        .unwrap()
}

fn exchange(
    server: LoopbackPairingProofServer,
    cert: &[u8],
    request: Vec<u8>,
) -> Result<Vec<u8>, ()> {
    let addr = server.local_addr();
    let handle = thread::spawn(move || server.accept_one());
    let mut tls = tls_stream(addr, cert);
    let write_result = tls.write_all(&request).and_then(|_| tls.flush());
    let mut response = Vec::new();
    let _ = tls.read_to_end(&mut response);
    let server_result = handle.join().unwrap();
    if write_result.is_ok() && server_result.is_ok() {
        Ok(response)
    } else {
        Err(())
    }
}

fn connect_without_proof(
    server: LoopbackPairingProofServer,
    cert: &[u8],
) -> Result<(), LoopbackPairingProofServerError> {
    let addr = server.local_addr();
    let handle = thread::spawn(move || server.accept_one());
    let _tls = tls_stream(addr, cert);
    handle.join().unwrap()
}

fn tls_stream(addr: std::net::SocketAddr, cert: &[u8]) -> StreamOwned<ClientConnection, TcpStream> {
    let stream = TcpStream::connect(addr).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_millis(500)))
        .unwrap();
    StreamOwned::new(tls_connection(cert), stream)
}

fn tls_connection(root_cert: &[u8]) -> ClientConnection {
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
