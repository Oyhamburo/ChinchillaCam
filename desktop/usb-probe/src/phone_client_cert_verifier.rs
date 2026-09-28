//! Verifies the phone's TLS client certificate during a USB mTLS handshake.
//!
//! Identity is the certificate's SubjectPublicKeyInfo (SPKI), not its issuer or validity
//! period: ChinchillaCam phone identities are self-signed EC P-256 certificates backed by
//! Android Keystore (see `odd/tasks/phone-mtls-identity.md`, section 4). Every policy below
//! intentionally ignores certificate chains, validity dates, and issuers.

use std::{fmt, sync::Arc};

use rustls::{
    client::danger::HandshakeSignatureValid,
    crypto::{
        ring::default_provider, verify_tls12_signature, verify_tls13_signature,
        WebPkiSupportedAlgorithms,
    },
    pki_types::{CertificateDer, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
    CertificateError, DigitallySignedStruct, DistinguishedName, Error as RustlsError,
    SignatureScheme,
};
use sha2::{Digest, Sha256};

use crate::trusted_phone_store::{
    FileTrustedPhoneStore, PhoneTrustSnapshot, TrustedPhoneStoreError,
};

/// DER prefix shared by every canonical, uncompressed NIST P-256 SubjectPublicKeyInfo:
/// `SEQUENCE { SEQUENCE { OID ecPublicKey, OID prime256v1 }, BIT STRING (0x00 || 0x04 || X || Y) }`.
/// 27 prefix bytes followed by 64 raw point bytes (X || Y) = 91 bytes total.
const P256_SPKI_PREFIX: [u8; 27] = [
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
    0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
];
const P256_SPKI_LEN: usize = 91;

/// Returns true only for the exact 91-byte canonical DER encoding of an uncompressed NIST
/// P-256 public key -- the only SPKI shape ChinchillaCam phone identities use.
pub fn is_canonical_p256_spki(spki_der: &[u8]) -> bool {
    spki_der.len() == P256_SPKI_LEN && spki_der.starts_with(&P256_SPKI_PREFIX)
}

/// `phone_id` is the lowercase hex SHA-256 digest of the client certificate's SPKI DER
/// bytes: the same fingerprint algorithm Android's `PairingTrustFingerprint` uses.
pub fn phone_id_for_spki(spki_der: &[u8]) -> String {
    encode_lower_hex(&Sha256::digest(spki_der))
}

fn encode_lower_hex(bytes: &[u8]) -> String {
    const HEX_DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut hex = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        hex.push(HEX_DIGITS[(byte >> 4) as usize] as char);
        hex.push(HEX_DIGITS[(byte & 0x0f) as usize] as char);
    }
    hex
}

/// Outcome of looking up a phone's trust state by its `phone_id`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrustedPhoneStatus {
    /// A phone this desktop has confirmed, with its trusted public key (SPKI DER).
    Trusted { public_key: Vec<u8> },
    /// A phone this desktop previously trusted and has since revoked.
    Revoked,
    /// A phone this desktop has never trusted.
    Unknown,
}

/// A trust lookup failure. Any error here must fail the handshake closed (reject).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrustedPhoneLookupError(String);

impl TrustedPhoneLookupError {
    pub fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl fmt::Display for TrustedPhoneLookupError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "trusted phone lookup failed: {}", self.0)
    }
}

impl std::error::Error for TrustedPhoneLookupError {}

/// Looks up a phone's current trust state. Implementations share no state with the QR
/// pairing issuer: the pairing policy below never consults this trait at all.
pub trait TrustedPhoneLookup: Send + Sync {
    fn phone_status(&self, phone_id: &str) -> Result<TrustedPhoneStatus, TrustedPhoneLookupError>;
}

impl TrustedPhoneLookup for FileTrustedPhoneStore {
    fn phone_status(&self, phone_id: &str) -> Result<TrustedPhoneStatus, TrustedPhoneLookupError> {
        match self.phone_trust_snapshot(phone_id) {
            Ok(PhoneTrustSnapshot::Trusted(public_key)) => {
                Ok(TrustedPhoneStatus::Trusted { public_key })
            }
            Ok(PhoneTrustSnapshot::Revoked) => Ok(TrustedPhoneStatus::Revoked),
            Ok(PhoneTrustSnapshot::Unknown) => Ok(TrustedPhoneStatus::Unknown),
            Err(error) => Err(lookup_error(error)),
        }
    }
}

