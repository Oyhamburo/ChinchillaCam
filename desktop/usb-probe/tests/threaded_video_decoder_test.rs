//! Threaded decoder boundary (`odd/tasks/threaded-decoder.md`, contract section 4): a
//! `ThreadedVideoDecoder` builds its inner decoder on a worker thread, only enqueues on
//! `decode_encoded_video`, reports a full queue as backpressure, reports an inner failure on
//! the next chunk, publishes `frames_emitted` atomically, and never hangs on drop.

use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

use usb_probe::{
    ChannelDecodedFrameSink, DecodedFrameCounter, EncodedVideoChunk, EncodedVideoChunkLimits,
    EncodedVideoFrameKind, FakeVideoDecoder, PresentationTimestamp, RecordingDecodedFrameSink,
    ThreadedVideoDecoder, ThreadedVideoDecoderConfig, ThreadedVideoDecoderError, VideoDecoder,
    VideoDecoderError,
};

const GENEROUS: Duration = Duration::from_secs(5);

fn chunk(kind: EncodedVideoFrameKind, pts_us: u64) -> EncodedVideoChunk {
    let limits = EncodedVideoChunkLimits::new(4096).unwrap();
    EncodedVideoChunk::new(
        7,
        PresentationTimestamp::from_micros(pts_us),
        kind,
        vec![0x65],
        &limits,
    )
    .unwrap()
}

fn delta(pts_us: u64) -> EncodedVideoChunk {
    chunk(EncodedVideoFrameKind::Delta, pts_us)
}

fn config(queue_capacity: usize, join_timeout: Duration) -> ThreadedVideoDecoderConfig {
    ThreadedVideoDecoderConfig::new(queue_capacity, join_timeout).unwrap()
}

/// A slow inner decoder: announces each chunk it starts, then blocks until the test releases
/// one permit (or drops the gate sender, which releases every pending and future chunk).
struct GatedDecoder {
    started: Sender<()>,
    gate: Receiver<()>,
    frames: u64,
}

impl VideoDecoder for GatedDecoder {
    fn decode_encoded_video(&mut self, _chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        let _ = self.started.send(());
        let _ = self.gate.recv();
        self.frames += 1;
        Ok(())
    }
}

impl DecodedFrameCounter for GatedDecoder {
    fn frames_emitted(&self) -> u64 {
        self.frames
    }
}

/// Spawns a threaded decoder around a [`GatedDecoder`]; returns the decoder, the "started"
/// receiver and the gate sender.
fn gated_decoder(
    queue_capacity: usize,
    join_timeout: Duration,
) -> (ThreadedVideoDecoder, Receiver<()>, Sender<()>) {
    let (started_tx, started_rx) = mpsc::channel();
    let (gate_tx, gate_rx) = mpsc::channel();
    let decoder = ThreadedVideoDecoder::spawn(
        "gated-decoder",
        config(queue_capacity, join_timeout),
        move || GatedDecoder {
            started: started_tx,
            gate: gate_rx,
            frames: 0,
        },
    )
    .unwrap();
    (decoder, started_rx, gate_tx)
}

fn wait_until(mut condition: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + GENEROUS;
    while Instant::now() < deadline {
        if condition() {
            return true;
        }
        thread::sleep(Duration::from_millis(5));
    }
    condition()
}

#[test]
fn enqueue_does_not_block_on_slow_decoder() {
    let (mut decoder, started, gate) = gated_decoder(4, Duration::from_millis(200));

    decoder.decode_encoded_video(delta(10)).unwrap();
    started.recv_timeout(GENEROUS).unwrap();

    let begin = Instant::now();
    for pts in [20, 30, 40] {
        decoder.decode_encoded_video(delta(pts)).unwrap();
    }
    assert!(
        begin.elapsed() < Duration::from_secs(1),
        "enqueue blocked for {:?} while the inner decoder was stuck",
        begin.elapsed()
    );

    drop(gate);
    assert!(wait_until(|| decoder.frames_emitted() == 4));
}

#[test]
fn full_queue_reports_backpressure() {
    let (mut decoder, started, gate) = gated_decoder(1, Duration::from_millis(200));

    decoder.decode_encoded_video(delta(10)).unwrap();
    started.recv_timeout(GENEROUS).unwrap();
    decoder.decode_encoded_video(delta(20)).unwrap();

    assert_eq!(
        decoder.decode_encoded_video(delta(30)),
        Err(VideoDecoderError::Backpressure)
    );

    drop(gate);
    assert!(wait_until(|| decoder.frames_emitted() == 2));
}

#[test]
fn inner_failure_is_reported_on_next_chunk() {
    let failure = VideoDecoderError::Failure("boom".to_string());
    let scripted = failure.clone();
    let mut decoder = ThreadedVideoDecoder::spawn(
        "failing-decoder",
        config(64, Duration::from_millis(200)),
        move || FakeVideoDecoder::new(RecordingDecodedFrameSink::default()).script_error(scripted),
    )
    .unwrap();

    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0))
        .unwrap();
    let mut observed = None;
    assert!(wait_until(|| {
        observed = decoder.decode_encoded_video(delta(10)).err();
        observed.is_some()
    }));

    assert_eq!(observed, Some(failure.clone()));
    assert_eq!(decoder.decode_encoded_video(delta(20)), Err(failure));
    assert_eq!(decoder.frames_emitted(), 0);
}

