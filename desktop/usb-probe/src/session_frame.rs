use std::collections::BTreeMap;

const SESSION_FRAME_MAGIC: &[u8; 4] = b"CCSF";
const SUPPORTED_SESSION_FRAME_VERSION: u8 = 1;
const DEFAULT_MAX_SESSION_FRAME_SIZE: usize = 1024 * 1024;
const HEADER_WITHOUT_SESSION_BYTES: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionFrame {
    version: u8,
    sequence: i32,
    session_id: String,
    payload: SessionFramePayload,
}

impl SessionFrame {
    pub fn new(sequence: i32, session_id: impl Into<String>, payload: SessionFramePayload) -> Self {
        Self {
            version: SUPPORTED_SESSION_FRAME_VERSION,
            sequence,
            session_id: session_id.into(),
            payload,
        }
    }

    pub fn version(&self) -> u8 {
        self.version
    }

    pub fn sequence(&self) -> i32 {
        self.sequence
    }

    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    pub fn payload(&self) -> &SessionFramePayload {
        &self.payload
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionFramePayload {
    HandshakeHello {
        device_id: String,
        app_name: String,
        capabilities: Vec<String>,
    },
    HandshakeAccept {
        desktop_id: String,
        message: String,
    },
    HandshakeReject {
        reason_code: String,
        message: String,
    },
    StreamMetadata {
        mime_type: String,
        width: i32,
        height: i32,
        frame_rate: i32,
        profile: String,
    },
    VideoChunk {
        chunk_index: i32,
        presentation_time_us: i64,
        h264_bytes: Vec<u8>,
    },
    VideoChunkV2 {
        chunk_index: i32,
        presentation_time_us: i64,
        kind: VideoFrameKind,
        h264_bytes: Vec<u8>,
    },
    MetricsSnapshot {
        captured_at_us: i64,
        dropped_frames: i32,
        latency_ms: i32,
        frame_rate: i32,
    },
    CameraControlCommand {
        command: String,
        arguments: BTreeMap<String, String>,
    },
}

impl SessionFramePayload {
    pub fn video_chunk_v2_delta(
        chunk_index: i32,
        presentation_time_us: i64,
        h264_bytes: Vec<u8>,
    ) -> Self {
        Self::video_chunk_v2(
            chunk_index,
            presentation_time_us,
            VideoFrameKind::Delta,
            h264_bytes,
        )
    }

    pub fn video_chunk_v2_key(
        chunk_index: i32,
        presentation_time_us: i64,
        h264_bytes: Vec<u8>,
    ) -> Self {
        Self::video_chunk_v2(
            chunk_index,
            presentation_time_us,
            VideoFrameKind::Key,
            h264_bytes,
        )
    }

    pub fn video_chunk_v2_codec_config(
        chunk_index: i32,
        presentation_time_us: i64,
        h264_bytes: Vec<u8>,
    ) -> Self {
        Self::video_chunk_v2(
            chunk_index,
            presentation_time_us,
            VideoFrameKind::CodecConfig,
            h264_bytes,
        )
    }

    fn video_chunk_v2(
        chunk_index: i32,
        presentation_time_us: i64,
        kind: VideoFrameKind,
        h264_bytes: Vec<u8>,
    ) -> Self {
        Self::VideoChunkV2 {
            chunk_index,
            presentation_time_us,
            kind,
            h264_bytes,
        }
    }

    fn type_id(&self) -> u8 {
        match self {
            Self::HandshakeHello { .. } => 1,
            Self::HandshakeAccept { .. } => 2,
            Self::HandshakeReject { .. } => 3,
            Self::StreamMetadata { .. } => 4,
            Self::VideoChunk { .. } => 5,
            Self::MetricsSnapshot { .. } => 6,
            Self::CameraControlCommand { .. } => 7,
            Self::VideoChunkV2 { .. } => 8,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoFrameKind {
    Delta,
    Key,
    CodecConfig,
}

impl VideoFrameKind {
    fn wire_value(self) -> u8 {
        match self {
            Self::Delta => 0,
            Self::Key => 1,
            Self::CodecConfig => 2,
        }
    }

    fn from_wire(value: u8) -> Result<Self, SessionFrameDecodeError> {
        match value {
            0 => Ok(Self::Delta),
            1 => Ok(Self::Key),
            2 => Ok(Self::CodecConfig),
            other => Err(SessionFrameDecodeError::InvalidPayload(format!(
                "unknown video chunk v2 kind: {other}"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionFrameEncodeError {
    InvalidSequence(i32),
    InvalidSessionId,
    FieldTooLarge(&'static str),
    FrameTooLarge { actual_size: usize, max_size: usize },
    InvalidPayload(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionFrameDecodeError {
    UnsupportedVersion(u8),
    UnknownType(u8),
    FrameTooLarge { actual_size: usize, max_size: usize },
    InvalidPayload(String),
    InvalidSequence(i32),
    InvalidSessionId,
    TruncatedFrame(&'static str),
}

pub struct SessionFrameCodec;

impl SessionFrameCodec {
    pub const DEFAULT_MAX_FRAME_SIZE: usize = DEFAULT_MAX_SESSION_FRAME_SIZE;

    pub fn encode(frame: &SessionFrame) -> Result<Vec<u8>, SessionFrameEncodeError> {
        if frame.sequence < 0 {
            return Err(SessionFrameEncodeError::InvalidSequence(frame.sequence));
        }
        if frame.session_id.is_empty() {
            return Err(SessionFrameEncodeError::InvalidSessionId);
        }

        let session_id = frame.session_id.as_bytes();
        write_len_fits_u16(session_id.len(), "sessionId")?;
        let payload_size = encoded_payload_size(&frame.payload)?;
        if payload_size > i32::MAX as usize {
            return Err(SessionFrameEncodeError::FieldTooLarge("payload"));
        }
        let frame_size = checked_add(
            HEADER_WITHOUT_SESSION_BYTES,
            checked_add(session_id.len(), payload_size, "frame")?,
            "frame",
        )?;
        if frame_size > Self::DEFAULT_MAX_FRAME_SIZE {
            return Err(SessionFrameEncodeError::FrameTooLarge {
                actual_size: frame_size,
                max_size: Self::DEFAULT_MAX_FRAME_SIZE,
            });
        }

        let payload = encode_payload(&frame.payload)?;
        let mut encoded = Vec::with_capacity(frame_size);
        encoded.extend_from_slice(SESSION_FRAME_MAGIC);
        encoded.push(frame.version);
        encoded.push(frame.payload.type_id());
        encoded.extend_from_slice(&frame.sequence.to_be_bytes());
        encoded.extend_from_slice(&(session_id.len() as u16).to_be_bytes());
        encoded.extend_from_slice(session_id);
        encoded.extend_from_slice(&(payload.len() as i32).to_be_bytes());
        encoded.extend_from_slice(&payload);
        Ok(encoded)
    }

    pub fn decode(bytes: &[u8]) -> Result<SessionFrame, SessionFrameDecodeError> {
        Self::decode_with_limit(bytes, DEFAULT_MAX_SESSION_FRAME_SIZE)
    }

    pub fn decode_with_limit(
        bytes: &[u8],
        max_frame_size: usize,
    ) -> Result<SessionFrame, SessionFrameDecodeError> {
        if bytes.len() > max_frame_size {
            return Err(SessionFrameDecodeError::FrameTooLarge {
                actual_size: bytes.len(),
                max_size: max_frame_size,
            });
        }
        if bytes.len() < HEADER_WITHOUT_SESSION_BYTES {
            return Err(SessionFrameDecodeError::TruncatedFrame("header"));
        }

        let mut reader = Reader::new(bytes);
        if reader.read_bytes(4, "magic")? != SESSION_FRAME_MAGIC {
            return Err(SessionFrameDecodeError::InvalidPayload(
                "bad magic".to_string(),
            ));
        }

        let version = reader.read_u8("version")?;
        if version != SUPPORTED_SESSION_FRAME_VERSION {
            return Err(SessionFrameDecodeError::UnsupportedVersion(version));
        }

        let type_id = reader.read_u8("type")?;
        let sequence = reader.read_i32("sequence")?;
        if sequence < 0 {
            return Err(SessionFrameDecodeError::InvalidSequence(sequence));
        }

        let session_id_len = reader.read_u16("sessionIdLength")? as usize;
        if session_id_len == 0 {
            return Err(SessionFrameDecodeError::InvalidSessionId);
        }
        let session_id = reader.read_utf8(session_id_len, "sessionId")?;

        let payload_len = reader.read_i32("payloadLength")?;
        if payload_len < 0 {
            return Err(SessionFrameDecodeError::InvalidPayload(
                "payload length must be non-negative".to_string(),
            ));
        }
        let payload_len = payload_len as usize;
        if reader.remaining() < payload_len {
            return Err(SessionFrameDecodeError::TruncatedFrame("payload"));
        }

        let payload = decode_payload(type_id, reader.read_bytes(payload_len, "payload")?)?;
        if reader.remaining() != 0 {
            return Err(SessionFrameDecodeError::InvalidPayload(
                "trailing bytes".to_string(),
            ));
        }

        Ok(SessionFrame {
            version,
            sequence,
            session_id,
            payload,
        })
    }
}

fn encoded_payload_size(payload: &SessionFramePayload) -> Result<usize, SessionFrameEncodeError> {
    match payload {
        SessionFramePayload::HandshakeHello {
            device_id,
            app_name,
            capabilities,
        } => {
            write_len_fits_u16(capabilities.len(), "capability count")?;
            let mut size = checked_add(
                encoded_bytes_with_len_size(device_id.len())?,
                encoded_bytes_with_len_size(app_name.len())?,
                "payload",
            )?;
            size = checked_add(size, 2, "payload")?;
            for capability in capabilities {
                size = checked_add(
                    size,
                    encoded_bytes_with_len_size(capability.len())?,
                    "payload",
                )?;
            }
            Ok(size)
        }
        SessionFramePayload::HandshakeAccept {
            desktop_id,
            message,
        }
        | SessionFramePayload::HandshakeReject {
            reason_code: desktop_id,
            message,
        } => checked_add(
            encoded_bytes_with_len_size(desktop_id.len())?,
            encoded_bytes_with_len_size(message.len())?,
            "payload",
        ),
        SessionFramePayload::StreamMetadata {
            mime_type,
            width,
            height,
            frame_rate,
            profile,
        } => {
            validate_stream_metadata_for_encode(*width, *height, *frame_rate)?;
            checked_add(
                checked_add(encoded_bytes_with_len_size(mime_type.len())?, 12, "payload")?,
                encoded_bytes_with_len_size(profile.len())?,
                "payload",
            )
        }
        SessionFramePayload::VideoChunk {
            chunk_index,
            h264_bytes,
            ..
        } => {
            if *chunk_index < 0 {
                return Err(SessionFrameEncodeError::InvalidPayload(
                    "chunk index must be non-negative".to_string(),
                ));
            }
            checked_add(
                12,
                encoded_bytes_with_len_size(h264_bytes.len())?,
                "payload",
            )
        }
        SessionFramePayload::VideoChunkV2 {
            chunk_index,
            presentation_time_us,
            h264_bytes,
            ..
        } => {
            validate_video_chunk_v2_for_encode(*chunk_index, *presentation_time_us, h264_bytes)?;
            checked_add(
                13,
                encoded_bytes_with_len_size(h264_bytes.len())?,
                "payload",
            )
        }
        SessionFramePayload::MetricsSnapshot {
            captured_at_us,
            dropped_frames,
            latency_ms,
            frame_rate,
        } => {
            validate_metrics_snapshot_for_encode(
                *captured_at_us,
                *dropped_frames,
                *latency_ms,
                *frame_rate,
            )?;
            Ok(20)
        }
        SessionFramePayload::CameraControlCommand { command, arguments } => {
            write_len_fits_u16(arguments.len(), "argument count")?;
            let mut size = checked_add(encoded_bytes_with_len_size(command.len())?, 2, "payload")?;
            for (key, value) in arguments {
                size = checked_add(size, encoded_bytes_with_len_size(key.len())?, "payload")?;
                size = checked_add(size, encoded_bytes_with_len_size(value.len())?, "payload")?;
            }
            Ok(size)
        }
    }
}

fn encoded_bytes_with_len_size(length: usize) -> Result<usize, SessionFrameEncodeError> {
    write_len_fits_u16(length, "field")?;
    checked_add(2, length, "field")
}

fn encode_payload(payload: &SessionFramePayload) -> Result<Vec<u8>, SessionFrameEncodeError> {
    let mut writer = Writer::new();
    match payload {
        SessionFramePayload::HandshakeHello {
            device_id,
            app_name,
            capabilities,
        } => {
            writer.write_string(device_id)?;
            writer.write_string(app_name)?;
            writer.write_u16_len(capabilities.len(), "capability count")?;
            for capability in capabilities {
                writer.write_string(capability)?;
            }
        }
        SessionFramePayload::HandshakeAccept {
            desktop_id,
            message,
        } => {
            writer.write_string(desktop_id)?;
            writer.write_string(message)?;
        }
        SessionFramePayload::HandshakeReject {
            reason_code,
            message,
        } => {
            writer.write_string(reason_code)?;
            writer.write_string(message)?;
        }
        SessionFramePayload::StreamMetadata {
            mime_type,
            width,
            height,
            frame_rate,
            profile,
        } => {
            validate_stream_metadata_for_encode(*width, *height, *frame_rate)?;
            writer.write_string(mime_type)?;
            writer.write_i32(*width);
            writer.write_i32(*height);
            writer.write_i32(*frame_rate);
            writer.write_string(profile)?;
        }
        SessionFramePayload::VideoChunk {
            chunk_index,
            presentation_time_us,
            h264_bytes,
        } => {
            if *chunk_index < 0 {
                return Err(SessionFrameEncodeError::InvalidPayload(
                    "chunk index must be non-negative".to_string(),
                ));
            }
            writer.write_i32(*chunk_index);
            writer.write_i64(*presentation_time_us);
            writer.write_bytes_with_len(h264_bytes)?;
        }
        SessionFramePayload::VideoChunkV2 {
            chunk_index,
            presentation_time_us,
            kind,
            h264_bytes,
        } => {
            validate_video_chunk_v2_for_encode(*chunk_index, *presentation_time_us, h264_bytes)?;
            writer.write_i32(*chunk_index);
            writer.write_i64(*presentation_time_us);
            writer.write_u8(kind.wire_value());
            writer.write_bytes_with_len_field(h264_bytes, "h264Bytes")?;
        }
        SessionFramePayload::MetricsSnapshot {
            captured_at_us,
            dropped_frames,
            latency_ms,
            frame_rate,
        } => {
            validate_metrics_snapshot_for_encode(
                *captured_at_us,
                *dropped_frames,
                *latency_ms,
                *frame_rate,
            )?;
            writer.write_i64(*captured_at_us);
            writer.write_i32(*dropped_frames);
            writer.write_i32(*latency_ms);
            writer.write_i32(*frame_rate);
        }
        SessionFramePayload::CameraControlCommand { command, arguments } => {
            writer.write_string(command)?;
            writer.write_u16_len(arguments.len(), "argument count")?;
            for (key, value) in arguments {
                writer.write_string(key)?;
                writer.write_string(value)?;
            }
        }
    }
    Ok(writer.finish())
}

fn decode_payload(
    type_id: u8,
    payload: &[u8],
) -> Result<SessionFramePayload, SessionFrameDecodeError> {
    let mut reader = Reader::new(payload);
    let decoded = match type_id {
        1 => {
            let device_id = reader.read_string("deviceId")?;
            let app_name = reader.read_string("appName")?;
            let count = reader.read_u16("capabilityCount")? as usize;
            let mut capabilities = Vec::with_capacity(count);
            for _ in 0..count {
                capabilities.push(reader.read_string("capability")?);
            }
            SessionFramePayload::HandshakeHello {
                device_id,
                app_name,
                capabilities,
            }
        }
        2 => SessionFramePayload::HandshakeAccept {
            desktop_id: reader.read_string("desktopId")?,
            message: reader.read_string("message")?,
        },
        3 => SessionFramePayload::HandshakeReject {
            reason_code: reader.read_string("reasonCode")?,
            message: reader.read_string("message")?,
        },
        4 => {
            let mime_type = reader.read_string("mimeType")?;
            let width = reader.read_i32("width")?;
            let height = reader.read_i32("height")?;
            let frame_rate = reader.read_i32("frameRate")?;
            let profile = reader.read_string("profile")?;
            validate_stream_metadata_for_decode(width, height, frame_rate)?;
            SessionFramePayload::StreamMetadata {
                mime_type,
                width,
                height,
                frame_rate,
                profile,
            }
        }
        5 => {
            let chunk_index = reader.read_i32("chunkIndex")?;
            if chunk_index < 0 {
                return Err(SessionFrameDecodeError::InvalidPayload(
                    "chunk index must be non-negative".to_string(),
                ));
            }
            SessionFramePayload::VideoChunk {
                chunk_index,
                presentation_time_us: reader.read_i64("presentationTimeUs")?,
                h264_bytes: reader.read_bytes_with_len("h264Bytes")?,
            }
        }
        6 => {
            let captured_at_us = reader.read_i64("capturedAtUs")?;
            let dropped_frames = reader.read_i32("droppedFrames")?;
            let latency_ms = reader.read_i32("latencyMs")?;
            let frame_rate = reader.read_i32("frameRate")?;
            validate_metrics_snapshot_for_decode(
                captured_at_us,
                dropped_frames,
                latency_ms,
                frame_rate,
            )?;
            SessionFramePayload::MetricsSnapshot {
                captured_at_us,
                dropped_frames,
                latency_ms,
                frame_rate,
            }
        }
        7 => {
            let command = reader.read_string("command")?;
            let count = reader.read_u16("argumentCount")? as usize;
            let mut arguments = BTreeMap::new();
            for _ in 0..count {
                let key = reader.read_string("argumentKey")?;
                let value = reader.read_string("argumentValue")?;
                if arguments.insert(key.clone(), value).is_some() {
                    return Err(SessionFrameDecodeError::InvalidPayload(format!(
                        "duplicate argument key: {key}"
                    )));
                }
            }
            SessionFramePayload::CameraControlCommand { command, arguments }
        }
        8 => {
            let chunk_index = reader.read_i32("chunkIndex")?;
            let presentation_time_us = reader.read_i64("presentationTimeUs")?;
            let kind = VideoFrameKind::from_wire(reader.read_u8("kind")?)?;
            let h264_bytes = reader.read_bytes_with_len("h264Bytes")?;
            validate_video_chunk_v2_for_decode(chunk_index, presentation_time_us, &h264_bytes)?;
            SessionFramePayload::VideoChunkV2 {
                chunk_index,
                presentation_time_us,
                kind,
                h264_bytes,
            }
        }
        other => return Err(SessionFrameDecodeError::UnknownType(other)),
    };

    if reader.remaining() != 0 {
        return Err(SessionFrameDecodeError::InvalidPayload(
            "trailing payload bytes".to_string(),
        ));
    }
    Ok(decoded)
}

fn validate_video_chunk_v2_for_encode(
    chunk_index: i32,
    presentation_time_us: i64,
    h264_bytes: &[u8],
) -> Result<(), SessionFrameEncodeError> {
    validate_non_negative_for_encode(chunk_index, "video chunk v2 chunk index")?;
    validate_non_negative_for_encode(presentation_time_us, "video chunk v2 presentationTimeUs")?;
    if h264_bytes.is_empty() {
        return Err(SessionFrameEncodeError::InvalidPayload(
            "video chunk v2 h264Bytes must not be empty".to_string(),
        ));
    }
    write_len_fits_u16(h264_bytes.len(), "h264Bytes")?;
    Ok(())
}

fn validate_stream_metadata_for_encode(
    width: i32,
    height: i32,
    frame_rate: i32,
) -> Result<(), SessionFrameEncodeError> {
    validate_positive_for_encode(width, "stream metadata width")?;
    validate_positive_for_encode(height, "stream metadata height")?;
    validate_positive_for_encode(frame_rate, "stream metadata frameRate")?;
    Ok(())
}

fn validate_metrics_snapshot_for_encode(
    captured_at_us: i64,
    dropped_frames: i32,
    latency_ms: i32,
    frame_rate: i32,
) -> Result<(), SessionFrameEncodeError> {
    validate_non_negative_for_encode(captured_at_us, "metrics snapshot capturedAtUs")?;
    validate_non_negative_for_encode(dropped_frames, "metrics snapshot droppedFrames")?;
    validate_non_negative_for_encode(latency_ms, "metrics snapshot latencyMs")?;
    validate_non_negative_for_encode(frame_rate, "metrics snapshot frameRate")?;
    Ok(())
}

fn validate_positive_for_encode<T>(
    value: T,
    field: &'static str,
) -> Result<(), SessionFrameEncodeError>
where
    T: PartialOrd + From<u8>,
{
    if value <= T::from(0) {
        return Err(SessionFrameEncodeError::InvalidPayload(format!(
            "{field} must be positive"
        )));
    }
    Ok(())
}

fn validate_non_negative_for_encode<T>(
    value: T,
    field: &'static str,
) -> Result<(), SessionFrameEncodeError>
where
    T: PartialOrd + From<u8>,
{
    if value < T::from(0) {
        return Err(SessionFrameEncodeError::InvalidPayload(format!(
            "{field} must be non-negative"
        )));
    }
    Ok(())
}

fn validate_video_chunk_v2_for_decode(
    chunk_index: i32,
    presentation_time_us: i64,
    h264_bytes: &[u8],
) -> Result<(), SessionFrameDecodeError> {
    validate_non_negative_for_decode(chunk_index, "video chunk v2 chunk index")?;
    validate_non_negative_for_decode(presentation_time_us, "video chunk v2 presentationTimeUs")?;
    if h264_bytes.is_empty() {
        return Err(SessionFrameDecodeError::InvalidPayload(
            "video chunk v2 h264Bytes must not be empty".to_string(),
        ));
    }
    Ok(())
}

fn validate_stream_metadata_for_decode(
    width: i32,
    height: i32,
    frame_rate: i32,
) -> Result<(), SessionFrameDecodeError> {
    validate_positive_for_decode(width, "stream metadata width")?;
    validate_positive_for_decode(height, "stream metadata height")?;
    validate_positive_for_decode(frame_rate, "stream metadata frameRate")?;
    Ok(())
}

fn validate_metrics_snapshot_for_decode(
    captured_at_us: i64,
    dropped_frames: i32,
    latency_ms: i32,
    frame_rate: i32,
) -> Result<(), SessionFrameDecodeError> {
    validate_non_negative_for_decode(captured_at_us, "metrics snapshot capturedAtUs")?;
    validate_non_negative_for_decode(dropped_frames, "metrics snapshot droppedFrames")?;
    validate_non_negative_for_decode(latency_ms, "metrics snapshot latencyMs")?;
    validate_non_negative_for_decode(frame_rate, "metrics snapshot frameRate")?;
    Ok(())
}

fn validate_positive_for_decode<T>(
    value: T,
    field: &'static str,
) -> Result<(), SessionFrameDecodeError>
where
    T: PartialOrd + From<u8>,
{
    if value <= T::from(0) {
        return Err(SessionFrameDecodeError::InvalidPayload(format!(
            "{field} must be positive"
        )));
    }
    Ok(())
}

fn validate_non_negative_for_decode<T>(
    value: T,
    field: &'static str,
) -> Result<(), SessionFrameDecodeError>
where
    T: PartialOrd + From<u8>,
{
    if value < T::from(0) {
        return Err(SessionFrameDecodeError::InvalidPayload(format!(
            "{field} must be non-negative"
        )));
    }
    Ok(())
}

struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    fn write_string(&mut self, value: &str) -> Result<(), SessionFrameEncodeError> {
        self.write_bytes_with_len(value.as_bytes())
    }

    fn write_bytes_with_len(&mut self, value: &[u8]) -> Result<(), SessionFrameEncodeError> {
        self.write_bytes_with_len_field(value, "field")
    }

    fn write_bytes_with_len_field(
        &mut self,
        value: &[u8],
        field: &'static str,
    ) -> Result<(), SessionFrameEncodeError> {
        self.write_u16_len(value.len(), field)?;
        self.bytes.extend_from_slice(value);
        Ok(())
    }

    fn write_u16_len(
        &mut self,
        length: usize,
        field: &'static str,
    ) -> Result<(), SessionFrameEncodeError> {
        write_len_fits_u16(length, field)?;
        self.bytes.extend_from_slice(&(length as u16).to_be_bytes());
        Ok(())
    }

    fn write_u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn write_i32(&mut self, value: i32) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }

    fn write_i64(&mut self, value: i64) {
        self.bytes.extend_from_slice(&value.to_be_bytes());
    }

    fn finish(self) -> Vec<u8> {
        self.bytes
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    fn remaining(&self) -> usize {
        self.bytes.len() - self.position
    }

    fn read_u8(&mut self, field: &'static str) -> Result<u8, SessionFrameDecodeError> {
        self.ensure_available(1, field)?;
        let value = self.bytes[self.position];
        self.position += 1;
        Ok(value)
    }

    fn read_u16(&mut self, field: &'static str) -> Result<u16, SessionFrameDecodeError> {
        let bytes = self.read_array::<2>(field)?;
        Ok(u16::from_be_bytes(bytes))
    }

    fn read_i32(&mut self, field: &'static str) -> Result<i32, SessionFrameDecodeError> {
        let bytes = self.read_array::<4>(field)?;
        Ok(i32::from_be_bytes(bytes))
    }

    fn read_i64(&mut self, field: &'static str) -> Result<i64, SessionFrameDecodeError> {
        let bytes = self.read_array::<8>(field)?;
        Ok(i64::from_be_bytes(bytes))
    }

    fn read_array<const N: usize>(
        &mut self,
        field: &'static str,
    ) -> Result<[u8; N], SessionFrameDecodeError> {
        let bytes = self.read_bytes(N, field)?;
        Ok(bytes
            .try_into()
            .expect("fixed-length read returned exact length"))
    }

    fn read_string(&mut self, field: &'static str) -> Result<String, SessionFrameDecodeError> {
        let length = self.read_u16("stringLength")? as usize;
        self.read_utf8(length, field)
    }

    fn read_bytes_with_len(
        &mut self,
        field: &'static str,
    ) -> Result<Vec<u8>, SessionFrameDecodeError> {
        let length = self.read_u16("bytesLength")? as usize;
        self.read_bytes(length, field).map(Vec::from)
    }

    fn read_utf8(
        &mut self,
        length: usize,
        field: &'static str,
    ) -> Result<String, SessionFrameDecodeError> {
        let bytes = self.read_bytes(length, field)?;
        std::str::from_utf8(bytes).map(str::to_string).map_err(|_| {
            SessionFrameDecodeError::InvalidPayload(format!("invalid utf-8 in {field}"))
        })
    }

    fn read_bytes(
        &mut self,
        length: usize,
        field: &'static str,
    ) -> Result<&'a [u8], SessionFrameDecodeError> {
        self.ensure_available(length, field)?;
        let start = self.position;
        self.position += length;
        Ok(&self.bytes[start..self.position])
    }

    fn ensure_available(
        &self,
        length: usize,
        field: &'static str,
    ) -> Result<(), SessionFrameDecodeError> {
        if self.remaining() < length {
            return Err(SessionFrameDecodeError::TruncatedFrame(field));
        }
        Ok(())
    }
}

fn write_len_fits_u16(length: usize, field: &'static str) -> Result<(), SessionFrameEncodeError> {
    if length > u16::MAX as usize {
        return Err(SessionFrameEncodeError::FieldTooLarge(field));
    }
    Ok(())
}

fn checked_add(
    left: usize,
    right: usize,
    field: &'static str,
) -> Result<usize, SessionFrameEncodeError> {
    left.checked_add(right)
        .ok_or(SessionFrameEncodeError::FieldTooLarge(field))
}
