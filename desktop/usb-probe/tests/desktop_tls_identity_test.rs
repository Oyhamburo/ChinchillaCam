use usb_probe::{DesktopTlsIdentity, PairingQrPayload, PairingQrProducer};

#[test]
fn generates_p256_tls_identity_with_spki_embedded_in_certificate() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Chinchilla Desktop")
        .expect("OS CSPRNG-backed P-256 identity");

    assert!(identity.certificate_der().starts_with(&[0x30]));
    assert!(identity
        .spki_der_p256()
        .windows(10)
        .any(|window| { window == [0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07] }));
    assert!(identity
        .certificate_der()
        .windows(identity.spki_der_p256().len())
        .any(|window| window == identity.spki_der_p256()));
}

#[test]
fn qr_trust_material_is_exact_identity_spki_der() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop")
        .expect("OS CSPRNG-backed P-256 identity");
    let payload = PairingQrPayload::new(
        "desktop-01",
        "Studio Desktop",
        1_700_000_600,
        vec![0x10, 0x20, 0x30, 0x40],
        identity.qr_trust_material().to_vec(),
    )
    .expect("valid QR payload");

    assert_eq!(identity.qr_trust_material(), identity.spki_der_p256());
    assert!(
        String::from_utf8(PairingQrProducer::encode_v1(&payload).unwrap())
            .unwrap()
            .contains("&trustMaterial=")
    );
}

#[test]
fn generated_identity_does_not_reuse_default_key_material() {
    let first = DesktopTlsIdentity::generate_ephemeral("Chinchilla Desktop").unwrap();
    let second = DesktopTlsIdentity::generate_ephemeral("Chinchilla Desktop").unwrap();

    assert_ne!(first.spki_der_p256(), second.spki_der_p256());
}
