//! Task d2 (`odd/tasks/wifi-loopback-transport.md`, contract section 4): a loopback-only TCP
//! "fake Wi-Fi LAN" listener that feeds real `TcpStream`s into the existing mTLS
//! pairing/reconnection accept functions, with no logic change to that stack. The desktop may
//! only bind `127.0.0.1` with an ephemeral port (section 4.2); these tests assert that, drive
//! pairing + reconnection end-to-end over a real TCP socket, and prove a silent peer and a
//! peerless accept both fail within a bounded time rather than hanging.
//!
//! Modelled on `in_memory_duplex_session_test.rs` (thread::scope, a rustls `ClientConnection`
//! presenting a phone client cert, `TestRng`, temp trusted-phone store), but over
//! `TcpStream::connect(listener.local_addr())` instead of the in-memory duplex double.

use std::{
    io::{Read, Write},
    net::TcpStream,
    path::PathBuf,
    sync::{mpsc, Arc},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_pairing_connection, accept_phone_reconnect_connection, phone_id_for_spki,
    DesktopTlsIdentity, FileTrustedPhoneStore, LoopbackLanError, LoopbackLanListener,
    LoopbackLanOptions, PairingProofFrame, PairingProofRequest, PairingProofResponse,
    PairingQrIssuer, PairingQrIssuerError, SessionFrame, SessionFramePayload,
};

const OP_TIMEOUT: Duration = Duration::from_millis(1500);
const ACCEPT_DEADLINE: Duration = Duration::from_millis(1500);
/// Hard cap on how long a helper peer thread may hold its socket. Helpers are released
/// earlier through a channel (explicitly, or implicitly when the sender is dropped while a
/// failing test unwinds), so this cap only matters if the release is somehow lost; it
/// guarantees `thread::scope` can always join the helper instead of hanging.
const PEER_HOLD_LIMIT: Duration = Duration::from_secs(10);
/// Scheduler/clock slack allowed below a deadline when asserting a timing lower bound.
const TIMING_TOLERANCE: Duration = Duration::from_millis(50);

#[test]
fn loopback_lan_listener_binds_only_loopback() {
    let listener = LoopbackLanListener::bind().expect("bind loopback listener");
    let addr = listener.local_addr();
    assert!(
        addr.ip().is_loopback(),
        "listener must bind a loopback address, got {addr}"
    );
    assert_ne!(addr.port(), 0, "listener must bind an effective port");
}

#[test]
fn pairing_then_reconnect_over_loopback_tcp() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Loopback Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let expected_desktop_id = phone_id_for_spki(identity.spki_der_p256());

    let store_path = unique_store_path("loopback-pairing-then-reconnect");
    let store = FileTrustedPhoneStore::new(&store_path);

    let listener = LoopbackLanListener::bind().expect("bind loopback listener");
    let addr = listener.local_addr();

    std::thread::scope(|scope| {
        // Hang safety: every blocking call in this helper is bounded (1500 ms socket read/write
        // timeouts in `phone_tls_stream`, `test_deadline()` for the session frame), so if a
        // desktop-side assertion below fails and the scope unwinds, the helper still finishes
        // on its own and `thread::scope` joins it within a bounded time.
        let phone = scope.spawn(|| -> Result<(), String> {
            // Connection 1: pairing (CCP1 proof over mTLS).
            let tcp = TcpStream::connect(addr).map_err(|error| error.to_string())?;
            let mut tls = phone_tls_stream(tcp, &cert, &phone_identity);
            tls.write_all(&request_frame(&request))
                .map_err(|error| error.to_string())?;
            tls.flush().map_err(|error| error.to_string())?;
            let response = read_ccp1_frame(&mut tls).map_err(|error| error.to_string())?;
            let expected = PairingProofFrame::response(PairingProofResponse::ok(request.clone()))
                .encode()
                .map_err(|error| format!("{error:?}"))?;
            if response != expected {
                return Err("unexpected CCP1 response".to_string());
            }
            drop(tls);

            // Connection 2: trusted reconnection (HELLO/ACCEPT) for the same phone.
            let tcp = TcpStream::connect(addr).map_err(|error| error.to_string())?;
            let mut tls = phone_tls_stream(tcp, &cert, &phone_identity);
            let hello = SessionFrame::new(
                1,
                "session-01",
                SessionFramePayload::HandshakeHello {
                    device_id: phone_id.clone(),
                    app_name: "ChinchillaCam".to_string(),
                    capabilities: vec!["video".to_string()],
                },
            );
            usb_probe::write_session_frame(&mut tls, &hello).map_err(|error| error.to_string())?;
            let accept = usb_probe::read_session_frame(&mut tls, test_deadline())
                .map_err(|error| error.to_string())?;
            match accept.payload() {
                SessionFramePayload::HandshakeAccept { desktop_id, .. } => {
                    if desktop_id != &expected_desktop_id {
                        return Err(format!("unexpected desktop_id {desktop_id}"));
                    }
                }
                other => return Err(format!("expected HandshakeAccept, got {other:?}")),
            }
            Ok(())
        });

        // Desktop side: accept the pairing connection, confirm the candidate into the trusted
        // store, then accept the reconnection and finish the HELLO/ACCEPT exchange.
        let tcp = listener
            .accept(ACCEPT_DEADLINE)
            .expect("accept pairing connection");
        let pending = accept_phone_pairing_connection(tcp, &identity, &mut issuer, OP_TIMEOUT)
            .expect("pairing handshake");
        assert_eq!(pending.candidate.phone_id, phone_id);
        let authenticated = pending
            .confirm("Loopback Phone", &store)
            .expect("confirm candidate into trusted store");
        assert_eq!(authenticated.phone_id, phone_id);
        drop(authenticated);

        let lookup: Arc<FileTrustedPhoneStore> = Arc::new(store);
        let tcp = listener
            .accept(ACCEPT_DEADLINE)
            .expect("accept reconnect connection");
        let session = accept_phone_reconnect_connection(tcp, &identity, lookup, OP_TIMEOUT)
            .expect("trusted reconnect handshake");
        assert_eq!(session.phone_id, phone_id);

        phone.join().unwrap().unwrap();
    });

    cleanup(store_path);
}

