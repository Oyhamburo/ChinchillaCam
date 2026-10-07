//! Task q2 (`odd/tasks/session-pipeline-wiring.md`, contract section 4.4): saturation must not
//! end the session. `KeyframeGatedSink` wraps an `EncodedVideoSink` and absorbs
//! backpressure-class rejections (`QueueFull`, `QueueBytesFull`, `Decoder(Backpressure)`): it
//! drops `Delta` frames until the next `Key`, never drops a `CodecConfig` (it re-pushes the
//! latest pending config before the next `Key`), counts the drops, and still returns `Ok` so
//! the receiver stays open. A non-backpressure error is propagated unchanged.

use std::collections::VecDeque;

use usb_probe::{
    EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind, EncodedVideoSink,
    EncodedVideoSinkError, KeyframeGatedSink, PresentationTimestamp, VideoDecoderError,
};

fn chunk(kind: EncodedVideoFrameKind, pts_us: u64, payload: Vec<u8>) -> EncodedVideoChunk {
    let limits = EncodedVideoChunkLimits::new(4096).unwrap();
    EncodedVideoChunk::new(
        7,
        PresentationTimestamp::from_micros(pts_us),
        kind,
        payload,
        &limits,
    )
    .unwrap()
}

/// A scriptable [`EncodedVideoSink`] double. Each push consumes the next scripted directive
/// (`None` succeeds and records the chunk, `Some(error)` fails with that error); once the
/// script is empty every push succeeds.
#[derive(Default)]
struct ScriptedSink {
    script: VecDeque<Option<EncodedVideoSinkError>>,
    delivered: Vec<EncodedVideoChunk>,
}

impl ScriptedSink {
    fn with_script(script: Vec<Option<EncodedVideoSinkError>>) -> Self {
        Self {
            script: script.into(),
            delivered: Vec::new(),
        }
    }

    fn delivered(&self) -> &[EncodedVideoChunk] {
        &self.delivered
    }
}

impl EncodedVideoSink for ScriptedSink {
    fn push_encoded_video(
        &mut self,
        chunk: EncodedVideoChunk,
    ) -> Result<(), EncodedVideoSinkError> {
        if let Some(Some(error)) = self.script.pop_front() {
            return Err(error);
        }
        self.delivered.push(chunk);
        Ok(())
    }
}

#[test]
fn backpressure_drops_until_next_keyframe() {
    // Inner: Key ok, next push (a Delta) backpressures, everything after is ok again.
    let inner = ScriptedSink::with_script(vec![
        None,
        Some(EncodedVideoSinkError::QueueFull { capacity: 4 }),
    ]);
    let mut sink = KeyframeGatedSink::new(inner);

    // A keyframe opens the gate.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Key, 10, vec![0x65]))
        .unwrap();
    assert!(!sink.is_gated());

    // The first Delta hits inner backpressure: dropped, gate closes, session stays open.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Delta, 20, vec![0x41, 0x42]))
        .unwrap();
    assert!(sink.is_gated());

    // Further Deltas are dropped without ever touching the inner sink.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Delta, 30, vec![0x43]))
        .unwrap();
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Delta, 40, vec![0x44]))
        .unwrap();

    // The next Key re-opens the gate; Deltas flow again.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Key, 50, vec![0x66]))
        .unwrap();
    assert!(!sink.is_gated());
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Delta, 60, vec![0x45]))
        .unwrap();

    assert_eq!(sink.dropped_chunks(), 3);
    assert_eq!(sink.dropped_bytes(), 2 + 1 + 1);
    assert_eq!(sink.saturation_events(), 1);

    let delivered: Vec<EncodedVideoFrameKind> = sink
        .inner()
        .delivered()
        .iter()
        .map(EncodedVideoChunk::frame_kind)
        .collect();
    assert_eq!(
        delivered,
        vec![
            EncodedVideoFrameKind::Key,
            EncodedVideoFrameKind::Key,
            EncodedVideoFrameKind::Delta,
        ]
    );
}

#[test]
fn codec_config_is_never_dropped() {
    // Inner backpressures on the first push (the CodecConfig); everything after is ok.
    let inner = ScriptedSink::with_script(vec![Some(EncodedVideoSinkError::Decoder(
        VideoDecoderError::Backpressure,
    ))]);
    let mut sink = KeyframeGatedSink::new(inner);

    // The CodecConfig backpressures: it is NOT dropped, it is held pending and the gate closes.
    sink.push_encoded_video(chunk(
        EncodedVideoFrameKind::CodecConfig,
        0,
        vec![0x01, 0x02],
    ))
    .unwrap();
    assert!(sink.is_gated());
    assert_eq!(sink.dropped_chunks(), 0);

    // A Delta while gated is dropped.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Delta, 10, vec![0x41]))
        .unwrap();

    // The next Key first re-pushes the pending config, then the Key itself, and ungates.
    sink.push_encoded_video(chunk(EncodedVideoFrameKind::Key, 20, vec![0x65]))
        .unwrap();
    assert!(!sink.is_gated());

    // The config was never counted as a drop; only the Delta was.
    assert_eq!(sink.dropped_chunks(), 1);
    assert_eq!(sink.saturation_events(), 1);

    // The inner sink saw the config re-pushed before the Key.
    let delivered: Vec<EncodedVideoFrameKind> = sink
        .inner()
        .delivered()
        .iter()
        .map(EncodedVideoChunk::frame_kind)
        .collect();
    assert_eq!(
        delivered,
        vec![
            EncodedVideoFrameKind::CodecConfig,
            EncodedVideoFrameKind::Key
        ]
    );
}

#[test]
fn non_backpressure_failure_is_propagated() {
    let inner = ScriptedSink::with_script(vec![Some(EncodedVideoSinkError::Decoder(
        VideoDecoderError::Failure("synthetic decode failure".to_string()),
    ))]);
    let mut sink = KeyframeGatedSink::new(inner);

    let error = sink
        .push_encoded_video(chunk(EncodedVideoFrameKind::Key, 10, vec![0x65]))
        .unwrap_err();

    assert_eq!(
        error,
        EncodedVideoSinkError::Decoder(VideoDecoderError::Failure(
            "synthetic decode failure".to_string()
        ))
    );
    // A non-backpressure failure does not gate or count as saturation: it ends the session.
    assert!(!sink.is_gated());
    assert_eq!(sink.saturation_events(), 0);
    assert_eq!(sink.dropped_chunks(), 0);
}
