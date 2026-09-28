use std::str;

const MAGIC: &[u8; 4] = b"CCP1";
const VERSION: u8 = 1;
const MAX_PAYLOAD_LEN: usize = 1024;
const MAX_TEXT_BYTES: usize = 64;
const MAX_NONCE_BYTES: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingProofProtocolError {
    InvalidMagic,
    UnsupportedVersion,
    UnsupportedType,
    PayloadTooLarge,
    Truncated,
    TrailingBytes,
    UnknownTlv,
    DuplicateTlv,
    OutOfOrderTlv,
    MissingTlv,
    InvalidUtf8,
    InvalidDesktopId,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingProofRequest {
    desktop_id: String,
    qr_nonce: Vec<u8>,
    challenge_nonce: Vec<u8>,
    session_id: String,
}

impl PairingProofRequest {
    pub fn new(
        desktop_id: &str,
        qr_nonce: Vec<u8>,
        challenge_nonce: Vec<u8>,
        session_id: &str,
    ) -> Result<Self, PairingProofProtocolError> {
        let request = Self {
            desktop_id: desktop_id.to_string(),
            qr_nonce,
            challenge_nonce,
            session_id: session_id.to_string(),
        };
        request.validate()?;
        Ok(request)
    }

    fn validate(&self) -> Result<(), PairingProofProtocolError> {
        if self.desktop_id.is_empty()
            || self.session_id.is_empty()
            || self.qr_nonce.is_empty()
            || self.challenge_nonce.is_empty()
        {
            return Err(PairingProofProtocolError::MissingTlv);
        }
        if self.desktop_id.len() > MAX_TEXT_BYTES
            || self.session_id.len() > MAX_TEXT_BYTES
            || self.qr_nonce.len() > MAX_NONCE_BYTES
            || self.challenge_nonce.len() > MAX_NONCE_BYTES
        {
            return Err(PairingProofProtocolError::PayloadTooLarge);
        }
        if !self
            .desktop_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        {
            return Err(PairingProofProtocolError::InvalidDesktopId);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingProofResponse {
    status: u8,
    echo: PairingProofRequest,
}

impl PairingProofResponse {
    pub fn new(status: u8, echo: PairingProofRequest) -> Self {
        Self { status, echo }
    }

    pub fn ok(echo: PairingProofRequest) -> Self {
        Self::new(0, echo)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingProofFrame {
    Request(PairingProofRequest),
    Response(PairingProofResponse),
}

impl PairingProofFrame {
    pub fn request(request: PairingProofRequest) -> Self {
        Self::Request(request)
    }
    pub fn response(response: PairingProofResponse) -> Self {
        Self::Response(response)
    }

    pub fn encode(&self) -> Result<Vec<u8>, PairingProofProtocolError> {
        let (kind, payload) = match self {
            Self::Request(request) => (1, encode_request(request)?),
            Self::Response(response) => (2, encode_response(response)?),
        };
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err(PairingProofProtocolError::PayloadTooLarge);
        }
        let mut frame = Vec::with_capacity(10 + payload.len());
        frame.extend_from_slice(MAGIC);
        frame.push(VERSION);
        frame.push(kind);
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);
        Ok(frame)
    }

    pub fn decode(frame: &[u8]) -> Result<Self, PairingProofProtocolError> {
        if frame.len() < 10 {
            return Err(PairingProofProtocolError::Truncated);
        }
        if &frame[0..4] != MAGIC {
            return Err(PairingProofProtocolError::InvalidMagic);
        }
        if frame[4] != VERSION {
            return Err(PairingProofProtocolError::UnsupportedVersion);
        }
        let payload_len = u32::from_be_bytes(frame[6..10].try_into().unwrap()) as usize;
        if payload_len > MAX_PAYLOAD_LEN {
            return Err(PairingProofProtocolError::PayloadTooLarge);
        }
        if frame.len() < 10 + payload_len {
            return Err(PairingProofProtocolError::Truncated);
        }
        if frame.len() > 10 + payload_len {
            return Err(PairingProofProtocolError::TrailingBytes);
        }
        match frame[5] {
            1 => Ok(Self::Request(decode_request(&frame[10..])?)),
            2 => Ok(Self::Response(decode_response(&frame[10..])?)),
            _ => Err(PairingProofProtocolError::UnsupportedType),
        }
    }
}

fn encode_response(response: &PairingProofResponse) -> Result<Vec<u8>, PairingProofProtocolError> {
    let mut payload = Vec::new();
    write_tlv(&mut payload, 0, &[response.status])?;
    write_request_tlvs(&mut payload, &response.echo)?;
    Ok(payload)
}

fn encode_request(request: &PairingProofRequest) -> Result<Vec<u8>, PairingProofProtocolError> {
    let mut payload = Vec::new();
    write_request_tlvs(&mut payload, request)?;
    Ok(payload)
}

fn write_request_tlvs(
    payload: &mut Vec<u8>,
    request: &PairingProofRequest,
) -> Result<(), PairingProofProtocolError> {
    request.validate()?;
    write_tlv(payload, 1, request.desktop_id.as_bytes())?;
    write_tlv(payload, 2, &request.qr_nonce)?;
    write_tlv(payload, 3, &request.challenge_nonce)?;
    write_tlv(payload, 4, request.session_id.as_bytes())
}

fn write_tlv(out: &mut Vec<u8>, tag: u8, value: &[u8]) -> Result<(), PairingProofProtocolError> {
    if value.is_empty() || value.len() > u16::MAX as usize {
        return Err(PairingProofProtocolError::MissingTlv);
    }
    out.push(tag);
    out.extend_from_slice(&(value.len() as u16).to_be_bytes());
    out.extend_from_slice(value);
    Ok(())
}

fn decode_request(payload: &[u8]) -> Result<PairingProofRequest, PairingProofProtocolError> {
    let fields = read_tlvs(payload, &[1, 2, 3, 4])?;
    PairingProofRequest::new(
        text(fields[0])?,
        fields[1].to_vec(),
        fields[2].to_vec(),
        text(fields[3])?,
    )
}

fn decode_response(payload: &[u8]) -> Result<PairingProofResponse, PairingProofProtocolError> {
    let fields = read_tlvs(payload, &[0, 1, 2, 3, 4])?;
    if fields[0].len() != 1 {
        return Err(PairingProofProtocolError::MissingTlv);
    }
    Ok(PairingProofResponse::new(
        fields[0][0],
        PairingProofRequest::new(
            text(fields[1])?,
            fields[2].to_vec(),
            fields[3].to_vec(),
            text(fields[4])?,
        )?,
    ))
}

fn read_tlvs<'a>(
    mut payload: &'a [u8],
    expected: &[u8],
) -> Result<Vec<&'a [u8]>, PairingProofProtocolError> {
    let mut seen = Vec::new();
    let mut last = None;
    while !payload.is_empty() {
        if payload.len() < 3 {
            return Err(PairingProofProtocolError::Truncated);
        }
        let tag = payload[0];
        let len = u16::from_be_bytes([payload[1], payload[2]]) as usize;
        payload = &payload[3..];
        if payload.len() < len {
            return Err(PairingProofProtocolError::Truncated);
        }
        if !expected.contains(&tag) {
            return Err(PairingProofProtocolError::UnknownTlv);
        }
        if last.is_some_and(|previous| tag < previous) {
            return Err(PairingProofProtocolError::OutOfOrderTlv);
        }
        if seen.iter().any(|(seen_tag, _)| *seen_tag == tag) {
            return Err(PairingProofProtocolError::DuplicateTlv);
        }
        if len == 0 {
            return Err(PairingProofProtocolError::MissingTlv);
        }
        seen.push((tag, &payload[..len]));
        last = Some(tag);
        payload = &payload[len..];
    }
    expected
        .iter()
        .map(|tag| {
            seen.iter()
                .find(|(seen_tag, _)| seen_tag == tag)
                .map(|(_, value)| *value)
                .ok_or(PairingProofProtocolError::MissingTlv)
        })
        .collect()
}

fn text(bytes: &[u8]) -> Result<&str, PairingProofProtocolError> {
    str::from_utf8(bytes).map_err(|_| PairingProofProtocolError::InvalidUtf8)
}
