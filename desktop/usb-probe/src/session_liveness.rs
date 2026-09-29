//! A pure, thread-free liveness tracker for a session (contract
//! `odd/tasks/session-liveness.md`, section 4.5): decides when a side must send a
//! `Keepalive` frame and when it must consider its peer dead. Every method takes its own
//! `now: Instant` instead of reading the wall clock itself, so it is deterministic and
//! unit-testable with an injected clock; nothing here spawns a thread or sleeps. Driving it
//! on an actual schedule -- the loop that sends keepalives and reads frames in parallel -- is
//! a separate, currently out-of-scope concern (contract section 4.6).

use std::fmt;
use std::time::{Duration, Instant};

/// Contract section 4.2 default: how long a side may stay silent before it must send a
/// `Keepalive` frame.
pub const DEFAULT_KEEPALIVE_INTERVAL: Duration = Duration::from_secs(2);

/// Contract section 4.2 default: how long a side may go without receiving anything from its
/// peer before considering it dead (three intervals).
pub const DEFAULT_DEAD_THRESHOLD: Duration = Duration::from_secs(6);

/// See the module docs. Constructed with an explicit `now` and queried/updated with further
/// explicit `Instant`s -- never `Instant::now()` internally -- so tests can drive it with a
/// fake clock and production code can drive it with the real one identically.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SessionLivenessTracker {
    interval: Duration,
    dead_threshold: Duration,
    last_sent: Instant,
    last_received: Instant,
}

impl SessionLivenessTracker {
    /// `now` seeds both the last-sent and last-received clocks, so a freshly created tracker
    /// behaves as though a frame had just been exchanged (matching the moment a session
    /// becomes live, e.g. right after its handshake completes) instead of immediately
    /// reporting a keepalive as due or the peer as already dead.
    ///
    /// Contract section 4.2: `dead_threshold` must be strictly greater than `interval`, so a
    /// peer that is keeping up with its own keepalive cadence is never mistaken for dead.
    pub fn new(
        now: Instant,
        interval: Duration,
        dead_threshold: Duration,
    ) -> Result<Self, SessionLivenessError> {
        if dead_threshold <= interval {
            return Err(SessionLivenessError::ThresholdNotGreaterThanInterval {
                interval,
                dead_threshold,
            });
        }

        Ok(Self {
            interval,
            dead_threshold,
            last_sent: now,
            last_received: now,
        })
    }

    /// Records that a frame (of any kind) was sent at `now`, resetting the keepalive-due
    /// clock (contract section 4.4: "cada lado envía KEEPALIVE cuando no envió ningún frame
    /// durante un intervalo" -- any sent frame, not just `Keepalive` itself, counts).
    pub fn record_sent(&mut self, now: Instant) {
        self.last_sent = now;
    }

    /// Records that a frame (of any kind) was received at `now`, resetting the dead-peer
    /// clock (contract section 4.4: "cualquier frame recibido cuenta como señal de vida").
    pub fn record_received(&mut self, now: Instant) {
        self.last_received = now;
    }

    /// `true` once `interval` has passed since the last recorded send: this side must send a
    /// `Keepalive` frame to honor contract section 4.4's symmetric-send rule.
    pub fn should_send_keepalive(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.last_sent) >= self.interval
    }

    /// `true` once `dead_threshold` has passed since the last recorded receipt: the peer must
    /// be considered dead.
    pub fn is_peer_dead(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.last_received) >= self.dead_threshold
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionLivenessError {
    /// Contract section 4.2: `dead_threshold` must be strictly greater than `interval`.
    ThresholdNotGreaterThanInterval {
        interval: Duration,
        dead_threshold: Duration,
    },
}

impl fmt::Display for SessionLivenessError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ThresholdNotGreaterThanInterval {
                interval,
                dead_threshold,
            } => write!(
                formatter,
                "dead_threshold ({dead_threshold:?}) must be greater than interval ({interval:?})"
            ),
        }
    }
}

impl std::error::Error for SessionLivenessError {}
