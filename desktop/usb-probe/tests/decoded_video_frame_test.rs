//! Task q1 (`odd/tasks/session-pipeline-wiring.md`, contract section 4.3): the decoded-frame
//! boundary. A `VideoDecoder` keeps its encoded-in contract unchanged; a fake decoder owns a
//! `DecodedFrameSink` output, emits one `DecodedVideoFrame` per non-`CodecConfig` chunk with
//! the chunk's presentation timestamp, requires a `CodecConfig` before the first frame, and
//! can be scripted to fail.

use usb_probe::{
    DecodedFrameSink, DecodedVideoFrame, EncodedVideoChunk, EncodedVideoChunkLimits,
    EncodedVideoFrameKind, FakeVideoDecoder, PixelFormat, PresentationTimestamp,
    RecordingDecodedFrameSink, VideoDecoder, VideoDecoderError,
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

#[test]
fn fake_decoder_emits_frames_in_pts_order() {
    let mut decoder = FakeVideoDecoder::new(RecordingDecodedFrameSink::default());

    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0, vec![0x01]))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 10, vec![0x65]))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Delta, 20, vec![0x41]))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Delta, 30, vec![0x42]))
        .unwrap();

    let observed: Vec<u64> = decoder
        .sink()
        .frames()
        .iter()
        .map(DecodedVideoFrame::pts_us)
        .collect();
    assert_eq!(observed, vec![10, 20, 30]);
    assert_eq!(decoder.sink().frames()[0].stream_id(), 7);
    assert_eq!(decoder.sink().frames()[0].pixel_format(), PixelFormat::Nv12);
}

#[test]
fn fake_decoder_requires_codec_config_first() {
    let mut decoder = FakeVideoDecoder::new(RecordingDecodedFrameSink::default());

    let error = decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 10, vec![0x65]))
        .unwrap_err();

    assert!(
        matches!(error, VideoDecoderError::Failure(_)),
        "expected a decoder failure, got {error:?}"
    );
    assert!(decoder.sink().frames().is_empty());
}

#[test]
fn fake_decoder_can_be_scripted_to_return_backpressure() {
    let mut decoder = FakeVideoDecoder::new(RecordingDecodedFrameSink::default())
        .script_error(VideoDecoderError::Backpressure);

    let error = decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0, vec![0x01]))
        .unwrap_err();

    assert_eq!(error, VideoDecoderError::Backpressure);
    assert!(decoder.sink().frames().is_empty());
}
