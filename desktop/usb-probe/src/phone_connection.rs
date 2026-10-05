//! Task s2 (`odd/tasks/usb-authenticated-session.md`, contract section 4.1): the desktop's
//! own per-connection choice between the pairing flow and the trusted-reconnection flow.
//!
//! # Design note: two functions, not one `mode` enum (deviation from the task's sketch)
//!
//! The task sketch suggested a single `accept_phone_connection(stream, identity, mode,
//! timeout)` entry point with a `mode: Pairing { issuer } | Reconnect { lookup }` enum.
//! That was tried first: `Pairing` needs to carry `&mut PairingQrIssuer<R>`, generic over
//! `R: PairingQrNonceGenerator` (the crate already keeps this generic rather than hardcoding
//! `OsPairingQrNonceGenerator`, precisely so tests can substitute a deterministic `TestRng`).
//! A single `PhoneConnectionMode<'a, R>` enum shared by both variants forces `R` onto
//! `Reconnect` too, even though that variant never uses it -- and `R` then has NO
//! constraining evidence anywhere in a `Reconnect { .. }` value's type, so every call site
//! constructing one would fail to compile with "type annotations needed" unless it added an
//! arbitrary, unused turbofish (e.g. `::<_, OsPairingQrNonceGenerator>`). Splitting into two
//! top-level functions below avoids that wart entirely, and is arguably MORE faithful to the
//! contract's own section 4.1 wording, which already describes mode selection as "choose
//! which function to call" (`complete_handshake_and_pairing_proof` vs.
//! `complete_trusted_phone_handshake`) rather than as a shared enum. The contract's "modo
//! por conexión" requirement is preserved exactly: the caller still explicitly decides, per
//! connection and from its own state (a pairing window open or not), which of the two
//! functions to call.

use std::{
    fmt,
    io::{Read, Write},
    sync::Arc,
    time::{Duration, Instant},
};

use rustls::{ServerConnection, StreamOwned};

use crate::{
    complete_trusted_phone_handshake, pairing_short_code_v1, phone_id_for_spki, read_session_frame,
    write_session_frame, DesktopTlsIdentity, FileTrustedPhoneStore, PairedPhoneCandidate,
    PairedPhoneCandidateConfirmError, PairingQrIssuer, PairingQrNonceGenerator, PairingShortCode,
    SessionFrame, SessionFramePayload, ShortCodeError, TlsSessionFrameError, TrustedPhoneLookup,
    UsbTlsPairingProofError, UsbTlsPairingProofServer,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PhoneConnectionError {
    /// The pairing handshake/CCP1 exchange itself failed (contract section 4.1).
    Pairing(UsbTlsPairingProofError),
    /// The trusted-reconnection TLS handshake itself failed (unknown/revoked phone, a TLS
    /// error, or a timeout) -- section 4.4, before any `SessionFrame` is exchanged.
    Reconnect(UsbTlsPairingProofError),
    /// The reconnected phone's first frame decoded cleanly but was not `HandshakeHello`:
    /// rejected with a best-effort `HandshakeReject` before closing.
    UnexpectedFirstFrame,
    /// Reading the reconnected phone's first frame failed (framing/decode error, or an
    /// already-passed deadline). Per `tls_session_frame`'s own fail-closed contract the
    /// stream is not reused for a `HandshakeReject` in this case -- unlike
    /// `UnexpectedFirstFrame` above, where the read itself succeeded cleanly.
    InvalidHello(TlsSessionFrameError),
    /// The `HandshakeHello` decoded and was accepted, but writing `HandshakeAccept` back
    /// failed: the phone never observed acceptance, so contract section 4.4 says this must
    /// NOT be treated as an active session.
    AcceptWriteFailed(TlsSessionFrameError),
    /// The `HandshakeHello` decoded cleanly and its payload type was correct, but its
    /// `device_id` did not match the `phone_id` this connection's TLS client certificate
    /// authenticated (contract section 4.4 addendum, task s2b): rejected with a
    /// best-effort `HandshakeReject` before closing, same as `UnexpectedFirstFrame`.
    HelloDeviceIdMismatch,
    /// The accepted `HandshakeHello`'s sequence `h` is so close to `i32::MAX` that the
    /// session's next inbound/outbound sequence (`h + 1` / `h + 2`, contract section 4.1)
    /// would overflow. Carries the rejected `h`. Fails closed before replying.
    SessionSequenceOverflow(i32),
}

impl fmt::Display for PhoneConnectionError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Pairing(error) => write!(formatter, "phone pairing connection error: {error}"),
            Self::Reconnect(error) => {
                write!(formatter, "phone reconnect connection error: {error}")
            }
            Self::UnexpectedFirstFrame => write!(
                formatter,
                "reconnected phone's first frame was not HandshakeHello"
            ),
            Self::InvalidHello(error) => {
                write!(formatter, "reconnected phone HandshakeHello error: {error}")
            }
            Self::AcceptWriteFailed(error) => {
                write!(formatter, "writing HandshakeAccept failed: {error}")
            }
            Self::HelloDeviceIdMismatch => write!(
                formatter,
                "reconnected phone's HandshakeHello device_id did not match its TLS-authenticated phone_id"
            ),
            Self::SessionSequenceOverflow(sequence) => write!(
                formatter,
                "HandshakeHello sequence {sequence} is too close to i32::MAX for the session's next inbound/outbound sequence"
            ),
        }
    }
}

