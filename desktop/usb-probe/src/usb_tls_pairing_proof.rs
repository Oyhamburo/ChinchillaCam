use std::{
    fmt,
    io::{self, Read, Write},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection, StreamOwned,
};

use crate::{
    phone_client_cert_verifier::accepted_client_spki, phone_id_for_spki, DesktopTlsIdentity,
    FileTrustedPhoneStore, PairingProofFrame, PairingProofRequest, PairingProofResponse,
    PairingQrIssuer, PairingQrIssuerError, PairingQrNonceGenerator, PhoneClientCertVerifier,
    TrustUnlessRevoked, TrustedPhoneIdentity, TrustedPhoneLookup, TrustedPhoneStoreError,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UsbTlsPairingProofError {
    Tls(String),
    Io(String),
    Timeout,
    InvalidProof,
    Issuer(PairingQrIssuerError),
}

impl fmt::Display for UsbTlsPairingProofError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Tls(error) => write!(formatter, "USB TLS pairing proof TLS error: {error}"),
            Self::Io(error) => write!(formatter, "USB TLS pairing proof transport error: {error}"),
            Self::Timeout => write!(formatter, "USB TLS pairing proof timed out"),
            Self::InvalidProof => write!(formatter, "USB TLS pairing proof was invalid"),
            Self::Issuer(error) => {
                write!(formatter, "USB TLS pairing proof issuer error: {error:?}")
            }
        }
    }
}

impl std::error::Error for UsbTlsPairingProofError {}

pub struct UsbTlsPairingProofServer {
    connection: ServerConnection,
}

/// The phone identity a pairing handshake's client certificate carried: its `phone_id` and
/// the exact canonical P-256 SPKI DER bytes, read from the live `ServerConnection` via
/// `peer_certificates()`. This is a candidate only -- the caller (task m4) must still gate
/// persisting it as a `TrustedPhoneIdentity` on an explicit user confirmation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairedPhoneCandidate {
    pub phone_id: String,
    pub spki: Vec<u8>,
}

/// Task m4: confirming a candidate failed for a reason other than the store's own
/// validation (`TrustedPhoneStoreError`, e.g. an oversized label or an I/O failure).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairedPhoneCandidateConfirmError {
    /// This candidate's `phone_id` is currently revoked. Confirming it must fail closed
    /// instead of silently un-revoking it: un-revocation is a separate, explicit action
    /// (out of scope for this contract; see `odd/tasks/phone-mtls-identity.md` section 4.6).
    PhoneRevoked,
    /// The store itself rejected the confirmation (validation or I/O failure).
    Store(TrustedPhoneStoreError),
}

impl fmt::Display for PairedPhoneCandidateConfirmError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PhoneRevoked => write!(
                formatter,
                "cannot confirm a paired phone candidate whose phone_id is currently revoked"
            ),
            Self::Store(error) => write!(formatter, "trusted phone store error: {error:?}"),
        }
    }
}

impl std::error::Error for PairedPhoneCandidateConfirmError {}

impl From<TrustedPhoneStoreError> for PairedPhoneCandidateConfirmError {
    fn from(value: TrustedPhoneStoreError) -> Self {
        Self::Store(value)
    }
}

impl PairedPhoneCandidate {
    /// Persists this candidate as a `TrustedPhoneIdentity` in `store`, under `label`. This
    /// is the ONLY way a paired phone becomes trusted: neither the pairing handshake nor
    /// the reconnection handshake ever calls `store.trust` on their own (task m4, contract
    /// section 4.6). Confirming a phone_id that is currently revoked fails closed rather
    /// than silently un-revoking it.
    ///
    /// Uses `store.trust_unless_revoked` -- a single atomic check-and-write -- instead of
    /// a separate `is_revoked` check followed by `trust`: that two-step sequence took the
    /// store's lock separately for each call, leaving a window in which a `revoke`
    /// landing between them was silently overwritten by the unconditional upsert in
    /// `trust` (time-of-check/time-of-use; found in native review readback, task m4b).
    pub fn confirm(
        &self,
        label: &str,
        store: &FileTrustedPhoneStore,
    ) -> Result<TrustedPhoneIdentity, PairedPhoneCandidateConfirmError> {
        let identity = TrustedPhoneIdentity::new(self.phone_id.clone(), label, self.spki.clone())?;
        match store.trust_unless_revoked(identity.clone())? {
            TrustUnlessRevoked::Trusted => Ok(identity),
            TrustUnlessRevoked::Refused => Err(PairedPhoneCandidateConfirmError::PhoneRevoked),
        }
    }
}

