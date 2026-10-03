use usb_probe::{
    BoundedEncodedVideoQueue, BulkFrame, DesktopReceiverError, EncodedVideoFrameKind, SessionFrame,
    SessionFrameCodec, SessionFrameDecodeError, SessionFramePayload, StaticFrameKindClassifier,
    VideoFrameKind, VideoFrameKindClassifier, USB_SESSION_FRAME_STREAM_ID,
};

const MAX_USB_SESSION_FRAME_BYTES: usize = 65_528;

fn video_bulk_frame(presentation_time_us: i64, payload: Vec<u8>) -> BulkFrame {
    video_bulk_frame_with_sequence(7, presentation_time_us, payload)
}

fn video_bulk_frame_with_sequence(
    sequence: i32,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    video_bulk_frame_with_sequence_and_session(sequence, "session-a", presentation_time_us, payload)
}

fn video_bulk_frame_with_sequence_and_session(
    sequence: i32,
    session_id: &str,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    let session = SessionFrame::new(
        sequence,
        session_id,
        SessionFramePayload::VideoChunk {
            chunk_index: 3,
            presentation_time_us,
            h264_bytes: payload,
        },
    );
    BulkFrame::new(
        USB_SESSION_FRAME_STREAM_ID,
        SessionFrameCodec::encode(&session).unwrap(),
    )
    .unwrap()
}

