use std::{
    fs,
    path::{Path, PathBuf},
};

const REVIEW_NOTE: &str = "If a new dependency is intentional, review its privacy/network impact and deliberately update the denied-crate list in privacy_contract_test.rs";

#[derive(Debug)]
struct Package {
    name: String,
    dependencies: Vec<String>,
}

// Cargo.lock v4 lists one package at a time; dependency entries may include a version.
fn packages(lock: &str) -> Vec<Package> {
    lock.split("[[package]]")
        .skip(1)
        .map(|block| {
            let mut name = None;
            let mut dependencies = Vec::new();
            let mut in_dependencies = false;
            for line in block.lines().map(str::trim) {
                if let Some(value) = line.strip_prefix("name = \"") {
                    name = Some(value.trim_end_matches('"').to_string());
                } else if line == "dependencies = [" {
                    in_dependencies = true;
                } else if in_dependencies && line == "]" {
                    in_dependencies = false;
                } else if in_dependencies {
                    let entry = line.trim_end_matches(',').trim_matches('"');
                    dependencies.push(entry.split_whitespace().next().unwrap().to_string());
                }
            }
            Package {
                name: name.expect("Cargo.lock package without name"),
                dependencies,
            }
        })
        .collect()
}

fn denied_crate(name: &str) -> bool {
    matches!(
        name,
        "reqwest"
            | "hyper"
            | "ureq"
            | "isahc"
            | "curl"
            | "curl-sys"
            | "openssl"
            | "openssl-sys"
            | "native-tls"
            | "self_update"
    ) || ["sentry", "opentelemetry", "posthog", "segment", "mixpanel"]
        .iter()
        .any(|prefix| name.starts_with(prefix))
}

fn check_packages(lock: &str) -> Vec<String> {
    let parsed = packages(lock);
    let mut violations = Vec::new();
    for package in &parsed {
        if denied_crate(&package.name) {
            violations.push(format!("denied crate {}", package.name));
        }
    }
    if parsed.iter().any(|package| package.name == "tokio") {
        let users: Vec<_> = parsed
            .iter()
            .filter(|package| package.dependencies.iter().any(|dep| dep == "tokio"))
            .map(|package| package.name.as_str())
            .collect();
        if users.is_empty() || users.iter().any(|user| *user != "wasm-bindgen-futures") {
            violations.push(format!(
                "tokio must only be used by wasm-bindgen-futures; dependents: {users:?}"
            ));
        }
    }
    violations
}

#[test]
fn lockfiles_contain_no_remote_or_telemetry_dependencies() {
    let app = Path::new(env!("CARGO_MANIFEST_DIR"));
    for lock in [app.join("Cargo.lock"), app.join("../usb-probe/Cargo.lock")] {
        let content = fs::read_to_string(&lock).unwrap();
        assert!(
            !packages(&content).is_empty(),
            "empty lockfile: {}",
            lock.display()
        );
        let violations = check_packages(&content);
        assert!(
            violations.is_empty(),
            "{}: {violations:?}. {REVIEW_NOTE}",
            lock.display()
        );
    }
}

#[test]
fn eframe_defaults_are_disabled() {
    let manifest =
        fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml")).unwrap();
    let eframe = manifest
        .lines()
        .find(|line| line.trim_start().starts_with("eframe ="));
    assert!(
        eframe.is_some_and(|line| line.contains("default-features = false")),
        "eframe must disable default features to avoid adding networking; {REVIEW_NOTE}"
    );
}

fn rust_files(dir: &Path) -> Vec<PathBuf> {
    let mut files = Vec::new();
    for entry in fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            files.extend(rust_files(&path));
        } else if path.extension().is_some_and(|ext| ext == "rs") {
            files.push(path);
        }
    }
    files.sort();
    files
}

fn network_violations(path: &Path, source: &str) -> Vec<String> {
    let allowed = path.starts_with("desktop/usb-probe/src/bin/")
        || (path.starts_with("desktop/usb-probe/src/")
            && path
                .file_name()
                .is_some_and(|name| name.to_string_lossy().starts_with("loopback_"))
            && path != Path::new("desktop/usb-probe/src/main.rs"));
    if allowed {
        return Vec::new();
    }
    source
        .lines()
        .enumerate()
        .filter(|(_, line)| {
            ["TcpListener", "TcpStream", "UdpSocket", "std::net::"]
                .iter()
                .any(|api| line.contains(api))
        })
        .map(|(line, _)| format!("{}:{}", path.display(), line + 1))
        .collect()
}

