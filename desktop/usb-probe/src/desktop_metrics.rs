use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// Default sliding window for the locally measured fps rates (contract section 4.5).
pub const DEFAULT_METRICS_WINDOW: Duration = Duration::from_secs(1);

/// Error building a [`DesktopMetricsAggregator`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopMetricsError {
    /// The sliding window must be strictly positive.
    InvalidWindow,
}

impl std::fmt::Display for DesktopMetricsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidWindow => write!(f, "metrics window must be greater than zero"),
        }
    }
}

impl std::error::Error for DesktopMetricsError {}

/// Metrics the phone reported in a `MetricsSnapshot`, kept verbatim and clearly labelled as
/// reported by the phone. They are never merged into the locally measured values: the phone clock
/// is independent, so no end-to-end latency is derived from `captured_at_us` (see
/// [`DesktopMetricsAggregator`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PhoneReportedMetrics {
    /// Local instant at which this report was received (desktop clock).
    pub received_at: Instant,
    /// Phone capture timestamp in microseconds (phone clock, not comparable to `received_at`).
    pub captured_at_us: i64,
    /// Frames the phone reported dropping.
    pub dropped_frames: i32,
    /// Latency in milliseconds as measured and reported by the phone.
    pub latency_ms: i32,
    /// Frame rate as measured and reported by the phone.
    pub frame_rate: i32,
}

/// Immutable snapshot of the desktop metrics at a given instant. Locally measured values and the
/// phone-reported values are kept in separate fields and never merged.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DesktopMetricsSnapshot {
    /// Locally measured arrival rate over the window, or `None` with fewer than 2 samples.
    pub arrival_fps: Option<f64>,
    /// Locally measured decoded rate over the window, or `None` with fewer than 2 samples.
    pub decoded_fps: Option<f64>,
    /// Lifetime count of arrived chunks (not windowed).
    pub total_chunks: u64,
    /// Lifetime sum of arrived chunk bytes (not windowed).
    pub total_bytes: u64,
    /// Lifetime count of dropped chunks under saturation (not windowed).
    pub dropped_chunks: u64,
    /// Lifetime sum of dropped chunk bytes under saturation (not windowed).
    pub dropped_bytes: u64,
    /// Latest values reported by the phone, clearly labelled as reported.
    pub phone_reported: Option<PhoneReportedMetrics>,
}

/// Pure aggregator for desktop-side metrics (contract section 4.5). It owns no threads and no
/// clock: the caller injects an `Instant` on every call so tests drive it with synthetic time.
///
/// Arrival and decoded rates are measured over a sliding window as `(samples - 1) / span`, where
/// `span` is the elapsed time between the first and last retained sample. Fewer than 2 retained
/// samples cannot measure an interval, so the rate is reported as `None` (unknown) rather than 0.
///
/// Drop counters are fed as deltas via [`record_chunks_dropped`](Self::record_chunks_dropped)
/// (e.g. from the difference of `KeyframeGatedSink`'s absolute counters between calls) and
/// accumulate into lifetime totals.
///
/// No end-to-end latency is computed from the phone timestamps: the phone and desktop clocks are
/// independent and unsynchronised, so subtracting `captured_at_us` from a local instant would be
/// meaningless. The phone-reported values are therefore kept verbatim and labelled as reported.
#[derive(Debug, Clone)]
pub struct DesktopMetricsAggregator {
    window: Duration,
    arrivals: VecDeque<Instant>,
    decodes: VecDeque<Instant>,
    total_chunks: u64,
    total_bytes: u64,
    dropped_chunks: u64,
    dropped_bytes: u64,
    last_keepalive: Option<Instant>,
    phone_reported: Option<PhoneReportedMetrics>,
}

impl DesktopMetricsAggregator {
    /// Builds an aggregator with the given sliding window, which must be strictly positive.
    pub fn new(window: Duration) -> Result<Self, DesktopMetricsError> {
        if window.is_zero() {
            return Err(DesktopMetricsError::InvalidWindow);
        }
        Ok(Self {
            window,
            arrivals: VecDeque::new(),
            decodes: VecDeque::new(),
            total_chunks: 0,
            total_bytes: 0,
            dropped_chunks: 0,
            dropped_bytes: 0,
            last_keepalive: None,
            phone_reported: None,
        })
    }