/// The outcome of a completed pairing handshake and proof: the still-live TLS stream (so
/// the caller can keep exchanging application data over the same session) and the phone
/// candidate the same connection's peer certificate carried, plus the QR and challenge
/// nonces of the phone's accepted CCP1 request (inputs of the SAS v1 pairing code, task d1).
pub struct CompletedPairingProof<S: Read + Write> {
    pub tls: StreamOwned<ServerConnection, S>,
    pub candidate: PairedPhoneCandidate,
    pub qr_nonce: Vec<u8>,
    pub challenge_nonce: Vec<u8>,
}

impl UsbTlsPairingProofServer {
    pub fn new(identity: &DesktopTlsIdentity) -> Result<Self, UsbTlsPairingProofError> {
        Ok(Self {
            connection: ServerConnection::new(server_config(identity)?)
                .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?,
        })
    }

    pub fn complete_handshake<S>(&mut self, stream: &mut S) -> Result<(), UsbTlsPairingProofError>
    where
        S: Read + Write,
    {
        while self.connection.is_handshaking() {
            self.connection
                .complete_io(stream)
                .map_err(map_complete_io_error)?;
        }
        Ok(())
    }

    pub fn complete_handshake_and_pairing_proof<S, R>(
        self,
        stream: S,
        issuer: &mut PairingQrIssuer<R>,
        timeout: Duration,
    ) -> Result<CompletedPairingProof<S>, UsbTlsPairingProofError>
    where
        S: Read + Write,
        R: PairingQrNonceGenerator,
    {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or(UsbTlsPairingProofError::Timeout)?;
        let mut connection = self.connection;
        let mut stream = stream;
        while connection.is_handshaking() {
            ensure_before_deadline(deadline)?;
            connection
                .complete_io(&mut stream)
                .map_err(map_complete_io_error)?;
            ensure_before_deadline(deadline)?;
        }

        let candidate = paired_phone_candidate(&connection)?;

        let mut tls = StreamOwned::new(connection, stream);
        let request = read_ccp1_request(&mut tls, deadline)?;
        validate_request(&request)?;
        let qr_nonce = request.qr_nonce().to_vec();
        let challenge_nonce = request.challenge_nonce().to_vec();
        consume_and_respond(&mut tls, issuer, request, deadline)?;
        Ok(CompletedPairingProof {
            tls,
            candidate,
            qr_nonce,
            challenge_nonce,
        })
    }

    pub fn connection(&self) -> &ServerConnection {
        &self.connection
    }
}

/// Reads the phone's client certificate off the just-completed handshake's own
/// `ServerConnection` -- never a separately supplied certificate -- and derives its
/// candidate identity. A missing peer certificate or one whose SPKI is not canonical P-256
/// fails closed: this never returns a default or placeholder candidate.
fn paired_phone_candidate(
    connection: &ServerConnection,
) -> Result<PairedPhoneCandidate, UsbTlsPairingProofError> {
    let end_entity = connection
        .peer_certificates()
        .and_then(|certificates| certificates.first())
        .ok_or_else(|| {
            UsbTlsPairingProofError::Tls("phone presented no client certificate".to_string())
        })?;
    let spki = accepted_client_spki(end_entity)
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?;
    let phone_id = phone_id_for_spki(&spki);
    Ok(PairedPhoneCandidate { phone_id, spki })
}

fn server_config(
    identity: &DesktopTlsIdentity,
) -> Result<Arc<ServerConfig>, UsbTlsPairingProofError> {
    build_server_config(identity, PhoneClientCertVerifier::pairing())
}

fn build_server_config(
    identity: &DesktopTlsIdentity,
    verifier: Arc<PhoneClientCertVerifier>,
) -> Result<Arc<ServerConfig>, UsbTlsPairingProofError> {
    let provider = rustls::crypto::ring::default_provider();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let cert = CertificateDer::from(identity.certificate_der().to_vec());
    let config = ServerConfig::builder_with_provider(provider.into())
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![cert], key)
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?;
    Ok(Arc::new(config))
}

/// Task m3: reconnection without a QR. Accepts only a phone the given `lookup` reports as
/// trusted and not revoked, rejecting unknown and revoked phones (and any lookup failure)
/// AT THE HANDSHAKE itself -- there is no `CCP1` exchange here, unlike
/// `complete_handshake_and_pairing_proof`. The returned `phone_id` is re-derived from the
/// peer certificate of this same completed connection (`paired_phone_candidate`, shared
/// with the pairing flow above), never trusted from a caller-supplied value.
pub fn complete_trusted_phone_handshake<S>(
    identity: &DesktopTlsIdentity,
    lookup: Arc<dyn TrustedPhoneLookup + Send + Sync>,
    stream: S,
    timeout: Duration,
) -> Result<CompletedTrustedHandshake<S>, UsbTlsPairingProofError>
where
    S: Read + Write,
{
    let deadline = Instant::now()
        .checked_add(timeout)
        .ok_or(UsbTlsPairingProofError::Timeout)?;
    let config = build_server_config(identity, PhoneClientCertVerifier::trusted_only(lookup))?;
    let mut connection = ServerConnection::new(config)
        .map_err(|error| UsbTlsPairingProofError::Tls(error.to_string()))?;
    let mut stream = stream;
    while connection.is_handshaking() {
        ensure_before_deadline(deadline)?;
        connection
            .complete_io(&mut stream)
            .map_err(map_complete_io_error)?;
        ensure_before_deadline(deadline)?;
    }

    let candidate = paired_phone_candidate(&connection)?;
    let tls = StreamOwned::new(connection, stream);
    Ok(CompletedTrustedHandshake {
        tls,
        phone_id: candidate.phone_id,
    })
}

