//! Task q3b (`odd/tasks/session-pipeline-wiring.md`, contract section 4): composes the desktop
//! session loop into a single driveable pipeline. It owns a [`SessionRuntime`] whose sink is a
//! [`KeyframeGatedSink`] wrapping a [`DecodingEncodedVideoSink`] (so received video is decoded,
//! and saturation drops frames until the next keyframe instead of ending the session) plus a
//! [`DesktopMetricsAggregator`] that measures arrival/decoded rates locally and keeps the
//! phone-reported values labelled as reported.
//!
//! Every [`step`](DesktopSessionPipeline::step) advances the runtime by one slice and then feeds
//! the aggregator from additive, side-channel accessors rather than re-deriving anything the
//! runtime already owns:
//! - arrivals come from the runtime's lifetime whole-chunk counters
//!   (`SessionRuntime::video_chunks_delivered`/`video_chunk_bytes_delivered`) by delta, so a
//!   fragmented chunk counts once when its last fragment reassembles (task f2 of
//!   `odd/tasks/review-followups.md`). An arrival is a chunk the receiver delivered to the sink,
//!   even if the keyframe gate then drops it; such drops are also counted as dropped;
//! - decoded frames come from the decoder's lifetime `frames_emitted` counter deltas;
//! - drops come from the gated sink's `dropped_chunks`/`dropped_bytes` counter deltas;
//! - phone metrics come from the latest `MetricsSnapshot` when the step received one.

use std::{
    collections::BTreeMap,
    io::{Read, Write},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

use crate::{
    AuthenticatedPhoneSession, DecodedFrameCounter, DecodingEncodedVideoSink,
    DesktopMetricsAggregator, DesktopMetricsError, DesktopMetricsSnapshot,
    DesktopVideoSessionReceiver, KeyframeGatedSink, SessionEnd, SessionFramePayload,
    SessionRuntime, SessionRuntimeConfig, SessionRuntimeError, StepOutcome, VideoDecoder,
    VideoFrameKindClassifier,
};

/// The sink type the pipeline composes: decode received chunks, but drop delta frames until the
/// next keyframe under saturation so the session survives (contract section 4.4).
type PipelineSink<D> = KeyframeGatedSink<DecodingEncodedVideoSink<D>>;

/// Why [`DesktopSessionPipeline::new`] refused to build a pipeline.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopSessionPipelineError {
    /// The underlying [`SessionRuntime`] rejected its configuration or session.
    Runtime(SessionRuntimeError),
    /// The metrics aggregator rejected its window.
    Metrics(DesktopMetricsError),
}

impl std::fmt::Display for DesktopSessionPipelineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Runtime(error) => write!(f, "session runtime error: {error}"),
            Self::Metrics(error) => write!(f, "desktop metrics error: {error}"),
        }
    }
}

impl std::error::Error for DesktopSessionPipelineError {}

/// What a single [`DesktopSessionPipeline::step`] observed: the runtime's [`StepOutcome`] plus the
/// metric deltas this step fed into the aggregator.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct PipelineStep {
    /// The underlying runtime outcome for this slice.
    pub outcome: StepOutcome,
    /// Frames the decoder emitted during this step.
    pub frames_decoded: u64,
    /// Chunks the gated sink dropped during this step.
    pub chunks_dropped: u64,
}

/// The desktop session pipeline: runtime + gated decoding sink + local metrics. See module docs.
pub struct DesktopSessionPipeline<S, C, D>
where
    S: Read + Write,
    C: VideoFrameKindClassifier,
    D: VideoDecoder + DecodedFrameCounter,
{
    runtime: SessionRuntime<S, C, PipelineSink<D>>,
    metrics: DesktopMetricsAggregator,
    prev_frames_emitted: u64,
    prev_chunks_delivered: u64,
    prev_chunk_bytes_delivered: u64,
    prev_dropped_chunks: u64,
    prev_dropped_bytes: u64,
    last_now: Option<Instant>,
}

