use std::{fs, path::Path};

fn plist_string<'a>(plist: &'a str, key: &str) -> &'a str {
    let key = format!("<key>{key}</key>");
    let (_, tail) = plist.split_once(&key).expect("missing plist key");
    let tail = tail.trim_start();
    let value = tail
        .strip_prefix("<string>")
        .expect("expected string value");
    value
        .split_once("</string>")
        .expect("unterminated string")
        .0
}

#[test]
fn info_plist_matches_cargo_version_and_bundle_id() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packaging/macos/Info.plist");
    let plist = fs::read_to_string(path).expect("macOS Info.plist must exist");
    assert_eq!(
        plist_string(&plist, "CFBundleIdentifier"),
        "io.github.oyhamburo.chinchillacam.desktop"
    );
    assert_eq!(
        plist_string(&plist, "CFBundleShortVersionString"),
        env!("CARGO_PKG_VERSION")
    );
    assert_eq!(plist_string(&plist, "CFBundleExecutable"), "chinchillacam");
    assert_eq!(plist_string(&plist, "LSMinimumSystemVersion"), "13.0");
    assert_eq!(plist_string(&plist, "CFBundlePackageType"), "APPL");
    assert_eq!(plist_string(&plist, "CFBundleVersion"), "1");
    for key in ["CFBundleName", "CFBundleDisplayName"] {
        assert_eq!(plist_string(&plist, key), "ChinchillaCam");
    }
    assert!(plist.contains("<key>NSHighResolutionCapable</key>\n\t<true/>"));
    for key in [
        "NSCameraUsageDescription",
        "NSMicrophoneUsageDescription",
        "CFBundleIconFile",
    ] {
        assert!(!plist.contains(&format!("<key>{key}</key>")));
    }
}
