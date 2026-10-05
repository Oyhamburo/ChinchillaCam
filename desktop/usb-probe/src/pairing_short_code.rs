//! Pairing short authentication string (SAS v1, task d1 of
//! `odd/tasks/desktop-production-app.md`).

use std::fmt;

use sha2::{Digest, Sha256};

const SHORT_CODE_DOMAIN: &[u8] = b"CHINCHILLACAM-SAS-v1";
const SHORT_CODE_MODULUS: u32 = 1_000_000;

/// A 6-digit pairing code that both the desktop and the phone display so the user can
/// confirm they are talking to each other.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PairingShortCode {
    value: u32,
}

impl PairingShortCode {
    /// Builds a code from a raw value (reduced modulo 1 000 000). Exposed only so tests can
    /// check formatting of specific values; production codes come from
    /// [`pairing_short_code_v1`].
    #[doc(hidden)]
    pub fn from_value(value: u32) -> Self {
        Self {
            value: value % SHORT_CODE_MODULUS,
        }
    }

    /// The six zero-padded digits, e.g. `"041406"`.
    pub fn digits(&self) -> String {
        format!("{:06}", self.value)
    }

    /// The digits grouped for humans, e.g. `"841 406"`.
    pub fn display(&self) -> String {
        let digits = self.digits();
        format!("{} {}", &digits[..3], &digits[3..])
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShortCodeError {
    /// The named input was empty.
    EmptyInput(&'static str),
    /// The named input does not fit the `u32` length prefix.
    InputTooLong(&'static str),
}

impl fmt::Display for ShortCodeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyInput(name) => write!(formatter, "pairing short code input {name} is empty"),
            Self::InputTooLong(name) => {
                write!(formatter, "pairing short code input {name} is too long")
            }
        }
    }
}

impl std::error::Error for ShortCodeError {}

/// Computes the SAS v1 pairing code.
///
/// Both ends know all four inputs after the CCP1 exchange: the desktop and phone SPKIs
/// from the mTLS handshake, the QR nonce the phone scanned and the challenge nonce it
/// sent. Comparing the code on both screens complements the QR certificate pin: it binds
/// the phone's own certificate, which the pairing verifier accepts without a pin.
///
/// `digest = SHA-256("CHINCHILLACAM-SAS-v1" || len||desktop_spki || len||phone_spki ||
/// len||qr_nonce || len||challenge_nonce)` with each `len` a big-endian `u32`, and the
/// code is `u32::from_be_bytes(digest[0..4]) % 1_000_000`. This must stay identical to
/// Android `PairingShortCode` (shared fixed vector in `tests/pairing_short_code_test.rs`).
pub fn pairing_short_code_v1(
    desktop_spki: &[u8],
    phone_spki: &[u8],
    qr_nonce: &[u8],
    challenge_nonce: &[u8],
) -> Result<PairingShortCode, ShortCodeError> {
    let inputs = [
        ("desktop_spki", desktop_spki),
        ("phone_spki", phone_spki),
        ("qr_nonce", qr_nonce),
        ("challenge_nonce", challenge_nonce),
    ];
    let mut hasher = Sha256::new();
    hasher.update(SHORT_CODE_DOMAIN);
    for (name, input) in inputs {
        if input.is_empty() {
            return Err(ShortCodeError::EmptyInput(name));
        }
        let len = u32::try_from(input.len()).map_err(|_| ShortCodeError::InputTooLong(name))?;
        hasher.update(len.to_be_bytes());
        hasher.update(input);
    }
    let digest = hasher.finalize();
    let prefix = u32::from_be_bytes(digest[0..4].try_into().expect("4-byte digest prefix"));
    Ok(PairingShortCode::from_value(prefix))
}
