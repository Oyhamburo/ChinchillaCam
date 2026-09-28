use std::{
    io::{self, Write},
    process::ExitCode,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use usb_probe::{DesktopTlsIdentity, LoopbackPairingProofServer, PairingQrIssuer};

const DESKTOP_ID: &str = "desktop-interop";
const DESKTOP_NAME: &str = "Studio Desktop";
const QR_TTL_SECONDS: u64 = 60;
const PROOF_TIMEOUT: Duration = Duration::from_millis(5000);

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("pairing proof interop helper failed: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let identity = DesktopTlsIdentity::generate_ephemeral(DESKTOP_NAME).map_err(debug_error)?;
    let mut issuer =
        PairingQrIssuer::new(DESKTOP_ID, DESKTOP_NAME, identity.clone(), QR_TTL_SECONDS)
            .map_err(debug_error)?;
    let issued = issuer.issue_at(now_epoch_seconds()?).map_err(debug_error)?;
    let server = LoopbackPairingProofServer::bind_pairing_proof(identity, PROOF_TIMEOUT, issuer)
        .map_err(debug_error)?;
    let endpoint = server.endpoint().encode().map_err(debug_error)?;

    let mut stdout = io::stdout().lock();
    writeln!(stdout, "CHINCHILLACAM-INTEROP:v1").map_err(io_error)?;
    writeln!(
        stdout,
        "qr={}",
        std::str::from_utf8(issued.qr_wire()).map_err(debug_error)?
    )
    .map_err(io_error)?;
    writeln!(stdout, "proof={}", hex_lower(&endpoint)).map_err(io_error)?;
    stdout.flush().map_err(io_error)?;

    server.accept_one().map_err(debug_error)
}

fn now_epoch_seconds() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(debug_error)
}

fn hex_lower(bytes: &[u8]) -> String {
    let mut hex = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        hex.push_str(&format!("{byte:02x}"));
    }
    hex
}

fn debug_error(error: impl std::fmt::Debug) -> String {
    format!("{error:?}")
}

fn io_error(error: io::Error) -> String {
    error.to_string()
}
