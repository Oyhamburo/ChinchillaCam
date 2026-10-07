//! Task s2 (`odd/tasks/usb-authenticated-session.md`, contract section 4.1): the desktop's
//! own per-connection choice between the pairing flow (`accept_phone_pairing_connection`)
//! and the trusted-reconnection flow (`accept_phone_reconnect_connection`). Reuses the
//! CCP1/pairing-proof fixtures from `usb_tls_pairing_proof_test.rs` and the trusted-store
//! fixtures from `usb_tls_trusted_session_test.rs`, each duplicated locally per this file's
//! existing per-test-file convention (see "Evidencia s1" desvío 1 in the task doc).

use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    path::PathBuf,
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, StreamOwned,
};
use usb_probe::{
    accept_phone_pairing_connection, accept_phone_reconnect_connection, pairing_short_code_v1,
    phone_id_for_spki, DesktopTlsIdentity, FileTrustedPhoneStore, FrameTransferBudget,
    FramedUsbStream, PairedPhoneCandidateConfirmError, PairingProofFrame, PairingProofRequest,
    PairingProofResponse, PairingQrIssuer, PairingQrIssuerError, PhoneConnectionError,
    SessionFrame, SessionFramePayload, SessionIdentity, TlsSessionFrameError, TrustedPhoneIdentity,
    UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
};

#[test]
fn pairing_mode_holds_channel_until_confirm() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Candidate Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("pairing-holds-channel");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<String, String> {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;

            let mut session = pending
                .confirm("Candidate Phone", &store)
                .map_err(|error| error.to_string())?;

            // Proves the SAME live TLS stream was retained across confirm(): app data
            // exchanged now must still reach the phone on the other end of it.
            let mut message = [0; 11];
            session
                .tls
                .read_exact(&mut message)
                .map_err(|error| error.to_string())?;
            if &message != b"after-proof" {
                return Err(format!("unexpected message: {message:?}"));
            }
            session
                .tls
                .write_all(b"server-ack")
                .and_then(|_| session.tls.flush())
                .map_err(|error| error.to_string())?;
            Ok(session.phone_id)
        });

        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        let response = read_ccp1_frame(&mut tls).unwrap();
        assert_eq!(
            response,
            PairingProofFrame::response(PairingProofResponse::ok(request))
                .encode()
                .unwrap()
        );
        tls.write_all(b"after-proof").unwrap();
        tls.flush().unwrap();
        let mut ack = [0; 10];
        tls.read_exact(&mut ack).unwrap();
        assert_eq!(&ack, b"server-ack");

        let returned_phone_id = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, expected_phone_id);
    });

    assert!(
        store
            .trusted_identity(&expected_phone_id)
            .unwrap()
            .is_some(),
        "store must hold the confirmed phone"
    );
    cleanup(store_path);
}

/// Task d1: the pending pairing exposes the SAS v1 code built from both SPKIs and the two
/// nonces the phone actually sent in its CCP1 request.
#[test]
fn pending_pairing_exposes_short_code_matching_phone_inputs() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Code Phone").unwrap();
    let expected = pairing_short_code_v1(
        identity.spki_der_p256(),
        phone_identity.spki_der_p256(),
        request.qr_nonce(),
        request.challenge_nonce(),
    )
    .unwrap();
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            let code = pending.short_code(&identity).map_err(|e| e.to_string());
            pending.reject();
            code
        });

        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        read_ccp1_frame(&mut tls).unwrap();

        assert_eq!(server.join().unwrap().unwrap(), expected);
    });
}

