use std::{fs, path::PathBuf, time::SystemTime};

use usb_probe::{FileTrustedPhoneStore, TrustedPhoneIdentity, TrustedPhoneStoreError};

#[test]
fn trusted_phone_store_persists_identity_without_private_keys() {
    let path = unique_store_path("persist");
    let store = FileTrustedPhoneStore::new(&path);
    let identity = TrustedPhoneIdentity::new(
        "phone-note10",
        "Samsung Note10",
        vec![0x10, 0x20, 0x30, 0x40],
    )
    .unwrap();

    store.trust(identity.clone()).unwrap();

    assert_eq!(
        FileTrustedPhoneStore::new(&path)
            .trusted_identity("phone-note10")
            .unwrap(),
        Some(identity)
    );
    let persisted = fs::read_to_string(&path).unwrap();
    assert!(persisted.contains("phone-note10"));
    assert!(persisted.contains("Samsung Note10"));
    assert!(!persisted.to_lowercase().contains("private"));
    cleanup(path);
}

#[test]
fn trusted_phone_store_revokes_identity_for_normal_lookup() {
    let path = unique_store_path("revoke");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new("phone-s24", "S24+", vec![1, 2, 3, 4]).unwrap())
        .unwrap();

    assert!(store.revoke("phone-s24").unwrap());
    assert_eq!(store.trusted_identity("phone-s24").unwrap(), None);
    assert!(store.is_revoked("phone-s24").unwrap());
    assert!(!store.revoke("missing-phone").unwrap());
    assert_eq!(
        FileTrustedPhoneStore::new(&path)
            .trusted_identity("phone-s24")
            .unwrap(),
        None
    );
    assert!(FileTrustedPhoneStore::new(&path)
        .is_revoked("phone-s24")
        .unwrap());
    cleanup(path);
}

#[test]
fn trusted_phone_identity_rejects_invalid_or_oversized_fields() {
    assert_invalid("", "Samsung", vec![1, 2, 3]);
    assert_invalid("phone", " ", vec![1, 2, 3]);
    assert_invalid("phone", "Samsung", Vec::new());
    assert_invalid(&"p".repeat(129), "Samsung", vec![1, 2, 3]);
    assert_invalid("phone", &"S".repeat(257), vec![1, 2, 3]);
    assert_invalid("phone", "Samsung", vec![0; 4097]);
}

#[test]
fn trusted_phone_store_rejects_corrupt_persistent_file() {
    let path = unique_store_path("corrupt");
    fs::write(&path, "not-chinchillacam-trust\n").unwrap();

    let err = FileTrustedPhoneStore::new(&path)
        .trusted_identity("phone")
        .unwrap_err();
    assert!(matches!(err, TrustedPhoneStoreError::CorruptStore(_)));
    cleanup(path);
}

fn assert_invalid(phone_id: &str, label: &str, public_key: Vec<u8>) {
    assert!(matches!(
        TrustedPhoneIdentity::new(phone_id, label, public_key),
        Err(TrustedPhoneStoreError::InvalidIdentity(_))
    ));
}

fn unique_store_path(name: &str) -> PathBuf {
    let mut path = std::env::temp_dir();
    let nanos = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    path.push(format!(
        "chinchillacam-trusted-phone-{name}-{}-{nanos}.txt",
        std::process::id()
    ));
    path
}

fn cleanup(path: PathBuf) {
    let _ = fs::remove_file(&path);
    let _ = fs::remove_file(path.with_extension("tmp"));
}