#[test]
fn socket_apis_stay_in_loopback_or_helper_modules() {
    let app = Path::new(env!("CARGO_MANIFEST_DIR"));
    let repo = app.join("../..");
    let mut violations = Vec::new();
    for root in ["desktop/app/src", "desktop/usb-probe/src"] {
        for file in rust_files(&repo.join(root)) {
            let relative = file.strip_prefix(&repo).unwrap();
            violations.extend(network_violations(
                relative,
                &fs::read_to_string(&file).unwrap(),
            ));
        }
    }
    assert!(
        violations.is_empty(),
        "socket APIs outside loopback/helper modules: {violations:?}"
    );
}

fn secret_print_violations(path: &Path, source: &str) -> Vec<String> {
    let mut violations = Vec::new();
    for macro_name in ["println!", "eprintln!", "dbg!"] {
        let mut rest = source;
        let mut offset = 0;
        while let Some(found) = rest.find(macro_name) {
            let start = offset + found;
            let after = start + macro_name.len();
            let tail = source[after..].trim_start();
            if tail.starts_with('(') {
                // Bound the scan to the matching closing parenthesis (also covers multiline calls).
                let args_start = source.len() - tail.len();
                let mut depth = 0;
                let mut end = source.len();
                for (index, ch) in source[args_start..].char_indices() {
                    match ch {
                        '(' => depth += 1,
                        ')' => {
                            depth -= 1;
                            if depth == 0 {
                                end = args_start + index;
                                break;
                            }
                        }
                        _ => {}
                    }
                }
                let args = source[args_start..end].to_ascii_lowercase();
                if ["qr", "nonce", "short_code", "spki", "key", "secret"]
                    .iter()
                    .any(|word| args.contains(word))
                {
                    let line = source[..start]
                        .bytes()
                        .filter(|byte| *byte == b'\n')
                        .count()
                        + 1;
                    violations.push(format!("{}:{line} ({macro_name})", path.display()));
                }
            }
            offset = after;
            rest = &source[offset..];
        }
    }
    violations
}

#[test]
fn production_entrypoints_do_not_print_secrets() {
    let app = Path::new(env!("CARGO_MANIFEST_DIR"));
    let repo = app.join("../..");
    let mut files = rust_files(&repo.join("desktop/app/src"));
    files.push(repo.join("desktop/usb-probe/src/main.rs"));
    let mut violations = Vec::new();
    for file in files {
        let relative = file.strip_prefix(&repo).unwrap();
        violations.extend(secret_print_violations(
            relative,
            &fs::read_to_string(&file).unwrap(),
        ));
    }
    assert!(
        violations.is_empty(),
        "prints with secret-related arguments: {violations:?}"
    );
}

#[test]
fn scanner_rejects_simulated_regressions() {
    let synthetic = "[[package]]\nname = \"sentry-core\"\n[[package]]\nname = \"tokio\"\n[[package]]\nname = \"native-client\"\ndependencies = [\n \"tokio 1.0.0\",\n]\n";
    let issues = check_packages(synthetic);
    assert!(issues.iter().any(|issue| issue.contains("sentry-core")));
    assert!(issues.iter().any(|issue| issue.contains("native-client")));
    assert_eq!(
        network_violations(
            Path::new("desktop/app/src/main.rs"),
            "std::net::TcpListener;\n"
        ),
        vec!["desktop/app/src/main.rs:1"]
    );
    assert!(network_violations(
        Path::new("desktop/usb-probe/src/loopback_demo.rs"),
        "TcpStream"
    )
    .is_empty());
    assert_eq!(
        secret_print_violations(
            Path::new("desktop/app/src/main.rs"),
            "println!(\n  \"{}\",\n  nonce\n);"
        ),
        vec!["desktop/app/src/main.rs:1 (println!)"]
    );
}