impl std::error::Error for PhoneConnectionError {}

/// A pairing candidate whose TLS channel is retained live (contract section 4.2) until the
/// caller explicitly [`confirm`](Self::confirm)s or [`reject`](Self::reject)s it. It also
/// keeps the QR and challenge nonces of the phone's CCP1 request so the caller can show the
/// SAS v1 code ([`short_code`](Self::short_code)) before confirming.
pub struct PendingPairedPhoneSession<S: Read + Write> {
    pub tls: StreamOwned<ServerConnection, S>,
    pub candidate: PairedPhoneCandidate,
    pub qr_nonce: Vec<u8>,
    pub challenge_nonce: Vec<u8>,
}

impl<S: Read + Write> PendingPairedPhoneSession<S> {
    /// The SAS v1 code the user compares with the phone's screen: built from this desktop's
    /// SPKI (`identity`), the candidate phone's SPKI and the two CCP1 nonces.
    pub fn short_code(
        &self,
        identity: &DesktopTlsIdentity,
    ) -> Result<PairingShortCode, ShortCodeError> {
        pairing_short_code_v1(
            identity.spki_der_p256(),
            &self.candidate.spki,
            &self.qr_nonce,
            &self.challenge_nonce,
        )
    }

    /// Persists `self.candidate` via `PairedPhoneCandidate::confirm` (atomic, refuses
    /// revoked). On success, returns an authenticated session that RETAINS the same live
    /// TLS stream (contract section 4.5: it can now carry `HandshakeHello`/`HandshakeAccept`
    /// for this same connection). On failure (revoked, or a store error), the channel is
    /// closed instead and the typed error is returned.
    pub fn confirm(
        self,
        label: &str,
        store: &FileTrustedPhoneStore,
    ) -> Result<AuthenticatedPhoneSession<S>, PairedPhoneCandidateConfirmError> {
        match self.candidate.confirm(label, store) {
            Ok(_identity) => Ok(AuthenticatedPhoneSession {
                tls: self.tls,
                phone_id: self.candidate.phone_id,
                session: None,
            }),
            Err(error) => {
                close_best_effort(self.tls);
                Err(error)
            }
        }
    }

    /// Closes the channel (best-effort TLS `close_notify`, then drop) without persisting
    /// anything.
    pub fn reject(self) {
        close_best_effort(self.tls);
    }
}

/// The session identity a reconnect handshake established from `HandshakeHello`/
/// `HandshakeAccept` (contract section 4.1): the `session_id` shared for the whole session,
/// plus the next sequence number expected on each direction. `HELLO` carries the initial
/// sequence `h`, so the next inbound (phone to desktop) frame is `h + 1` and -- because
/// `ACCEPT` uses `h + 1` -- the next outbound (desktop to phone) frame is `h + 2`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionIdentity {
    pub session_id: String,
    pub next_inbound_sequence: i32,
    pub next_outbound_sequence: i32,
}