#[test]
fn silent_loopback_peer_fails_bounded() {
    let read_timeout = Duration::from_millis(300);
    let options = LoopbackLanOptions {
        read_timeout,
        write_timeout: Duration::from_millis(300),
    };
    let listener = LoopbackLanListener::bind_with_options(options).expect("bind loopback listener");
    let addr = listener.local_addr();

    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = test_issuer(&identity);

    // Hang safety: the silent peer holds its socket only until it is released through this
    // channel. The desktop side releases it and joins it BEFORE any assertion runs; if the
    // desktop side panics earlier (e.g. a failed `accept`), unwinding drops `release_tx`, which
    // disconnects the channel and frees the helper. `PEER_HOLD_LIMIT` caps it regardless.
    let (release_tx, release_rx) = mpsc::channel::<()>();

    let (result, elapsed) = std::thread::scope(|scope| {
        // A peer that connects but never sends anything: the TCP accept succeeds, but the TLS
        // handshake read must time out via the per-stream read timeout.
        let phone = scope.spawn(move || {
            let _tcp = TcpStream::connect(addr).expect("connect silent peer");
            let _ = release_rx.recv_timeout(PEER_HOLD_LIMIT);
        });

        let tcp = listener
            .accept(ACCEPT_DEADLINE)
            .expect("accept silent peer");
        // Generous logical timeout: the point is that the per-stream read timeout bounds the
        // handshake well below this, not the logical deadline.
        let logical_timeout = Duration::from_secs(5);
        let start = Instant::now();
        let result = accept_phone_pairing_connection(tcp, &identity, &mut issuer, logical_timeout);
        let elapsed = start.elapsed();

        drop(release_tx);
        phone.join().expect("silent peer helper panicked");
        (result, elapsed)
    });

    // The helper is already joined: a failing assertion here cannot hang the test.
    assert!(
        result.is_err(),
        "silent peer must fail the handshake, got Ok(..)"
    );
    assert!(
        elapsed < read_timeout * 10,
        "handshake against a silent peer must be bounded, took {elapsed:?}"
    );
}

#[test]
fn accept_times_out_without_peer() {
    let deadline = Duration::from_millis(200);
    let listener = LoopbackLanListener::bind().expect("bind loopback listener");
    let start = Instant::now();
    let result = listener.accept(deadline);
    let elapsed = start.elapsed();
    assert!(
        matches!(result, Err(LoopbackLanError::Timeout)),
        "accept with no peer must return a typed timeout, got {result:?}"
    );
    assert!(
        elapsed >= deadline - TIMING_TOLERANCE,
        "accept must wait for its deadline before timing out, took {elapsed:?}"
    );
    assert!(
        elapsed < Duration::from_secs(5),
        "accept timeout must be bounded, took {elapsed:?}"
    );
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
}

fn phone_tls_stream(
    tcp: TcpStream,
    root_cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
) -> StreamOwned<ClientConnection, TcpStream> {
    tcp.set_read_timeout(Some(Duration::from_millis(1500)))
        .unwrap();
    tcp.set_write_timeout(Some(Duration::from_millis(1500)))
        .unwrap();
    let mut tcp = tcp;
    let mut client = tls_client(root_cert, phone_identity);
    while client.is_handshaking() {
        client.complete_io(&mut tcp).unwrap();
    }
    StreamOwned::new(client, tcp)
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
    let mut issuer = test_issuer(&identity);
    issuer.issue_at(issued_at).unwrap();
    let request =
        PairingProofRequest::new(desktop_id, request_nonce, vec![3; 32], "session-01").unwrap();
    (identity, issuer, request)
}

fn test_issuer(identity: &DesktopTlsIdentity) -> PairingQrIssuer<TestRng> {
    PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        60,
        TestRng(7),
    )
    .unwrap()
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
        "chinchillacam-loopback-lan-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}

fn cleanup(path: PathBuf) {
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(path.with_extension("lock"));
}