#[test]
fn pairing_reject_closes_channel() {
    let now = now_seconds();
    let (identity, mut issuer, request) = proof_fixture(now, "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Rejected Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("pairing-reject-closes-channel");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(), String> {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            pending.reject();
            Ok(())
        });

        let mut tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        tls.write_all(&request_frame(&request)).unwrap();
        tls.flush().unwrap();
        let response = read_ccp1_frame(&mut tls).unwrap();
        assert_eq!(
            response,
            PairingProofFrame::response(PairingProofResponse::ok(request))
                .encode()
                .unwrap()
        );

        // The desktop rejected the candidate: the channel must now be closed instead of
        // accepting any further application data. rustls reports a cleanly-received
        // close_notify as `Ok(0)` from `Read::read` regardless of TCP-level EOF.
        let mut probe = [0u8; 1];
        let observed_close = match tls.read(&mut probe) {
            Ok(0) => true,
            Err(error) => error.kind() == io::ErrorKind::UnexpectedEof,
            Ok(_) => false,
        };
        assert!(
            observed_close,
            "expected the channel to be closed after reject()"
        );

        server.join().unwrap().unwrap();
    });

    assert!(
        store
            .trusted_identity(&expected_phone_id)
            .unwrap()
            .is_none(),
        "reject() must never persist the candidate"
    );
    cleanup(store_path);
}

#[test]
fn reconnect_mode_accepts_trusted_phone_hello() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let expected_desktop_id = phone_id_for_spki(identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-accepts-hello");
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
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(String, Vec<String>, bool), String> {
            let session = accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            let supports_quality = session.supports_quality_control();
            Ok((
                session.phone_id,
                session.phone_capabilities,
                supports_quality,
            ))
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        let hello = SessionFrame::new(
            1,
            "session-01",
            SessionFramePayload::HandshakeHello {
                device_id: phone_id.clone(),
                app_name: "ChinchillaCam".to_string(),
                capabilities: std::iter::once("quality-control-v1".to_string())
                    .chain(std::iter::once("x".repeat(65)))
                    .chain((0..40).map(|i| format!("other-{i}")))
                    .collect(),
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

        let (returned_phone_id, capabilities, supports_quality) = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, phone_id);
        assert!(supports_quality);
        assert_eq!(capabilities.len(), 32);
        assert_eq!(capabilities[0], "quality-control-v1");
        assert!(!capabilities.iter().any(|value| value.len() > 64));
    });

    cleanup(store_path);
}

/// Task s1 RED (`odd/tasks/session-runtime.md`, contract section 4.1): an authenticated
/// reconnect session must retain the session identity established by HELLO/ACCEPT -- the
/// `session_id`, the next inbound sequence (`h + 1`) and the next outbound sequence
/// (`h + 2`) -- so the runtime can validate and continue the shared sequence counter.
#[test]
fn reconnect_session_keeps_session_identity() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-keeps-session-identity");
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
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| -> Result<(Option<SessionIdentity>, bool), String> {
            let session = accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            Ok((session.session.clone(), session.supports_quality_control()))
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        let hello = SessionFrame::new(
            41,
            "session-keep",
            SessionFramePayload::HandshakeHello {
                device_id: phone_id.clone(),
                app_name: "ChinchillaCam".to_string(),
                capabilities: vec!["video".to_string()],
            },
        );
        usb_probe::write_session_frame(&mut phone_tls, &hello).unwrap();
        let accept = usb_probe::read_session_frame(&mut phone_tls, test_deadline()).unwrap();
        assert_eq!(
            accept.sequence(),
            42,
            "ACCEPT uses hello.sequence() + 1 (contract section 4.1)"
        );

        let (session, supports_quality) = server.join().unwrap().unwrap();
        assert!(!supports_quality);
        assert_eq!(
            session,
            Some(SessionIdentity {
                session_id: "session-keep".to_string(),
                next_inbound_sequence: 42,
                next_outbound_sequence: 43,
            })
        );
    });

    cleanup(store_path);
}

