#![cfg(target_os = "macos")]

use usb_probe::{
    parse_h264_config, DecodedFrameCounter, DecodedFrameSink, DecodedFrameSinkError,
    DecodedVideoFrame, EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind,
    H264InputFraming, PixelFormat, PresentationTimestamp, RecordingDecodedFrameSink, VideoDecoder,
    VideoDecoderError, VideoToolboxDecoder, VideoToolboxError, VideoToolboxFormat,
};

const FIXTURE: &[u8] = include_bytes!("fixtures/t21b1-16x16-idr.h264");
const FIXTURE_PTS_US: u64 = 33_333;

#[test]
fn format_description_reports_fixture_dimensions() {
    let parameter_sets = parse_h264_config(H264InputFraming::AnnexB, FIXTURE).unwrap();

    let format = VideoToolboxFormat::from_parameter_sets(&parameter_sets).unwrap();

    assert_eq!(format.dimensions(), (16, 16));
}

#[test]
fn invalid_parameter_sets_fail_without_panic() {
    let garbage = [
        0x00, 0x00, 0x00, 0x01, 0x67, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x01, 0x68, 0xff,
    ];
    let parameter_sets = parse_h264_config(H264InputFraming::AnnexB, &garbage).unwrap();

    let error = VideoToolboxFormat::from_parameter_sets(&parameter_sets).unwrap_err();

    match error {
        VideoToolboxError::FormatDescription { status } => assert_ne!(status, 0),
        other => panic!("unexpected error: {other:?}"),
    }
}

#[test]
fn decodes_fixture_idr_to_one_nv12_frame() {
    let (config, access_unit) = split_fixture();
    let mut decoder = VideoToolboxDecoder::new(RecordingDecodedFrameSink::default());

    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0, config))
        .unwrap();
    decoder
        .decode_encoded_video(chunk(
            EncodedVideoFrameKind::Key,
            FIXTURE_PTS_US,
            access_unit,
        ))
        .unwrap();

    assert_eq!(decoder.frames_emitted(), 1);
    let frames = decoder.sink().frames();
    assert_eq!(frames.len(), 1);
    let frame = &frames[0];
    assert_eq!(frame.stream_id(), 7);
    assert_eq!(frame.pts_us(), FIXTURE_PTS_US);
    assert_eq!((frame.width(), frame.height()), (16, 16));
    assert_eq!(frame.pixel_format(), PixelFormat::Nv12);
    assert_eq!(frame.data().len(), 16 * 16 * 3 / 2);
}

#[test]
fn access_unit_before_config_fails() {
    let (_, access_unit) = split_fixture();
    let mut decoder = VideoToolboxDecoder::new(RecordingDecodedFrameSink::default());

    let error = decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 0, access_unit))
        .unwrap_err();

    assert!(matches!(error, VideoDecoderError::Failure(_)), "{error:?}");
    assert_eq!(decoder.frames_emitted(), 0);
    assert!(decoder.sink().frames().is_empty());
}

#[test]
fn corrupt_access_unit_fails_without_panic() {
    let (config, _) = split_fixture();
    let mut decoder = VideoToolboxDecoder::new(RecordingDecodedFrameSink::default());
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0, config))
        .unwrap();
    let mut garbage = vec![0x00, 0x00, 0x00, 0x01, 0x65];
    garbage.extend(std::iter::repeat_n(0xa5, 64));

    let result = decoder.decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 0, garbage));

    // VideoToolbox reports the bad slice through the output callback (observed OSStatus
    // -8969), surfaced as `Failure`; a decoder that silently drops it must emit no frame.

    match result {
        Err(VideoDecoderError::Failure(detail)) => assert!(detail.contains("OSStatus"), "{detail}"),
        Err(other) => panic!("unexpected error: {other:?}"),
        Ok(()) => {}
    }
    assert_eq!(decoder.frames_emitted(), 0);
    assert!(decoder.sink().frames().is_empty());
}

#[test]
fn sink_backpressure_maps_to_decoder_backpressure() {
    let (config, access_unit) = split_fixture();
    let mut decoder = VideoToolboxDecoder::new(SaturatedSink::default());
    decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::CodecConfig, 0, config))
        .unwrap();

    let error = decoder
        .decode_encoded_video(chunk(EncodedVideoFrameKind::Key, 0, access_unit))
        .unwrap_err();

    assert_eq!(error, VideoDecoderError::Backpressure);
    assert_eq!(decoder.sink().attempts, 1);
    assert_eq!(decoder.frames_emitted(), 0);
}

#[derive(Debug, Default)]
struct SaturatedSink {
    attempts: usize,
}

impl DecodedFrameSink for SaturatedSink {
    fn push_decoded_frame(
        &mut self,
        _frame: DecodedVideoFrame,
    ) -> Result<(), DecodedFrameSinkError> {
        self.attempts += 1;
        Err(DecodedFrameSinkError::Backpressure)
    }
}

/// Splits the fixture into its SPS+PPS config and the remaining access unit (SEI + IDR),
/// both kept in Annex-B framing with their start codes.
fn split_fixture() -> (Vec<u8>, Vec<u8>) {
    let starts: Vec<usize> = (0..FIXTURE.len() - 3)
        .filter(|&index| FIXTURE[index..index + 3] == [0, 0, 1])
        .map(|index| {
            if index > 0 && FIXTURE[index - 1] == 0 {
                index - 1
            } else {
                index
            }
        })
        .collect();
    let access_unit_start = starts[2];
    (
        FIXTURE[..access_unit_start].to_vec(),
        FIXTURE[access_unit_start..].to_vec(),
    )
}

fn chunk(kind: EncodedVideoFrameKind, pts_us: u64, payload: Vec<u8>) -> EncodedVideoChunk {
    let limits = EncodedVideoChunkLimits::new(1024 * 1024).unwrap();
    EncodedVideoChunk::new(
        7,
        PresentationTimestamp::from_micros(pts_us),
        kind,
        payload,
        &limits,
    )
    .unwrap()
}
