use std::collections::BTreeMap;

use usb_probe::{
    BulkFrame, SessionFrame, SessionFrameCodec, SessionFrameDecodeError, SessionFrameEncodeError,
    SessionFramePayload,
};

// Golden bytes copied from Android SessionFrameCodec contract at
// /Users/jele/Desktop/codes/ChinchillaCam-usb-bulk-tdd commit
// f8504a91c27efb1be0004470b1ee30334d5ddc8b for:
// SessionFrame(1, 9, "abc", SessionPayload.HandshakeAccept("pc", "ok"))
const HANDSHAKE_ACCEPT_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 2, 0, 0, 0, 9, 0, 3, b'a', b'b', b'c', 0, 0, 0, 8, 0, 2, b'p', b'c',
    0, 2, b'o', b'k',
];

// Golden bytes copied from the same Android SessionFrameCodec contract for:
// SessionFrame(1, 0, "session-a", SessionPayload.HandshakeHello("android-phone", "ChinchillaCam", listOf("h264", "metrics")))
const HANDSHAKE_HELLO_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 1, 0, 0, 0, 0, 0, 9, b's', b'e', b's', b's', b'i', b'o', b'n', b'-',
    b'a', 0, 0, 0, 47, 0, 13, b'a', b'n', b'd', b'r', b'o', b'i', b'd', b'-', b'p', b'h', b'o',
    b'n', b'e', 0, 13, b'C', b'h', b'i', b'n', b'c', b'h', b'i', b'l', b'l', b'a', b'C', b'a',
    b'm', 0, 2, 0, 4, b'h', b'2', b'6', b'4', 0, 7, b'm', b'e', b't', b'r', b'i', b'c', b's',
];

// Golden bytes copied from the same Android SessionFrameCodec contract for:
// SessionFrame(1, 2, "session-a", SessionPayload.HandshakeReject("busy", "Ya hay una computadora activa"))
const HANDSHAKE_REJECT_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 3, 0, 0, 0, 2, 0, 9, b's', b'e', b's', b's', b'i', b'o', b'n', b'-',
    b'a', 0, 0, 0, 37, 0, 4, b'b', b'u', b's', b'y', 0, 29, b'Y', b'a', b' ', b'h', b'a', b'y',
    b' ', b'u', b'n', b'a', b' ', b'c', b'o', b'm', b'p', b'u', b't', b'a', b'd', b'o', b'r', b'a',
    b' ', b'a', b'c', b't', b'i', b'v', b'a',
];

// Golden bytes copied from the same Android SessionFrameCodec contract for:
// SessionFrame(1, 3, "session-a", SessionPayload.StreamMetadata("video/h264", 1920, 1080, 30, "baseline"))
const STREAM_METADATA_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 4, 0, 0, 0, 3, 0, 9, b's', b'e', b's', b's', b'i', b'o', b'n', b'-',
    b'a', 0, 0, 0, 34, 0, 10, b'v', b'i', b'd', b'e', b'o', b'/', b'h', b'2', b'6', b'4', 0, 0, 7,
    128, 0, 0, 4, 56, 0, 0, 0, 30, 0, 8, b'b', b'a', b's', b'e', b'l', b'i', b'n', b'e',
];

// Golden bytes copied from the same Android SessionFrameCodec contract for:
// SessionFrame(1, 5, "session-a", SessionPayload.MetricsSnapshot(123456790L, 42, 18, 30))
const METRICS_SNAPSHOT_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 6, 0, 0, 0, 5, 0, 9, b's', b'e', b's', b's', b'i', b'o', b'n', b'-',
    b'a', 0, 0, 0, 20, 0, 0, 0, 0, 7, 91, 205, 22, 0, 0, 0, 42, 0, 0, 0, 18, 0, 0, 0, 30,
];

const VIDEO_CHUNK_LEGACY_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 5, 0, 0, 0, 0, 0, 1, b's', 0, 0, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 1, 0x65,
];

const VIDEO_CHUNK_V2_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 8, 0, 0, 0, 0, 0, 1, b's', 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 1, 0, 1, 0x65,
];

const VIDEO_CHUNK_FRAGMENT_V1_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 9, 0, 0, 0, 0, 0, 1, b's', 0, 0, 0, 28, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1, 0x11, 0x70, 0, 1, 0x65,
];

