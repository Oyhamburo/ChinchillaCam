//! Frames `SessionFrame` values over a live TLS application-data stream (contract
//! `odd/tasks/usb-authenticated-session.md`, section 4.3): each frame is a 4-byte
//! big-endian length prefix (`1..=1_048_576`) followed by exactly one CCSF v1
//! encoded `SessionFrame`.
//!
//! # Fail-closed contract
//!
//! Every error returned by [`read_session_frame`] or [`write_session_frame`] leaves the
//! stream desynchronized: a partial length prefix, a partial payload, or an
//! invalid/oversized declared length may already have consumed bytes the peer expects to
//! be part of a different frame boundary. Callers MUST NOT keep reading or writing
//! `SessionFrame`s on the same stream after an error. A caller that owns the underlying
//! TLS connection (e.g. a `StreamOwned<ServerConnection, _>` or
//! `StreamOwned<ClientConnection, _>`) is responsible for closing/shutting it down
//! instead of reusing it.
//!
//! # Deadline is soft, not hard (task s1b, native review finding R3-soft-deadline)
//!
//! `read_session_frame`'s `deadline` is only checked BETWEEN individual `Read::read` calls,
//! never while one is blocked in progress. A single blocking read that never returns (e.g. a
//! transport with no read timeout of its own, or one longer than `deadline`) can overrun
//! `deadline` by however long that one call takes to return. Callers that need a HARD upper
//! bound on wall-clock time must impose a read timeout on the underlying transport itself
//! (as `FramedUsbStream`/`UsbTlsCiphertextStream` already do via `FrameTransferBudget`);
//! `deadline` here only bounds the number of retries once a read call actually returns.

use std::{
    fmt,
    io::{self, Read, Write},
    time::Instant,
};

use crate::{SessionFrame, SessionFrameCodec, SessionFrameDecodeError, SessionFrameEncodeError};

const LENGTH_PREFIX_BYTES: usize = 4;

/// Same bound as `SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE`, reused (not duplicated) as
/// the framing-layer limit so the two never drift apart.
const MAX_TLS_SESSION_FRAME_LEN: usize = SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TlsSessionFrameError {
    Io(String),
    Timeout,
    /// The 4-byte big-endian length prefix was zero or exceeded
    /// `MAX_TLS_SESSION_FRAME_LEN`. Carries the declared (rejected) length.
    InvalidLength(u32),
    /// The stream closed (`Read::read` returned `Ok(0)`) before the named section
    /// (`"length prefix"` or `"payload"`) was fully read.
    TruncatedFrame(&'static str),
    Encode(SessionFrameEncodeError),
    Decode(SessionFrameDecodeError),
}

impl fmt::Display for TlsSessionFrameError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "TLS session frame transport error: {error}"),
            Self::Timeout => write!(formatter, "TLS session frame read timed out"),
            Self::InvalidLength(length) => write!(
                formatter,
                "TLS session frame length prefix {length} is out of the allowed 1..={MAX_TLS_SESSION_FRAME_LEN} range"
            ),
            Self::TruncatedFrame(field) => write!(
                formatter,
                "TLS session frame stream closed while reading {field}"
            ),
            Self::Encode(error) => write!(formatter, "TLS session frame encode error: {error:?}"),
            Self::Decode(error) => write!(formatter, "TLS session frame decode error: {error:?}"),
        }
    }
}

impl std::error::Error for TlsSessionFrameError {}

/// Writes `frame` to `stream` as one length-prefixed CCSF v1 frame (contract section
/// 4.3). `SessionFrameCodec::encode` already refuses to produce an encoding larger than
/// `MAX_TLS_SESSION_FRAME_LEN` (and a CCSF v1 header is never empty), so the length check
/// here is a defense-in-depth invariant rather than a reachable branch today; it exists so
/// this function never silently writes a length prefix outside the framing contract even
/// if that codec-level guarantee ever changes.
///
/// On `Err`, `stream` MUST NOT be reused for further `SessionFrame`s: see the module docs.
pub fn write_session_frame<S: Write>(
    stream: &mut S,
    frame: &SessionFrame,
) -> Result<(), TlsSessionFrameError> {
    let encoded = SessionFrameCodec::encode(frame).map_err(TlsSessionFrameError::Encode)?;
    let len = encoded.len();
    if len == 0 || len > MAX_TLS_SESSION_FRAME_LEN {
        return Err(TlsSessionFrameError::InvalidLength(saturating_u32(len)));
    }
    let len_prefix = u32::try_from(len)
        .expect("len already bounded by MAX_TLS_SESSION_FRAME_LEN, which fits in u32");

    stream
        .write_all(&len_prefix.to_be_bytes())
        .map_err(|error| TlsSessionFrameError::Io(error.to_string()))?;
    stream
        .write_all(&encoded)
        .map_err(|error| TlsSessionFrameError::Io(error.to_string()))?;
    stream
        .flush()
        .map_err(|error| TlsSessionFrameError::Io(error.to_string()))
}