fn video_session_frame(
    sequence: i32,
    session_id: &str,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> SessionFrame {
    SessionFrame::new(
        sequence,
        session_id,
        SessionFramePayload::VideoChunk {
            chunk_index: 3,
            presentation_time_us,
            h264_bytes: payload,
        },
    )
}

#[allow(dead_code)]
fn video_fragment_bulk_frame(
    session_id: &str,
    kind: VideoFrameKind,
    presentation_time_us: i64,
    fragment_index: i32,
    fragment_count: i32,
    total_h264_bytes: i32,
    payload: Vec<u8>,
) -> BulkFrame {
    video_fragment_bulk_frame_with_sequence(
        9,
        session_id,
        kind,
        presentation_time_us,
        fragment_index,
        fragment_count,
        total_h264_bytes,
        payload,
    )
}

fn video_fragment_bulk_frame_with_sequence(
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

fn video_v2_bulk_frame(
    kind: VideoFrameKind,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    video_v2_bulk_frame_with_sequence(8, kind, presentation_time_us, payload)
}

fn video_v2_bulk_frame_with_sequence(
    sequence: i32,
    kind: VideoFrameKind,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    video_v2_bulk_frame_with_sequence_and_session(
        sequence,
        "session-a",
        kind,
        presentation_time_us,
        payload,
    )
}

fn video_v2_bulk_frame_with_sequence_and_session(
    sequence: i32,
    session_id: &str,
    kind: VideoFrameKind,
    presentation_time_us: i64,
    payload: Vec<u8>,
) -> BulkFrame {
    let session = SessionFrame::new(
        sequence,
        session_id,
        match kind {
            VideoFrameKind::Key => {
                SessionFramePayload::video_chunk_v2_key(4, presentation_time_us, payload)
            }
            VideoFrameKind::CodecConfig => {
                SessionFramePayload::video_chunk_v2_codec_config(4, presentation_time_us, payload)
            }
            VideoFrameKind::Delta => {
                SessionFramePayload::video_chunk_v2_delta(4, presentation_time_us, payload)
            }
        },
    );
    BulkFrame::new(
        USB_SESSION_FRAME_STREAM_ID,
        SessionFrameCodec::encode(&session).unwrap(),
    )
    .unwrap()
}

fn video_bulk_frame_with_session_bytes(target_session_bytes: usize) -> BulkFrame {
    let overhead_without_h264_bytes = video_bulk_frame(1, Vec::new()).payload().len();
    let h264_len = target_session_bytes
        .checked_sub(overhead_without_h264_bytes)
        .expect("target session bytes must fit legacy video overhead");
    video_bulk_frame(1, vec![0x65; h264_len])
}

fn video_v2_bulk_frame_with_session_bytes(target_session_bytes: usize) -> BulkFrame {
    let overhead_without_h264_bytes = video_v2_bulk_frame(VideoFrameKind::Key, 1, vec![0x65])
        .payload()
        .len()
        - 1;
    let h264_len = target_session_bytes
        .checked_sub(overhead_without_h264_bytes)
        .expect("target session bytes must fit v2 video overhead");
    video_v2_bulk_frame(VideoFrameKind::Key, 1, vec![0x65; h264_len])
}

fn non_video_bulk_frame() -> BulkFrame {
    let session = SessionFrame::new(
        1,
        "session-a",
        SessionFramePayload::HandshakeAccept {
            desktop_id: "desktop".to_string(),
            message: "ok".to_string(),
        },
    );
    BulkFrame::new(
        USB_SESSION_FRAME_STREAM_ID,
        SessionFrameCodec::encode(&session).unwrap(),
    )
    .unwrap()
}

#[test]
fn desktop_receiver_session_replay_same_sequence_closes_without_second_sink_push() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(2).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence(10, 1_000, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    assert!(receiver
        .receive(
            &video_bulk_frame_with_sequence(10, 1_001, vec![0x41]),
            &mut queue
        )
        .is_err());
    assert_eq!(queue.len(), 1);
    assert_eq!(
        receiver.receive(
            &video_bulk_frame_with_sequence(11, 1_002, vec![0x41]),
            &mut queue
        ),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    receiver
        .receive(
            &video_bulk_frame_with_sequence_and_session(0, "session-fresh", 1_003, vec![0x65]),
            &mut queue,
        )
        .unwrap();
}

// Task s1 (`odd/tasks/session-runtime.md`, contract section 4.2): the receiver now requires a
// strictly INCREASING sequence (shared with interleaved keepalives, which consume sequence
// numbers the video receiver never sees) rather than an exact +1 between video frames. A gap
// is therefore accepted; only an equal or decreasing sequence, or a different session id, is
// rejected. This test was `desktop_receiver_session_gap_sequence_closes_without_sink_push`
// before s1, where it asserted the now-removed exact-+1 gap rejection.
#[test]
fn desktop_receiver_session_sequence_gap_is_accepted_decreasing_closes() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence(20, 2_000, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    // A gap (keepalives consumed 21) is now accepted: strictly increasing, same session id.
    receiver
        .receive(
            &video_bulk_frame_with_sequence(22, 2_002, vec![0x41]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(queue.len(), 2);
    // A decreasing sequence is still rejected and closes the session.
    assert!(receiver
        .receive(
            &video_bulk_frame_with_sequence(21, 2_001, vec![0x41]),
            &mut queue
        )
        .is_err());
    assert_eq!(
        receiver.receive(
            &video_bulk_frame_with_sequence(23, 2_003, vec![0x41]),
            &mut queue
        ),
        Err(DesktopReceiverError::Closed)
    );
}

// Task s1 RED (`odd/tasks/session-runtime.md`, contract section 4.2): a keepalive-bearing
// session advances the shared sequence counter between video frames, so the video receiver
// legitimately sees gaps. It must accept them while still rejecting an equal sequence.
#[test]
fn receiver_accepts_sequence_gaps_from_interleaved_keepalives() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(4).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence(20, 2_000, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    // Keepalives in the shared session sequence consumed 21 and 22; the next video frame is 23.
    receiver
        .receive(
            &video_bulk_frame_with_sequence(23, 2_003, vec![0x41]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(queue.len(), 2);
    // An equal sequence is still rejected.
    assert!(receiver
        .receive(
            &video_bulk_frame_with_sequence(23, 2_004, vec![0x41]),
            &mut queue
        )
        .is_err());
}

// Task s1 RED (`odd/tasks/session-runtime.md`, contract section 4.4 / task 2): the receiver
// accepts an already-decoded `SessionFrame` directly (the runtime decodes once, then
// dispatches), with the same binding/sequence behaviour as the `BulkFrame` path.
#[test]
fn receiver_accepts_session_frames_directly() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();

    receiver
        .receive_session_frame(
            &video_session_frame(5, "session-direct", 9_000, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive_session_frame(
            &video_session_frame(6, "session-direct", 9_001, vec![0x41]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(queue.len(), 2);
    let chunk = queue.pop_front().unwrap();
    assert_eq!(chunk.stream_id(), USB_SESSION_FRAME_STREAM_ID);
    assert_eq!(chunk.presentation_timestamp().as_micros(), 9_000);
    // A different session id is still rejected and closes the session.
    assert!(receiver
        .receive_session_frame(
            &video_session_frame(7, "session-other", 9_002, vec![0x41]),
            &mut queue
        )
        .is_err());
    assert_eq!(
        receiver.receive_session_frame(
            &video_session_frame(8, "session-direct", 9_003, vec![0x41]),
            &mut queue
        ),
        Err(DesktopReceiverError::Closed)
    );
}

#[test]
fn desktop_receiver_session_mixed_session_id_closes_before_push_or_reassembly() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence_and_session(0, "session-a", 3_000, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    assert!(receiver
        .receive(
            &video_bulk_frame_with_sequence_and_session(1, "session-b", 3_001, vec![0x41]),
            &mut queue,
        )
        .is_err());
    assert_eq!(queue.len(), 1);
    assert_eq!(
        receiver.receive(
            &video_bulk_frame_with_sequence_and_session(2, "session-a", 3_002, vec![0x41]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    receiver
        .receive(
            &video_v2_bulk_frame_with_sequence_and_session(
                0,
                "session-a",
                VideoFrameKind::Key,
                3_010,
                vec![0x65],
            ),
            &mut queue,
        )
        .unwrap();
    assert!(receiver
        .receive(
            &video_v2_bulk_frame_with_sequence_and_session(
                1,
                "session-b",
                VideoFrameKind::Delta,
                3_011,
                vec![0x41],
            ),
            &mut queue,
        )
        .is_err());
    assert_eq!(queue.len(), 2);

    receiver.reset_for_new_session();
    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                0,
                "session-a",
                VideoFrameKind::Key,
                3_020,
                0,
                2,
                3,
                vec![0x00],
            ),
            &mut queue,
        )
        .unwrap();
    assert!(receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                1,
                "session-b",
                VideoFrameKind::Key,
                3_020,
                1,
                2,
                3,
                vec![0x01, 0x65]
            ),
            &mut queue,
        )
        .is_err());
    assert_eq!(queue.len(), 2);
    assert_eq!(
        receiver.receive(
            &video_fragment_bulk_frame_with_sequence(
                2,
                "session-a",
                VideoFrameKind::Key,
                3_020,
                1,
                2,
                3,
                vec![0x01, 0x65]
            ),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );
}

#[test]
fn desktop_receiver_session_strict_monotonic_happy_path_spans_type5_type8_type9() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence(0, 4_000, vec![0x41]),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &video_v2_bulk_frame_with_sequence(1, VideoFrameKind::Key, 4_001, vec![0x65]),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                2,
                "session-a",
                VideoFrameKind::CodecConfig,
                4_002,
                0,
                2,
                3,
                vec![0x00],
            ),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                3,
                "session-a",
                VideoFrameKind::CodecConfig,
                4_002,
                1,
                2,
                3,
                vec![0x01, 0x67],
            ),
            &mut queue,
        )
        .unwrap();

    assert_eq!(queue.pop_front().unwrap().payload(), &[0x41]);
    assert_eq!(
        queue.pop_front().unwrap().frame_kind(),
        EncodedVideoFrameKind::Key
    );
    let fragment = queue.pop_front().unwrap();
    assert_eq!(fragment.frame_kind(), EncodedVideoFrameKind::CodecConfig);
    assert_eq!(fragment.payload(), &[0x00, 0x01, 0x67]);
}

/// Task f2 (`odd/tasks/review-followups.md`, contract section 4.2): the receiver counts whole
/// chunks delivered to the sink (type 5, type 8, one per reassembled type-9 set), never partial
/// fragments, and never a chunk the sink rejected.
#[test]
fn receiver_counts_whole_chunks_delivered_not_fragments() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();

    receiver
        .receive(
            &video_bulk_frame_with_sequence(0, 4_000, vec![0x41]),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &video_v2_bulk_frame_with_sequence(1, VideoFrameKind::Key, 4_001, vec![0x65, 0x88]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(receiver.chunks_delivered(), 2);
    assert_eq!(receiver.chunk_bytes_delivered(), 3);

    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                2,
                "session-a",
                VideoFrameKind::CodecConfig,
                4_002,
                0,
                2,
                3,
                vec![0x00],
            ),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.chunks_delivered(),
        2,
        "a partial fragment is not a delivered chunk"
    );

    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                3,
                "session-a",
                VideoFrameKind::CodecConfig,
                4_002,
                1,
                2,
                3,
                vec![0x01, 0x67],
            ),
            &mut queue,
        )
        .unwrap();
    assert_eq!(receiver.chunks_delivered(), 3);
    assert_eq!(receiver.chunk_bytes_delivered(), 6);

    // The queue is full: the sink rejects the next chunk, which must not be counted.
    assert!(receiver
        .receive(
            &video_v2_bulk_frame_with_sequence(4, VideoFrameKind::Delta, 4_003, vec![0x41]),
            &mut queue,
        )
        .is_err());
    assert_eq!(receiver.chunks_delivered(), 3);
    assert_eq!(receiver.chunk_bytes_delivered(), 6);
}

#[test]
fn desktop_receiver_session_type9_first_fragment_returns_ok_without_sink_push() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let frame = video_fragment_bulk_frame_with_sequence(
        0,
        "session-fragment",
        VideoFrameKind::Key,
        55_555,
        0,
        2,
        4,
        vec![0x00, 0x00],
    );

    receiver.receive(&frame, &mut queue).unwrap();

    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_session_type9_final_fragment_emits_concatenated_chunk() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();

    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                0,
                "session-fragment",
                VideoFrameKind::CodecConfig,
                66_666,
                0,
                2,
                5,
                vec![0x00, 0x00],
            ),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                1,
                "session-fragment",
                VideoFrameKind::CodecConfig,
                66_666,
                1,
                2,
                5,
                vec![0x01, 0x67, 0x64],
            ),
            &mut queue,
        )
        .unwrap();

    let chunk = queue.pop_front().unwrap();
    assert_eq!(chunk.stream_id(), USB_SESSION_FRAME_STREAM_ID);
    assert_eq!(chunk.presentation_timestamp().as_micros(), 66_666);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::CodecConfig);
    assert_eq!(chunk.payload(), &[0x00, 0x00, 0x01, 0x67, 0x64]);
}

