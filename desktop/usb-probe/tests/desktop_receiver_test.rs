use usb_probe::{
    BoundedEncodedVideoQueue, BulkFrame, DesktopReceiverError, EncodedVideoFrameKind, SessionFrame,
    SessionFrameCodec, SessionFrameDecodeError, SessionFramePayload, StaticFrameKindClassifier,
    VideoFrameKind, VideoFrameKindClassifier, USB_SESSION_FRAME_STREAM_ID,
};

const MAX_USB_SESSION_FRAME_BYTES: usize = 65_528;

fn video_bulk_frame(presentation_time_us: i64, payload: Vec<u8>) -> BulkFrame {
    let session = SessionFrame::new(
        7,
        "session-a",
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

fn video_fragment_bulk_frame(
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
    let session = SessionFrame::new(9, session_id, payload);
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
    let session = SessionFrame::new(
        8,
        "session-a",
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
fn desktop_receiver_session_type9_first_fragment_returns_ok_without_sink_push() {
    let mut receiver = usb_probe::DesktopVideoSessionReceiver::new(
        StaticFrameKindClassifier::known(EncodedVideoFrameKind::Delta),
    );
    let mut queue = BoundedEncodedVideoQueue::new(1).unwrap();
    let frame = video_fragment_bulk_frame(
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
            &video_fragment_bulk_frame(
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
            &video_fragment_bulk_frame(
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
    let partial = || {
        video_fragment_bulk_frame(
            "session-fragment",
            VideoFrameKind::Key,
            77_777,
            0,
            2,
            3,
            vec![0x00],
        )
    };
    let final_fragment = || {
        video_fragment_bulk_frame(
            "session-fragment",
            VideoFrameKind::Key,
            77_777,
            1,
            2,
            3,
            vec![0x01, 0x65],
        )
    };

    receiver.receive(&partial(), &mut queue).unwrap();
    assert_eq!(
        receiver.receive(
            &video_v2_bulk_frame(VideoFrameKind::Delta, 77_778, vec![0x41]),
            &mut queue,
        ),
        Err(DesktopReceiverError::UnexpectedSessionPayload { type_id: 8 })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment(), &mut queue),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    receiver.receive(&partial(), &mut queue).unwrap();
    assert_eq!(
        receiver.receive(&video_bulk_frame(77_779, vec![0x65]), &mut queue),
        Err(DesktopReceiverError::UnexpectedSessionPayload { type_id: 5 })
    );
    assert!(queue.is_empty());
    assert_eq!(
        receiver.receive(&final_fragment(), &mut queue),
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

fn type9_part(session_id: &str, pts: i64, fragment_index: i32, payload: &[u8]) -> BulkFrame {
    video_fragment_bulk_frame(
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
        .receive(&type9_part("session-after-reset", pts, 0, &[0x00]), queue)
        .unwrap();
    receiver
        .receive(
            &type9_part("session-after-reset", pts, 1, &[0x01, 0x65]),
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
    let final_fragment = type9_part("session-fragment", 101_001, 1, &[0x01, 0x65]);

    receiver
        .receive(
            &type9_part("session-fragment", 101_001, 0, &[0x00]),
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
    let final_fragment = type9_part("session-fragment", 202_001, 1, &[0x01, 0x65]);

    receiver
        .receive(
            &type9_part("session-fragment", 202_001, 0, &[0x00]),
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
            &video_fragment_bulk_frame(
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
            &type9_part("session-fragment", 303_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.receive(
            &type9_part("session-fragment", 303_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::SinkRejected(
            usb_probe::EncodedVideoSinkError::QueueFull { capacity: 1 }
        ))
    );
    assert_eq!(queue.len(), 1);
    assert_eq!(
        receiver.receive(
            &type9_part("session-fragment", 303_001, 1, &[0x01, 0x65]),
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
            &type9_part("session-fragment", 404_001, 0, &[0x00]),
            &mut queue,
        )
        .unwrap();
    assert_eq!(
        receiver.receive(
            &type9_part("session-fragment", 404_001, 1, &[0x01, 0x65]),
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
            &type9_part("session-fragment", 404_001, 1, &[0x01, 0x65]),
            &mut queue,
        ),
        Err(DesktopReceiverError::Closed)
    );

    receiver.reset_for_new_session();
    let mut accepting_queue = BoundedEncodedVideoQueue::new(1).unwrap();
    assert_accepts_fresh_two_fragment_sequence(&mut receiver, &mut accepting_queue, 404_002);
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
