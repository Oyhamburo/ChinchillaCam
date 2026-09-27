use usb_probe::{
    ReassembledVideoChunk, SessionFrame, SessionFramePayload, VideoFragmentReassembler,
    VideoFragmentReassemblerError, VideoFrameKind,
};

fn fragment(index: i32, count: i32, total: i32, bytes: &[u8]) -> SessionFrame {
    fragment_with(
        "s",
        7,
        33_366,
        VideoFrameKind::Key,
        index,
        count,
        total,
        bytes,
    )
}

fn fragment_with(
    session_id: &str,
    chunk_index: i32,
    pts: i64,
    kind: VideoFrameKind,
    index: i32,
    count: i32,
    total: i32,
    bytes: &[u8],
) -> SessionFrame {
    let payload = match kind {
        VideoFrameKind::Delta => SessionFramePayload::video_chunk_fragment_v1_delta(
            chunk_index,
            pts,
            index,
            count,
            total,
            bytes.to_vec(),
        ),
        VideoFrameKind::Key => SessionFramePayload::video_chunk_fragment_v1_key(
            chunk_index,
            pts,
            index,
            count,
            total,
            bytes.to_vec(),
        ),
        VideoFrameKind::CodecConfig => SessionFramePayload::video_chunk_fragment_v1_codec_config(
            chunk_index,
            pts,
            index,
            count,
            total,
            bytes.to_vec(),
        ),
    };
    SessionFrame::new(0, session_id, payload)
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

#[test]
fn reassembler_allows_sequential_sessions_after_completion_or_reset() {
    let mut reassembler = VideoFragmentReassembler::new();
    assert!(reassembler
        .push_frame(&fragment_with(
            "a",
            7,
            33_366,
            VideoFrameKind::Key,
            0,
            1,
            1,
            &[0x65]
        ))
        .unwrap()
        .is_some());

    assert!(reassembler
        .push_frame(&fragment_with(
            "b",
            7,
            33_366,
            VideoFrameKind::Key,
            0,
            2,
            2,
            &[0x41]
        ))
        .unwrap()
        .is_none());
    reassembler.reset_session("b");
    assert!(reassembler
        .push_frame(&fragment_with(
            "c",
            7,
            33_366,
            VideoFrameKind::Key,
            0,
            1,
            1,
            &[0x67]
        ))
        .unwrap()
        .is_some());
}

#[test]
fn reassembler_fails_closed_on_second_session_while_active() {
    let mut reassembler = VideoFragmentReassembler::new();
    assert!(reassembler
        .push_frame(&fragment(0, 2, 2, &[0x65]))
        .unwrap()
        .is_none());

    assert_eq!(
        reassembler.push_frame(&fragment_with(
            "other",
            7,
            33_366,
            VideoFrameKind::Key,
            1,
            2,
            2,
            &[0x41]
        )),
        Err(VideoFragmentReassemblerError::ConcurrentSession {
            active_session_id: "s".to_string(),
            incoming_session_id: "other".to_string(),
        })
    );
    assert!(reassembler
        .push_frame(&fragment(0, 1, 1, &[0x41]))
        .unwrap()
        .is_some());
}

#[test]
fn reassembler_fails_closed_on_duplicate_or_out_of_order_fragment() {
    for bad_index in [0, 2] {
        let mut reassembler = VideoFragmentReassembler::new();
        assert!(reassembler
            .push_frame(&fragment(0, 3, 3, &[0x65]))
            .unwrap()
            .is_none());

        assert_eq!(
            reassembler.push_frame(&fragment(bad_index, 3, 3, &[0x41])),
            Err(VideoFragmentReassemblerError::UnexpectedFragmentIndex {
                expected_index: 1,
                actual_index: bad_index,
            })
        );
        assert!(reassembler
            .push_frame(&fragment(0, 1, 1, &[0x41]))
            .unwrap()
            .is_some());
    }
}

#[test]
fn reassembler_fails_closed_on_metadata_mismatch() {
    let mismatches = [
        fragment_with("s", 8, 33_366, VideoFrameKind::Key, 1, 2, 2, &[0x41]),
        fragment_with("s", 7, 33_367, VideoFrameKind::Key, 1, 2, 2, &[0x41]),
        fragment_with("s", 7, 33_366, VideoFrameKind::Delta, 1, 2, 2, &[0x41]),
        fragment_with("s", 7, 33_366, VideoFrameKind::Key, 1, 3, 2, &[0x41]),
        fragment_with("s", 7, 33_366, VideoFrameKind::Key, 1, 2, 3, &[0x41]),
    ];

    for mismatch in mismatches {
        let mut reassembler = VideoFragmentReassembler::new();
        assert!(reassembler
            .push_frame(&fragment(0, 2, 2, &[0x65]))
            .unwrap()
            .is_none());
        assert_eq!(
            reassembler.push_frame(&mismatch),
            Err(VideoFragmentReassemblerError::MetadataMismatch)
        );
        assert!(reassembler
            .push_frame(&fragment(0, 1, 1, &[0x41]))
            .unwrap()
            .is_some());
    }
}

#[test]
fn reassembler_fails_closed_on_byte_count_violations() {
    let mut overflow = VideoFragmentReassembler::new();
    assert!(overflow
        .push_frame(&fragment(0, 2, 3, &[0x65, 0x01]))
        .unwrap()
        .is_none());
    assert_eq!(
        overflow.push_frame(&fragment(1, 2, 3, &[0x02, 0x03])),
        Err(VideoFragmentReassemblerError::TotalBytesExceeded {
            total_h264_bytes: 3,
            actual_bytes: 4,
        })
    );

    let mut early_complete = VideoFragmentReassembler::new();
    assert_eq!(
        early_complete.push_frame(&fragment(0, 2, 2, &[0x65, 0x01])),
        Err(VideoFragmentReassemblerError::CompleteBeforeLastFragment)
    );

    let mut incomplete = VideoFragmentReassembler::new();
    assert!(incomplete
        .push_frame(&fragment(0, 2, 4, &[0x65]))
        .unwrap()
        .is_none());
    assert_eq!(
        incomplete.push_frame(&fragment(1, 2, 4, &[0x41])),
        Err(VideoFragmentReassemblerError::IncompleteTotalBytes {
            expected_bytes: 4,
            actual_bytes: 2,
        })
    );
}

#[test]
fn reassembler_rejects_non_fragment_payload_and_reset_all_clears_state() {
    let mut reassembler = VideoFragmentReassembler::new();
    let non_fragment = SessionFrame::new(
        0,
        "s",
        SessionFramePayload::video_chunk_v2_key(7, 33_366, vec![0x65]),
    );
    assert_eq!(
        reassembler.push_frame(&non_fragment),
        Err(VideoFragmentReassemblerError::UnexpectedPayloadType { type_id: 8 })
    );

    assert!(reassembler
        .push_frame(&fragment(0, 2, 2, &[0x65]))
        .unwrap()
        .is_none());
    reassembler.reset_all();
    assert!(reassembler
        .push_frame(&fragment(0, 1, 1, &[0x41]))
        .unwrap()
        .is_some());
}