#[test]
fn desktop_receiver_session_type9_pending_rejects_type8_and_type5_until_reset() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let partial = |sequence| {
        video_fragment_bulk_frame_with_sequence(
            sequence,
            "session-fragment",
            VideoFrameKind::Key,
            77_777,
            0,
            2,
            3,
            vec![0x00],
        )
    };
    let final_fragment = |sequence| {
        video_fragment_bulk_frame_with_sequence(
            sequence,
            "session-fragment",
            VideoFrameKind::Key,
            77_777,
            1,
            2,
            3,
            vec![0x01, 0x65],
        )
    };

    receiver.receive(&partial(0), &mut queue).unwrap();
    assert_eq!(
        receiver.receive(
            &video_v2_bulk_frame_with_sequence_and_session(
                1,
                "session-fragment",
                VideoFrameKind::Delta,
                77_778,
                vec![0x41],
            ),
            &mut queue,
        ),
        Err(DesktopReceiverError::UnexpectedSessionPayload { type_id: 8 })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment(2), &mut queue),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    receiver.receive(&partial(0), &mut queue).unwrap();
    assert_eq!(
        receiver.receive(
            &video_bulk_frame_with_sequence_and_session(1, "session-fragment", 77_779, vec![0x65]),
            &mut queue
        ),
        Err(DesktopReceiverError::UnexpectedSessionPayload { type_id: 5 })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment(2), &mut queue),
        Err(DesktopReceiverError::Closed)
    );
}

