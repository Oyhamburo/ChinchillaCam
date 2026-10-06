use chinchillacam_app::paths::{desktop_id_for, sanitize_desktop_name, AppPaths};
use std::{
    fs,
    path::PathBuf,
    process,
    time::{SystemTime, UNIX_EPOCH},
};
use usb_probe::{phone_id_for_spki, DesktopTlsIdentity};

fn unique_dir() -> PathBuf {
    std::env::temp_dir().join(format!(
        "chinchillacam-app-test-{}-{}",
        process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ))
}

#[test]
fn prepare_creates_private_identity_dir() {
    let root = unique_dir();
    let paths = AppPaths::under(root.clone());
    assert_eq!(paths.root, root);
    assert_eq!(paths.identity_dir, root.join("identity"));
    assert_eq!(paths.trusted_phones, root.join("trusted-phones.txt"));
    paths.prepare().unwrap();
    assert!(paths.identity_dir.is_dir());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            fs::metadata(&root).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(&paths.identity_dir)
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
    }
    let identity = DesktopTlsIdentity::generate_ephemeral("Desktop").unwrap();
    assert_eq!(
        desktop_id_for(&identity),
        phone_id_for_spki(identity.spki_der_p256())
    );
    assert_eq!(desktop_id_for(&identity).len(), 64);
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn sanitize_desktop_name_bounds_and_cleans() {
    assert_eq!(sanitize_desktop_name("\n \t"), "Mi computadora");
    assert_eq!(sanitize_desktop_name("  Mi\n Mac\t "), "Mi Mac");
    let name = sanitize_desktop_name(&"é".repeat(40));
    assert_eq!(name.len(), 64);
    assert_eq!(name, "é".repeat(32));
    assert_eq!(sanitize_desktop_name("abc"), "abc");
}