/// A connection that has passed authentication (either a confirmed pairing candidate, or a
/// trusted phone's successful reconnection handshake + `HandshakeHello`/`HandshakeAccept`
/// exchange) and still holds its live TLS stream, ready for `SessionFrame` traffic.
///
/// `session` is `Some` only for the reconnect path, which establishes a session identity
/// through HELLO/ACCEPT (contract section 4.1). The pairing-`confirm` path has no HELLO, so
/// it carries `None`: a runtime session with a live sequence counter requires the reconnect
/// path (contract section 8 lists pairing-initiated sessions without HELLO as out of scope).
pub struct AuthenticatedPhoneSession<S: Read + Write> {
    pub tls: StreamOwned<ServerConnection, S>,
    pub phone_id: String,
    pub session: Option<SessionIdentity>,
}

/// Contract section 4.1, pairing branch: runs the existing pairing handshake + CCP1
/// (`UsbTlsPairingProofServer::complete_handshake_and_pairing_proof`) and returns a pending
/// value that retains the live TLS stream and the candidate until the caller confirms or
/// rejects it.
pub fn accept_phone_pairing_connection<S, R>(
    stream: S,
    identity: &DesktopTlsIdentity,
    issuer: &mut PairingQrIssuer<R>,
    timeout: Duration,
) -> Result<PendingPairedPhoneSession<S>, PhoneConnectionError>
where
    S: Read + Write,
    R: PairingQrNonceGenerator,
{
    let outcome = UsbTlsPairingProofServer::new(identity)
        .map_err(PhoneConnectionError::Pairing)?
        .complete_handshake_and_pairing_proof(stream, issuer, timeout)
        .map_err(PhoneConnectionError::Pairing)?;
    Ok(PendingPairedPhoneSession {
        tls: outcome.tls,
        candidate: outcome.candidate,
        qr_nonce: outcome.qr_nonce,
        challenge_nonce: outcome.challenge_nonce,
    })
}