#[test]
fn session_frame_encodes_android_golden_handshake_hello_big_endian() {
    let frame = SessionFrame::new(
        0,
        "session-a",
        SessionFramePayload::HandshakeHello {
            device_id: "android-phone".to_string(),
            app_name: "ChinchillaCam".to_string(),
            capabilities: vec!["h264".to_string(), "metrics".to_string()],
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        HANDSHAKE_HELLO_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(HANDSHAKE_HELLO_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_handshake_accept_big_endian() {
    let frame = SessionFrame::new(
        9,
        "abc",
        SessionFramePayload::HandshakeAccept {
            desktop_id: "pc".to_string(),
            message: "ok".to_string(),
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        HANDSHAKE_ACCEPT_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(HANDSHAKE_ACCEPT_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_handshake_reject_big_endian() {
    let frame = SessionFrame::new(
        2,
        "session-a",
        SessionFramePayload::HandshakeReject {
            reason_code: "busy".to_string(),
            message: "Ya hay una computadora activa".to_string(),
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        HANDSHAKE_REJECT_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(HANDSHAKE_REJECT_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_stream_metadata_big_endian() {
    let frame = SessionFrame::new(
        3,
        "session-a",
        SessionFramePayload::StreamMetadata {
            mime_type: "video/h264".to_string(),
            width: 1920,
            height: 1080,
            frame_rate: 30,
            profile: "baseline".to_string(),
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        STREAM_METADATA_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(STREAM_METADATA_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_metrics_snapshot_big_endian() {
    let frame = SessionFrame::new(
        5,
        "session-a",
        SessionFramePayload::MetricsSnapshot {
            captured_at_us: 123_456_790,
            dropped_frames: 42,
            latency_ms: 18,
            frame_rate: 30,
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        METRICS_SNAPSHOT_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(METRICS_SNAPSHOT_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_preserves_legacy_video_chunk_type5_golden() {
    let frame = SessionFrame::new(
        0,
        "s",
        SessionFramePayload::VideoChunk {
            chunk_index: 0,
            presentation_time_us: 0,
            h264_bytes: vec![0x65],
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        VIDEO_CHUNK_LEGACY_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(VIDEO_CHUNK_LEGACY_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_video_chunk_v2_with_frame_kind() {
    let frame = SessionFrame::new(
        0,
        "s",
        SessionFramePayload::video_chunk_v2_key(0, 0, vec![0x65]),
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        VIDEO_CHUNK_V2_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(VIDEO_CHUNK_V2_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_encodes_android_golden_video_chunk_fragment_v1() {
    let frame = SessionFrame::new(
        0,
        "s",
        SessionFramePayload::video_chunk_fragment_v1_key(0, 0, 0, 2, 70_000, vec![0x65]),
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame).unwrap(),
        VIDEO_CHUNK_FRAGMENT_V1_GOLDEN
    );
    assert_eq!(
        SessionFrameCodec::decode(VIDEO_CHUNK_FRAGMENT_V1_GOLDEN).unwrap(),
        frame
    );
}

#[test]
fn session_frame_roundtrips_video_chunk_fragment_v1_kinds() {
    for payload in [
        SessionFramePayload::video_chunk_fragment_v1_delta(7, 123, 0, 1, 1, vec![0x41]),
        SessionFramePayload::video_chunk_fragment_v1_codec_config(
            8,
            456,
            1,
            2,
            4,
            vec![0x67, 0x42],
        ),
    ] {
        let frame = SessionFrame::new(3, "session-a", payload);
        let encoded = SessionFrameCodec::encode(&frame).unwrap();

        assert_eq!(SessionFrameCodec::decode(&encoded).unwrap(), frame);
    }
}

#[test]
fn session_frame_rejects_invalid_video_chunk_fragment_v1_values() {
    for (field, payload) in [
        (
            "chunk index",
            SessionFramePayload::video_chunk_fragment_v1_delta(-1, 0, 0, 1, 1, vec![0x41]),
        ),
        (
            "presentationTimeUs",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, -1, 0, 1, 1, vec![0x41]),
        ),
        (
            "fragment index",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, -1, 1, 1, vec![0x41]),
        ),
    ] {
        let frame = SessionFrame::new(1, "s", payload);
        assert_eq!(
            SessionFrameCodec::encode(&frame),
            Err(SessionFrameEncodeError::InvalidPayload(format!(
                "video chunk fragment v1 {field} must be non-negative"
            )))
        );
    }

    for (message, payload) in [
        (
            "video chunk fragment v1 fragment count must be positive",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 0, 1, vec![0x41]),
        ),
        (
            "video chunk fragment v1 fragment count must be <= 1024",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 1025, 1, vec![0x41]),
        ),
        (
            "video chunk fragment v1 fragment index must be less than fragment count",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 1, 1, 1, vec![0x41]),
        ),
        (
            "video chunk fragment v1 total h264Bytes must be positive",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 1, 0, vec![0x41]),
        ),
        (
            "video chunk fragment v1 total h264Bytes must be <= 4194304",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 1, 4_194_305, vec![0x41]),
        ),
        (
            "video chunk fragment v1 fragment h264Bytes must not be empty",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 1, 1, Vec::new()),
        ),
        (
            "video chunk fragment v1 fragment h264Bytes must not exceed total h264Bytes",
            SessionFramePayload::video_chunk_fragment_v1_delta(0, 0, 0, 1, 1, vec![0x41, 0x42]),
        ),
    ] {
        let frame = SessionFrame::new(1, "s", payload);
        assert_eq!(
            SessionFrameCodec::encode(&frame),
            Err(SessionFrameEncodeError::InvalidPayload(message.to_string()))
        );
    }

    let unknown_kind = bytes()
        .int(0)
        .long(0)
        .byte(99)
        .int(0)
        .int(1)
        .int(1)
        .short(1)
        .byte(0x41)
        .finish();
    assert_eq!(
        SessionFrameCodec::decode(&raw_frame(9, unknown_kind)),
        Err(SessionFrameDecodeError::InvalidPayload(
            "unknown video chunk fragment v1 kind: 99".to_string()
        ))
    );
}

#[test]
fn session_frame_rejects_invalid_video_chunk_v2_values() {
    for (field, chunk_index, presentation_time_us) in
        [("chunk index", -1, 0), ("presentationTimeUs", 0, -1)]
    {
        let frame = SessionFrame::new(
            1,
            "s",
            SessionFramePayload::video_chunk_v2_delta(
                chunk_index,
                presentation_time_us,
                vec![0x41],
            ),
        );
        assert_eq!(
            SessionFrameCodec::encode(&frame),
            Err(SessionFrameEncodeError::InvalidPayload(format!(
                "video chunk v2 {field} must be non-negative"
            )))
        );

        let payload = bytes()
            .int(chunk_index)
            .long(presentation_time_us)
            .byte(0)
            .short(1)
            .byte(0x41)
            .finish();
        assert_eq!(
            SessionFrameCodec::decode(&raw_frame(8, payload)),
            Err(SessionFrameDecodeError::InvalidPayload(format!(
                "video chunk v2 {field} must be non-negative"
            )))
        );
    }

    let unknown_kind = bytes().int(0).long(0).byte(99).short(1).byte(0x41).finish();
    assert_eq!(
        SessionFrameCodec::decode(&raw_frame(8, unknown_kind)),
        Err(SessionFrameDecodeError::InvalidPayload(
            "unknown video chunk v2 kind: 99".to_string()
        ))
    );

    let empty_bytes = bytes().int(0).long(0).byte(1).short(0).finish();
    assert_eq!(
        SessionFrameCodec::decode(&raw_frame(8, empty_bytes)),
        Err(SessionFrameDecodeError::InvalidPayload(
            "video chunk v2 h264Bytes must not be empty".to_string()
        ))
    );

    let oversized_bytes = SessionFrame::new(
        1,
        "s",
        SessionFramePayload::video_chunk_v2_codec_config(0, 0, vec![0x41; u16::MAX as usize + 1]),
    );
    assert_eq!(
        SessionFrameCodec::encode(&oversized_bytes),
        Err(SessionFrameEncodeError::FieldTooLarge("h264Bytes"))
    );
}

#[test]
fn session_frame_roundtrips_video_chunk_without_transport_claims() {
    let frame = SessionFrame::new(
        4,
        "session-a",
        SessionFramePayload::VideoChunk {
            chunk_index: 7,
            presentation_time_us: 123_456_789,
            h264_bytes: vec![0x00, 0x00, 0x01, 0x65],
        },
    );

    let encoded = SessionFrameCodec::encode(&frame).unwrap();
    assert!(encoded.len() <= SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE);
    let decoded = SessionFrameCodec::decode(&encoded).unwrap();

    assert_eq!(decoded, frame);
}

#[test]
fn session_frame_encode_rejects_frames_larger_than_decode_cap() {
    let arguments = (0..u16::MAX)
        .map(|index| (format!("k{index:05}"), format!("v{index:05}")))
        .collect::<BTreeMap<_, _>>();
    let frame = SessionFrame::new(
        1,
        "s",
        SessionFramePayload::CameraControlCommand {
            command: "setZoom".to_string(),
            arguments,
        },
    );

    assert_eq!(
        SessionFrameCodec::encode(&frame),
        Err(SessionFrameEncodeError::FrameTooLarge {
            actual_size: 1_048_588,
            max_size: SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE,
        })
    );
}

#[test]
fn session_frame_is_big_endian_payload_inside_little_endian_bulk_frame() {
    let session_bytes = HANDSHAKE_ACCEPT_GOLDEN.to_vec();
    let bulk = BulkFrame::new(0x0102_0304, session_bytes.clone()).unwrap();
    let encoded_bulk = bulk.encode();

    assert_eq!(&encoded_bulk[..8], &[0x04, 0x03, 0x02, 0x01, 27, 0, 0, 0]);
    assert_eq!(&encoded_bulk[8..], session_bytes.as_slice());
    assert_eq!(&encoded_bulk[8..12], &[b'C', b'C', b'S', b'F']);
    assert_eq!(&encoded_bulk[14..18], &[0, 0, 0, 9]);
}

#[test]
fn session_frame_rejects_header_and_size_errors_before_payload_allocation() {
    assert_eq!(
        SessionFrameCodec::decode(&[b'C', b'C']),
        Err(SessionFrameDecodeError::TruncatedFrame("header"))
    );
    assert_eq!(
        SessionFrameCodec::decode_with_limit(HANDSHAKE_ACCEPT_GOLDEN, 12),
        Err(SessionFrameDecodeError::FrameTooLarge {
            actual_size: 27,
            max_size: 12
        })
    );

    let mut truncated_payload = HANDSHAKE_ACCEPT_GOLDEN.to_vec();
    truncated_payload.pop();
    assert_eq!(
        SessionFrameCodec::decode(&truncated_payload),
        Err(SessionFrameDecodeError::TruncatedFrame("payload"))
    );

    let mut trailing = HANDSHAKE_ACCEPT_GOLDEN.to_vec();
    trailing.push(0);
    assert_eq!(
        SessionFrameCodec::decode(&trailing),
        Err(SessionFrameDecodeError::InvalidPayload(
            "trailing bytes".to_string()
        ))
    );
}

#[test]
fn session_frame_rejects_unsupported_version_unknown_type_and_invalid_identity() {
    let mut version = HANDSHAKE_ACCEPT_GOLDEN.to_vec();
    version[4] = 2;
    assert_eq!(
        SessionFrameCodec::decode(&version),
        Err(SessionFrameDecodeError::UnsupportedVersion(2))
    );

    let mut frame_type = HANDSHAKE_ACCEPT_GOLDEN.to_vec();
    frame_type[5] = 99;
    assert_eq!(
        SessionFrameCodec::decode(&frame_type),
        Err(SessionFrameDecodeError::UnknownType(99))
    );

    let negative_sequence = [
        b'C', b'C', b'S', b'F', 1, 2, 0x80, 0, 0, 0, 0, 1, b's', 0, 0, 0, 0,
    ];
    assert_eq!(
        SessionFrameCodec::decode(&negative_sequence),
        Err(SessionFrameDecodeError::InvalidSequence(i32::MIN))
    );

    let empty_session_id = [b'C', b'C', b'S', b'F', 1, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    assert_eq!(
        SessionFrameCodec::decode(&empty_session_id),
        Err(SessionFrameDecodeError::InvalidSessionId)
    );
}

#[test]
fn session_frame_rejects_malformed_utf8_without_replacement() {
    let malformed_session_id = [
        b'C', b'C', b'S', b'F', 1, 2, 0, 0, 0, 1, 0, 1, 0xC3, 0, 0, 0, 0,
    ];

    assert_eq!(
        SessionFrameCodec::decode(&malformed_session_id),
        Err(SessionFrameDecodeError::InvalidPayload(
            "invalid utf-8 in sessionId".to_string()
        ))
    );
}

#[test]
fn session_frame_rejects_invalid_stream_metadata_and_metrics_values() {
    for (field, width, height, frame_rate) in [
        ("width", 0, 1080, 30),
        ("height", 1920, 0, 30),
        ("frameRate", 1920, 1080, 0),
    ] {
        let frame = SessionFrame::new(
            1,
            "s",
            SessionFramePayload::StreamMetadata {
                mime_type: "video/h264".to_string(),
                width,
                height,
                frame_rate,
                profile: "baseline".to_string(),
            },
        );
        assert_eq!(
            SessionFrameCodec::encode(&frame),
            Err(SessionFrameEncodeError::InvalidPayload(format!(
                "stream metadata {field} must be positive"
            )))
        );

        let payload = bytes()
            .string("video/h264")
            .int(width)
            .int(height)
            .int(frame_rate)
            .string("baseline")
            .finish();
        assert_eq!(
            SessionFrameCodec::decode(&raw_frame(4, payload)),
            Err(SessionFrameDecodeError::InvalidPayload(format!(
                "stream metadata {field} must be positive"
            )))
        );
    }

    for (field, captured_at_us, dropped_frames, latency_ms, frame_rate) in [
        ("capturedAtUs", -1, 0, 0, 0),
        ("droppedFrames", 0, -1, 0, 0),
        ("latencyMs", 0, 0, -1, 0),
        ("frameRate", 0, 0, 0, -1),
    ] {
        let frame = SessionFrame::new(
            1,
            "s",
            SessionFramePayload::MetricsSnapshot {
                captured_at_us,
                dropped_frames,
                latency_ms,
                frame_rate,
            },
        );
        assert_eq!(
            SessionFrameCodec::encode(&frame),
            Err(SessionFrameEncodeError::InvalidPayload(format!(
                "metrics snapshot {field} must be non-negative"
            )))
        );

        let payload = bytes()
            .long(captured_at_us)
            .int(dropped_frames)
            .int(latency_ms)
            .int(frame_rate)
            .finish();
        assert_eq!(
            SessionFrameCodec::decode(&raw_frame(6, payload)),
            Err(SessionFrameDecodeError::InvalidPayload(format!(
                "metrics snapshot {field} must be non-negative"
            )))
        );
    }
}

#[test]
fn session_frame_rejects_duplicate_camera_control_argument_keys() {
    let payload = bytes()
        .string("setZoom")
        .short(2)
        .string("level")
        .string("2.0")
        .string("level")
        .string("3.0")
        .finish();
    let frame = raw_frame(7, payload);

    assert_eq!(
        SessionFrameCodec::decode(&frame),
        Err(SessionFrameDecodeError::InvalidPayload(
            "duplicate argument key: level".to_string()
        ))
    );
}

fn raw_frame(frame_type: u8, payload: Vec<u8>) -> Vec<u8> {
    bytes()
        .raw(&[b'C', b'C', b'S', b'F'])
        .byte(1)
        .byte(frame_type)
        .int(1)
        .string("s")
        .int(payload.len() as i32)
        .raw(&payload)
        .finish()
}

fn bytes() -> Bytes {
    Bytes(Vec::new())
}

struct Bytes(Vec<u8>);

impl Bytes {
    fn byte(mut self, value: u8) -> Self {
        self.0.push(value);
        self
    }

    fn raw(mut self, value: &[u8]) -> Self {
        self.0.extend_from_slice(value);
        self
    }

    fn short(mut self, value: u16) -> Self {
        self.0.extend_from_slice(&value.to_be_bytes());
        self
    }

    fn int(mut self, value: i32) -> Self {
        self.0.extend_from_slice(&value.to_be_bytes());
        self
    }

    fn long(mut self, value: i64) -> Self {
        self.0.extend_from_slice(&value.to_be_bytes());
        self
    }

    fn string(self, value: &str) -> Self {
        self.short(value.len() as u16).raw(value.as_bytes())
    }

    fn finish(self) -> Vec<u8> {
        self.0
    }
}
