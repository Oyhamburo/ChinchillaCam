use std::{
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection, StreamOwned,
};

use crate::{
    DesktopTlsIdentity, OsPairingQrNonceGenerator, PairingProofEndpoint, PairingProofFrame,
    PairingProofRequest, PairingProofResponse, PairingQrIssuer, PairingQrIssuerError,
    PairingQrNonceGenerator,
};

const CCP1_HEADER_BYTES: usize = 10;
const CCP1_MAX_PAYLOAD_BYTES: usize = 1024;

#[derive(Debug)]
pub enum LoopbackPairingProofServerError {
    Bind(String),
    Tls(String),
    Io(String),
    Timeout,
    InvalidProof,
    Issuer(PairingQrIssuerError),
}

pub struct LoopbackPairingProofServer<R = OsPairingQrNonceGenerator> {
    listener: TcpListener,
    config: Arc<ServerConfig>,
    endpoint: PairingProofEndpoint,
    timeout: Duration,
    read_proof: bool,
    issuer: Option<PairingQrIssuer<R>>,
}

impl LoopbackPairingProofServer<OsPairingQrNonceGenerator> {
    pub fn bind(
        identity: DesktopTlsIdentity,
        timeout: Duration,
    ) -> Result<Self, LoopbackPairingProofServerError> {
        bind_with_mode(identity, timeout, false, None)
    }

    pub fn bind_pairing_proof_reader(
        identity: DesktopTlsIdentity,
        timeout: Duration,
    ) -> Result<Self, LoopbackPairingProofServerError> {
        bind_with_mode(identity, timeout, true, None)
    }
}

impl<R: PairingQrNonceGenerator> LoopbackPairingProofServer<R> {
    pub fn bind_pairing_proof(
        identity: DesktopTlsIdentity,
        timeout: Duration,
        issuer: PairingQrIssuer<R>,
    ) -> Result<Self, LoopbackPairingProofServerError> {
        bind_with_mode(identity, timeout, true, Some(issuer))
    }

    pub fn endpoint(&self) -> &PairingProofEndpoint {
        &self.endpoint
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.listener
            .local_addr()
            .expect("bound listener has local addr")
    }

    pub fn accept_one(self) -> Result<(), LoopbackPairingProofServerError> {
        self.accept_one_returning_issuer().map(|_| ())
    }

    pub fn accept_one_returning_issuer(
        mut self,
    ) -> Result<Option<PairingQrIssuer<R>>, LoopbackPairingProofServerError> {
        let tcp = accept_with_deadline(&self.listener, self.timeout)?;
        tcp.set_nonblocking(false)
            .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
        tcp.set_read_timeout(Some(self.timeout))
            .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
        tcp.set_write_timeout(Some(self.timeout))
            .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
        let mut connection = ServerConnection::new(self.config)
            .map_err(|error| LoopbackPairingProofServerError::Tls(error.to_string()))?;
        let mut tcp = tcp;
        let deadline = Instant::now() + self.timeout;
        while connection.is_handshaking() {
            if Instant::now() >= deadline {
                return Err(LoopbackPairingProofServerError::Timeout);
            }
            connection
                .complete_io(&mut tcp)
                .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
        }
        if self.read_proof {
            let mut stream = StreamOwned::new(connection, tcp);
            let request = read_ccp1_request(&mut stream)?;
            validate_request(&request)?;
            if let Some(issuer) = &mut self.issuer {
                consume_and_respond(&mut stream, issuer, request)?;
            }
        }
        Ok(self.issuer)
    }
}

fn bind_with_mode<R: PairingQrNonceGenerator>(
    identity: DesktopTlsIdentity,
    timeout: Duration,
    read_proof: bool,
    issuer: Option<PairingQrIssuer<R>>,
) -> Result<LoopbackPairingProofServer<R>, LoopbackPairingProofServerError> {
    let timeout_ms = u32::try_from(timeout.as_millis()).unwrap_or(u32::MAX);
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|error| LoopbackPairingProofServerError::Bind(error.to_string()))?;
    let port = listener
        .local_addr()
        .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?
        .port();
    let endpoint = PairingProofEndpoint::loopback(port, timeout_ms)
        .map_err(|error| LoopbackPairingProofServerError::Bind(format!("{error:?}")))?;
    let config = server_config(&identity)?;
    Ok(LoopbackPairingProofServer {
        listener,
        config,
        endpoint,
        timeout,
        read_proof,
        issuer,
    })
}

