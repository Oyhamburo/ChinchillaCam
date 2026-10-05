use std::{
    fs,
    path::{Path, PathBuf},
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, SystemTime},
};

use usb_probe::{
    FileTrustedPhoneStore, TrustUnlessRevoked, TrustedPhoneIdentity, TrustedPhoneStoreError,
    TrustedPhoneStoreWriteCoordinator, TrustedPhoneSummary,
};

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
fn trusted_phone_store_initializes_missing_parent_directory() {
    let path = unique_store_path("fresh-install")
        .join("new")
        .join("subdir")
        .join("store.txt");
    let store = FileTrustedPhoneStore::new(&path);
    let identity = TrustedPhoneIdentity::new("phone-fresh", "Fresh Phone", vec![7, 8, 9]).unwrap();

    store.trust(identity.clone()).unwrap();

    assert_eq!(
        FileTrustedPhoneStore::new(&path)
            .trusted_identity("phone-fresh")
            .unwrap(),
        Some(identity)
    );
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
fn trusted_phone_store_prevents_stale_trust_from_overwriting_concurrent_revoke() {
    let path = unique_store_path("race");
    FileTrustedPhoneStore::new(&path)
        .trust(TrustedPhoneIdentity::new("phone-race", "Original", vec![9]).unwrap())
        .unwrap();

    let (loaded_tx, loaded_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let coordinator = Arc::new(PauseOnceAfterLoad::new(loaded_tx, release_rx));
    let stale_store = FileTrustedPhoneStore::with_write_coordinator(&path, coordinator);
    let revoke_store = FileTrustedPhoneStore::new(&path);

    let stale_trust = thread::spawn(move || {
        stale_store
            .trust(TrustedPhoneIdentity::new("phone-race", "Stale", vec![1, 2, 3]).unwrap())
            .unwrap();
    });
    loaded_rx.recv_timeout(Duration::from_secs(1)).unwrap();

    let revoke = thread::spawn(move || revoke_store.revoke("phone-race").unwrap());
    thread::sleep(Duration::from_millis(25));
    release_tx.send(()).unwrap();

    stale_trust.join().unwrap();
    assert!(revoke.join().unwrap());
    assert_eq!(
        FileTrustedPhoneStore::new(&path)
            .trusted_identity("phone-race")
            .unwrap(),
        None
    );
    assert!(FileTrustedPhoneStore::new(&path)
        .is_revoked("phone-race")
        .unwrap());

    cleanup(path);
}

#[test]
fn trust_unless_revoked_refuses_and_writes_nothing_when_already_revoked() {
    let path = unique_store_path("trust-unless-revoked-refuses");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new("phone-refuse", "Original", vec![9]).unwrap())
        .unwrap();
    store.revoke("phone-refuse").unwrap();
    let before = fs::read_to_string(&path).unwrap();

    let outcome = store
        .trust_unless_revoked(
            TrustedPhoneIdentity::new("phone-refuse", "New Label", vec![1, 2, 3]).unwrap(),
        )
        .unwrap();

    assert_eq!(outcome, TrustUnlessRevoked::Refused);
    assert!(store.is_revoked("phone-refuse").unwrap());
    assert_eq!(store.trusted_identity("phone-refuse").unwrap(), None);
    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        before,
        "a refused trust_unless_revoked must not write to the store file at all"
    );
    cleanup(path);
}

#[test]
fn trust_unless_revoked_trusts_when_not_currently_revoked() {
    let path = unique_store_path("trust-unless-revoked-trusts");
    let store = FileTrustedPhoneStore::new(&path);
    let identity = TrustedPhoneIdentity::new("phone-trust", "New Phone", vec![4, 5, 6]).unwrap();

    let outcome = store.trust_unless_revoked(identity.clone()).unwrap();

    assert_eq!(outcome, TrustUnlessRevoked::Trusted);
    assert_eq!(
        store.trusted_identity("phone-trust").unwrap(),
        Some(identity)
    );
    assert!(!store.is_revoked("phone-trust").unwrap());
    cleanup(path);
}

#[test]
fn trust_unless_revoked_refuses_when_revocation_completes_while_call_is_pending() {
    let path = unique_store_path("trust-unless-revoked-race");
    FileTrustedPhoneStore::new(&path)
        .trust(TrustedPhoneIdentity::new("phone-race2", "Original", vec![9]).unwrap())
        .unwrap();

    let (loaded_tx, loaded_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let coordinator = Arc::new(PauseOnceAfterLoad::new(loaded_tx, release_rx));
    let revoking_store = FileTrustedPhoneStore::with_write_coordinator(&path, coordinator);
    let confirming_store = FileTrustedPhoneStore::new(&path);

    let revoke = thread::spawn(move || revoking_store.revoke("phone-race2").unwrap());
    loaded_rx.recv_timeout(Duration::from_secs(1)).unwrap();

    let confirm = thread::spawn(move || {
        confirming_store.trust_unless_revoked(
            TrustedPhoneIdentity::new("phone-race2", "Stale Confirm", vec![1, 2, 3]).unwrap(),
        )
    });
    thread::sleep(Duration::from_millis(25));
    release_tx.send(()).unwrap();

    assert!(revoke.join().unwrap());
    let outcome = confirm.join().unwrap().unwrap();

    assert_eq!(
        outcome,
        TrustUnlessRevoked::Refused,
        "a revoke that completes while trust_unless_revoked is waiting on the lock must \
         still be visible to its single load, so it must refuse instead of overwriting it"
    );
    assert!(FileTrustedPhoneStore::new(&path)
        .is_revoked("phone-race2")
        .unwrap());
    assert_eq!(
        FileTrustedPhoneStore::new(&path)
            .trusted_identity("phone-race2")
            .unwrap(),
        None
    );

    cleanup(path);
}

#[test]
fn forgotten_phone_is_not_listed_or_trusted() {
    let path = unique_store_path("forget");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new("phone-a", "Phone A", vec![1, 2]).unwrap())
        .unwrap();
    store
        .trust(TrustedPhoneIdentity::new("phone-b", "Phone B", vec![3, 4]).unwrap())
        .unwrap();

    assert!(store.forget("phone-a").unwrap());

    let reopened = FileTrustedPhoneStore::new(&path);
    assert_eq!(reopened.trusted_identity("phone-a").unwrap(), None);
    assert!(!reopened.is_revoked("phone-a").unwrap());
    assert_eq!(
        reopened.list().unwrap(),
        vec![summary("phone-b", "Phone B", false)]
    );
    cleanup(path);
}