#[test]
fn desktop_receiver_preserves_injected_frame_kind_classification() {
    for frame_kind in [
        EncodedVideoFrameKind::Key,
        EncodedVideoFrameKind::CodecConfig,
        EncodedVideoFrameKind::Delta,
    ] {
        let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();
        let mut classifier = StaticFrameKindClassifier::known(frame_kind);
        let frame = video_bulk_frame(33_366, vec![0x00, 0x00, 0x01, 0x65]);

        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue).unwrap();

        let chunk = queue.pop_front().unwrap();
        assert_eq!(chunk.stream_id(), USB_SESSION_FRAME_STREAM_ID);
        assert_eq!(chunk.presentation_timestamp().as_micros(), 33_366);
        assert_eq!(chunk.frame_kind(), frame_kind);
        assert_eq!(chunk.payload(), &[0x00, 0x00, 0x01, 0x65]);
    }
}

#[test]
fn desktop_receiver_accepts_video_chunk_v2_direct_frame_kind_without_classifier() {
    for (wire_kind, expected_kind, payload) in [
        (
            VideoFrameKind::Key,
            EncodedVideoFrameKind::Key,
            vec![0x00, 0x00, 0x01, 0x65],
        ),
        (
            VideoFrameKind::CodecConfig,
            EncodedVideoFrameKind::CodecConfig,
            vec![0x00, 0x00, 0x01, 0x67],
        ),
        (
            VideoFrameKind::Delta,
            EncodedVideoFrameKind::Delta,
            vec![0x41],
        ),
    ] {
        let mut queue = BoundedEncodedVideoQueue::new(3).unwrap();
        let mut classifier = PanicIfCalledClassifier;
        let frame = video_v2_bulk_frame(wire_kind, 44_488, payload.clone());

        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue).unwrap();

        let chunk = queue.pop_front().unwrap();
        assert_eq!(chunk.stream_id(), USB_SESSION_FRAME_STREAM_ID);
        assert_eq!(chunk.presentation_timestamp().as_micros(), 44_488);
        assert_eq!(chunk.frame_kind(), expected_kind);
        assert_eq!(chunk.payload(), payload.as_slice());
    }
}