/// Task s2b (contract section 4.4 addendum, agreed with the Android side): a trusted
/// phone's certificate authenticates ITS OWN `phone_id` for this connection, so a
/// `HandshakeHello` claiming a DIFFERENT `device_id` must be rejected even though the TLS
/// handshake itself succeeded against a trusted certificate.
#[test]
fn reconnect_mode_rejects_hello_with_foreign_device_id() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let foreign_device_id = format!("{phone_id}-not-mine");

    let store_path = unique_store_path("reconnect-rejects-foreign-device-id");
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
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        // The certificate proves `phone_id`, but the HELLO claims a different device_id:
        // the desktop must not take the phone's word for its own identity.
        let hello = SessionFrame::new(
            1,
            "session-01",
            SessionFramePayload::HandshakeHello {
                device_id: foreign_device_id,
                app_name: "ChinchillaCam".to_string(),
                capabilities: vec!["video".to_string()],
            },
        );
        usb_probe::write_session_frame(&mut phone_tls, &hello).unwrap();

        match usb_probe::read_session_frame(&mut phone_tls, test_deadline()) {
            Ok(frame) => assert!(
                matches!(frame.payload(), SessionFramePayload::HandshakeReject { .. }),
                "expected HandshakeReject, got {frame:?}"
            ),
            Err(error) => {
                panic!("expected a decodable HandshakeReject, got a read error instead: {error:?}")
            }
        }

        match server.join().unwrap() {
            Err(PhoneConnectionError::HelloDeviceIdMismatch) => {}
            Err(other) => panic!("expected Err(HelloDeviceIdMismatch), got Err({other:?})"),
            Ok(_) => panic!("expected Err(HelloDeviceIdMismatch), got Ok"),
        }
    });

    cleanup(store_path);
}

#[test]
fn reconnect_mode_rejects_invalid_hello() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-rejects-invalid-hello");
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
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        // Not a HandshakeHello: the desktop must reject the reconnection instead of
        // accepting it.
        let not_hello = SessionFrame::new(
            1,
            "session-01",
            SessionFramePayload::HandshakeAccept {
                desktop_id: "not-a-hello".to_string(),
                message: "wrong frame type".to_string(),
            },
        );
        usb_probe::write_session_frame(&mut phone_tls, &not_hello).unwrap();

        // Task s2b (native review finding R3-reject-not-asserted): tightened from
        // tolerating any read error to requiring the phone actually receive a decodable
        // HandshakeReject. Framing was intact (the read itself succeeded), so the channel
        // is still usable for a best-effort reply before closing, and this in-memory
        // transport is synchronous and deterministic (no real network raciness), so the
        // reply must be observable if it was written.
        match usb_probe::read_session_frame(&mut phone_tls, test_deadline()) {
            Ok(frame) => assert!(
                matches!(frame.payload(), SessionFramePayload::HandshakeReject { .. }),
                "expected HandshakeReject, got {frame:?}"
            ),
            Err(error) => {
                panic!("expected a decodable HandshakeReject, got a read error instead: {error:?}")
            }
        }

        match server.join().unwrap() {
            Err(PhoneConnectionError::UnexpectedFirstFrame) => {}
            Err(other) => panic!("expected Err(UnexpectedFirstFrame), got Err({other:?})"),
            Ok(_) => panic!("expected Err(UnexpectedFirstFrame), got Ok"),
        }
    });

    cleanup(store_path);
}

/// Task s2b (native review finding R3-error-paths-uncovered, partial): a first frame that
/// fails to even decode (as opposed to decoding cleanly into some other payload type, see
/// `reconnect_mode_rejects_invalid_hello` above) must map to `InvalidHello`, not
/// `UnexpectedFirstFrame`, and per `tls_session_frame`'s own fail-closed contract the
/// channel is closed WITHOUT attempting a `HandshakeReject` (the stream may already be
/// desynchronized by a bad length prefix).
#[test]
fn reconnect_mode_rejects_unparseable_first_frame_as_invalid_hello() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Reconnecting Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());

    let store_path = unique_store_path("reconnect-rejects-unparseable-first-frame");
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
    let (desktop_io, phone_io) = crossed_bulk_pair();

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_reconnect_connection(
                ciphertext_stream(desktop_io),
                &identity,
                Arc::new(store),
                Duration::from_millis(1500),
            )
        });

        let mut phone_tls = phone_tls_stream(phone_io, &cert, &phone_identity);
        // An oversized length prefix (no valid frame behind it): `read_session_frame` must
        // reject it before allocating or reading further.
        phone_tls.write_all(&[0xFF, 0xFF, 0xFF, 0xFF]).unwrap();
        phone_tls.flush().unwrap();

        match server.join().unwrap() {
            Err(PhoneConnectionError::InvalidHello(TlsSessionFrameError::InvalidLength(
                length,
            ))) => {
                assert_eq!(length, 0xFFFF_FFFF);
            }
            Err(other) => {
                panic!("expected Err(InvalidHello(InvalidLength(_))), got Err({other:?})")
            }
            Ok(_) => panic!("expected Err(InvalidHello(InvalidLength(_))), got Ok"),
        }

        // Fail-closed contract: no HandshakeReject is attempted for a read/decode error
        // (unlike `UnexpectedFirstFrame`/`HelloDeviceIdMismatch` above), but the channel is
        // still closed regardless.
        let mut probe = [0u8; 1];
        let observed_close = match phone_tls.read(&mut probe) {
            Ok(0) => true,
            Err(error) => error.kind() == io::ErrorKind::UnexpectedEof,
            Ok(_) => false,
        };
        assert!(
            observed_close,
            "expected the channel to be closed after an unparseable first frame"
        );
    });

    cleanup(store_path);
}

