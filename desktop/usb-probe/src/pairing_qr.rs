use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use sha2::{Digest, Sha256};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingQrError {
    EmptyDesktopId,
    EmptyDesktopName,
    InvalidExpiry,
    EmptySpkiDer,
    InvalidSpkiDerP256,
    InvalidDesktopIdCharacter(char),
    InvalidDesktopNameCharacter(char),
}

impl fmt::Display for PairingQrError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyDesktopId => write!(formatter, "desktop id is required"),
            Self::EmptyDesktopName => write!(formatter, "desktop name is required"),
            Self::InvalidExpiry => write!(formatter, "expiry must be a positive unix timestamp"),
            Self::EmptySpkiDer => write!(formatter, "SPKI DER is required"),
            Self::InvalidSpkiDerP256 => {
                write!(formatter, "SPKI DER must identify a P-256 public key")
            }
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
    name: String,
    expiry_unix_seconds: u64,
    nonce: [u8; 16],
    spki_der_p256: Vec<u8>,
}

impl PairingQrPayload {
    pub fn new(
        desktop_id: impl Into<String>,
        name: impl Into<String>,
        expiry_unix_seconds: u64,
        nonce: [u8; 16],
        spki_der_p256: Vec<u8>,
    ) -> Result<Self, PairingQrError> {
        let desktop_id = desktop_id.into();
        let name = name.into();
        validate_desktop_id(&desktop_id)?;
        validate_desktop_name(&name)?;
        if expiry_unix_seconds == 0 {
            return Err(PairingQrError::InvalidExpiry);
        }
        if spki_der_p256.is_empty() {
            return Err(PairingQrError::EmptySpkiDer);
        }
        if !is_p256_spki_der(&spki_der_p256) {
            return Err(PairingQrError::InvalidSpkiDerP256);
        }

        Ok(Self {
            desktop_id,
            name,
            expiry_unix_seconds,
            nonce,
            spki_der_p256,
        })
    }

    pub fn desktop_id(&self) -> &str {
        &self.desktop_id
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn expiry_unix_seconds(&self) -> u64 {
        self.expiry_unix_seconds
    }

    pub fn nonce(&self) -> &[u8; 16] {
        &self.nonce
    }

    pub fn spki_der_p256(&self) -> &[u8] {
        &self.spki_der_p256
    }
}

#[derive(Debug, Default)]
pub struct PairingQrProducer;

impl PairingQrProducer {
    pub fn encode_v1(payload: &PairingQrPayload) -> Result<Vec<u8>, PairingQrError> {
        let unsigned = format!(
            "chinchillacam://pair?v=1&desktopId={}&name={}&expiry={}&nonce={}&spkiDerP256={}",
            percent_encode(payload.desktop_id()),
            percent_encode(payload.name()),
            payload.expiry_unix_seconds(),
            URL_SAFE_NO_PAD.encode(payload.nonce()),
            URL_SAFE_NO_PAD.encode(payload.spki_der_p256()),
        );
        let checksum = pairing_checksum(unsigned.as_bytes());
        Ok(format!("{unsigned}&checksum={checksum}").into_bytes())
    }
}

fn validate_desktop_id(desktop_id: &str) -> Result<(), PairingQrError> {
    if desktop_id.trim().is_empty() {
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

fn is_p256_spki_der(bytes: &[u8]) -> bool {
    const EC_PUBLIC_KEY_OID: &[u8] = &[0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
    const PRIME256V1_OID: &[u8] = &[0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];
    bytes.starts_with(&[0x30])
        && bytes
            .windows(EC_PUBLIC_KEY_OID.len())
            .any(|window| window == EC_PUBLIC_KEY_OID)
        && bytes
            .windows(PRIME256V1_OID.len())
            .any(|window| window == PRIME256V1_OID)
}

fn pairing_checksum(unsigned_wire: &[u8]) -> String {
    let digest = Sha256::digest(unsigned_wire);
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
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => {
                encoded.push(*byte as char);
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}