#[test]
fn desktop_receiver_rejects_malformed_session_frame() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);
    let frame = BulkFrame::new(USB_SESSION_FRAME_STREAM_ID, vec![b'C', b'C']).unwrap();

    assert!(matches!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::MalformedSessionFrame(_))
    ));
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_accepts_max_usb_session_frame_budget_for_legacy_and_v2() {
    let cases = [
        (
            video_bulk_frame_with_session_bytes(MAX_USB_SESSION_FRAME_BYTES),
            EncodedVideoFrameKind::Delta,
        ),
        (
            video_v2_bulk_frame_with_session_bytes(MAX_USB_SESSION_FRAME_BYTES),
            EncodedVideoFrameKind::Key,
        ),
    ];

    for (frame, expected_kind) in cases {
        assert_eq!(frame.payload().len(), MAX_USB_SESSION_FRAME_BYTES);
        let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
        let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);

        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue).unwrap();

        let chunk = queue.pop_front().unwrap();
        assert_eq!(chunk.frame_kind(), expected_kind);
        assert!(!chunk.payload().is_empty());
    }
}

#[test]
fn desktop_receiver_rejects_oversize_usb_session_frame_budget_before_sink_push() {
    for frame in [
        video_bulk_frame_with_session_bytes(MAX_USB_SESSION_FRAME_BYTES + 1),
        video_v2_bulk_frame_with_session_bytes(MAX_USB_SESSION_FRAME_BYTES + 1),
    ] {
        assert_eq!(frame.payload().len(), MAX_USB_SESSION_FRAME_BYTES + 1);
        let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
        let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);

        assert_eq!(
            usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
            Err(DesktopReceiverError::MalformedSessionFrame(
                SessionFrameDecodeError::FrameTooLarge {
                    actual_size: MAX_USB_SESSION_FRAME_BYTES + 1,
                    max_size: MAX_USB_SESSION_FRAME_BYTES,
                }
            ))
        );
        assert!(queue.is_empty());
    }
}

