use std::{
    io::{self, Read, Write},
    process::ExitCode,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use usb_probe::{
    DesktopTlsIdentity, FrameTransferBudget, FramedUsbStream, PairingQrIssuer, UsbBulkIo,
    UsbProbeError, UsbTlsCiphertextStream, UsbTlsPairingProofServer,
    USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES,
};

const DESKTOP_ID: &str = "desktop-interop";
const DESKTOP_NAME: &str = "Studio Desktop";
const QR_TTL_SECONDS: u64 = 60;
const PROOF_TIMEOUT: Duration = Duration::from_millis(5000);
const BULK_IO_ATTEMPTS: usize = 8192;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("USB pairing proof stdio helper failed: {error}");
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

    {
        let mut stdout = io::stdout().lock();
        writeln!(stdout, "CHINCHILLACAM-USB-INTEROP:v1").map_err(io_error)?;
        writeln!(
            stdout,
            "qr={}",
            std::str::from_utf8(issued.qr_wire()).map_err(debug_error)?
        )
        .map_err(io_error)?;
        stdout.flush().map_err(io_error)?;
    }

    let bulk_io = StdioUsbBulkIo::new();
    let budget = FrameTransferBudget::new(
        PROOF_TIMEOUT,
        USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES,
        BULK_IO_ATTEMPTS,
    )
    .map_err(debug_error)?;
    let framed = FramedUsbStream::new(bulk_io, budget);
    let stream = UsbTlsCiphertextStream::new(framed);
    let server = UsbTlsPairingProofServer::new(&identity).map_err(debug_error)?;
    let outcome = server
        .complete_handshake_and_pairing_proof(stream, &mut issuer, PROOF_TIMEOUT)
        .map_err(debug_error)?;
    let _live_tls_stream = outcome.tls;

    {
        let mut stderr = io::stderr().lock();
        writeln!(stderr, "phone_id={}", outcome.candidate.phone_id).map_err(io_error)?;
        stderr.flush().map_err(io_error)?;
    }
    Ok(())
}

#[derive(Debug)]
struct StdioUsbBulkIo {
    stdin: io::Stdin,
    stdout: io::Stdout,
}

impl StdioUsbBulkIo {
    fn new() -> Self {
        Self {
            stdin: io::stdin(),
            stdout: io::stdout(),
        }
    }
}

impl UsbBulkIo for StdioUsbBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.stdin
            .read(buffer)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        let written = self
            .stdout
            .write(bytes)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))?;
        self.stdout
            .flush()
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))?;
        Ok(written)
    }
}

fn now_epoch_seconds() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(debug_error)
}

fn debug_error(error: impl std::fmt::Debug) -> String {
    format!("{error:?}")
}

fn io_error(error: io::Error) -> String {
    error.to_string()
}