/// Task d2 RED (`odd/tasks/desktop-production-app.md` section 4.2): right after its own
/// confirm the phone sends `HandshakeHello` on the same channel; `confirm_and_start` must
/// persist the phone, answer `HandshakeAccept` and return a session identity exactly like
/// the reconnect path does.
#[test]
fn confirmed_pairing_accepts_phone_hello() {
    let (identity, mut issuer, request) = proof_fixture(now_seconds(), "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Paired Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let expected_desktop_id = phone_id_for_spki(identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("confirmed-pairing-accepts-hello");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .map_err(|error| error.to_string())?;
            let session = pending
                .confirm_and_start(
                    "Paired Phone",
                    &store,
                    &identity,
                    Duration::from_millis(1500),
                )
                .map_err(|error| error.to_string())?;
            Ok::<_, String>((session.phone_id, session.session))
        });

        let mut phone_tls = paired_phone_tls(phone_io, &cert, &phone_identity, &request);
        usb_probe::write_session_frame(&mut phone_tls, &hello_frame(0, "pair-session", &phone_id))
            .unwrap();
        let accept = usb_probe::read_session_frame(&mut phone_tls, test_deadline()).unwrap();
        match accept.payload() {
            SessionFramePayload::HandshakeAccept { desktop_id, .. } => {
                assert_eq!(desktop_id, &expected_desktop_id);
            }
            other => panic!("expected HandshakeAccept, got {other:?}"),
        }
        assert_eq!(accept.sequence(), 1, "ACCEPT uses hello.sequence() + 1");
        assert_eq!(accept.session_id(), "pair-session");

        let (returned_phone_id, session) = server.join().unwrap().unwrap();
        assert_eq!(returned_phone_id, phone_id);
        assert_eq!(
            session,
            Some(SessionIdentity {
                session_id: "pair-session".to_string(),
                next_inbound_sequence: 1,
                next_outbound_sequence: 2,
            })
        );
    });

    assert!(store.trusted_identity(&phone_id).unwrap().is_some());
    cleanup(store_path);
}

#[test]
fn confirmed_pairing_rejects_foreign_hello_device_id() {
    let (identity, mut issuer, request) = proof_fixture(now_seconds(), "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Paired Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("confirmed-pairing-foreign-hello");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .unwrap()
            .confirm_and_start(
                "Paired Phone",
                &store,
                &identity,
                Duration::from_millis(1500),
            )
            .map(|session| session.phone_id)
        });

        let mut phone_tls = paired_phone_tls(phone_io, &cert, &phone_identity, &request);
        let foreign = format!("{phone_id}-not-mine");
        usb_probe::write_session_frame(&mut phone_tls, &hello_frame(0, "pair-session", &foreign))
            .unwrap();
        let reject = usb_probe::read_session_frame(&mut phone_tls, test_deadline()).unwrap();
        match reject.payload() {
            SessionFramePayload::HandshakeReject { reason_code, .. } => {
                assert_eq!(reason_code, "device_id_mismatch");
            }
            other => panic!("expected HandshakeReject, got {other:?}"),
        }

        assert_eq!(
            server.join().unwrap(),
            Err(PhoneConnectionError::HelloDeviceIdMismatch)
        );
    });

    cleanup(store_path);
}