#[test]
fn desktop_receiver_rejects_wrong_bulk_stream_at_boundary() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);
    let frame = BulkFrame::new(0x1111_2222, vec![b'C', b'C']).unwrap();

    assert_eq!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::UnexpectedBulkStream {
            actual: 0x1111_2222,
            expected: USB_SESSION_FRAME_STREAM_ID,
        })
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_rejects_non_video_session_payload() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);
    let frame = non_video_bulk_frame();

    assert_eq!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::UnexpectedSessionPayload { type_id: 2 })
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_rejects_negative_video_presentation_timestamp() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);
    let frame = video_bulk_frame(-1, vec![0x65]);

    assert_eq!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::NegativePresentationTimestamp(-1))
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_rejects_unknown_injected_frame_kind() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::unknown();
    let frame = video_bulk_frame(1, vec![0x65]);

    assert_eq!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::UnknownFrameKind)
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_rejects_unknown_video_chunk_v2_kind_as_malformed_frame() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = PanicIfCalledClassifier;
    let mut encoded = video_v2_bulk_frame(VideoFrameKind::Key, 1, vec![0x65])
        .payload()
        .to_vec();
    encoded[37] = 99;
    let frame = BulkFrame::new(USB_SESSION_FRAME_STREAM_ID, encoded).unwrap();

    assert!(matches!(
        usb_probe::receive_desktop_video_frame(&frame, &mut classifier, &mut queue),
        Err(DesktopReceiverError::MalformedSessionFrame(_))
    ));
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_reports_queue_backpressure_without_dropping_existing_chunk() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta);

    usb_probe::receive_desktop_video_frame(
        &video_bulk_frame(1, vec![0x65]),
        &mut classifier,
        &mut queue,
    )
    .unwrap();

    assert_eq!(
        usb_probe::receive_desktop_video_frame(
            &video_bulk_frame(2, vec![0x41]),
            &mut classifier,
            &mut queue,
        ),
        Err(DesktopReceiverError::SinkRejected(
            usb_probe::EncodedVideoSinkError::QueueFull { capacity: 1 }
        ))
    );

    let retained = queue.pop_front().unwrap();
    assert_eq!(retained.presentation_timestamp().as_micros(), 1);
    assert_eq!(retained.payload(), &[0x65]);
    assert!(queue.pop_front().is_none());
}

#[test]
fn desktop_receiver_reports_video_chunk_v2_queue_backpressure() {
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let mut classifier = PanicIfCalledClassifier;

    usb_probe::receive_desktop_video_frame(
        &video_v2_bulk_frame(VideoFrameKind::Delta, 1, vec![0x65]),
        &mut classifier,
        &mut queue,
    )
    .unwrap();

    assert_eq!(
        usb_probe::receive_desktop_video_frame(
            &video_v2_bulk_frame(VideoFrameKind::Key, 2, vec![0x65]),
            &mut classifier,
            &mut queue,
        ),
        Err(DesktopReceiverError::SinkRejected(
            usb_probe::EncodedVideoSinkError::QueueFull { capacity: 1 }
        ))
    );

    let retained = queue.pop_front().unwrap();
    assert_eq!(retained.presentation_timestamp().as_micros(), 1);
    assert_eq!(retained.frame_kind(), EncodedVideoFrameKind::Delta);
    assert!(queue.pop_front().is_none());
}

fn type9_part(
    sequence: i32,
    session_id: &str,
    pts: i64,
    fragment_index: i32,
    payload: &[u8],
) -> BulkFrame {
    video_fragment_bulk_frame_with_sequence(
        sequence,
        session_id,
        VideoFrameKind::Key,
        pts,
        fragment_index,
        2,
        3,
        payload.to_vec(),
    )
}

fn assert_accepts_fresh_two_fragment_sequence(
    receiver: &mut usb_probe::DesktopVideoSessionReceiver<PanicIfCalledClassifier>,
    queue: &mut BoundedEncodedVideoQueue,
    pts: i64,
) {
    receiver
        .receive(
            &type9_part(0, "session-after-reset", pts, 0, &[0x00]),
            queue,
        )
        .unwrap();
    receiver
        .receive(
            &type9_part(1, "session-after-reset", pts, 1, &[0x01, 0x65]),
            queue,
        )
        .unwrap();

    let chunk = queue.pop_front().unwrap();
    assert_eq!(chunk.presentation_timestamp().as_micros(), pts as u64);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(chunk.payload(), &[0x00, 0x01, 0x65]);
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_session_type9_pending_wrong_bulk_stream_closes_until_reset() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let final_fragment = type9_part(1, "session-fragment", 101_001, 1, &[0x01, 0x65]);

    receiver
        .receive(
            &type9_part(0, "session-fragment", 101_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.receive(
            &BulkFrame::new(0x1111_2222, vec![b'C']).unwrap(),
            &mut queue
        ),
        Err(DesktopReceiverError::UnexpectedBulkStream {
            actual: 0x1111_2222,
            expected: USB_SESSION_FRAME_STREAM_ID,
        })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment, &mut queue),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut queue, 101_002);
}

#[test]
fn desktop_receiver_session_type9_pending_malformed_session_frame_closes_until_reset() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let final_fragment = type9_part(1, "session-fragment", 202_001, 1, &[0x01, 0x65]);

    receiver
        .receive(
            &type9_part(0, "session-fragment", 202_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert!(matches!(
        receiver.receive(
            &BulkFrame::new(USB_SESSION_FRAME_STREAM_ID, vec![b'C']).unwrap(),
            &mut queue,
        ),
        Err(DesktopReceiverError::MalformedSessionFrame(_))
    ));
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment, &mut queue),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut queue, 202_002);
}

