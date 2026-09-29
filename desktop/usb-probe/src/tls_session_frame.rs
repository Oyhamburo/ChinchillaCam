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
            Err(error) => return Err(map_read_error(error)),
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

fn map_read_error(error: io::Error) -> TlsSessionFrameError {
    if matches!(
        error.kind(),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
    ) {
        TlsSessionFrameError::Timeout
    } else {
        TlsSessionFrameError::Io(error.to_string())
    }
}

fn saturating_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}
