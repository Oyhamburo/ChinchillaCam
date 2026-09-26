use usb_probe::{
    BoundedEncodedVideoQueue, EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind,
    EncodedVideoSink, EncodedVideoSinkError, PresentationTimestamp,
};

#[derive(Debug, Default)]
struct CapturingSink {
    received: Vec<EncodedVideoChunk>,
}

impl EncodedVideoSink for CapturingSink {
    fn push_encoded_video(
        &mut self,
        chunk: EncodedVideoChunk,
    ) -> Result<(), EncodedVideoSinkError> {
        self.received.push(chunk);
        Ok(())
    }
}

#[test]
fn encoded_video_sink_builds_owned_chunk_with_typed_metadata() {
    let limits = EncodedVideoChunkLimits::new(4).unwrap();
    let mut payload = vec![0x65, 0x88, 0x84];

    let chunk = EncodedVideoChunk::new(
        7,
        PresentationTimestamp::from_micros(33_366),
        EncodedVideoFrameKind::Key,
        payload.clone(),
        &limits,
    )
    .unwrap();

    payload.fill(0);

    assert_eq!(chunk.stream_id(), 7);
    assert_eq!(chunk.presentation_timestamp().as_micros(), 33_366);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(chunk.payload(), &[0x65, 0x88, 0x84]);
    assert!(chunk.is_keyframe());
    assert!(!chunk.is_codec_config());
}

#[test]
fn encoded_video_sink_rejects_invalid_payload_boundaries() {
    let limits = EncodedVideoChunkLimits::new(2).unwrap();

    assert_eq!(
        EncodedVideoChunk::new(
            1,
            PresentationTimestamp::from_micros(0),
            EncodedVideoFrameKind::Delta,
            Vec::new(),
            &limits,
        ),
        Err(EncodedVideoSinkError::EmptyPayload)
    );

    assert_eq!(
        EncodedVideoChunk::new(
            1,
            PresentationTimestamp::from_micros(0),
            EncodedVideoFrameKind::Delta,
            vec![1, 2, 3],
            &limits,
        ),
        Err(EncodedVideoSinkError::PayloadTooLarge { length: 3, max: 2 })
    );

    assert_eq!(
        EncodedVideoChunkLimits::new(0),
        Err(EncodedVideoSinkError::InvalidPayloadLimit)
    );
}

#[test]
fn encoded_video_sink_injects_chunks_without_transport_or_decoder() {
    let limits = EncodedVideoChunkLimits::new(8).unwrap();
    let mut sink = CapturingSink::default();
    let config = EncodedVideoChunk::new(
        3,
        PresentationTimestamp::from_micros(0),
        EncodedVideoFrameKind::CodecConfig,
        vec![0x01, 0x64],
        &limits,
    )
    .unwrap();

    sink.push_encoded_video(config).unwrap();

    assert_eq!(sink.received.len(), 1);
    assert_eq!(sink.received[0].stream_id(), 3);
    assert!(sink.received[0].is_codec_config());
    assert_eq!(sink.received[0].payload(), &[0x01, 0x64]);
}

#[test]
fn encoded_video_sink_queue_applies_bounded_backpressure_without_dropping() {
    let limits = EncodedVideoChunkLimits::new(8).unwrap();
    let first = EncodedVideoChunk::new(
        9,
        PresentationTimestamp::from_micros(1_000),
        EncodedVideoFrameKind::Delta,
        vec![0xaa],
        &limits,
    )
    .unwrap();
    let second = EncodedVideoChunk::new(
        9,
        PresentationTimestamp::from_micros(2_000),
        EncodedVideoFrameKind::Delta,
        vec![0xbb],
        &limits,
    )
    .unwrap();
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();

    queue.push_encoded_video(first.clone()).unwrap();

    assert_eq!(queue.len(), 1);
    assert_eq!(
        queue.push_encoded_video(second),
        Err(EncodedVideoSinkError::QueueFull { capacity: 1 })
    );
    assert_eq!(queue.len(), 1);
    assert_eq!(queue.pop_front(), Some(first));
    assert!(queue.pop_front().is_none());
}

#[test]
fn encoded_video_sink_queue_rejects_zero_capacity() {
    assert_eq!(
        BoundedEncodedVideoQueue::new(0),
        Err(EncodedVideoSinkError::InvalidQueueCapacity)
    );
}