#[test]
fn confirmed_pairing_hello_deadline_expires() {
    let (identity, mut issuer, request) = proof_fixture(now_seconds(), "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Silent Phone").unwrap();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("confirmed-pairing-hello-deadline");
    let store = FileTrustedPhoneStore::new(&store_path);

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            let pending = accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .unwrap();
            let started = Instant::now();
            let result = pending
                .confirm_and_start(
                    "Silent Phone",
                    &store,
                    &identity,
                    Duration::from_millis(300),
                )
                .map(|session| session.phone_id);
            (result, started.elapsed())
        });

        // The phone completes the pairing proof but never sends HandshakeHello. This fake
        // transport surfaces the stall as an I/O error after its own 1500 ms bulk budget
        // (real USB maps it to `TimedOut`, which `read_session_frame` turns into `Timeout`
        // at the deadline); either way the wait is bounded and fails closed.
        let _phone_tls = paired_phone_tls(phone_io, &cert, &phone_identity, &request);

        let (result, elapsed) = server.join().unwrap();
        assert!(
            matches!(result, Err(PhoneConnectionError::InvalidHello(_))),
            "expected Err(InvalidHello), got {result:?}"
        );
        assert!(
            elapsed < Duration::from_secs(5),
            "HELLO wait must stay bounded, took {elapsed:?}"
        );
    });

    cleanup(store_path);
}

#[test]
fn confirm_and_start_maps_revoked_phone_and_closes_channel() {
    let (identity, mut issuer, request) = proof_fixture(now_seconds(), "desktop-01", vec![7; 32]);
    let cert = identity.certificate_der().to_vec();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Revoked Phone").unwrap();
    let phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let store_path = unique_store_path("confirm-and-start-revoked");
    let store = FileTrustedPhoneStore::new(&store_path);
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
    assert!(store.revoke(&phone_id).unwrap());

    thread::scope(|scope| {
        let server = scope.spawn(|| {
            accept_phone_pairing_connection(
                ciphertext_stream(desktop_io),
                &identity,
                &mut issuer,
                Duration::from_millis(1500),
            )
            .unwrap()
            .confirm_and_start(
                "Revoked Phone",
                &store,
                &identity,
                Duration::from_millis(1500),
            )
            .map(|session| session.phone_id)
        });

        let mut phone_tls = paired_phone_tls(phone_io, &cert, &phone_identity, &request);
        assert_eq!(
            server.join().unwrap(),
            Err(PhoneConnectionError::PairingConfirm(
                PairedPhoneCandidateConfirmError::PhoneRevoked
            ))
        );
        let mut probe = [0u8; 1];
        let observed_close = match phone_tls.read(&mut probe) {
            Ok(0) => true,
            Err(error) => error.kind() == io::ErrorKind::UnexpectedEof,
            Ok(_) => false,
        };
        assert!(observed_close, "expected the channel to be closed");
    });

    cleanup(store_path);
}

/// Phone side of a successful pairing proof: TLS handshake, CCP1 request, OK response.
fn paired_phone_tls(
    phone_io: CrossedBulkIo,
    cert: &[u8],
    phone_identity: &DesktopTlsIdentity,
    request: &PairingProofRequest,
) -> StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>> {
    let mut tls = phone_tls_stream(phone_io, cert, phone_identity);
    tls.write_all(&request_frame(request)).unwrap();
    tls.flush().unwrap();
    read_ccp1_frame(&mut tls).unwrap();
    tls
}

fn hello_frame(sequence: i32, session_id: &str, device_id: &str) -> SessionFrame {
    SessionFrame::new(
        sequence,
        session_id,
        SessionFramePayload::HandshakeHello {
            device_id: device_id.to_string(),
            app_name: "ChinchillaCam".to_string(),
            capabilities: vec!["video".to_string()],
        },
    )
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
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
        "chinchillacam-phone-connection-{name}-{}-{nanos}.txt",
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