fn consume_and_respond<S: Read + Write, R: PairingQrNonceGenerator>(
    stream: &mut S,
    issuer: &mut PairingQrIssuer<R>,
    request: PairingProofRequest,
) -> Result<(), LoopbackPairingProofServerError> {
    issuer
        .consume_issued_nonce(
            request.desktop_id(),
            request.qr_nonce(),
            current_epoch_seconds()?,
        )
        .map_err(LoopbackPairingProofServerError::Issuer)?;
    let response = PairingProofFrame::response(PairingProofResponse::ok(request))
        .encode()
        .map_err(|_| LoopbackPairingProofServerError::InvalidProof)?;
    stream
        .write_all(&response)
        .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
    stream
        .flush()
        .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
    Ok(())
}

fn read_ccp1_request<S: Read>(
    stream: &mut S,
) -> Result<PairingProofRequest, LoopbackPairingProofServerError> {
    let mut header = [0; CCP1_HEADER_BYTES];
    stream
        .read_exact(&mut header)
        .map_err(|error| map_read_error(error))?;
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    if payload_len > CCP1_MAX_PAYLOAD_BYTES {
        return Err(LoopbackPairingProofServerError::InvalidProof);
    }
    let mut frame = Vec::with_capacity(CCP1_HEADER_BYTES + payload_len);
    frame.extend_from_slice(&header);
    frame.resize(CCP1_HEADER_BYTES + payload_len, 0);
    stream
        .read_exact(&mut frame[CCP1_HEADER_BYTES..])
        .map_err(|error| map_read_error(error))?;
    match PairingProofFrame::decode(&frame) {
        Ok(PairingProofFrame::Request(request)) => Ok(request),
        _ => Err(LoopbackPairingProofServerError::InvalidProof),
    }
}

fn validate_request(request: &PairingProofRequest) -> Result<(), LoopbackPairingProofServerError> {
    if !(32..=64).contains(&request.challenge_nonce().len())
        || !valid_token(request.session_id())
        || !valid_token(request.desktop_id())
    {
        return Err(LoopbackPairingProofServerError::InvalidProof);
    }
    Ok(())
}

fn valid_token(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn map_read_error(error: std::io::Error) -> LoopbackPairingProofServerError {
    if matches!(
        error.kind(),
        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock
    ) {
        LoopbackPairingProofServerError::Timeout
    } else {
        LoopbackPairingProofServerError::Io(error.to_string())
    }
}

fn current_epoch_seconds() -> Result<u64, LoopbackPairingProofServerError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| LoopbackPairingProofServerError::InvalidProof)
}

fn accept_with_deadline(
    listener: &TcpListener,
    timeout: Duration,
) -> Result<TcpStream, LoopbackPairingProofServerError> {
    listener
        .set_nonblocking(true)
        .map_err(|error| LoopbackPairingProofServerError::Io(error.to_string()))?;
    let deadline = Instant::now() + timeout;
    loop {
        match listener.accept() {
            Ok((tcp, _)) => return Ok(tcp),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    return Err(LoopbackPairingProofServerError::Timeout);
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(error) => return Err(LoopbackPairingProofServerError::Io(error.to_string())),
        }
    }
}

fn server_config(
    identity: &DesktopTlsIdentity,
) -> Result<Arc<ServerConfig>, LoopbackPairingProofServerError> {
    let provider = rustls::crypto::ring::default_provider();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let cert = CertificateDer::from(identity.certificate_der().to_vec());
    let config = ServerConfig::builder_with_provider(provider.into())
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|error| LoopbackPairingProofServerError::Tls(error.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|error| LoopbackPairingProofServerError::Tls(error.to_string()))?;
    Ok(Arc::new(config))
}
