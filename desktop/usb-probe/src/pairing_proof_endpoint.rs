const MAGIC: &[u8; 4] = b"CCPB";
const VERSION: u8 = 1;
const HOST: &str = "127.0.0.1";
const MAX_BYTES: usize = 256;
const MIN_TIMEOUT_MS: u32 = 250;
const MAX_TIMEOUT_MS: u32 = 5000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingProofEndpointError {
    InvalidMagic,
    UnsupportedVersion,
    Truncated,
    TrailingBytes,
    UnknownTlv,
    DuplicateTlv,
    OutOfOrderTlv,
    MissingTlv,
    InvalidHost,
    InvalidPort,
    InvalidTimeout,
    TooLarge,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingProofEndpoint {
    port: u16,
    timeout_ms: u32,
}

impl PairingProofEndpoint {
    pub fn loopback(port: u16, timeout_ms: u32) -> Result<Self, PairingProofEndpointError> {
        if port == 0 {
            return Err(PairingProofEndpointError::InvalidPort);
        }
        if !(MIN_TIMEOUT_MS..=MAX_TIMEOUT_MS).contains(&timeout_ms) {
            return Err(PairingProofEndpointError::InvalidTimeout);
        }
        Ok(Self { port, timeout_ms })
    }

    pub fn host(&self) -> &str {
        HOST
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn timeout_ms(&self) -> u32 {
        self.timeout_ms
    }

    pub fn encode(&self) -> Result<Vec<u8>, PairingProofEndpointError> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(MAGIC);
        bytes.push(VERSION);
        write_tlv(&mut bytes, 1, HOST.as_bytes());
        write_tlv(&mut bytes, 2, &self.port.to_be_bytes());
        write_tlv(&mut bytes, 3, &self.timeout_ms.to_be_bytes());
        if bytes.len() > MAX_BYTES {
            return Err(PairingProofEndpointError::TooLarge);
        }
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, PairingProofEndpointError> {
        if bytes.len() > MAX_BYTES {
            return Err(PairingProofEndpointError::TooLarge);
        }
        if bytes.len() < 5 {
            return Err(PairingProofEndpointError::Truncated);
        }
        if &bytes[..4] != MAGIC {
            return Err(PairingProofEndpointError::InvalidMagic);
        }
        if bytes[4] != VERSION {
            return Err(PairingProofEndpointError::UnsupportedVersion);
        }
        let fields = read_tlvs(&bytes[5..])?;
        if fields[0] != HOST.as_bytes() {
            return Err(PairingProofEndpointError::InvalidHost);
        }
        if fields[1].len() != 2 {
            return Err(PairingProofEndpointError::InvalidPort);
        }
        if fields[2].len() != 4 {
            return Err(PairingProofEndpointError::InvalidTimeout);
        }
        Self::loopback(
            u16::from_be_bytes([fields[1][0], fields[1][1]]),
            u32::from_be_bytes([fields[2][0], fields[2][1], fields[2][2], fields[2][3]]),
        )
    }
}

fn write_tlv(out: &mut Vec<u8>, tag: u8, value: &[u8]) {
    out.push(tag);
    out.extend_from_slice(&(value.len() as u16).to_be_bytes());
    out.extend_from_slice(value);
}

fn read_tlvs(mut bytes: &[u8]) -> Result<[&[u8]; 3], PairingProofEndpointError> {
    let mut seen = Vec::new();
    let mut previous = 0;
    while !bytes.is_empty() {
        if bytes.len() < 3 {
            return Err(PairingProofEndpointError::Truncated);
        }
        let tag = bytes[0];
        let len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
        bytes = &bytes[3..];
        if bytes.len() < len {
            return Err(PairingProofEndpointError::Truncated);
        }
        if !(1..=3).contains(&tag) {
            return Err(PairingProofEndpointError::UnknownTlv);
        }
        if tag < previous {
            return Err(PairingProofEndpointError::OutOfOrderTlv);
        }
        if seen.iter().any(|(seen_tag, _)| *seen_tag == tag) {
            return Err(PairingProofEndpointError::DuplicateTlv);
        }
        seen.push((tag, &bytes[..len]));
        previous = tag;
        bytes = &bytes[len..];
    }
    Ok([field(&seen, 1)?, field(&seen, 2)?, field(&seen, 3)?])
}

fn field<'a>(seen: &[(u8, &'a [u8])], tag: u8) -> Result<&'a [u8], PairingProofEndpointError> {
    seen.iter()
        .find(|(seen_tag, _)| *seen_tag == tag)
        .map(|(_, value)| *value)
        .ok_or(PairingProofEndpointError::MissingTlv)
}
