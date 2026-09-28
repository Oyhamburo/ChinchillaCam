use std::{
    fmt,
    io::{self, Read, Write},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection, StreamOwned,
};

use crate::{
    DesktopTlsIdentity, PairingProofFrame, PairingProofRequest, PairingProofResponse,
    PairingQrIssuer, PairingQrIssuerError, PairingQrNonceGenerator, UsbBulkIo,
    UsbTlsCiphertextStream,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UsbTlsPairingProofError {
    Tls(String),
    Io(String),
    Timeout,
    InvalidProof,
    Issuer(PairingQrIssuerError),
}

impl fmt::Display for UsbTlsPairingProofError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Tls(error) => write!(formatter, "USB TLS pairing proof TLS error: {error}"),
            Self::Io(error) => write!(formatter, "USB TLS pairing proof transport error: {error}"),
            Self::Timeout => write!(formatter, "USB TLS pairing proof timed out"),
            Self::InvalidProof => write!(formatter, "USB TLS pairing proof was invalid"),
            Self::Issuer(error) => {
                write!(formatter, "USB TLS pairing proof issuer error: {error:?}")
            }
        }
    }
}

impl std::error::Error for UsbTlsPairingProofError {}

pub struct UsbTlsPairingProofServer {
    connection: ServerConnection,
}

impl UsbTlsPairingProofServer {
    pub fn new(identity: &DesktopTlsIdentity) -> Result<Self, UsbTlsPairingProofError> {
        Ok(Self {
            connection: ServerConnection::new(server_config(identity)?)
                .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?,
        })
    }

    pub fn complete_handshake<I>(
        &mut self,
        stream: &mut UsbTlsCiphertextStream<I>,
    ) -> Result<(), UsbTlsPairingProofError>
    where
        I: UsbBulkIo,
    {
        while self.connection.is_handshaking() {
            self.connection
                .complete_io(stream)
                .map_err(map_complete_io_error)?;
        }
        Ok(())
    }

    pub fn complete_handshake_and_pairing_proof<I, R>(
        self,
        stream: UsbTlsCiphertextStream<I>,
        issuer: &mut PairingQrIssuer<R>,
        timeout: Duration,
    ) -> Result<StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>, UsbTlsPairingProofError>
    where
        I: UsbBulkIo,
        R: PairingQrNonceGenerator,
    {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or(UsbTlsPairingProofError::Timeout)?;
        let mut connection = self.connection;
        let mut stream = stream;
        while connection.is_handshaking() {
            ensure_before_deadline(deadline)?;
            connection
                .complete_io(&mut stream)
                .map_err(map_complete_io_error)?;
            ensure_before_deadline(deadline)?;
        }

        let mut tls = StreamOwned::new(connection, stream);
        let request = read_ccp1_request(&mut tls, deadline)?;
        validate_request(&request)?;
        consume_and_respond(&mut tls, issuer, request, deadline)?;
        Ok(tls)
    }

    pub fn connection(&self) -> &ServerConnection {
        &self.connection
    }
}

fn server_config(
    identity: &DesktopTlsIdentity,
) -> Result<Arc<ServerConfig>, UsbTlsPairingProofError> {
    let provider = rustls::crypto::ring::default_provider();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let cert = CertificateDer::from(identity.certificate_der().to_vec());
    let config = ServerConfig::builder_with_provider(provider.into())
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?;
    Ok(Arc::new(config))
}

fn map_complete_io_error(error: io::Error) -> UsbTlsPairingProofError {
    match error.kind() {
        io::ErrorKind::InvalidData => UsbTlsPairingProofError::Tls(error.to_string()),
        _ => UsbTlsPairingProofError::Io(error.to_string()),
    }
}

fn consume_and_respond<S: Read + Write, R: PairingQrNonceGenerator>(
    stream: &mut S,
    issuer: &mut PairingQrIssuer<R>,
    request: PairingProofRequest,
    deadline: Instant,
) -> Result<(), UsbTlsPairingProofError> {
    ensure_before_deadline(deadline)?;
    issuer
        .consume_issued_nonce(
            request.desktop_id(),
            request.qr_nonce(),
            current_epoch_seconds()?,
        )
        .map_err(UsbTlsPairingProofError::Issuer)?;
    ensure_before_deadline(deadline)?;
    let response = PairingProofFrame::response(PairingProofResponse::ok(request))
        .encode()
        .map_err(|_| UsbTlsPairingProofError::InvalidProof)?;
    stream
        .write_all(&response)
        .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
    ensure_before_deadline(deadline)?;
    stream
        .flush()
        .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
    ensure_before_deadline(deadline)?;
    Ok(())
}

fn read_ccp1_request<S: Read>(
    stream: &mut S,
    deadline: Instant,
) -> Result<PairingProofRequest, UsbTlsPairingProofError> {
    let mut header = [0; 10];
    read_exact_before(stream, &mut header, deadline)?;
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    if payload_len > 1024 {
        return Err(UsbTlsPairingProofError::InvalidProof);
    }
    let mut frame = Vec::with_capacity(10 + payload_len);
    frame.extend_from_slice(&header);
    frame.resize(10 + payload_len, 0);
    read_exact_before(stream, &mut frame[10..], deadline)?;
    match PairingProofFrame::decode(&frame) {
        Ok(PairingProofFrame::Request(request)) => Ok(request),
        _ => Err(UsbTlsPairingProofError::InvalidProof),
    }
}

fn read_exact_before<S: Read>(
    stream: &mut S,
    mut buf: &mut [u8],
    deadline: Instant,
) -> Result<(), UsbTlsPairingProofError> {
    while !buf.is_empty() {
        if Instant::now() >= deadline {
            return Err(UsbTlsPairingProofError::Timeout);
        }
        match stream.read(buf) {
            Ok(0) => return Err(UsbTlsPairingProofError::Io("truncated proof".into())),
            Ok(read) => {
                ensure_before_deadline(deadline)?;
                buf = &mut buf[read..];
            }
            Err(error) => return Err(map_read_error(error)),
        }
    }
    ensure_before_deadline(deadline)
}

fn validate_request(request: &PairingProofRequest) -> Result<(), UsbTlsPairingProofError> {
    if !(32..=64).contains(&request.challenge_nonce().len())
        || !valid_token(request.session_id())
        || !valid_token(request.desktop_id())
    {
        return Err(UsbTlsPairingProofError::InvalidProof);
    }
    Ok(())
}

fn ensure_before_deadline(deadline: Instant) -> Result<(), UsbTlsPairingProofError> {
    if Instant::now() >= deadline {
        Err(UsbTlsPairingProofError::Timeout)
    } else {
        Ok(())
    }
}

fn valid_token(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn map_read_error(error: io::Error) -> UsbTlsPairingProofError {
    if matches!(
        error.kind(),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
    ) {
        UsbTlsPairingProofError::Timeout
    } else {
        UsbTlsPairingProofError::Io(error.to_string())
    }
}

fn current_epoch_seconds() -> Result<u64, UsbTlsPairingProofError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| UsbTlsPairingProofError::InvalidProof)
}