/// The outcome of a completed trusted-reconnection handshake (task m3): the still-live TLS
/// stream and the `phone_id` its peer certificate authenticated, re-derived from the same
/// connection rather than trusted from a caller-supplied value.
pub struct CompletedTrustedHandshake<S: Read + Write> {
    pub tls: StreamOwned<ServerConnection, S>,
    pub phone_id: String,
}

fn map_complete_io_error(error: io::Error) -> UsbTlsPairingProofError {
    match error.kind() {
        io::ErrorKind::InvalidData => UsbTlsPairingProofError::Tls(error.to_string()),
        _ => UsbTlsPairingProofError::Io(error.to_string()),
    }
}

fn consume_and_respond<S: Read + Write, R: PairingQrNonceGenerator>(
    stream: &mut S,
    issuer: &mut PairingQrIssuer<R>,
    request: PairingProofRequest,
    deadline: Instant,
) -> Result<(), UsbTlsPairingProofError> {
    ensure_before_deadline(deadline)?;
    issuer
        .consume_issued_nonce(
            request.desktop_id(),
            request.qr_nonce(),
            current_epoch_seconds()?,
        )
        .map_err(UsbTlsPairingProofError::Issuer)?;
    ensure_before_deadline(deadline)?;
    let response = PairingProofFrame::response(PairingProofResponse::ok(request))
        .encode()
        .map_err(|_| UsbTlsPairingProofError::InvalidProof)?;
    stream
        .write_all(&response)
        .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
    ensure_before_deadline(deadline)?;
    stream
        .flush()
        .map_err(|error| UsbTlsPairingProofError::Io(error.to_string()))?;
    ensure_before_deadline(deadline)?;
    Ok(())
}

fn read_ccp1_request<S: Read>(
    stream: &mut S,
    deadline: Instant,
) -> Result<PairingProofRequest, UsbTlsPairingProofError> {
    let mut header = [0; 10];
    read_exact_before(stream, &mut header, deadline)?;
    let payload_len = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
    if payload_len > 1024 {
        return Err(UsbTlsPairingProofError::InvalidProof);
    }
    let mut frame = Vec::with_capacity(10 + payload_len);
    frame.extend_from_slice(&header);
    frame.resize(10 + payload_len, 0);
    read_exact_before(stream, &mut frame[10..], deadline)?;
    match PairingProofFrame::decode(&frame) {
        Ok(PairingProofFrame::Request(request)) => Ok(request),
        _ => Err(UsbTlsPairingProofError::InvalidProof),
    }
}

fn read_exact_before<S: Read>(
    stream: &mut S,
    mut buf: &mut [u8],
    deadline: Instant,
) -> Result<(), UsbTlsPairingProofError> {
    while !buf.is_empty() {
        if Instant::now() >= deadline {
            return Err(UsbTlsPairingProofError::Timeout);
        }
        match stream.read(buf) {
            Ok(0) => return Err(UsbTlsPairingProofError::Io("truncated proof".into())),
            Ok(read) => {
                ensure_before_deadline(deadline)?;
                buf = &mut buf[read..];
            }
            Err(error) => return Err(map_read_error(error)),
        }
    }
    ensure_before_deadline(deadline)
}

fn validate_request(request: &PairingProofRequest) -> Result<(), UsbTlsPairingProofError> {
    if !(32..=64).contains(&request.challenge_nonce().len())
        || !valid_token(request.session_id())
        || !valid_token(request.desktop_id())
    {
        return Err(UsbTlsPairingProofError::InvalidProof);
    }
    Ok(())
}

fn ensure_before_deadline(deadline: Instant) -> Result<(), UsbTlsPairingProofError> {
    if Instant::now() >= deadline {
        Err(UsbTlsPairingProofError::Timeout)
    } else {
        Ok(())
    }
}

fn valid_token(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn map_read_error(error: io::Error) -> UsbTlsPairingProofError {
    if matches!(
        error.kind(),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
    ) {
        UsbTlsPairingProofError::Timeout
    } else {
        UsbTlsPairingProofError::Io(error.to_string())
    }
}

fn current_epoch_seconds() -> Result<u64, UsbTlsPairingProofError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| UsbTlsPairingProofError::InvalidProof)
}
