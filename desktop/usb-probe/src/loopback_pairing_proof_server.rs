use std::{
    net::{SocketAddr, TcpListener, TcpStream},
    sync::Arc,
    time::{Duration, Instant},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection,
};

use crate::{DesktopTlsIdentity, PairingProofEndpoint};

#[derive(Debug)]
pub enum LoopbackPairingProofServerError {
    Bind(String),
    Tls(String),
    Io(String),
    Timeout,
}

pub struct LoopbackPairingProofServer {
    listener: TcpListener,
    config: Arc<ServerConfig>,
    endpoint: PairingProofEndpoint,
    timeout: Duration,
}

impl LoopbackPairingProofServer {
    pub fn bind(
        identity: DesktopTlsIdentity,
        timeout: Duration,
    ) -> Result<Self, LoopbackPairingProofServerError> {
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
        Ok(Self {
            listener,
            config,
            endpoint,
            timeout,
        })
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
        Ok(())
    }
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
