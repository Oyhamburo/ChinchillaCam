use crate::{DesktopTlsIdentity, PairingQrPayload, PairingQrProducer};

const QR_NONCE_BYTES: usize = 32;
const MAX_OUTSTANDING_NONCES: usize = 64;
const MIN_TTL_SECONDS: u64 = 60;
const MAX_TTL_SECONDS: u64 = 120;
const MAX_DESKTOP_ID_BYTES: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingQrIssuerError {
    InvalidDesktopId,
    InvalidTtl,
    RandomFailed,
    QrEncodingFailed,
    TooManyOutstandingNonces,
    UnknownNonce,
    WrongDesktopId,
    ExpiredNonce,
    ClockRollback,
    DuplicateNonce,
    ExpiryOverflow,
}

pub trait PairingQrNonceGenerator {
    fn fill_nonce(&mut self, nonce: &mut [u8; QR_NONCE_BYTES]) -> Result<(), PairingQrIssuerError>;
}

#[derive(Debug, Default, Clone, Copy)]
pub struct OsPairingQrNonceGenerator;

impl PairingQrNonceGenerator for OsPairingQrNonceGenerator {
    fn fill_nonce(&mut self, nonce: &mut [u8; QR_NONCE_BYTES]) -> Result<(), PairingQrIssuerError> {
        getrandom::getrandom(nonce).map_err(|_| PairingQrIssuerError::RandomFailed)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IssuedPairingQr {
    payload: PairingQrPayload,
    qr_wire: Vec<u8>,
}

impl IssuedPairingQr {
    pub fn payload(&self) -> &PairingQrPayload {
        &self.payload
    }

    pub fn qr_wire(&self) -> &[u8] {
        &self.qr_wire
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct OutstandingNonce {
    nonce: [u8; QR_NONCE_BYTES],
    issued_at: u64,
    expires_at: u64,
}

/// Mutable in-memory issuer state is intentionally not Clone; cloning would duplicate live nonces.
pub struct PairingQrIssuer<R = OsPairingQrNonceGenerator> {
    desktop_id: String,
    desktop_name: String,
    identity: DesktopTlsIdentity,
    ttl_seconds: u64,
    rng: R,
    outstanding: Vec<OutstandingNonce>,
}

impl PairingQrIssuer<OsPairingQrNonceGenerator> {
    pub fn new(
        desktop_id: &str,
        desktop_name: &str,
        identity: DesktopTlsIdentity,
        ttl_seconds: u64,
    ) -> Result<Self, PairingQrIssuerError> {
        Self::with_test_rng(
            desktop_id,
            desktop_name,
            identity,
            ttl_seconds,
            OsPairingQrNonceGenerator,
        )
    }
}

impl<R: PairingQrNonceGenerator> PairingQrIssuer<R> {
    pub fn with_test_rng(
        desktop_id: &str,
        desktop_name: &str,
        identity: DesktopTlsIdentity,
        ttl_seconds: u64,
        rng: R,
    ) -> Result<Self, PairingQrIssuerError> {
        validate_desktop_id(desktop_id)?;
        if !(MIN_TTL_SECONDS..=MAX_TTL_SECONDS).contains(&ttl_seconds) {
            return Err(PairingQrIssuerError::InvalidTtl);
        }
        Ok(Self {
            desktop_id: desktop_id.to_string(),
            desktop_name: desktop_name.to_string(),
            identity,
            ttl_seconds,
            rng,
            outstanding: Vec::new(),
        })
    }

    pub fn issue_at(
        &mut self,
        now_epoch_seconds: u64,
    ) -> Result<IssuedPairingQr, PairingQrIssuerError> {
        if self.outstanding.len() >= MAX_OUTSTANDING_NONCES {
            return Err(PairingQrIssuerError::TooManyOutstandingNonces);
        }
        let mut nonce = [0u8; QR_NONCE_BYTES];
        self.rng.fill_nonce(&mut nonce)?;
        if self.outstanding.iter().any(|issued| issued.nonce == nonce) {
            return Err(PairingQrIssuerError::DuplicateNonce);
        }
        let expires_at = now_epoch_seconds
            .checked_add(self.ttl_seconds)
            .filter(|expires_at| *expires_at <= i64::MAX as u64)
            .ok_or(PairingQrIssuerError::ExpiryOverflow)?;
        let payload = PairingQrPayload::new(
            self.desktop_id.clone(),
            self.desktop_name.clone(),
            expires_at,
            nonce.to_vec(),
            self.identity.qr_trust_material().to_vec(),
        )
        .map_err(|_| PairingQrIssuerError::QrEncodingFailed)?;
        let qr_wire = PairingQrProducer::encode_v1(&payload)
            .map_err(|_| PairingQrIssuerError::QrEncodingFailed)?;
        self.outstanding.push(OutstandingNonce {
            nonce,
            issued_at: now_epoch_seconds,
            expires_at,
        });
        Ok(IssuedPairingQr { payload, qr_wire })
    }

    pub fn trust_material(&self) -> &[u8] {
        self.identity.qr_trust_material()
    }

    pub fn consume_issued_nonce(
        &mut self,
        desktop_id: &str,
        nonce: &[u8],
        now_epoch_seconds: u64,
    ) -> Result<(), PairingQrIssuerError> {
        if desktop_id != self.desktop_id {
            return Err(PairingQrIssuerError::WrongDesktopId);
        }
        let index = self
            .outstanding
            .iter()
            .position(|issued| issued.nonce.as_slice() == nonce)
            .ok_or(PairingQrIssuerError::UnknownNonce)?;
        let issued = &self.outstanding[index];
        if now_epoch_seconds < issued.issued_at {
            return Err(PairingQrIssuerError::ClockRollback);
        }
        if now_epoch_seconds >= issued.expires_at {
            return Err(PairingQrIssuerError::ExpiredNonce);
        }
        self.outstanding.remove(index);
        Ok(())
    }
}

fn validate_desktop_id(desktop_id: &str) -> Result<(), PairingQrIssuerError> {
    if desktop_id.is_empty()
        || desktop_id.len() > MAX_DESKTOP_ID_BYTES
        || !desktop_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        return Err(PairingQrIssuerError::InvalidDesktopId);
    }
    Ok(())
}
