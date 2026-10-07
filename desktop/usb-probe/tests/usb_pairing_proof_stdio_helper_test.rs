use std::{
    io::{BufRead, BufReader, Read, Write},
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
    sync::{mpsc, Arc},
    thread,
    time::{Duration, Instant},
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use rustls::{
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::WebPkiSupportedAlgorithms,
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
    CertificateError, ClientConfig, ClientConnection, DigitallySignedStruct, Error as RustlsError,
    SignatureScheme, StreamOwned,
};
use usb_probe::{
    phone_id_for_spki, DesktopTlsIdentity, FrameTransferBudget, FramedUsbStream, PairingProofFrame,
    PairingProofRequest, PairingProofResponse, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
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

/// Native review finding R3-stdio-phone-id-report-untested: the prelude-only test above
/// never completes a handshake, so the helper's stderr `phone_id=<hex>` contract (added in
/// task m2) had no test. This drives the helper end to end: read the two prelude lines
/// without over-buffering (byte-by-byte, so no bytes belonging to the binary AOA stream
/// that follows are lost into a `BufReader`'s internal buffer), decode the QR, act as the
/// phone over AOA frames on the child's own stdin/stdout, complete a real mTLS handshake
/// pinned to the QR's SPKI, exchange one CCP1 request/response, and observe the child exit
/// cleanly with exactly the expected `phone_id=` line on stderr.
#[test]
fn stdio_helper_completes_trusted_phone_handshake_and_reports_phone_id_on_stderr() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_usb_pairing_proof_stdio_helper"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();

    let mut stdout = child.stdout.take().unwrap();
    let (first_line, second_line) = read_two_prelude_lines(&mut stdout);
    assert_eq!(first_line, "CHINCHILLACAM-USB-INTEROP:v1");

    let qr_line = second_line
        .strip_prefix("qr=")
        .expect("second prelude line carries the qr= payload");
    let body = qr_line
        .strip_prefix("CHINCHILLACAM-PAIR:v1:")
        .expect("QR wire uses the frozen CHINCHILLACAM-PAIR:v1: prefix");
    let desktop_id = extract_query_value(body, "desktopId").to_string();
    let qr_nonce = URL_SAFE_NO_PAD
        .decode(extract_query_value(body, "nonce"))
        .expect("QR nonce decodes as URL-safe base64");
    let expected_spki = URL_SAFE_NO_PAD
        .decode(extract_query_value(body, "trustMaterial"))
        .expect("QR trust material decodes as URL-safe base64");

    let stdin = child.stdin.take().unwrap();
    let bulk_io = ChildStdioBulkIo { stdin, stdout };
    let budget = FrameTransferBudget::new(Duration::from_millis(3000), 65_536, 4096).unwrap();
    let mut ciphertext_stream = UsbTlsCiphertextStream::new(FramedUsbStream::new(bulk_io, budget));

    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Interop Phone").unwrap();
    let expected_phone_id = phone_id_for_spki(phone_identity.spki_der_p256());
    let mut client = ClientConnection::new(
        Arc::new(phone_side_client_config(expected_spki, &phone_identity)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();
    while client.is_handshaking() {
        client.complete_io(&mut ciphertext_stream).unwrap();
    }
    let mut tls = StreamOwned::new(client, ciphertext_stream);

    let request =
        PairingProofRequest::new(&desktop_id, qr_nonce, vec![9; 32], "interop-session").unwrap();
    let request_frame = PairingProofFrame::request(request.clone())
        .encode()
        .unwrap();
    tls.write_all(&request_frame).unwrap();
    tls.flush().unwrap();

    let response_frame = read_ccp1_frame(&mut tls);
    let expected_response_frame = PairingProofFrame::response(PairingProofResponse::ok(request))
        .encode()
        .unwrap();
    assert_eq!(response_frame, expected_response_frame);
    drop(tls);

    let status = wait_for_exit_with_timeout(&mut child, Duration::from_secs(5));
    assert!(status.success(), "helper exited with failure: {status:?}");

    let mut stderr_text = String::new();
    child
        .stderr
        .take()
        .unwrap()
        .read_to_string(&mut stderr_text)
        .unwrap();
    assert_eq!(stderr_text, format!("phone_id={expected_phone_id}\n"));
}

/// Reads exactly the two `\n`-terminated prelude lines from `stdout`, one byte at a time.
/// A `BufReader` (as the prelude-only test above uses) would over-read into its own
/// internal buffer past the second newline, silently swallowing the binary AOA frame bytes
/// that immediately follow -- fatal here, since this test keeps reading the same raw
/// `ChildStdout` afterwards.
fn read_two_prelude_lines(stdout: &mut ChildStdout) -> (String, String) {
    let mut lines: Vec<String> = Vec::with_capacity(2);
    let mut current = Vec::new();
    let mut byte = [0u8; 1];
    while lines.len() < 2 {
        stdout
            .read_exact(&mut byte)
            .expect("reading prelude byte from helper stdout");
        if byte[0] == b'\n' {
            lines.push(
                String::from_utf8(std::mem::take(&mut current))
                    .expect("prelude line is valid utf-8"),
            );
        } else {
            current.push(byte[0]);
        }
    }
    (lines.remove(0), lines.remove(0))
}

fn extract_query_value<'a>(body: &'a str, key: &str) -> &'a str {
    let prefix = format!("{key}=");
    body.split('&')
        .find_map(|pair| pair.strip_prefix(prefix.as_str()))
        .unwrap_or_else(|| panic!("missing {key} in QR body: {body}"))
}

fn wait_for_exit_with_timeout(child: &mut Child, timeout: Duration) -> std::process::ExitStatus {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child.try_wait().expect("polling helper exit status") {
            return status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            panic!("helper did not exit within {timeout:?}");
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn read_ccp1_frame<S: Read>(stream: &mut S) -> Vec<u8> {
    let mut header = [0u8; 10];
    stream.read_exact(&mut header).unwrap();
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    let mut frame = header.to_vec();
    frame.resize(10 + payload_len, 0);
    stream.read_exact(&mut frame[10..]).unwrap();
    frame
}

/// Bridges a spawned child's `stdin`/`stdout` pipes into `UsbBulkIo` so the crate's own
/// `FramedUsbStream`/`UsbTlsCiphertextStream` can carry AOA-framed TLS ciphertext over them
/// exactly as they would over real USB bulk endpoints. Timeouts are not enforced on the
/// underlying blocking pipe reads/writes, mirroring the production `StdioUsbBulkIo` in
/// `src/bin/usb_pairing_proof_stdio_helper.rs`, which has the same limitation.
#[derive(Debug)]
struct ChildStdioBulkIo {
    stdin: ChildStdin,
    stdout: ChildStdout,
}

impl UsbBulkIo for ChildStdioBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.stdout
            .read(buffer)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        let written = self
            .stdin
            .write(bytes)
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))?;
        self.stdin
            .flush()
            .map_err(|error| UsbProbeError::UsbBulkTransferFailed(error.to_string()))?;
        Ok(written)
    }
}

