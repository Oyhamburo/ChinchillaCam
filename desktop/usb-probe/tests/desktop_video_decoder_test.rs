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