/// Reads exactly one length-prefixed CCSF v1 frame from `stream` (contract section 4.3),
/// bounded by an absolute `deadline`. The declared length is validated against
/// `1..=MAX_TLS_SESSION_FRAME_LEN` BEFORE allocating a buffer for it, so a corrupt or
/// hostile oversized length prefix never drives an allocation. Decoding reuses
/// `SessionFrameCodec::decode_with_limit` with that same bound.
///
/// On `Err`, `stream` MUST NOT be reused for further `SessionFrame`s: see the module docs.
pub fn read_session_frame<S: Read>(
    stream: &mut S,
    deadline: Instant,
) -> Result<SessionFrame, TlsSessionFrameError> {
    let mut length_prefix = [0u8; LENGTH_PREFIX_BYTES];
    read_exact_before_deadline(stream, &mut length_prefix, deadline, "length prefix")?;
    let declared_len = u32::from_be_bytes(length_prefix);
    if declared_len == 0 || declared_len as usize > MAX_TLS_SESSION_FRAME_LEN {
        return Err(TlsSessionFrameError::InvalidLength(declared_len));
    }

    let mut payload = vec![0u8; declared_len as usize];
    read_exact_before_deadline(stream, &mut payload, deadline, "payload")?;

    SessionFrameCodec::decode_with_limit(&payload, MAX_TLS_SESSION_FRAME_LEN)
        .map_err(TlsSessionFrameError::Decode)
}

/// Reuses the crate's deadline-checking pattern (see `read_exact_before` in
/// `usb_tls_pairing_proof.rs`): check the absolute deadline before every blocking read
/// (so an already-elapsed deadline never issues a read at all) and again after every
/// partial read, failing closed with a typed `Timeout` rather than blocking past it.
/// Reimplemented locally (rather than exposed from `usb_tls_pairing_proof.rs`) because
/// that module's helper returns `UsbTlsPairingProofError`, a different typed-error domain;
/// only the visibility of that helper -- not its signature -- was available to change.
///
/// Task s1b (native review of s1, finding R3-error-kind-mapping) changed two things here
/// relative to that reused pattern (and relative to `usb_tls_pairing_proof.rs`'s own
/// `read_exact_before`, which was deliberately left as-is -- see "Evidencia s1b"):
/// `ErrorKind::Interrupted` is now retried transparently, exactly like
/// `std::io::Read::read_exact` does, instead of surfacing as `TlsSessionFrameError::Io`; and
/// `WouldBlock`/`TimedOut` are retried too -- looping back to the top of the loop, the only
/// place that decides `Timeout`, once `deadline` has actually passed -- instead of being
/// mapped to `Timeout` on the very first occurrence regardless of how much of `deadline`
/// actually remains. Both retries still pass back through the same deadline check on their
/// next iteration, so a signal storm or a transport stuck on `WouldBlock` past `deadline`
/// still fails closed with `Timeout` rather than retrying forever (see
/// `would_block_past_the_deadline_still_times_out` in `tls_session_frame_test.rs`).
fn read_exact_before_deadline<S: Read>(
    stream: &mut S,
    mut buf: &mut [u8],
    deadline: Instant,
    field: &'static str,
) -> Result<(), TlsSessionFrameError> {
    while !buf.is_empty() {
        ensure_before_deadline(deadline)?;
        match stream.read(buf) {
            Ok(0) => return Err(TlsSessionFrameError::TruncatedFrame(field)),
            Ok(read) => {
                ensure_before_deadline(deadline)?;
                buf = &mut buf[read..];
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {
                // Consumed no bytes; retry the same (unconsumed) `buf` slice, like
                // `std::io::Read::read_exact` does.
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                // Not necessarily a real timeout yet: a non-blocking transport (or one with
                // its own shorter internal timeout) can report this well before our
                // absolute `deadline`. Loop back to the top instead of deciding `Timeout`
                // here.
            }
            Err(error) => return Err(TlsSessionFrameError::Io(error.to_string())),
        }
    }
    Ok(())
}

fn ensure_before_deadline(deadline: Instant) -> Result<(), TlsSessionFrameError> {
    if Instant::now() >= deadline {
        Err(TlsSessionFrameError::Timeout)
    } else {
        Ok(())
    }
}

fn saturating_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}
