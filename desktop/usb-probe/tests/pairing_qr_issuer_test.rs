use usb_probe::{DesktopTlsIdentity, PairingQrIssuer, PairingQrIssuerError};

#[test]
fn issues_qr_with_32_byte_nonce_bounded_expiry_and_identity_spki() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        90,
        TestRng::new(7),
    )
    .unwrap();

    let issued = issuer.issue_at(1_700_000_000).unwrap();

    assert_eq!(issued.payload().desktop_id(), "desktop-01");
    assert_eq!(issued.payload().expires_at_epoch_seconds(), 1_700_000_090);
    assert_eq!(issued.payload().nonce().len(), 32);
    assert_eq!(issued.payload().trust_material(), identity.spki_der_p256());
    assert!(std::str::from_utf8(issued.qr_wire())
        .unwrap()
        .starts_with("CHINCHILLACAM-PAIR:v1:"));
}

#[test]
fn consumes_issued_nonce_once_and_rejects_stale_or_wrong_binding() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity,
        60,
        TestRng::new(1),
    )
    .unwrap();
    let issued = issuer.issue_at(100).unwrap();
    let nonce = issued.payload().nonce().to_vec();

    assert!(issuer.consume_issued_nonce("wrong", &nonce, 101).is_err());
    assert!(issuer
        .consume_issued_nonce("desktop-01", &[9; 32], 101)
        .is_err());
    issuer
        .consume_issued_nonce("desktop-01", &nonce, 101)
        .unwrap();
    assert_eq!(
        issuer.consume_issued_nonce("desktop-01", &nonce, 102),
        Err(PairingQrIssuerError::UnknownNonce)
    );

    let stale = issuer.issue_at(200).unwrap().payload().nonce().to_vec();
    assert_eq!(
        issuer.consume_issued_nonce("desktop-01", &stale, 260),
        Err(PairingQrIssuerError::ExpiredNonce)
    );
    let rollback = issuer.issue_at(300).unwrap().payload().nonce().to_vec();
    assert_eq!(
        issuer.consume_issued_nonce("desktop-01", &rollback, 299),
        Err(PairingQrIssuerError::ClockRollback)
    );
}

#[test]
fn rejects_duplicate_nonce_and_expiry_overflow() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        60,
        ConstantRng,
    )
    .unwrap();

    issuer.issue_at(100).unwrap();
    assert_eq!(
        issuer.issue_at(101),
        Err(PairingQrIssuerError::DuplicateNonce)
    );

    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity,
        60,
        TestRng::new(1),
    )
    .unwrap();
    assert_eq!(
        issuer.issue_at(i64::MAX as u64 - 30),
        Err(PairingQrIssuerError::ExpiryOverflow)
    );
}

#[test]
fn rejects_invalid_configuration_and_caps_outstanding_nonces() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    assert!(PairingQrIssuer::with_test_rng(
        "bad id",
        "Studio Desktop",
        identity.clone(),
        90,
        TestRng::new(1)
    )
    .is_err());
    assert!(PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity.clone(),
        30,
        TestRng::new(1)
    )
    .is_err());

    let mut issuer = PairingQrIssuer::with_test_rng(
        "desktop-01",
        "Studio Desktop",
        identity,
        60,
        TestRng::new(1),
    )
    .unwrap();
    for second in 0..64 {
        issuer.issue_at(second).unwrap();
    }
    assert_eq!(
        issuer.issue_at(64),
        Err(PairingQrIssuerError::TooManyOutstandingNonces)
    );
}

struct ConstantRng;

impl usb_probe::PairingQrNonceGenerator for ConstantRng {
    fn fill_nonce(&mut self, nonce: &mut [u8; 32]) -> Result<(), PairingQrIssuerError> {
        nonce.fill(7);
        Ok(())
    }
}

#[derive(Clone)]
struct TestRng {
    next: u8,
}

impl TestRng {
    fn new(next: u8) -> Self {
        Self { next }
    }
}

impl usb_probe::PairingQrNonceGenerator for TestRng {
    fn fill_nonce(&mut self, nonce: &mut [u8; 32]) -> Result<(), PairingQrIssuerError> {
        nonce.fill(self.next);
        self.next = self.next.wrapping_add(1);
        Ok(())
    }
}
