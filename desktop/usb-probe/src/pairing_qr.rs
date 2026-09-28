use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use sha2::{Digest, Sha256};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingQrError {
    EmptyDesktopId,
    EmptyDesktopName,
    InvalidExpiry,
    EmptyNonce,
    EmptyTrustMaterial,
    InvalidDesktopIdCharacter(char),
    InvalidDesktopNameCharacter(char),
}

impl fmt::Display for PairingQrError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyDesktopId => write!(formatter, "desktop id is required"),
            Self::EmptyDesktopName => write!(formatter, "desktop name is required"),
            Self::InvalidExpiry => write!(formatter, "expiresAt must be a positive unix timestamp"),
            Self::EmptyNonce => write!(formatter, "nonce is required"),
            Self::EmptyTrustMaterial => write!(formatter, "trust material is required"),
            Self::InvalidDesktopIdCharacter(character) => {
                write!(
                    formatter,
                    "desktop id contains invalid character {character:?}"
                )
            }
            Self::InvalidDesktopNameCharacter(character) => {
                write!(
                    formatter,
                    "desktop name contains invalid character {character:?}"
                )
            }
        }
    }
}

impl std::error::Error for PairingQrError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingQrPayload {
    desktop_id: String,
    desktop_name: String,
    expires_at_epoch_seconds: u64,
    nonce: Vec<u8>,
    trust_material: Vec<u8>,
}

impl PairingQrPayload {
    pub fn new(
        desktop_id: impl Into<String>,
        desktop_name: impl Into<String>,
        expires_at_epoch_seconds: u64,
        nonce: Vec<u8>,
        trust_material: Vec<u8>,
    ) -> Result<Self, PairingQrError> {
        let desktop_id = desktop_id.into();
        let desktop_name = desktop_name.into();
        validate_desktop_id(&desktop_id)?;
        validate_desktop_name(&desktop_name)?;
        if expires_at_epoch_seconds == 0 {
            return Err(PairingQrError::InvalidExpiry);
        }
        if nonce.is_empty() {
            return Err(PairingQrError::EmptyNonce);
        }
        if trust_material.is_empty() {
            return Err(PairingQrError::EmptyTrustMaterial);
        }

        Ok(Self {
            desktop_id,
            desktop_name,
            expires_at_epoch_seconds,
            nonce,
            trust_material,
        })
    }

    pub fn desktop_id(&self) -> &str {
        &self.desktop_id
    }

    pub fn desktop_name(&self) -> &str {
        &self.desktop_name
    }

    pub fn expires_at_epoch_seconds(&self) -> u64 {
        self.expires_at_epoch_seconds
    }

    pub fn nonce(&self) -> &[u8] {
        &self.nonce
    }

    pub fn trust_material(&self) -> &[u8] {
        &self.trust_material
    }
}

#[derive(Debug, Default)]
pub struct PairingQrProducer;

impl PairingQrProducer {
    pub fn encode_v1(payload: &PairingQrPayload) -> Result<Vec<u8>, PairingQrError> {
        let canonical_body = format!(
            "desktopId={}&desktopName={}&expiresAt={}&nonce={}&trustMaterial={}",
            percent_encode(payload.desktop_id()),
            percent_encode(payload.desktop_name()),
            percent_encode(&payload.expires_at_epoch_seconds().to_string()),
            percent_encode(&URL_SAFE_NO_PAD.encode(payload.nonce())),
            percent_encode(&URL_SAFE_NO_PAD.encode(payload.trust_material())),
        );
        let checksum = pairing_checksum(canonical_body.as_bytes());
        Ok(format!("CHINCHILLACAM-PAIR:v1:{canonical_body}&checksum={checksum}").into_bytes())
    }
}

fn validate_desktop_id(desktop_id: &str) -> Result<(), PairingQrError> {
    if desktop_id.is_empty() {
        return Err(PairingQrError::EmptyDesktopId);
    }
    for character in desktop_id.chars() {
        if !(character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')) {
            return Err(PairingQrError::InvalidDesktopIdCharacter(character));
        }
    }
    Ok(())
}

fn validate_desktop_name(name: &str) -> Result<(), PairingQrError> {
    if name.trim().is_empty() {
        return Err(PairingQrError::EmptyDesktopName);
    }
    for character in name.chars() {
        if character.is_control() {
            return Err(PairingQrError::InvalidDesktopNameCharacter(character));
        }
    }
    Ok(())
}

fn pairing_checksum(canonical_body: &[u8]) -> String {
    let digest = Sha256::digest(canonical_body);
    let mut checksum = String::with_capacity(32);
    for byte in &digest[..16] {
        checksum.push_str(&format!("{byte:02x}"));
    }
    checksum
}

fn percent_encode(value: &str) -> String {
    let mut encoded = String::new();
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'*' => {
                encoded.push(*byte as char);
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}
