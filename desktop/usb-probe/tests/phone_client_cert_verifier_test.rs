use std::{path::PathBuf, sync::Arc, time::SystemTime};

use rcgen::{
    Certificate, CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P384_SHA384,
};
use rustls::{
    pki_types::{CertificateDer, UnixTime},
    server::danger::ClientCertVerifier,
    CertificateError, Error as RustlsError,
};
use sha2::{Digest, Sha256};

use usb_probe::{
    phone_id_for_spki, DesktopTlsIdentity, FileTrustedPhoneStore, PhoneClientCertVerifier,
    TrustedPhoneIdentity, TrustedPhoneLookup, TrustedPhoneLookupError, TrustedPhoneStatus,
};

#[test]
fn pairing_policy_accepts_canonical_p256_client_spki() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Pairing Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let verifier = PhoneClientCertVerifier::pairing();

    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(result.is_ok(), "expected Ok, got {result:?}");
}

#[test]
fn rejects_non_p256_client_certificate() {
    let cert_der = CertificateDer::from(generate_p384_certificate_der());
    let pairing = PhoneClientCertVerifier::pairing();
    let trusted_only = PhoneClientCertVerifier::trusted_only(Arc::new(EmptyTrustedPhoneLookup));

    assert!(pairing
        .verify_client_cert(&cert_der, &[], UnixTime::now())
        .is_err());
    assert!(trusted_only
        .verify_client_cert(&cert_der, &[], UnixTime::now())
        .is_err());
}

#[test]
fn trusted_only_rejects_unknown_phone() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Unknown Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(EmptyTrustedPhoneLookup));

    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(matches!(
        result,
        Err(RustlsError::InvalidCertificate(
            CertificateError::UnknownIssuer
        ))
    ));
}

#[test]
fn trusted_only_rejects_revoked_phone() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Revoked Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let spki = identity.spki_der_p256().to_vec();
    let phone_id = phone_id_for_spki(&spki);

    let path = unique_store_path("revoked");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new(phone_id.clone(), "Revoked Phone", spki).unwrap())
        .unwrap();
    store.revoke(&phone_id).unwrap();

    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(store));
    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(matches!(
        result,
        Err(RustlsError::InvalidCertificate(CertificateError::Revoked))
    ));
    cleanup(path);
}

#[test]
fn trusted_only_accepts_trusted_phone() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Trusted Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let spki = identity.spki_der_p256().to_vec();
    let phone_id = phone_id_for_spki(&spki);

    let path = unique_store_path("trusted");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new(phone_id, "Trusted Phone", spki).unwrap())
        .unwrap();

    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(store));
    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(result.is_ok(), "expected Ok, got {result:?}");
    cleanup(path);
}

#[test]
fn trusted_only_rejects_when_lookup_fails() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Erroring Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(FailingTrustedPhoneLookup));

    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(result.is_err());
}

#[test]
fn phone_id_is_lowercase_sha256_hex_of_spki() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Hash Phone").unwrap();
    let spki = identity.spki_der_p256();

    let phone_id = phone_id_for_spki(spki);

    assert_eq!(phone_id, hex_lower(&Sha256::digest(spki)));
    assert_eq!(phone_id.len(), 64);
    assert!(phone_id
        .chars()
        .all(|character| character.is_ascii_digit() || ('a'..='f').contains(&character)));
}

#[derive(Debug)]
struct EmptyTrustedPhoneLookup;

impl TrustedPhoneLookup for EmptyTrustedPhoneLookup {
    fn phone_status(&self, _phone_id: &str) -> Result<TrustedPhoneStatus, TrustedPhoneLookupError> {
        Ok(TrustedPhoneStatus::Unknown)
    }
}

#[derive(Debug)]
struct FailingTrustedPhoneLookup;

impl TrustedPhoneLookup for FailingTrustedPhoneLookup {
    fn phone_status(&self, _phone_id: &str) -> Result<TrustedPhoneStatus, TrustedPhoneLookupError> {
        Err(TrustedPhoneLookupError::new("simulated lookup failure"))
    }
}

fn generate_p384_certificate_der() -> Vec<u8> {
    let key_pair = KeyPair::generate(&PKCS_ECDSA_P384_SHA384).unwrap();
    let mut distinguished_name = DistinguishedName::new();
    distinguished_name.push(DnType::CommonName, "Non P256 Phone");
    let mut params = CertificateParams::new(vec!["localhost".to_string()]);
    params.distinguished_name = distinguished_name;
    params.alg = &PKCS_ECDSA_P384_SHA384;
    params.key_pair = Some(key_pair);
    let certificate = Certificate::from_params(params).unwrap();
    certificate.serialize_der().unwrap()
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-phone-cert-verifier-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}

fn cleanup(path: PathBuf) {
    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(path.with_extension("tmp"));
}