#[test]
fn list_reports_labels_and_revocation_sorted() {
    let path = unique_store_path("list");
    let store = FileTrustedPhoneStore::new(&path);
    assert_eq!(store.list().unwrap(), Vec::new());
    store
        .trust(TrustedPhoneIdentity::new("phone-z", "Zeta", vec![1]).unwrap())
        .unwrap();
    store
        .trust(TrustedPhoneIdentity::new("phone-2", "Alpha", vec![2]).unwrap())
        .unwrap();
    store
        .trust(TrustedPhoneIdentity::new("phone-1", "Alpha", vec![3]).unwrap())
        .unwrap();
    store.revoke("phone-z").unwrap();

    assert_eq!(
        store.list().unwrap(),
        vec![
            summary("phone-1", "Alpha", false),
            summary("phone-2", "Alpha", false),
            summary("phone-z", "Zeta", true),
        ]
    );
    cleanup(path);
}

#[test]
fn forget_missing_phone_returns_false() {
    let path = unique_store_path("forget-missing");
    let store = FileTrustedPhoneStore::new(&path);
    assert!(!store.forget("phone-missing").unwrap());
    store
        .trust(TrustedPhoneIdentity::new("phone-kept", "Kept", vec![5]).unwrap())
        .unwrap();
    let before = fs::read_to_string(&path).unwrap();

    assert!(!store.forget("phone-missing").unwrap());

    assert_eq!(fs::read_to_string(&path).unwrap(), before);
    assert!(matches!(
        store.forget("bad\tid"),
        Err(TrustedPhoneStoreError::InvalidIdentity(_))
    ));
    cleanup(path);
}

#[test]
fn forgotten_revoked_phone_can_be_trusted_again() {
    let path = unique_store_path("forget-revoked");
    let store = FileTrustedPhoneStore::new(&path);
    store
        .trust(TrustedPhoneIdentity::new("phone-r", "Old", vec![7]).unwrap())
        .unwrap();
    store.revoke("phone-r").unwrap();

    assert!(store.forget("phone-r").unwrap());
    assert!(!store.is_revoked("phone-r").unwrap());
    assert_eq!(store.list().unwrap(), Vec::new());

    let identity = TrustedPhoneIdentity::new("phone-r", "New", vec![8]).unwrap();
    assert_eq!(
        store.trust_unless_revoked(identity.clone()).unwrap(),
        TrustUnlessRevoked::Trusted
    );
    assert_eq!(store.trusted_identity("phone-r").unwrap(), Some(identity));
    cleanup(path);
}

#[test]
fn trusted_phone_store_rejects_oversized_persistent_file_before_parse() {
    let path = unique_store_path("too-large");
    let file = fs::File::create(&path).unwrap();
    file.set_len(65_537).unwrap();

    let err = FileTrustedPhoneStore::new(&path)
        .trusted_identity("phone")
        .unwrap_err();
    assert!(matches!(
        err,
        TrustedPhoneStoreError::StoreTooLarge {
            length: 65_537,
            max: 65_536
        }
    ));
    cleanup(path);
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

fn summary(phone_id: &str, label: &str, revoked: bool) -> TrustedPhoneSummary {
    TrustedPhoneSummary {
        phone_id: phone_id.to_string(),
        label: label.to_string(),
        revoked,
    }
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

struct PauseOnceAfterLoad {
    loaded_tx: Mutex<Option<mpsc::Sender<()>>>,
    release_rx: Mutex<mpsc::Receiver<()>>,
}

impl PauseOnceAfterLoad {
    fn new(loaded_tx: mpsc::Sender<()>, release_rx: mpsc::Receiver<()>) -> Self {
        Self {
            loaded_tx: Mutex::new(Some(loaded_tx)),
            release_rx: Mutex::new(release_rx),
        }
    }
}

impl TrustedPhoneStoreWriteCoordinator for PauseOnceAfterLoad {
    fn after_records_loaded(&self, _path: &Path) -> Result<(), TrustedPhoneStoreError> {
        if let Some(sender) = self.loaded_tx.lock().unwrap().take() {
            sender.send(()).unwrap();
            self.release_rx
                .lock()
                .unwrap()
                .recv_timeout(Duration::from_secs(1))
                .unwrap();
        }
        Ok(())
    }
}