fn lookup_error(error: TrustedPhoneStoreError) -> TrustedPhoneLookupError {
    TrustedPhoneLookupError::new(format!("{error:?}"))
}

enum Policy {
    /// Pairing window: accept any client certificate with a canonical P-256 SPKI. Safe
    /// only because the caller (task m2) only trusts the outcome once `CCP1` proves a
    /// live, single-use QR nonce over this same TLS session.
    Pairing,
    /// Reconnection without a QR: accept only a phone `lookup` reports as trusted and not
    /// revoked, and only when its stored public key exactly matches the certificate's SPKI.
    TrustedOnly(Arc<dyn TrustedPhoneLookup + Send + Sync>),
}

/// Verifies a phone's TLS client certificate by its SPKI alone. Certificate chains,
/// validity dates, and issuers are intentionally ignored (see module docs); rustls
/// requires this type to also verify `CertificateVerify` signatures, which is delegated
/// unchanged to the standard rustls/webpki helpers.
pub struct PhoneClientCertVerifier {
    policy: Policy,
    signature_algorithms: WebPkiSupportedAlgorithms,
}

impl PhoneClientCertVerifier {
    /// Accepts any client certificate with a canonical P-256 SPKI. Use only while an
    /// active pairing window (a live QR nonce) also gates the result.
    pub fn pairing() -> Arc<Self> {
        Arc::new(Self {
            policy: Policy::Pairing,
            signature_algorithms: supported_signature_algorithms(),
        })
    }

    /// Accepts only phones `lookup` reports as trusted and not revoked, with an exact
    /// public-key match. Use for reconnection without a QR.
    pub fn trusted_only(lookup: Arc<dyn TrustedPhoneLookup + Send + Sync>) -> Arc<Self> {
        Arc::new(Self {
            policy: Policy::TrustedOnly(lookup),
            signature_algorithms: supported_signature_algorithms(),
        })
    }
}

fn supported_signature_algorithms() -> WebPkiSupportedAlgorithms {
    default_provider().signature_verification_algorithms
}

/// Parses `end_entity` and returns its SPKI DER bytes, rejecting anything that is not a
/// canonical P-256 SubjectPublicKeyInfo.
fn accepted_client_spki(end_entity: &CertificateDer<'_>) -> Result<Vec<u8>, RustlsError> {
    let parsed = webpki::EndEntityCert::try_from(end_entity)
        .map_err(|_| RustlsError::InvalidCertificate(CertificateError::BadEncoding))?;
    let spki = parsed.subject_public_key_info();
    let spki: &[u8] = spki.as_ref();
    if !is_canonical_p256_spki(spki) {
        return Err(RustlsError::InvalidCertificate(
            CertificateError::BadEncoding,
        ));
    }
    Ok(spki.to_vec())
}

impl fmt::Debug for PhoneClientCertVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let policy = match &self.policy {
            Policy::Pairing => "Pairing",
            Policy::TrustedOnly(_) => "TrustedOnly",
        };
        formatter
            .debug_struct("PhoneClientCertVerifier")
            .field("policy", &policy)
            .finish()
    }
}

impl ClientCertVerifier for PhoneClientCertVerifier {
    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, RustlsError> {
        let spki = accepted_client_spki(end_entity)?;
        match &self.policy {
            Policy::Pairing => Ok(ClientCertVerified::assertion()),
            Policy::TrustedOnly(lookup) => {
                let phone_id = phone_id_for_spki(&spki);
                let status = lookup
                    .phone_status(&phone_id)
                    .map_err(|error| RustlsError::General(error.to_string()))?;
                match status {
                    TrustedPhoneStatus::Trusted { public_key } if public_key == spki => {
                        Ok(ClientCertVerified::assertion())
                    }
                    TrustedPhoneStatus::Revoked => {
                        Err(RustlsError::InvalidCertificate(CertificateError::Revoked))
                    }
                    TrustedPhoneStatus::Trusted { .. } | TrustedPhoneStatus::Unknown => Err(
                        RustlsError::InvalidCertificate(CertificateError::UnknownIssuer),
                    ),
                }
            }
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls12_signature(message, cert, dss, &self.signature_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        verify_tls13_signature(message, cert, dss, &self.signature_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ECDSA_NISTP256_SHA256]
    }
}
