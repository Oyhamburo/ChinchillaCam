use std::fmt;

use rcgen::{
    Certificate, CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopTlsIdentityError {
    GenerationFailed(String),
}

impl fmt::Display for DesktopTlsIdentityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::GenerationFailed(error) => {
                write!(formatter, "desktop TLS identity generation failed: {error}")
            }
        }
    }
}

impl std::error::Error for DesktopTlsIdentityError {}

#[derive(Clone, PartialEq, Eq)]
pub struct DesktopTlsIdentity {
    certificate_der: Vec<u8>,
    spki_der_p256: Vec<u8>,
    private_key_pkcs8_der: Vec<u8>,
}

impl DesktopTlsIdentity {
    pub fn generate_ephemeral(desktop_name: &str) -> Result<Self, DesktopTlsIdentityError> {
        if desktop_name.trim().is_empty() || desktop_name.len() > 64 {
            return Err(DesktopTlsIdentityError::GenerationFailed(
                "desktop name must be non-empty and at most 64 bytes".to_string(),
            ));
        }
        let key_pair = KeyPair::generate(&PKCS_ECDSA_P256_SHA256)
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        let mut distinguished_name = DistinguishedName::new();
        distinguished_name.push(DnType::CommonName, desktop_name);
        let mut params = CertificateParams::default();
        params.distinguished_name = distinguished_name;
        params.key_pair = Some(key_pair);
        let certificate = Certificate::from_params(params)
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        let certificate_der = certificate
            .serialize_der()
            .map_err(|error| DesktopTlsIdentityError::GenerationFailed(error.to_string()))?;
        let spki_der_p256 = certificate.get_key_pair().public_key_der();
        let private_key_pkcs8_der = certificate.serialize_private_key_der();

        Ok(Self {
            certificate_der,
            spki_der_p256,
            private_key_pkcs8_der,
        })
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.certificate_der
    }

    pub fn spki_der_p256(&self) -> &[u8] {
        &self.spki_der_p256
    }

    pub fn qr_trust_material(&self) -> &[u8] {
        self.spki_der_p256()
    }

    /// Sensitive PKCS#8 private key material for the TLS backend only.
    /// Do not log or expose this value outside the local TLS identity boundary.
    pub fn private_key_pkcs8_der(&self) -> &[u8] {
        &self.private_key_pkcs8_der
    }
}
