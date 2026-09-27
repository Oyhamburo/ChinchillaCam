use usb_probe::{
    BulkFrame, SessionFrame, SessionFrameCodec, SessionFrameDecodeError, SessionFramePayload,
};

// Golden bytes copied from Android SessionFrameCodec contract at
// /Users/jele/Desktop/codes/ChinchillaCam-usb-bulk-tdd commit
// f8504a91c27efb1be0004470b1ee30334d5ddc8b for:
// SessionFrame(1, 9, "abc", SessionPayload.HandshakeAccept("pc", "ok"))
const HANDSHAKE_ACCEPT_GOLDEN: &[u8] = &[
    b'C', b'C', b'S', b'F', 1, 2, 0, 0, 0, 9, 0, 3, b'a', b'b', b'c', 0, 0, 0, 8, 0, 2, b'p', b'c',
    0, 2, b'o', b'k',
];

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
    let decoded = SessionFrameCodec::decode(&encoded).unwrap();

    assert_eq!(decoded, frame);
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

    fn string(self, value: &str) -> Self {
        self.short(value.len() as u16).raw(value.as_bytes())
    }

    fn finish(self) -> Vec<u8> {
        self.0
    }
}
