use usb_probe::{
    ReassembledVideoChunk, SessionFrame, SessionFramePayload, VideoFragmentReassembler,
    VideoFrameKind,
};

fn fragment(index: i32, count: i32, total: i32, bytes: &[u8]) -> SessionFrame {
    SessionFrame::new(
        0,
        "s",
        SessionFramePayload::video_chunk_fragment_v1_key(
            7,
            33_366,
            index,
            count,
            total,
            bytes.to_vec(),
        ),
    )
}

fn expect_chunk(chunk: ReassembledVideoChunk, bytes: &[u8]) {
    assert_eq!(chunk.session_id(), "s");
    assert_eq!(chunk.chunk_index(), 7);
    assert_eq!(chunk.presentation_time_us(), 33_366);
    assert_eq!(chunk.kind(), VideoFrameKind::Key);
    assert_eq!(chunk.h264_bytes(), bytes);
}

#[test]
fn reassembler_emits_chunk_only_after_strict_completion() {
    let mut reassembler = VideoFragmentReassembler::new();

    assert_eq!(
        reassembler
            .push_frame(&fragment(0, 2, 4, &[0x65, 0x01]))
            .unwrap(),
        None
    );
    let chunk = reassembler
        .push_frame(&fragment(1, 2, 4, &[0x02, 0x03]))
        .unwrap()
        .unwrap();

    expect_chunk(chunk, &[0x65, 0x01, 0x02, 0x03]);
}
