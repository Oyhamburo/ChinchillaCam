//! Task s2 (`odd/tasks/session-runtime.md`, contract section 4): the desktop's single-thread
//! step loop over a live authenticated session. `SessionRuntime::step(now)` reads one frame
//! (bounded by a short poll slice), validates the shared `sessionId`/strict-+1 sequence,
//! dispatches per contract section 4.4 (keepalive -> liveness only, video -> the receiver,
//! metrics/metadata -> latest snapshot, anything else -> protocol violation), then sends a
//! `Keepalive` when the liveness tracker says one is due and ends the session when the peer
//! is dead. Every exit is a typed [`SessionEnd`], after which the runtime is unusable and the
//! TLS stream is closed best-effort.
//!
//! The runtime holds its own next-inbound/next-outbound sequence (seeded from the session's
//! [`SessionIdentity`]), so both directions advance by exactly one across every frame type
//! (video, metadata, metrics, commands, keepalive), honoring the one-counter-per-direction
//! rule of contract section 4.1. The desktop video receiver only requires a strictly
//! increasing sequence (contract section 4.2); the strict +1 is enforced here at the envelope
//! level for every inbound frame.

use std::{
    collections::BTreeMap,
    fmt,
    io::{Read, Write},
    time::{Duration, Instant},
};

use rustls::{ServerConnection, StreamOwned};

use crate::{
    phone_connection::close_best_effort, read_session_frame_with_budgets, write_session_frame,
    AuthenticatedPhoneSession, DesktopReceiverError, DesktopVideoSessionReceiver, EncodedVideoSink,
    SessionFrame, SessionFramePayload, SessionLivenessError, SessionLivenessTracker,
    TlsSessionFrameError, VideoFrameKindClassifier, DEFAULT_DEAD_THRESHOLD,
    DEFAULT_KEEPALIVE_INTERVAL,
};

/// Default read poll slice: how long a single `step` waits for the next frame before giving
/// the caller control back to send keepalives / check liveness. Must stay strictly below
/// `keepalive_interval` so a step cannot sleep past a due keepalive.
pub const DEFAULT_POLL_SLICE: Duration = Duration::from_millis(200);

/// Default per-frame budget: once a frame has started arriving, how long the rest of it may
/// take before the read fails closed (contract `session-liveness` section 4.1's frame phase).
pub const DEFAULT_FRAME_BUDGET: Duration = Duration::from_secs(2);

/// Tunables for [`SessionRuntime`]. `poll_slice` must be strictly less than
/// `keepalive_interval` (validated at construction) so one blocking read slice never spans a
/// whole keepalive interval; `dead_threshold` must be strictly greater than
/// `keepalive_interval` (validated by the liveness tracker).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionRuntimeConfig {
    pub poll_slice: Duration,
    pub frame_budget: Duration,
    pub keepalive_interval: Duration,
    pub dead_threshold: Duration,
}

impl Default for SessionRuntimeConfig {
    fn default() -> Self {
        Self {
            poll_slice: DEFAULT_POLL_SLICE,
            frame_budget: DEFAULT_FRAME_BUDGET,
            keepalive_interval: DEFAULT_KEEPALIVE_INTERVAL,
            dead_threshold: DEFAULT_DEAD_THRESHOLD,
        }
    }
}

/// Why [`SessionRuntime::new`] refused to build a runtime.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionRuntimeError {
    /// The authenticated session carried no [`SessionIdentity`](crate::SessionIdentity): only
    /// the reconnect path (HELLO/ACCEPT) establishes one, so a pairing-`confirm` session
    /// cannot drive a runtime (contract section 8).
    MissingSessionIdentity,
    /// `poll_slice` was not strictly less than `keepalive_interval`.
    PollSliceNotBelowKeepaliveInterval {
        poll_slice: Duration,
        keepalive_interval: Duration,
    },
    /// The liveness tracker rejected the interval/threshold pair (threshold must be greater
    /// than the interval).
    Liveness(SessionLivenessError),
}

