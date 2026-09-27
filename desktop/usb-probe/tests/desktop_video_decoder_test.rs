use usb_probe::{
    BulkFrame, DecodingEncodedVideoSink, DesktopReceiverError, DesktopVideoSessionReceiver,
    EncodedVideoChunk, EncodedVideoFrameKind, SessionFrame, SessionFrameCodec, SessionFramePayload,
    StaticFrameKindClassifier, VideoDecoder, VideoDecoderError, VideoFrameKind,
    USB_SESSION_FRAME_STREAM_ID,
};

fn video_v2_bulk_frame(
    sequence: i32,
    kind: VideoFrameKind,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    let payload = match kind {
        VideoFrameKind::Key => {
            SessionFramePayload::video_chunk_v2_key(4, presentation_time_us, payload)
        }
        VideoFrameKind::CodecConfig => {
            SessionFramePayload::video_chunk_v2_codec_config(4, presentation_time_us, payload)
        }
        VideoFrameKind::Delta => {
            SessionFramePayload::video_chunk_v2_delta(4, presentation_time_us, payload)
        }
    };
    let session = SessionFrame::new(sequence, "session-a", payload);
    BulkFrame::new(
        USB_SESSION_FRAME_STREAM_ID,
        SessionFrameCodec::encode(&session).unwrap(),
    )
    .unwrap()
}

fn video_fragment_bulk_frame(
    sequence: i32,
    session_id: &str,
    kind: VideoFrameKind,
    presentation_time_us: i64,
    fragment_index: i32,
    fragment_count: i32,
    total_h264_bytes: i32,
    payload: Vec<u8>,
) -> BulkFrame {
    let payload = match kind {
        VideoFrameKind::Key => SessionFramePayload::video_chunk_fragment_v1_key(
            9,
            presentation_time_us,
            fragment_index,
            fragment_count,
            total_h264_bytes,
            payload,
        ),
        VideoFrameKind::CodecConfig => SessionFramePayload::video_chunk_fragment_v1_codec_config(
            9,
            presentation_time_us,
            fragment_index,
            fragment_count,
            total_h264_bytes,
            payload,
        ),
        VideoFrameKind::Delta => SessionFramePayload::video_chunk_fragment_v1_delta(
            9,
            presentation_time_us,
            fragment_index,
            fragment_count,
            total_h264_bytes,
            payload,
        ),
    };
    let session = SessionFrame::new(sequence, session_id, payload);
    BulkFrame::new(
        USB_SESSION_FRAME_STREAM_ID,
        SessionFrameCodec::encode(&session).unwrap(),
    )
    .unwrap()
}

#[derive(Debug, Default)]
struct RecordingDecoder {
    chunks: Vec<EncodedVideoChunk>,
    next_error: Option<VideoDecoderError>,
}

impl RecordingDecoder {
    fn reject_once(error: VideoDecoderError) -> Self {
        Self {
            chunks: Vec::new(),
            next_error: Some(error),
        }
    }
}

impl VideoDecoder for RecordingDecoder {
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        if let Some(error) = self.next_error.take() {
            return Err(error);
        }
        self.chunks.push(chunk);
        Ok(())
    }
}

#[test]
fn desktop_video_decoder_receiver_forwards_codec_config_with_exact_chunk_fields() {
    let mut receiver = DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown());
    let mut sink = DecodingEncodedVideoSink::new(RecordingDecoder::default());

    receiver
        .receive(
            &video_v2_bulk_frame(
                0,
                VideoFrameKind::CodecConfig,
                12_345,
                vec![0x01, 0x64, 0x00, 0x1f],
            ),
            &mut sink,
        )
        .unwrap();

    let chunks = sink.decoder().chunks.as_slice();
    assert_eq!(chunks.len(), 1);
    assert_eq!(chunks[0].stream_id(), USB_SESSION_FRAME_STREAM_ID);
    assert_eq!(chunks[0].presentation_timestamp().as_micros(), 12_345);
    assert_eq!(chunks[0].frame_kind(), EncodedVideoFrameKind::CodecConfig);
    assert_eq!(chunks[0].payload(), &[0x01, 0x64, 0x00, 0x1f]);
}

#[test]
fn desktop_video_decoder_receiver_forwards_codec_config_key_and_delta_in_order() {
    let mut receiver = DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown());
    let mut sink = DecodingEncodedVideoSink::new(RecordingDecoder::default());

    receiver
        .receive(
            &video_v2_bulk_frame(0, VideoFrameKind::CodecConfig, 10, vec![0x01]),
            &mut sink,
        )
        .unwrap();
    receiver
        .receive(
            &video_v2_bulk_frame(1, VideoFrameKind::Key, 11, vec![0x65, 0x88]),
            &mut sink,
        )
        .unwrap();
    receiver
        .receive(
            &video_v2_bulk_frame(2, VideoFrameKind::Delta, 12, vec![0x41, 0x9a]),
            &mut sink,
        )
        .unwrap();

    let observed = sink
        .decoder()
        .chunks
        .iter()
        .map(|chunk| {
            (
                chunk.presentation_timestamp().as_micros(),
                chunk.frame_kind(),
                chunk.payload().to_vec(),
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        observed,
        vec![
            (10, EncodedVideoFrameKind::CodecConfig, vec![0x01]),
            (11, EncodedVideoFrameKind::Key, vec![0x65, 0x88]),
            (12, EncodedVideoFrameKind::Delta, vec![0x41, 0x9a]),
        ]
    );
}

#[test]
fn desktop_video_decoder_receiver_propagates_typed_rejection_as_sink_rejected() {
    let cause = VideoDecoderError::Backpressure;
    let mut receiver = DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown());
    let mut sink = DecodingEncodedVideoSink::new(RecordingDecoder::reject_once(cause.clone()));

    let error = receiver
        .receive(
            &video_v2_bulk_frame(0, VideoFrameKind::Key, 20, vec![0x65]),
            &mut sink,
        )
        .unwrap_err();

    assert_eq!(
        error,
        DesktopReceiverError::SinkRejected(usb_probe::EncodedVideoSinkError::Decoder(cause))
    );
}

