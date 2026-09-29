//! Task l2 (`odd/tasks/session-liveness.md`, contract section 4.5): a pure, thread-free
//! liveness tracker with an injected clock (`Instant` arguments throughout, never reading
//! the wall clock itself), used identically on both sides of a session to decide when to
//! send a `Keepalive` frame and when to consider the peer dead.

use std::time::{Duration, Instant};

use usb_probe::SessionLivenessTracker;

#[test]
fn sends_keepalive_after_interval_of_silence() {
    let t0 = Instant::now();
    let interval = Duration::from_millis(50);
    let dead_threshold = Duration::from_millis(200);
    let mut tracker = SessionLivenessTracker::new(t0, interval, dead_threshold).unwrap();

    assert!(!tracker.should_send_keepalive(t0 + Duration::from_millis(49)));
    assert!(tracker.should_send_keepalive(t0 + Duration::from_millis(50)));

    // Sending resets the interval clock: silence must start counting again from `sent_at`,
    // not from `t0`.
    let sent_at = t0 + Duration::from_millis(30);
    tracker.record_sent(sent_at);
    assert!(!tracker.should_send_keepalive(sent_at + Duration::from_millis(49)));
    assert!(tracker.should_send_keepalive(sent_at + Duration::from_millis(50)));
}

#[test]
fn declares_dead_peer_after_threshold() {
    let t0 = Instant::now();
    let interval = Duration::from_millis(50);
    let dead_threshold = Duration::from_millis(200);
    let tracker = SessionLivenessTracker::new(t0, interval, dead_threshold).unwrap();

    assert!(!tracker.is_peer_dead(t0 + Duration::from_millis(199)));
    assert!(tracker.is_peer_dead(t0 + Duration::from_millis(200)));
}

#[test]
fn receiving_any_frame_resets_peer_deadline() {
    let t0 = Instant::now();
    let interval = Duration::from_millis(50);
    let dead_threshold = Duration::from_millis(200);
    let mut tracker = SessionLivenessTracker::new(t0, interval, dead_threshold).unwrap();

    let received_at = t0 + Duration::from_millis(199);
    assert!(!tracker.is_peer_dead(received_at));
    tracker.record_received(received_at);

    // Without the reset this would already be >= 200ms past t0 (the original baseline).
    assert!(!tracker.is_peer_dead(received_at + Duration::from_millis(199)));
    assert!(tracker.is_peer_dead(received_at + Duration::from_millis(200)));
}

#[test]
fn dead_threshold_must_exceed_interval() {
    let t0 = Instant::now();

    let equal =
        SessionLivenessTracker::new(t0, Duration::from_millis(50), Duration::from_millis(50));
    assert!(equal.is_err());

    let less =
        SessionLivenessTracker::new(t0, Duration::from_millis(50), Duration::from_millis(49));
    assert!(less.is_err());
}
