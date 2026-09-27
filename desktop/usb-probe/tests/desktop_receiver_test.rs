use usb_probe::{
    BoundedEncodedVideoQueue, BulkFrame, DesktopReceiverError, EncodedVideoFrameKind, SessionFrame,
    SessionFrameCodec, SessionFramePayload, StaticFrameKindClassifier, USB_SESSION_FRAME_STREAM_ID,
};

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
