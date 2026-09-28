use std::{
    io::{BufRead, BufReader},
    process::{Command, Stdio},
    sync::mpsc,
    time::Duration,
};

#[test]
fn stdio_helper_flushes_prelude_and_qr_before_usb_tls_binary_mode() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_usb_pairing_proof_stdio_helper"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let stdout = child.stdout.take().unwrap();
    let (tx, rx) = mpsc::channel();

    std::thread::spawn(move || {
        let mut lines = BufReader::new(stdout).lines();
        let result = (|| -> Result<Vec<String>, String> {
            let first = lines
                .next()
                .ok_or_else(|| "missing prelude line".to_string())?
                .map_err(|error| error.to_string())?;
            let second = lines
                .next()
                .ok_or_else(|| "missing QR line".to_string())?
                .map_err(|error| error.to_string())?;
            Ok(vec![first, second])
        })();
        let _ = tx.send(result);
    });

    let lines = rx.recv_timeout(Duration::from_secs(3));
    let _ = child.kill();
    let _ = child.wait();
    drop(child.stdin.take());

    let lines = lines.expect("helper did not flush prelude lines before entering binary mode");
    let lines = lines.unwrap();
    assert_eq!(lines[0], "CHINCHILLACAM-USB-INTEROP:v1");
    assert!(lines[1].starts_with(
        "qr=CHINCHILLACAM-PAIR:v1:desktopId=desktop-interop&desktopName=Studio%20Desktop&expiresAt="
    ));
    assert!(lines[1].contains("&nonce="));
    assert!(lines[1].contains("&trustMaterial="));
    assert!(lines[1].contains("&checksum="));
}