    /// Builds an aggregator with the [`DEFAULT_METRICS_WINDOW`].
    pub fn with_default_window() -> Self {
        Self::new(DEFAULT_METRICS_WINDOW).expect("default window is positive")
    }

    /// Configured sliding window.
    pub fn window(&self) -> Duration {
        self.window
    }

    /// Records that a chunk of `bytes` bytes arrived at `now`.
    pub fn record_chunk_arrived(&mut self, now: Instant, bytes: u64) {
        self.arrivals.push_back(now);
        self.total_chunks = self.total_chunks.saturating_add(1);
        self.total_bytes = self.total_bytes.saturating_add(bytes);
    }

    /// Records that a frame was decoded at `now`.
    pub fn record_frame_decoded(&mut self, now: Instant) {
        self.decodes.push_back(now);
    }

    /// Accumulates dropped-chunk deltas (count and bytes) from the saturation gate.
    pub fn record_chunks_dropped(&mut self, count: u64, bytes: u64) {
        self.dropped_chunks = self.dropped_chunks.saturating_add(count);
        self.dropped_bytes = self.dropped_bytes.saturating_add(bytes);
    }

    /// Records that a keepalive was observed at `now` (optional; kept for liveness reporting).
    pub fn record_keepalive(&mut self, now: Instant) {
        self.last_keepalive = Some(now);
    }

    /// Latest keepalive instant observed, if any.
    pub fn last_keepalive(&self) -> Option<Instant> {
        self.last_keepalive
    }

    /// Records the phone-reported metrics fields from a `MetricsSnapshot`, received at `now`.
    pub fn record_phone_metrics(
        &mut self,
        now: Instant,
        captured_at_us: i64,
        dropped_frames: i32,
        latency_ms: i32,
        frame_rate: i32,
    ) {
        self.phone_reported = Some(PhoneReportedMetrics {
            received_at: now,
            captured_at_us,
            dropped_frames,
            latency_ms,
            frame_rate,
        });
    }

    /// Produces an immutable snapshot of the metrics as measured at `now`. Samples older than the
    /// window relative to `now` are pruned first.
    pub fn snapshot(&mut self, now: Instant) -> DesktopMetricsSnapshot {
        self.prune(now);
        DesktopMetricsSnapshot {
            arrival_fps: windowed_fps(&self.arrivals),
            decoded_fps: windowed_fps(&self.decodes),
            total_chunks: self.total_chunks,
            total_bytes: self.total_bytes,
            dropped_chunks: self.dropped_chunks,
            dropped_bytes: self.dropped_bytes,
            phone_reported: self.phone_reported,
        }
    }

    fn prune(&mut self, now: Instant) {
        let cutoff = now.checked_sub(self.window);
        prune_before(&mut self.arrivals, cutoff);
        prune_before(&mut self.decodes, cutoff);
    }
}

/// Drops every sample strictly older than `cutoff`. When `cutoff` is `None` (the window extends
/// before the measurable epoch) nothing is pruned.
fn prune_before(samples: &mut VecDeque<Instant>, cutoff: Option<Instant>) {
    let Some(cutoff) = cutoff else {
        return;
    };
    while let Some(front) = samples.front() {
        if *front < cutoff {
            samples.pop_front();
        } else {
            break;
        }
    }
}

/// Computes the fps over the retained samples as `(count - 1) / span_seconds`. Fewer than 2
/// samples, or a zero span, cannot measure an interval and yield `None`.
fn windowed_fps(samples: &VecDeque<Instant>) -> Option<f64> {
    if samples.len() < 2 {
        return None;
    }
    let first = *samples.front().expect("len >= 2");
    let last = *samples.back().expect("len >= 2");
    let span = last.duration_since(first).as_secs_f64();
    if span <= 0.0 {
        return None;
    }
    Some((samples.len() as f64 - 1.0) / span)
}