#[test]
fn desktop_receiver_session_type9_final_queue_full_closes_until_reset() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();

    receiver
        .receive(
            &video_fragment_bulk_frame_with_sequence(
                0,
                "session-fill",
                VideoFrameKind::Delta,
                303_000,
                0,
                1,
                1,
                vec![0x41],
            ),
            &mut queue,
        )
        .unwrap();
    receiver
        .receive(
            &type9_part(1, "session-fill", 303_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.receive(
            &type9_part(2, "session-fill", 303_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::SinkRejected(
            usb_probe::EncodedVideoSinkError::QueueFull { capacity: 1 }
        ))
    );
    assert_eq!(queue.len(), 1);
    assert_eq!(
        receiver.receive(
            &type9_part(3, "session-fill", 303_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    let mut accepting_queue = BoundedEncodedVideoQueue::new(1).unwrap();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut accepting_queue, 303_002);
}

#[test]
fn desktop_receiver_session_type9_final_queue_bytes_full_closes_until_reset() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::with_limits(2, 2).unwrap();

    receiver
        .receive(
            &type9_part(0, "session-fragment", 404_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.receive(
            &type9_part(1, "session-fragment", 404_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::SinkRejected(
            usb_probe::EncodedVideoSinkError::QueueBytesFull {
                queued: 0,
                incoming: 3,
                max: 2,
            }
        ))
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(
            &type9_part(2, "session-fragment", 404_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    let mut accepting_queue = BoundedEncodedVideoQueue::new(1).unwrap();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut accepting_queue, 404_002);
}

#[test]
fn desktop_receiver_session_v2_max_sequence_emits_once_then_closes() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(2).unwrap();

    receiver
        .receive(
            &video_v2_bulk_frame_with_sequence(i32::MAX, VideoFrameKind::Key, 505_001, vec![0x65]),
            &mut queue,
        )
        .unwrap();

    assert_eq!(queue.len(), 1);
    let chunk = queue.pop_front().unwrap();
    assert_eq!(chunk.presentation_timestamp().as_micros(), 505_001);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(chunk.payload(), &[0x65]);
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(
            &video_v2_bulk_frame_with_sequence(
                i32::MAX,
                VideoFrameKind::Delta,
                505_002,
                vec![0x41],
            ),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_session_type9_final_max_sequence_emits_once_then_closes() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(2).unwrap();

    receiver
        .receive(
            &type9_part(i32::MAX - 1, "session-max-fragment", 606_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert!(queue.is_empty());
    receiver
        .receive(
            &type9_part(i32::MAX, "session-max-fragment", 606_001, 1, &[0x01, 0x65]),
            &mut queue,
        )
        .unwrap();

    assert_eq!(queue.len(), 1);
    let chunk = queue.pop_front().unwrap();
    assert_eq!(chunk.presentation_timestamp().as_micros(), 606_001);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(chunk.payload(), &[0x00, 0x01, 0x65]);
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(
            &type9_part(i32::MAX, "session-max-fragment", 606_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );
    assert!(queue.is_empty());
}

#[test]
fn desktop_receiver_session_type9_partial_max_sequence_exhausts_closes_and_resets() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(PanicIfCalledClassifier);
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();

    assert_eq!(
        receiver.receive(
            &type9_part(i32::MAX, "session-max-partial", 707_001, 0, &[0x00]),
            &mut queue,
        ),
        Err(DesktopReceiverError::SessionSequenceExhausted { sequence: i32::MAX })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(
            &type9_part(i32::MAX, "session-max-partial", 707_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );
    assert!(queue.is_empty());

    receiver.reset_for_new_session();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut queue, 707_002);
}

struct PanicIfCalledClassifier;

impl VideoFrameKindClassifier for PanicIfCalledClassifier {
    fn classify_frame_kind(
        &mut self,
        _frame: &SessionFrame,
    ) -> Result<Option<EncodedVideoFrameKind>, DesktopReceiverError> {
        panic!("v2 receiver path must not call legacy frame kind classifier")
    }
}
