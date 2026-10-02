#![cfg(target_os = "macos")]

use usb_probe::{parse_h264_config, H264InputFraming, VideoToolboxError, VideoToolboxFormat};

#[test]
fn format_description_reports_fixture_dimensions() {
    let annex_b = include_bytes!("fixtures/t21b1-16x16-idr.h264");
    let parameter_sets = parse_h264_config(H264InputFraming::AnnexB, annex_b).unwrap();

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
