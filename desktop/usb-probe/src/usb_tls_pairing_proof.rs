use std::{fmt, io, sync::Arc};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection,
};

use crate::{DesktopTlsIdentity, UsbBulkIo, UsbTlsCiphertextStream};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UsbTlsPairingProofError {
    Tls(String),
    Io(String),
}

impl fmt::Display for UsbTlsPairingProofError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Tls(error) => write!(formatter, "USB TLS pairing proof TLS error: {error}"),
            Self::Io(error) => write!(formatter, "USB TLS pairing proof transport error: {error}"),
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
