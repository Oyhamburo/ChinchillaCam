//! Desktop-measured metrics aggregator (contract section 4.5): fps of arrival and decoded
//! frames over a sliding window with an injected clock, accumulating drop/byte counters, plus the
//! values reported by the phone kept clearly labelled as reported (never merged into the locally
//! measured values). The aggregator is pure: no threads and the caller injects `Instant` on every
//! call.

use std::time::{Duration, Instant};

use usb_probe::{DesktopMetricsAggregator, DesktopMetricsError};

fn base() -> Instant {
    Instant::now()
}

#[test]
fn aggregator_reports_arrival_and_decoded_fps() {
    let mut aggregator = DesktopMetricsAggregator::new(Duration::from_secs(1)).unwrap();
    let start = base();

    // 31 arrivals spaced 1/30 s apart span exactly 1 s, so (31 - 1) / 1 s = 30 fps.
    for i in 0..=30u32 {
        let now = start + Duration::from_micros(u64::from(i) * 1_000_000 / 30);
        aggregator.record_chunk_arrived(now, 1_024);
    }
    // 16 decodes spaced 1/15 s apart span exactly 1 s, so (16 - 1) / 1 s = 15 fps.
    for i in 0..=15u32 {
        let now = start + Duration::from_micros(u64::from(i) * 1_000_000 / 15);
        aggregator.record_frame_decoded(now);
    }

    let snapshot = aggregator.snapshot(start + Duration::from_secs(1));
    let arrival_fps = snapshot.arrival_fps.expect("arrival fps known");
    let decoded_fps = snapshot.decoded_fps.expect("decoded fps known");
    assert!(
        (arrival_fps - 30.0).abs() < 0.5,
        "arrival fps {arrival_fps} expected near 30"
    );
    assert!(
        (decoded_fps - 15.0).abs() < 0.5,
        "decoded fps {decoded_fps} expected near 15"
    );
    assert_eq!(snapshot.total_chunks, 31);
    assert_eq!(snapshot.total_bytes, 31 * 1_024);

    // Sliding the window past every sample drops the old samples, so fps becomes unknown.
    let later = aggregator.snapshot(start + Duration::from_secs(10));
    assert_eq!(later.arrival_fps, None);
    assert_eq!(later.decoded_fps, None);
    // Lifetime counters are not windowed.
    assert_eq!(later.total_chunks, 31);
}

#[test]
fn phone_reported_metrics_are_labelled() {
    let mut aggregator = DesktopMetricsAggregator::with_default_window();
    let start = base();
    let received_at = start + Duration::from_millis(500);

    aggregator.record_phone_metrics(received_at, 1_234_567, 7, 42, 30);

    let snapshot = aggregator.snapshot(start + Duration::from_secs(1));
    // Phone values live only in the clearly separate `phone_reported` field.
    let phone = snapshot.phone_reported.expect("phone metrics reported");
    assert_eq!(phone.received_at, received_at);
    assert_eq!(phone.captured_at_us, 1_234_567);
    assert_eq!(phone.dropped_frames, 7);
    assert_eq!(phone.latency_ms, 42);
    assert_eq!(phone.frame_rate, 30);
    // Reported values are never merged into the locally measured values.
    assert_eq!(snapshot.arrival_fps, None);
    assert_eq!(snapshot.decoded_fps, None);
    assert_eq!(snapshot.dropped_chunks, 0);
}

#[test]
fn fps_is_unknown_with_insufficient_samples() {
    let mut aggregator = DesktopMetricsAggregator::with_default_window();
    let start = base();

    let empty = aggregator.snapshot(start);
    assert_eq!(empty.arrival_fps, None);
    assert_eq!(empty.decoded_fps, None);

    aggregator.record_chunk_arrived(start, 512);
    aggregator.record_frame_decoded(start);
    let one = aggregator.snapshot(start + Duration::from_millis(10));
    // A single sample cannot measure an interval, so fps stays unknown.
    assert_eq!(one.arrival_fps, None);
    assert_eq!(one.decoded_fps, None);
    assert_eq!(one.total_chunks, 1);
}

#[test]
fn dropped_counters_accumulate() {
    let mut aggregator = DesktopMetricsAggregator::with_default_window();
    let start = base();

    aggregator.record_chunks_dropped(2, 100);
    aggregator.record_chunks_dropped(3, 50);

    let snapshot = aggregator.snapshot(start);
    assert_eq!(snapshot.dropped_chunks, 5);
    assert_eq!(snapshot.dropped_bytes, 150);
}

#[test]
fn window_must_be_positive() {
    assert!(matches!(
        DesktopMetricsAggregator::new(Duration::ZERO),
        Err(DesktopMetricsError::InvalidWindow)
    ));
}