/// Contract section 4.1/4.4, reconnection branch: completes the trusted-reconnection
/// handshake (`complete_trusted_phone_handshake`), then reads exactly one `SessionFrame`
/// with the same `timeout` budget (freshly timed from when the handshake finished, so a
/// slow handshake never eats into this budget). A `HandshakeHello` gets a
/// `HandshakeAccept` reply and an authenticated session; anything else fails closed (see
/// `PhoneConnectionError`).
pub fn accept_phone_reconnect_connection<S>(
    stream: S,
    identity: &DesktopTlsIdentity,
    lookup: Arc<dyn TrustedPhoneLookup + Send + Sync>,
    timeout: Duration,
) -> Result<AuthenticatedPhoneSession<S>, PhoneConnectionError>
where
    S: Read + Write,
{
    let handshake = complete_trusted_phone_handshake(identity, lookup, stream, timeout)
        .map_err(PhoneConnectionError::Reconnect)?;
    let mut tls = handshake.tls;

    // A fresh `timeout`-length budget for the HELLO/ACCEPT exchange specifically, timed
    // from NOW (after the handshake already completed) rather than from before the
    // handshake -- otherwise a slow handshake would silently eat into this budget.
    let deadline =
        Instant::now()
            .checked_add(timeout)
            .ok_or(PhoneConnectionError::InvalidHello(
                TlsSessionFrameError::Timeout,
            ))?;

    let hello = match read_session_frame(&mut tls, deadline) {
        Ok(frame) => frame,
        Err(error) => {
            // Fail closed WITHOUT a HandshakeReject: per `tls_session_frame`'s own
            // contract, a stream must not be reused for further SessionFrames after a
            // read error (framing/decode error or an already-passed deadline may have
            // desynchronized it) -- unlike the "wrong payload type" case below, where the
            // read itself succeeded cleanly.
            close_best_effort(tls);
            return Err(PhoneConnectionError::InvalidHello(error));
        }
    };

    let device_id = match hello.payload() {
        SessionFramePayload::HandshakeHello { device_id, .. } => device_id.clone(),
        _ => {
            let reject = SessionFrame::new(
                hello.sequence().saturating_add(1),
                hello.session_id().to_string(),
                SessionFramePayload::HandshakeReject {
                    reason_code: "unexpected_frame".to_string(),
                    message: "expected HandshakeHello".to_string(),
                },
            );
            // Best-effort: framing was intact, so the channel is still usable for one more
            // framed write, but the phone is rejected regardless of whether it observes
            // this.
            let _ = write_session_frame(&mut tls, &reject);
            close_best_effort(tls);
            return Err(PhoneConnectionError::UnexpectedFirstFrame);
        }
    };

    // Contract section 4.4 addendum (task s2b, agreed with the Android side): a
    // `HandshakeHello`'s `device_id` must equal the `phone_id` THIS connection's TLS
    // client certificate authenticated (`handshake.phone_id`, re-derived from the
    // connection itself -- see `complete_trusted_phone_handshake`'s own doc comment). A
    // trusted certificate only vouches for its own `phone_id`, never for whatever
    // `device_id` the application-layer HELLO happens to claim.
    if device_id != handshake.phone_id {
        let reject = SessionFrame::new(
            hello.sequence().saturating_add(1),
            hello.session_id().to_string(),
            SessionFramePayload::HandshakeReject {
                reason_code: "device_id_mismatch".to_string(),
                message: "HandshakeHello device_id does not match the TLS-authenticated phone_id"
                    .to_string(),
            },
        );
        // Best-effort, same reasoning as the unexpected-frame-type case above: framing
        // was intact, so a reply is still attempted, but the phone is rejected regardless
        // of whether it observes this.
        let _ = write_session_frame(&mut tls, &reject);
        close_best_effort(tls);
        return Err(PhoneConnectionError::HelloDeviceIdMismatch);
    }

    // Contract section 4.1: the session keeps the identity HELLO established. `HELLO` carries
    // sequence `h`; the next inbound frame is `h + 1` and the next outbound frame is `h + 2`
    // (because `ACCEPT` below uses `h + 1`). Checked arithmetic fails closed BEFORE replying
    // if `h` is too close to `i32::MAX`, rather than saturating into a reused sequence.
    let hello_sequence = hello.sequence();
    let next_inbound_sequence =
        hello_sequence
            .checked_add(1)
            .ok_or(PhoneConnectionError::SessionSequenceOverflow(
                hello_sequence,
            ))?;
    let next_outbound_sequence =
        hello_sequence
            .checked_add(2)
            .ok_or(PhoneConnectionError::SessionSequenceOverflow(
                hello_sequence,
            ))?;

    // `desktop_id` is derived the same way a phone's own stable id is (`phone_id_for_spki`
    // over the desktop's own SPKI), so Reconnect mode does not need a separate desktop-id
    // parameter: there is no `PairingQrIssuer` in this flow to source one from otherwise.
    let accept = SessionFrame::new(
        next_inbound_sequence,
        hello.session_id().to_string(),
        SessionFramePayload::HandshakeAccept {
            desktop_id: phone_id_for_spki(identity.spki_der_p256()),
            message: "trusted phone reconnected".to_string(),
        },
    );
    if let Err(error) = write_session_frame(&mut tls, &accept) {
        close_best_effort(tls);
        return Err(PhoneConnectionError::AcceptWriteFailed(error));
    }

    Ok(AuthenticatedPhoneSession {
        tls,
        phone_id: handshake.phone_id,
        session: Some(SessionIdentity {
            session_id: hello.session_id().to_string(),
            next_inbound_sequence,
            next_outbound_sequence,
        }),
    })
}

/// Best-effort graceful close: send a TLS `close_notify` and try to flush it, ignoring any
/// write failure (the stream is being abandoned either way), then drop. Shared with
/// `session_runtime` (task s2), which closes the same `StreamOwned` on every typed session
/// end, so the close logic lives in one place.
pub(crate) fn close_best_effort<S: Read + Write>(mut tls: StreamOwned<ServerConnection, S>) {
    tls.conn.send_close_notify();
    let _ = tls.conn.write_tls(&mut tls.sock);
}
