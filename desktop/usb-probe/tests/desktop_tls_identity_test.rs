use std::{fs, path::PathBuf};

use usb_probe::{
    DesktopTlsIdentity, DesktopTlsIdentityError, DesktopTlsIdentityStore, PairingQrPayload,
    PairingQrProducer,
};

#[test]
fn generates_p256_tls_identity_with_spki_embedded_in_certificate() {
    let identity = DesktopTlsIdentity::generate_ephemeral("Chinchilla Desktop").unwrap();

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
    let identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let payload = PairingQrPayload::new(
        "desktop-01",
        "Studio Desktop",
        1_700_000_600,
        vec![0x10, 0x20, 0x30, 0x40],
        identity.qr_trust_material().to_vec(),
    )
    .unwrap();

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

#[cfg(unix)]
#[test]
fn load_or_create_persists_same_private_key_and_spki_with_restrictive_modes() {
    use std::os::unix::fs::PermissionsExt;

    let dir = test_dir("persist");
    fs::remove_dir_all(&dir).unwrap();
    let store = DesktopTlsIdentityStore::new(dir.clone());
    let first = store.load_or_create("Studio Desktop").unwrap();
    let second = DesktopTlsIdentityStore::new(dir.clone())
        .load_or_create("Studio Desktop")
        .unwrap();

    assert_eq!(
        first.private_key_pkcs8_der(),
        second.private_key_pkcs8_der()
    );
    assert_eq!(first.spki_der_p256(), second.spki_der_p256());
    assert!(second
        .certificate_der()
        .windows(second.spki_der_p256().len())
        .any(|window| window == second.spki_der_p256()));
    assert_eq!(
        fs::metadata(&dir).unwrap().permissions().mode() & 0o777,
        0o700
    );
    assert_eq!(
        fs::metadata(dir.join("desktop-p256.pkcs8.der"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
}

#[cfg(unix)]
#[test]
fn load_or_create_rejects_symlink_relaxed_perms_corrupt_oversize_and_partial_state() {
    use std::os::unix::{fs as unix_fs, fs::PermissionsExt};

    let relaxed = test_dir("relaxed");
    fs::set_permissions(&relaxed, fs::Permissions::from_mode(0o755)).unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(relaxed).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::InsecureStorage)
    ));

    let symlink_target = test_dir("symlink-target");
    let symlink = std::env::temp_dir().join(format!("chinchilla-symlink-{}", std::process::id()));
    let _ = fs::remove_file(&symlink);
    unix_fs::symlink(&symlink_target, &symlink).unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(symlink).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::InsecureStorage)
    ));

    let dangling = test_dir("dangling");
    fs::set_permissions(&dangling, fs::Permissions::from_mode(0o700)).unwrap();
    unix_fs::symlink(
        dangling.join("missing"),
        dangling.join("desktop-p256.pkcs8.der"),
    )
    .unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(dangling).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::InsecureStorage)
    ));

    let corrupt = test_dir("corrupt");
    fs::set_permissions(&corrupt, fs::Permissions::from_mode(0o700)).unwrap();
    fs::write(corrupt.join("desktop-p256.pkcs8.der"), b"truncated").unwrap();
    fs::set_permissions(
        corrupt.join("desktop-p256.pkcs8.der"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(corrupt).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::CorruptKeyFile)
    ));

    let oversize = test_dir("oversize");
    fs::set_permissions(&oversize, fs::Permissions::from_mode(0o700)).unwrap();
    fs::write(
        oversize.join("desktop-p256.pkcs8.der"),
        vec![0u8; 8 * 1024 + 1],
    )
    .unwrap();
    fs::set_permissions(
        oversize.join("desktop-p256.pkcs8.der"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(oversize).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::KeyFileTooLarge)
    ));

    let partial = test_dir("partial");
    fs::set_permissions(&partial, fs::Permissions::from_mode(0o700)).unwrap();
    fs::write(partial.join("desktop-p256.pkcs8.der.tmp"), b"partial").unwrap();
    assert!(matches!(
        DesktopTlsIdentityStore::new(partial).load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::PartialState)
    ));
}

#[cfg(windows)]
#[test]
fn load_or_create_fails_closed_on_windows_until_secure_storage_exists() {
    assert!(matches!(
        DesktopTlsIdentityStore::new(PathBuf::from("C:/tmp/chinchilla"))
            .load_or_create("Studio Desktop"),
        Err(DesktopTlsIdentityError::UnsupportedPlatform)
    ));
}

fn test_dir(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "chinchilla-desktop-tls-{}-{}",
        name,
        std::process::id()
    ));
    let _ = fs::remove_dir_all(&path);
    fs::create_dir_all(&path).unwrap();
    path
}
