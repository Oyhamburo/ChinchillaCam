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
    sync::Arc,
    time::{Duration, Instant},
};

use rustls::{ServerConnection, StreamOwned};

use crate::{
    complete_trusted_phone_handshake, phone_id_for_spki, read_session_frame, write_session_frame,
    DesktopTlsIdentity, FileTrustedPhoneStore, PairedPhoneCandidate,
    PairedPhoneCandidateConfirmError, PairingQrIssuer, PairingQrNonceGenerator, SessionFrame,
    SessionFramePayload, TlsSessionFrameError, TrustedPhoneLookup, UsbBulkIo,
    UsbTlsCiphertextStream, UsbTlsPairingProofError, UsbTlsPairingProofServer,
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
        }
    }
}

impl std::error::Error for PhoneConnectionError {}

/// A pairing candidate whose TLS channel is retained live (contract section 4.2) until the
/// caller explicitly [`confirm`](Self::confirm)s or [`reject`](Self::reject)s it.
pub struct PendingPairedPhoneSession<I: UsbBulkIo> {
    pub tls: StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>,
    pub candidate: PairedPhoneCandidate,
}

impl<I: UsbBulkIo> PendingPairedPhoneSession<I> {
    /// Persists `self.candidate` via `PairedPhoneCandidate::confirm` (atomic, refuses
    /// revoked). On success, returns an authenticated session that RETAINS the same live
    /// TLS stream (contract section 4.5: it can now carry `HandshakeHello`/`HandshakeAccept`
    /// for this same connection). On failure (revoked, or a store error), the channel is
    /// closed instead and the typed error is returned.
    pub fn confirm(
        self,
        label: &str,
        store: &FileTrustedPhoneStore,
    ) -> Result<AuthenticatedPhoneSession<I>, PairedPhoneCandidateConfirmError> {
        match self.candidate.confirm(label, store) {
            Ok(_identity) => Ok(AuthenticatedPhoneSession {
                tls: self.tls,
                phone_id: self.candidate.phone_id,
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

/// A connection that has passed authentication (either a confirmed pairing candidate, or a
/// trusted phone's successful reconnection handshake + `HandshakeHello`/`HandshakeAccept`
/// exchange) and still holds its live TLS stream, ready for `SessionFrame` traffic.
pub struct AuthenticatedPhoneSession<I: UsbBulkIo> {
    pub tls: StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>,
    pub phone_id: String,
}

/// Contract section 4.1, pairing branch: runs the existing pairing handshake + CCP1
/// (`UsbTlsPairingProofServer::complete_handshake_and_pairing_proof`) and returns a pending
/// value that retains the live TLS stream and the candidate until the caller confirms or
/// rejects it.
pub fn accept_phone_pairing_connection<I, R>(
    stream: UsbTlsCiphertextStream<I>,
    identity: &DesktopTlsIdentity,
    issuer: &mut PairingQrIssuer<R>,
    timeout: Duration,
) -> Result<PendingPairedPhoneSession<I>, PhoneConnectionError>
where
    I: UsbBulkIo,
    R: PairingQrNonceGenerator,
{
    let outcome = UsbTlsPairingProofServer::new(identity)
        .map_err(PhoneConnectionError::Pairing)?
        .complete_handshake_and_pairing_proof(stream, issuer, timeout)
        .map_err(PhoneConnectionError::Pairing)?;
    Ok(PendingPairedPhoneSession {
        tls: outcome.tls,
        candidate: outcome.candidate,
    })
}

/// Contract section 4.1/4.4, reconnection branch: completes the trusted-reconnection
/// handshake (`complete_trusted_phone_handshake`), then reads exactly one `SessionFrame`
/// with the same `timeout` budget (freshly timed from when the handshake finished, so a
/// slow handshake never eats into this budget). A `HandshakeHello` gets a
/// `HandshakeAccept` reply and an authenticated session; anything else fails closed (see
/// `PhoneConnectionError`).
pub fn accept_phone_reconnect_connection<I>(
    stream: UsbTlsCiphertextStream<I>,
    identity: &DesktopTlsIdentity,
    lookup: Arc<dyn TrustedPhoneLookup + Send + Sync>,
    timeout: Duration,
) -> Result<AuthenticatedPhoneSession<I>, PhoneConnectionError>
where
    I: UsbBulkIo,
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

    if !matches!(hello.payload(), SessionFramePayload::HandshakeHello { .. }) {
        let reject = SessionFrame::new(
            hello.sequence().saturating_add(1),
            hello.session_id().to_string(),
            SessionFramePayload::HandshakeReject {
                reason_code: "unexpected_frame".to_string(),
                message: "expected HandshakeHello".to_string(),
            },
        );
        // Best-effort: framing was intact, so the channel is still usable for one more
        // framed write, but the phone is rejected regardless of whether it observes this.
        let _ = write_session_frame(&mut tls, &reject);
        close_best_effort(tls);
        return Err(PhoneConnectionError::UnexpectedFirstFrame);
    }

    // `desktop_id` is derived the same way a phone's own stable id is (`phone_id_for_spki`
    // over the desktop's own SPKI), so Reconnect mode does not need a separate desktop-id
    // parameter: there is no `PairingQrIssuer` in this flow to source one from otherwise.
    let accept = SessionFrame::new(
        hello.sequence().saturating_add(1),
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
    })
}

/// Best-effort graceful close: send a TLS `close_notify` and try to flush it, ignoring any
/// write failure (the stream is being abandoned either way), then drop.
fn close_best_effort<I: UsbBulkIo>(
    mut tls: StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>,
) {
    tls.conn.send_close_notify();
    let _ = tls.conn.write_tls(&mut tls.sock);
}
