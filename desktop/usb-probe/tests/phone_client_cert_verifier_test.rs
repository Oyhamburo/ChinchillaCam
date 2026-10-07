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

    assert!(matches!(
        pairing.verify_client_cert(&cert_der, &[], UnixTime::now()),
        Err(RustlsError::InvalidCertificate(
            CertificateError::BadEncoding
        ))
    ));
    assert!(matches!(
        trusted_only.verify_client_cert(&cert_der, &[], UnixTime::now()),
        Err(RustlsError::InvalidCertificate(
            CertificateError::BadEncoding
        ))
    ));
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

    match result {
        Err(RustlsError::General(message)) => {
            assert!(
                message.contains("simulated lookup failure"),
                "expected the lookup error's Display text in the General message, got {message:?}"
            );
        }
        other => panic!("expected Err(General(_)) with the lookup error text, got {other:?}"),
    }
}

#[test]
fn trusted_only_rejects_revoked_phone_despite_earlier_duplicate_trusted_record() {
    // Native review finding R3-snapshot-first-match: `load_records` does not reject
    // duplicate phone_id records, and `phone_trust_snapshot` used to pick the FIRST
    // matching record via `find()`. A store file with two records sharing the same
    // phone_id -- inconsistent state the store's own `trust`/`revoke` API can never
    // produce, but a hand-edited or externally written file could -- let an earlier
    // non-revoked duplicate win over a later revocation, fail-opening a revoked phone.
    let identity = DesktopTlsIdentity::generate_ephemeral("Duplicate Record Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let spki = identity.spki_der_p256().to_vec();
    let phone_id = phone_id_for_spki(&spki);

    let path = unique_store_path("duplicate-revoked");
    write_duplicate_phone_id_store(&path, &phone_id, &hex_lower(&spki));

    let store = FileTrustedPhoneStore::new(&path);
    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(store));
    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(
        matches!(
            result,
            Err(RustlsError::InvalidCertificate(CertificateError::Revoked))
        ),
        "expected Err(InvalidCertificate(Revoked)), got {result:?}"
    );
    cleanup(path);
}

#[test]
fn trusted_only_rejects_client_when_store_has_conflicting_non_revoked_duplicate_keys() {
    // Native review finding R3-corrupt-store-branch-untested: `phone_trust_snapshot`'s
    // `CorruptStore` branch (two NON-revoked records for the same phone_id that disagree
    // on public_key -- state `trust`/`revoke` can never produce, but a hand-edited or
    // externally written file could) had no test through the public API. Unlike the
    // `..._despite_earlier_duplicate_trusted_record` test above (one revoked, one not --
    // resolves to `Revoked`), both records here are `trusted`, so neither revocation nor a
    // single agreed-upon key can win: the store must fail closed instead of picking either
    // key arbitrarily.
    let identity = DesktopTlsIdentity::generate_ephemeral("Conflicting Duplicate Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let spki = identity.spki_der_p256().to_vec();
    let phone_id = phone_id_for_spki(&spki);
    let other_spki = DesktopTlsIdentity::generate_ephemeral("Other Duplicate Phone")
        .unwrap()
        .spki_der_p256()
        .to_vec();

    let path = unique_store_path("conflicting-duplicate");
    write_duplicate_non_revoked_phone_id_store(
        &path,
        &phone_id,
        &hex_lower(&spki),
        &hex_lower(&other_spki),
    );

    let store = FileTrustedPhoneStore::new(&path);
    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(store));
    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    match result {
        Err(RustlsError::General(message)) => {
            assert!(
                message.contains("CorruptStore"),
                "expected the CorruptStore error text in the General message, got {message:?}"
            );
        }
        other => panic!("expected Err(General(_)) containing CorruptStore, got {other:?}"),
    }
    cleanup(path);
}

#[test]
fn trusted_only_rejects_trusted_phone_with_different_public_key() {
    // Characterization test: `verify_client_cert` already rejects a `Trusted` status whose
    // stored public key does not match the presented certificate's SPKI (the `if public_key
    // == spki` guard). This passed before this commit with no direct test covering it
    // (native review finding R3-untested-key-mismatch); added here to lock in the behavior.
    let identity = DesktopTlsIdentity::generate_ephemeral("Mismatched Key Phone").unwrap();
    let other_identity = DesktopTlsIdentity::generate_ephemeral("Other Phone").unwrap();
    let cert_der = CertificateDer::from(identity.certificate_der().to_vec());
    let other_spki = other_identity.spki_der_p256().to_vec();
    let verifier = PhoneClientCertVerifier::trusted_only(Arc::new(
        MismatchedKeyTrustedPhoneLookup(other_spki),
    ));

    let result = verifier.verify_client_cert(&cert_der, &[], UnixTime::now());

    assert!(matches!(
        result,
        Err(RustlsError::InvalidCertificate(
            CertificateError::UnknownIssuer
        ))
    ));
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

#[derive(Debug)]
struct MismatchedKeyTrustedPhoneLookup(Vec<u8>);

impl TrustedPhoneLookup for MismatchedKeyTrustedPhoneLookup {
    fn phone_status(&self, _phone_id: &str) -> Result<TrustedPhoneStatus, TrustedPhoneLookupError> {
        Ok(TrustedPhoneStatus::Trusted {
            public_key: self.0.clone(),
        })
    }
}

/// Writes a trusted-phone store file directly in its real on-disk format (see
/// `trusted_phone_store.rs`: a `CHINCHILLACAM_TRUSTED_PHONES_V1` header line followed by
/// tab-separated `state\tphone_id\tlabel\thex(public_key)` records), bypassing the store's
/// own `trust`/`revoke` API so two records can share the same `phone_id` -- state that API
/// can never produce, used here to exercise the store's handling of an inconsistent file.
fn write_duplicate_phone_id_store(path: &std::path::Path, phone_id: &str, trusted_hex_key: &str) {
    let contents = format!(
        "CHINCHILLACAM_TRUSTED_PHONES_V1\ntrusted\t{phone_id}\tOriginal\t{trusted_hex_key}\nrevoked\t{phone_id}\tRotated\tdeadbeef\n"
    );
    std::fs::write(path, contents).unwrap();
}

/// Same on-disk format as `write_duplicate_phone_id_store`, but both records are
/// `trusted` (neither revoked) and disagree on public key -- the shape that must
/// trigger `TrustedPhoneStoreError::CorruptStore` instead of a `Revoked`/`Trusted` guess.
fn write_duplicate_non_revoked_phone_id_store(
    path: &std::path::Path,
    phone_id: &str,
    first_hex_key: &str,
    second_hex_key: &str,
) {
    let contents = format!(
        "CHINCHILLACAM_TRUSTED_PHONES_V1\ntrusted\t{phone_id}\tOriginal\t{first_hex_key}\ntrusted\t{phone_id}\tRotated\t{second_hex_key}\n"
    );
    std::fs::write(path, contents).unwrap();
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