impl fmt::Display for SessionRuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::MissingSessionIdentity => write!(
                formatter,
                "authenticated session has no session identity (reconnect/HELLO path required)"
            ),
            Self::PollSliceNotBelowKeepaliveInterval {
                poll_slice,
                keepalive_interval,
            } => write!(
                formatter,
                "poll_slice ({poll_slice:?}) must be strictly less than keepalive_interval ({keepalive_interval:?})"
            ),
            Self::Liveness(error) => write!(formatter, "session liveness configuration error: {error}"),
        }
    }
}

impl std::error::Error for SessionRuntimeError {}

/// A typed reason the session ended. Once a runtime returns one of these, it is unusable: the
/// TLS stream has been closed best-effort and every later `step`/`send_command` returns the
/// same end.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEnd {
    /// No frame was received within `dead_threshold` (contract section 4.3).
    PeerDead,
    /// A frame violated the shared contract: wrong `sessionId`, a non-+1 sequence, an illegal
    /// frame type after the handshake, or a sequence counter exhausted.
    ProtocolViolation(String),
    /// The decoder or the decoded-frame sink rejected a video frame (contract section 4.3): a
    /// decoder/sink failure is not a protocol violation, so it carries its own cause.
    DecoderFailed(String),
    /// Reading the next frame failed for a non-idle reason (framing/decode/transport).
    ReadFailed(String),
    /// Writing an outbound frame (keepalive or command) failed.
    WriteFailed(String),
    /// Included for parity with the shared contract (section 4.6). The desktop runtime writes
    /// synchronously without an outbound queue, so it never produces this itself today; it
    /// exists so both sides name the same set of end causes.
    Backpressure,
    /// The local side closed the session on purpose ([`SessionRuntime::shutdown`]).
    LocalClose,
}

impl fmt::Display for SessionEnd {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PeerDead => write!(formatter, "session ended: peer is dead"),
            Self::ProtocolViolation(detail) => {
                write!(formatter, "session ended: protocol violation: {detail}")
            }
            Self::DecoderFailed(detail) => {
                write!(formatter, "session ended: decoder failed: {detail}")
            }
            Self::ReadFailed(detail) => write!(formatter, "session ended: read failed: {detail}"),
            Self::WriteFailed(detail) => write!(formatter, "session ended: write failed: {detail}"),
            Self::Backpressure => write!(formatter, "session ended: outbound backpressure"),
            Self::LocalClose => write!(formatter, "session ended: local close"),
        }
    }
}

impl std::error::Error for SessionEnd {}

/// Encoded H.264 payload byte length of a video frame, or 0 for any non-video payload. A
/// fragment reports only its own fragment bytes (the receiver reassembles whole chunks
/// elsewhere); whole chunks report their full encoded payload.
fn video_payload_byte_len(payload: &SessionFramePayload) -> u64 {
    match payload {
        SessionFramePayload::VideoChunk { h264_bytes, .. }
        | SessionFramePayload::VideoChunkV2 { h264_bytes, .. } => h264_bytes.len() as u64,
        SessionFramePayload::VideoChunkFragmentV1 {
            fragment_h264_bytes,
            ..
        } => fragment_h264_bytes.len() as u64,
        _ => 0,
    }
}

/// What a single [`SessionRuntime::step`] observed. A step reads at most one frame and may
/// also send one keepalive, so several of these flags can be set at once.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct StepOutcome {
    /// A valid frame of any type was received and dispatched this step.
    pub received_frame: bool,
    /// The received frame was a video payload delivered to the receiver/sink.
    pub received_video: bool,
    /// The received frame was a `Keepalive`.
    pub received_keepalive: bool,
    /// The received frame was a `MetricsSnapshot`.
    pub received_metrics: bool,
    /// The received frame was `StreamMetadata`.
    pub received_metadata: bool,
    /// A `Keepalive` was sent this step because one was due.
    pub sent_keepalive: bool,
}