fn phone_side_client_config(
    expected_spki: Vec<u8>,
    phone_identity: &DesktopTlsIdentity,
) -> ClientConfig {
    let client_cert = CertificateDer::from(phone_identity.certificate_der().to_vec());
    let client_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        phone_identity.private_key_pkcs8_der().to_vec(),
    ));
    ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(PinnedSpkiServerCertVerifier::new(expected_spki))
        .with_client_auth_cert(vec![client_cert], client_key)
        .unwrap()
}

/// The phone-side equivalent of Android's `PinnedDesktopTlsTrustManager`: accepts the
/// desktop's certificate only if its SPKI equals the SPKI carried by the QR's
/// `trustMaterial`, exactly as a real phone would pin it -- never a root CA or chain.
#[derive(Debug)]
struct PinnedSpkiServerCertVerifier {
    expected_spki: Vec<u8>,
    signature_algorithms: WebPkiSupportedAlgorithms,
}

impl PinnedSpkiServerCertVerifier {
    fn new(expected_spki: Vec<u8>) -> Arc<Self> {
        Arc::new(Self {
            expected_spki,
            signature_algorithms: rustls::crypto::ring::default_provider()
                .signature_verification_algorithms,
        })
    }
}

impl ServerCertVerifier for PinnedSpkiServerCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        let parsed = webpki::EndEntityCert::try_from(end_entity)
            .map_err(|_| RustlsError::InvalidCertificate(CertificateError::BadEncoding))?;
        let spki = parsed.subject_public_key_info();
        let spki: &[u8] = spki.as_ref();
        if spki == self.expected_spki.as_slice() {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(RustlsError::InvalidCertificate(
                CertificateError::UnknownIssuer,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.signature_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.signature_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}