impl<S, C, D> DesktopSessionPipeline<S, C, D>
where
    S: Read + Write,
    C: VideoFrameKindClassifier,
    D: VideoDecoder + DecodedFrameCounter,
{
    /// Builds the pipeline from an authenticated reconnect session, a video receiver, a decoder,
    /// the runtime config, the metrics sliding window, and the session start instant.
    pub fn new(
        session: AuthenticatedPhoneSession<S>,
        receiver: DesktopVideoSessionReceiver<C>,
        decoder: D,
        config: SessionRuntimeConfig,
        metrics_window: Duration,
        start: Instant,
    ) -> Result<Self, DesktopSessionPipelineError> {
        let sink = KeyframeGatedSink::new(DecodingEncodedVideoSink::new(decoder));
        let runtime = SessionRuntime::new(session, receiver, sink, config, start)
            .map_err(DesktopSessionPipelineError::Runtime)?;
        let metrics = DesktopMetricsAggregator::new(metrics_window)
            .map_err(DesktopSessionPipelineError::Metrics)?;
        Ok(Self {
            runtime,
            metrics,
            prev_frames_emitted: 0,
            prev_chunks_delivered: 0,
            prev_chunk_bytes_delivered: 0,
            prev_dropped_chunks: 0,
            prev_dropped_bytes: 0,
            last_now: None,
        })
    }

    /// Advances the runtime by one slice and feeds the metrics aggregator from the step's
    /// observations. A [`SessionEnd`] propagates unchanged.
    pub fn step(&mut self, now: Instant) -> Result<PipelineStep, SessionEnd> {
        self.last_now = Some(now);
        let outcome = self.runtime.step(now)?;

        // Whole chunks only: a fragment that does not complete a chunk leaves these unchanged.
        // A step reads at most one frame, so at most one chunk arrives; the byte delta is
        // attributed to the first new chunk so lifetime totals stay exact either way.
        let chunks_now = self.runtime.video_chunks_delivered();
        let chunk_bytes_now = self.runtime.video_chunk_bytes_delivered();
        let chunks_arrived = chunks_now.saturating_sub(self.prev_chunks_delivered);
        let mut bytes_arrived = chunk_bytes_now.saturating_sub(self.prev_chunk_bytes_delivered);
        for _ in 0..chunks_arrived {
            self.metrics.record_chunk_arrived(now, bytes_arrived);
            bytes_arrived = 0;
        }
        self.prev_chunks_delivered = chunks_now;
        self.prev_chunk_bytes_delivered = chunk_bytes_now;
        if outcome.received_keepalive {
            self.metrics.record_keepalive(now);
        }

        let frames_now = self.runtime.sink().inner().decoder().frames_emitted();
        let frames_decoded = frames_now.saturating_sub(self.prev_frames_emitted);
        for _ in 0..frames_decoded {
            self.metrics.record_frame_decoded(now);
        }
        self.prev_frames_emitted = frames_now;

        let dropped_chunks_now = self.runtime.sink().dropped_chunks();
        let dropped_bytes_now = self.runtime.sink().dropped_bytes();
        let chunks_dropped = dropped_chunks_now.saturating_sub(self.prev_dropped_chunks);
        let bytes_dropped = dropped_bytes_now.saturating_sub(self.prev_dropped_bytes);
        if chunks_dropped > 0 || bytes_dropped > 0 {
            self.metrics
                .record_chunks_dropped(chunks_dropped, bytes_dropped);
        }
        self.prev_dropped_chunks = dropped_chunks_now;
        self.prev_dropped_bytes = dropped_bytes_now;

        if outcome.received_metrics {
            if let Some(SessionFramePayload::MetricsSnapshot {
                captured_at_us,
                dropped_frames,
                latency_ms,
                frame_rate,
            }) = self.runtime.latest_metrics()
            {
                self.metrics.record_phone_metrics(
                    now,
                    *captured_at_us,
                    *dropped_frames,
                    *latency_ms,
                    *frame_rate,
                );
            }
        }

        Ok(PipelineStep {
            outcome,
            frames_decoded,
            chunks_dropped,
        })
    }

    /// Sends a camera-control command on the runtime's shared outbound sequence. Returns its
    /// session end unchanged if the send fails or the session has already ended.
    pub fn send_command(
        &mut self,
        command: String,
        arguments: BTreeMap<String, String>,
        now: Instant,
    ) -> Result<(), SessionEnd> {
        self.runtime.send_command(command, arguments, now)
    }

    /// Drains pending inbound camera-control commands in arrival order. The runtime bounds
    /// this inbox to eight, dropping oldest entries on overflow without ending the session.
    pub fn take_controls(&mut self) -> Vec<(String, BTreeMap<String, String>)> {
        self.runtime.take_controls()
    }

    /// Snapshot of the desktop metrics measured at `now`.
    pub fn metrics(&mut self, now: Instant) -> DesktopMetricsSnapshot {
        self.last_now = Some(now);
        self.metrics.snapshot(now)
    }

    /// Lifetime count of `VIDEO_CHUNK_FRAGMENT_V1` wire frames received, counted apart from the
    /// whole-chunk arrivals reported in the metrics.
    pub fn video_fragments_received(&self) -> u64 {
        self.runtime.video_fragments_received()
    }

    /// Immutable access to the composed decoder.
    pub fn decoder(&self) -> &D {
        self.runtime.sink().inner().decoder()
    }

    /// Mutable access to the composed decoder.
    pub fn decoder_mut(&mut self) -> &mut D {
        self.runtime.sink_mut().inner_mut().decoder_mut()
    }

    /// Drives the pipeline until `stop` is set or the session ends, reading the clock on each
    /// step. A natural end returns its [`SessionEnd`]; a requested stop returns
    /// [`SessionEnd::LocalClose`] without closing the transport (call
    /// [`shutdown`](Self::shutdown) to release it).
    pub fn run_until(
        &mut self,
        stop: &AtomicBool,
        mut clock: impl FnMut() -> Instant,
    ) -> SessionEnd {
        while !stop.load(Ordering::SeqCst) {
            if let Err(end) = self.step(clock()) {
                return end;
            }
        }
        SessionEnd::LocalClose
    }

    /// Closes the session on purpose and returns the end cause together with a final metrics
    /// snapshot taken at the last observed instant.
    pub fn shutdown(mut self) -> (SessionEnd, DesktopMetricsSnapshot) {
        let now = self.last_now.unwrap_or_else(Instant::now);
        let snapshot = self.metrics.snapshot(now);
        let end = self.runtime.shutdown();
        (end, snapshot)
    }
}