/// The desktop's single-thread session loop. See the module docs.
pub struct SessionRuntime<S, C, K>
where
    S: Read + Write,
    C: VideoFrameKindClassifier,
    K: EncodedVideoSink,
{
    tls: Option<StreamOwned<ServerConnection, S>>,
    receiver: DesktopVideoSessionReceiver<C>,
    sink: K,
    config: SessionRuntimeConfig,
    session_id: String,
    next_inbound_sequence: i32,
    next_outbound_sequence: i32,
    tracker: SessionLivenessTracker,
    ended: Option<SessionEnd>,
    latest_metrics: Option<SessionFramePayload>,
    latest_metadata: Option<SessionFramePayload>,
    frames_received: u64,
    keepalives_received: u64,
    keepalives_sent: u64,
    video_frames_delivered: u64,
    video_fragments_received: u64,
    last_video_bytes_delivered: u64,
}

impl<S, C, K> SessionRuntime<S, C, K>
where
    S: Read + Write,
    C: VideoFrameKindClassifier,
    K: EncodedVideoSink,
{
    /// Builds a runtime from an authenticated reconnect session (its `session` must be
    /// `Some`), a video receiver, a sink, config, and the session's start instant (seeds the
    /// liveness tracker as though the handshake was the last traffic).
    pub fn new(
        session: AuthenticatedPhoneSession<S>,
        receiver: DesktopVideoSessionReceiver<C>,
        sink: K,
        config: SessionRuntimeConfig,
        start: Instant,
    ) -> Result<Self, SessionRuntimeError> {
        let identity = session
            .session
            .ok_or(SessionRuntimeError::MissingSessionIdentity)?;

        if config.poll_slice >= config.keepalive_interval {
            return Err(SessionRuntimeError::PollSliceNotBelowKeepaliveInterval {
                poll_slice: config.poll_slice,
                keepalive_interval: config.keepalive_interval,
            });
        }

        let tracker =
            SessionLivenessTracker::new(start, config.keepalive_interval, config.dead_threshold)
                .map_err(SessionRuntimeError::Liveness)?;

        Ok(Self {
            tls: Some(session.tls),
            receiver,
            sink,
            config,
            session_id: identity.session_id,
            next_inbound_sequence: identity.next_inbound_sequence,
            next_outbound_sequence: identity.next_outbound_sequence,
            tracker,
            ended: None,
            latest_metrics: None,
            latest_metadata: None,
            frames_received: 0,
            keepalives_received: 0,
            keepalives_sent: 0,
            video_frames_delivered: 0,
            video_fragments_received: 0,
            last_video_bytes_delivered: 0,
        })
    }

    pub fn sink(&self) -> &K {
        &self.sink
    }

    pub fn sink_mut(&mut self) -> &mut K {
        &mut self.sink
    }

    pub fn frames_received(&self) -> u64 {
        self.frames_received
    }

    pub fn keepalives_received(&self) -> u64 {
        self.keepalives_received
    }

    pub fn keepalives_sent(&self) -> u64 {
        self.keepalives_sent
    }

    pub fn video_frames_delivered(&self) -> u64 {
        self.video_frames_delivered
    }

    /// Lifetime count of `VIDEO_CHUNK_FRAGMENT_V1` wire frames dispatched to the receiver,
    /// counted apart from whole chunks (contract section 4.2 of `odd/tasks/review-followups.md`).
    pub fn video_fragments_received(&self) -> u64 {
        self.video_fragments_received
    }

    /// Lifetime count of whole encoded chunks the receiver pushed to the sink (one per
    /// `VideoChunk`/`VideoChunkV2`, one per fully reassembled fragment set), including chunks the
    /// sink accepted and then dropped under saturation. This, not
    /// [`video_frames_delivered`](Self::video_frames_delivered), is the arrival count for metrics.
    pub fn video_chunks_delivered(&self) -> u64 {
        self.receiver.chunks_delivered()
    }

    /// Lifetime total of encoded payload bytes of the chunks counted by
    /// [`video_chunks_delivered`](Self::video_chunks_delivered).
    pub fn video_chunk_bytes_delivered(&self) -> u64 {
        self.receiver.chunk_bytes_delivered()
    }

    /// Encoded byte length (the frame's H.264 payload) of the most recently delivered video
    /// frame. Refreshed only when a step delivers a video frame, so callers read it on the same
    /// step that saw `StepOutcome::received_video`. It is per wire frame (a fragment reports only
    /// its fragment bytes); whole-chunk arrival bytes come from
    /// [`video_chunk_bytes_delivered`](Self::video_chunk_bytes_delivered).
    pub fn last_video_bytes_delivered(&self) -> u64 {
        self.last_video_bytes_delivered
    }

    /// Latest `MetricsSnapshot` payload received, if any (contract section 4.4: metrics are
    /// kept as the latest snapshot rather than dispatched anywhere).
    pub fn latest_metrics(&self) -> Option<&SessionFramePayload> {
        self.latest_metrics.as_ref()
    }

    /// Latest `StreamMetadata` payload received, if any.
    pub fn latest_stream_metadata(&self) -> Option<&SessionFramePayload> {
        self.latest_metadata.as_ref()
    }

    /// Advances the session by one slice. See the module docs for the full sequence.
    pub fn step(&mut self, now: Instant) -> Result<StepOutcome, SessionEnd> {
        if let Some(end) = &self.ended {
            return Err(end.clone());
        }
        match self.step_inner(now) {
            Ok(outcome) => Ok(outcome),
            Err(end) => Err(self.end(end)),
        }
    }

    /// Sends a `CameraControlCommand` on the shared outbound counter (contract section 4.1),
    /// recording it as sent traffic.
    pub fn send_command(
        &mut self,
        command: String,
        arguments: BTreeMap<String, String>,
        now: Instant,
    ) -> Result<(), SessionEnd> {
        if let Some(end) = &self.ended {
            return Err(end.clone());
        }
        let payload = SessionFramePayload::CameraControlCommand { command, arguments };
        match self.write_frame(payload, now) {
            Ok(()) => Ok(()),
            Err(end) => Err(self.end(end)),
        }
    }

    /// Closes the session on purpose: best-effort `close_notify`, then returns
    /// [`SessionEnd::LocalClose`]. Consumes the runtime.
    pub fn shutdown(mut self) -> SessionEnd {
        if let Some(end) = self.ended.take() {
            // Already ended (and already closed): report the original cause.
            return end;
        }
        if let Some(tls) = self.tls.take() {
            close_best_effort(tls);
        }
        SessionEnd::LocalClose
    }

    fn step_inner(&mut self, now: Instant) -> Result<StepOutcome, SessionEnd> {
        let mut outcome = StepOutcome::default();

        let read = {
            let tls = self.tls.as_mut().expect("tls present while not ended");
            read_session_frame_with_budgets(tls, self.config.poll_slice, self.config.frame_budget)
        };

        match read {
            Ok(frame) => self.dispatch_frame(frame, now, &mut outcome)?,
            // No frame this slice: that is exactly what the idle phase reports.
            Err(TlsSessionFrameError::PeerIdle) => {}
            Err(other) => return Err(SessionEnd::ReadFailed(other.to_string())),
        }

        if self.tracker.is_peer_dead(now) {
            return Err(SessionEnd::PeerDead);
        }

        if self.tracker.should_send_keepalive(now) {
            self.write_frame(SessionFramePayload::Keepalive, now)?;
            self.keepalives_sent += 1;
            outcome.sent_keepalive = true;
        }

        Ok(outcome)
    }

    fn dispatch_frame(
        &mut self,
        frame: SessionFrame,
        now: Instant,
        outcome: &mut StepOutcome,
    ) -> Result<(), SessionEnd> {
        if frame.session_id() != self.session_id {
            return Err(SessionEnd::ProtocolViolation(format!(
                "unexpected session id: expected {}, got {}",
                self.session_id,
                frame.session_id()
            )));
        }
        if frame.sequence() != self.next_inbound_sequence {
            return Err(SessionEnd::ProtocolViolation(format!(
                "unexpected sequence: expected {}, got {}",
                self.next_inbound_sequence,
                frame.sequence()
            )));
        }

        // Any valid inbound frame is a liveness signal (contract section 4.3).
        self.tracker.record_received(now);
        self.frames_received += 1;
        outcome.received_frame = true;

        match frame.payload() {
            SessionFramePayload::Keepalive => {
                self.keepalives_received += 1;
                outcome.received_keepalive = true;
            }
            SessionFramePayload::VideoChunk { .. }
            | SessionFramePayload::VideoChunkV2 { .. }
            | SessionFramePayload::VideoChunkFragmentV1 { .. } => {
                self.last_video_bytes_delivered = video_payload_byte_len(frame.payload());
                self.receiver
                    .receive_session_frame(&frame, &mut self.sink)
                    .map_err(|error| match error {
                        // A sink/decoder rejection is not a protocol violation (contract
                        // section 4.3): it carries its own decoder cause.
                        DesktopReceiverError::SinkRejected(sink_error) => {
                            SessionEnd::DecoderFailed(format!(
                                "video sink rejected frame: {sink_error:?}"
                            ))
                        }
                        other => SessionEnd::ProtocolViolation(format!(
                            "video receiver rejected frame: {other:?}"
                        )),
                    })?;
                self.video_frames_delivered += 1;
                if matches!(
                    frame.payload(),
                    SessionFramePayload::VideoChunkFragmentV1 { .. }
                ) {
                    self.video_fragments_received += 1;
                }
                outcome.received_video = true;
            }
            SessionFramePayload::MetricsSnapshot { .. } => {
                self.latest_metrics = Some(frame.payload().clone());
                outcome.received_metrics = true;
            }
            SessionFramePayload::StreamMetadata { .. } => {
                self.latest_metadata = Some(frame.payload().clone());
                outcome.received_metadata = true;
            }
            SessionFramePayload::HandshakeHello { .. }
            | SessionFramePayload::HandshakeAccept { .. }
            | SessionFramePayload::HandshakeReject { .. }
            | SessionFramePayload::CameraControlCommand { .. } => {
                return Err(SessionEnd::ProtocolViolation(format!(
                    "unexpected frame type after session start at sequence {}",
                    frame.sequence()
                )));
            }
        }

        // The current frame consumed `next_inbound_sequence`; advance to the next expected
        // one. Exhausting the counter closes the session (contract section 4.1).
        self.next_inbound_sequence =
            self.next_inbound_sequence.checked_add(1).ok_or_else(|| {
                SessionEnd::ProtocolViolation("inbound sequence exhausted".to_string())
            })?;

        Ok(())
    }

    fn write_frame(
        &mut self,
        payload: SessionFramePayload,
        now: Instant,
    ) -> Result<(), SessionEnd> {
        let sequence = self.next_outbound_sequence;
        let frame = SessionFrame::new(sequence, self.session_id.clone(), payload);
        {
            let tls = self.tls.as_mut().expect("tls present while not ended");
            write_session_frame(tls, &frame)
                .map_err(|error| SessionEnd::WriteFailed(error.to_string()))?;
        }
        self.tracker.record_sent(now);
        // Advance the shared outbound counter; exhausting it closes the session.
        self.next_outbound_sequence = sequence.checked_add(1).ok_or_else(|| {
            SessionEnd::ProtocolViolation("outbound sequence exhausted".to_string())
        })?;
        Ok(())
    }

    fn end(&mut self, end: SessionEnd) -> SessionEnd {
        if let Some(existing) = &self.ended {
            return existing.clone();
        }
        if let Some(tls) = self.tls.take() {
            close_best_effort(tls);
        }
        self.ended = Some(end.clone());
        end
    }
}