#[test]
fn desktop_video_decoder_receiver_type9_two_fragments_decodes_one_concatenated_access_unit() {
    let mut receiver = DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown());
    let mut sink = DecodingEncodedVideoSink::new(RecordingDecoder::default());

    receiver
        .receive(
            &video_fragment_bulk_frame(
                0,
                "session-fragment",
                VideoFrameKind::Key,
                77_777,
                0,
                2,
                5,
                vec![0x00, 0x00],
            ),
            &mut sink,
        )
        .unwrap();
    assert!(sink.decoder().chunks.is_empty());

    receiver
        .receive(
            &video_fragment_bulk_frame(
                1,
                "session-fragment",
                VideoFrameKind::Key,
                77_777,
                1,
                2,
                5,
                vec![0x01, 0x65, 0x88],
            ),
            &mut sink,
        )
        .unwrap();

    let chunks = sink.decoder().chunks.as_slice();
    assert_eq!(chunks.len(), 1);
    assert_eq!(chunks[0].stream_id(), USB_SESSION_FRAME_STREAM_ID);
    assert_eq!(chunks[0].presentation_timestamp().as_micros(), 77_777);
    assert_eq!(chunks[0].frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(chunks[0].payload(), &[0x00, 0x00, 0x01, 0x65, 0x88]);
}

#[test]
fn desktop_video_decoder_receiver_type9_final_decoder_backpressure_closes_without_retry_until_reset(
) {
    assert_type9_final_decoder_error_closes_without_retry_until_reset(
        VideoDecoderError::Backpressure,
    );
}

#[test]
fn desktop_video_decoder_receiver_type9_final_decoder_failure_closes_without_retry_until_reset() {
    assert_type9_final_decoder_error_closes_without_retry_until_reset(VideoDecoderError::Failure(
        "decoder failed".to_string(),
    ));
}

fn assert_type9_final_decoder_error_closes_without_retry_until_reset(cause: VideoDecoderError) {
    let mut receiver = DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown());
    let mut rejecting_sink =
        DecodingEncodedVideoSink::new(RecordingDecoder::reject_once(cause.clone()));

    receiver
        .receive(
            &video_fragment_bulk_frame(
                0,
                "session-reject",
                VideoFrameKind::Delta,
                88_888,
                0,
                2,
                3,
                vec![0x41],
            ),
            &mut rejecting_sink,
        )
        .unwrap();

    let error = receiver
        .receive(
            &video_fragment_bulk_frame(
                1,
                "session-reject",
                VideoFrameKind::Delta,
                88_888,
                1,
                2,
                3,
                vec![0x9a, 0xbc],
            ),
            &mut rejecting_sink,
        )
        .unwrap_err();

    assert_eq!(
        error,
        DesktopReceiverError::SinkRejected(usb_probe::EncodedVideoSinkError::Decoder(cause))
    );
    assert!(rejecting_sink.decoder().chunks.is_empty());

    let closed = receiver
        .receive(
            &video_v2_bulk_frame(2, VideoFrameKind::Key, 88_889, vec![0x65]),
            &mut rejecting_sink,
        )
        .unwrap_err();
    assert_eq!(closed, DesktopReceiverError::Closed);
    assert!(rejecting_sink.decoder().chunks.is_empty());

    receiver.reset_for_new_session();
    let mut accepting_sink = DecodingEncodedVideoSink::new(RecordingDecoder::default());
    receiver
        .receive(
            &video_fragment_bulk_frame(
                0,
                "session-fresh",
                VideoFrameKind::CodecConfig,
                99_999,
                0,
                2,
                4,
                vec![0x00],
            ),
            &mut accepting_sink,
        )
        .unwrap();
    receiver
        .receive(
            &video_fragment_bulk_frame(
                1,
                "session-fresh",
                VideoFrameKind::CodecConfig,
                99_999,
                1,
                2,
                4,
                vec![0x01, 0x67, 0x64],
            ),
            &mut accepting_sink,
        )
        .unwrap();

    let chunks = accepting_sink.decoder().chunks.as_slice();
    assert_eq!(chunks.len(), 1);
    assert_eq!(chunks[0].presentation_timestamp().as_micros(), 99_999);
    assert_eq!(chunks[0].frame_kind(), EncodedVideoFrameKind::CodecConfig);
    assert_eq!(chunks[0].payload(), &[0x00, 0x01, 0x67, 0x64]);
}
