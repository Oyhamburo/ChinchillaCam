use std::{
    io::{BufRead, BufReader},
    process::{Command, Stdio},
};

use usb_probe::PairingProofEndpoint;

#[test]
fn interop_helper_prints_frozen_three_line_contract_before_accept() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_pairing_proof_interop_helper"))
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let stdout = child.stdout.take().unwrap();
    let mut lines = BufReader::new(stdout).lines();

    assert_eq!(lines.next().unwrap().unwrap(), "CHINCHILLACAM-INTEROP:v1");
    let qr = lines.next().unwrap().unwrap();
    assert!(qr.starts_with("qr=CHINCHILLACAM-PAIR:v1:desktopId=desktop-interop&desktopName=Studio%20Desktop&expiresAt="));
    assert!(qr.contains("&nonce="));
    assert!(qr.contains("&trustMaterial="));
    assert!(qr.contains("&checksum="));

    let proof = lines.next().unwrap().unwrap();
    assert!(proof.starts_with("proof="));
    let endpoint = PairingProofEndpoint::decode(&hex_decode(&proof[6..])).unwrap();
    assert_eq!(endpoint.host(), "127.0.0.1");
    assert!(endpoint.port() > 0);
    assert_eq!(endpoint.timeout_ms(), 5000);

    child.kill().unwrap();
    let _ = child.wait().unwrap();
}

fn hex_decode(hex: &str) -> Vec<u8> {
    assert_eq!(hex.len() % 2, 0);
    hex.as_bytes()
        .chunks_exact(2)
        .map(|chunk| {
            let text = std::str::from_utf8(chunk).unwrap();
            u8::from_str_radix(text, 16).unwrap()
        })
        .collect()
}