#[test]
fn inner_backpressure_is_counted_and_decoding_continues() {
    let mut decoder = ThreadedVideoDecoder::spawn(
        "saturated-decoder",
        config(64, Duration::from_millis(200)),
        || {
            FakeVideoDecoder::new(RecordingDecodedFrameSink::default())
                .script_error(VideoDecoderError::Backpressure)
        },
    )
    .unwrap();

    decoder.decode_encoded_video(delta(5)).unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 10))
        .unwrap();

    assert!(wait_until(|| decoder.frames_emitted() == 1));
    assert_eq!(decoder.dropped_decodes(), 1);
    assert_eq!(decoder.decode_encoded_video(delta(20)), Ok(()));
}

#[test]
fn inner_backpressure_drops_deltas_until_next_keyframe() {
    let (sink, frames) = ChannelDecodedFrameSink::bounded(16).unwrap();
    let mut decoder = ThreadedVideoDecoder::spawn(
        "keyframe-gated-decoder",
        config(16, Duration::from_millis(200)),
        move || FakeVideoDecoder::new(sink).script_error(VideoDecoderError::Backpressure),
    )
    .unwrap();

    // The key is lost to backpressure, so the delta that follows has no reference: it must be
    // dropped, while the codec config still reaches the decoder and the next key reopens it.
    for chunk in [
        chunk(EncodedVideoFrameKind::Key, 10),
        chunk(EncodedVideoFrameKind::CodecConfig, 0),
        delta(20),
        chunk(EncodedVideoFrameKind::Key, 30),
        delta(40),
    ] {
        decoder.decode_encoded_video(chunk).unwrap();
    }

    let observed: Vec<u64> = (0..2)
        .map(|_| frames.recv_timeout(GENEROUS).unwrap().pts_us())
        .collect();
    assert_eq!(observed, vec![30, 40]);
    assert!(wait_until(|| decoder.frames_emitted() == 2));
    assert_eq!(decoder.dropped_decodes(), 2);
}

#[test]
fn frames_reach_channel_consumer_in_order() {
    let (sink, frames) = ChannelDecodedFrameSink::bounded(16).unwrap();
    let mut decoder = ThreadedVideoDecoder::spawn(
        "channel-decoder",
        config(16, Duration::from_millis(200)),
        move || FakeVideoDecoder::new(sink),
    )
    .unwrap();

    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 10))
        .unwrap();
    for pts in [20, 30, 40] {
        decoder.decode_encoded_video(delta(pts)).unwrap();
    }

    let observed: Vec<u64> = (0..4)
        .map(|_| frames.recv_timeout(GENEROUS).unwrap().pts_us())
        .collect();
    assert_eq!(observed, vec![10, 20, 30, 40]);
    assert!(wait_until(|| decoder.frames_emitted() == 4));
}

#[test]
fn drop_joins_worker_within_bound() {
    let join_timeout = Duration::from_millis(100);
    let margin = Duration::from_secs(2);

    let idle = ThreadedVideoDecoder::spawn("idle-decoder", config(4, join_timeout), || {
        FakeVideoDecoder::new(RecordingDecodedFrameSink::default())
    })
    .unwrap();
    assert_drop_returns_within(idle, join_timeout + margin);

    let (mut stuck, started, gate) = gated_decoder(4, join_timeout);
    stuck.decode_encoded_video(delta(10)).unwrap();
    started.recv_timeout(GENEROUS).unwrap();
    assert_drop_returns_within(stuck, join_timeout + margin);
    drop(gate);
}

fn assert_drop_returns_within(decoder: ThreadedVideoDecoder, bound: Duration) {
    let (done_tx, done_rx) = mpsc::channel();
    let begin = Instant::now();
    thread::spawn(move || {
        drop(decoder);
        let _ = done_tx.send(());
    });
    done_rx
        .recv_timeout(bound)
        .expect("dropping the threaded decoder hung");
    assert!(begin.elapsed() < bound);
}

#[test]
fn factory_panic_is_reported_as_failure() {
    let mut decoder = ThreadedVideoDecoder::spawn(
        "panicking-decoder",
        config(4, Duration::from_millis(200)),
        || -> FakeVideoDecoder<RecordingDecodedFrameSink> { panic!("factory exploded") },
    )
    .unwrap();

    let mut observed = None;
    assert!(wait_until(|| {
        observed = decoder.decode_encoded_video(delta(10)).err();
        observed.is_some()
    }));
    assert!(
        matches!(observed, Some(VideoDecoderError::Failure(_))),
        "expected a sticky failure, got {observed:?}"
    );
}

#[test]
fn config_rejects_zero_capacity_and_zero_join_timeout() {
    assert_eq!(
        ThreadedVideoDecoderConfig::new(0, Duration::from_millis(1)).unwrap_err(),
        ThreadedVideoDecoderError::InvalidQueueCapacity
    );
    assert_eq!(
        ThreadedVideoDecoderConfig::new(1, Duration::ZERO).unwrap_err(),
        ThreadedVideoDecoderError::InvalidJoinTimeout
    );
    assert!(ChannelDecodedFrameSink::bounded(0).is_err());
}
