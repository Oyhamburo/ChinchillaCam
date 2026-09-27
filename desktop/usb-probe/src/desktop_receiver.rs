use crate::{
    BulkFrame, EncodedVideoChunk, EncodedVideoChunkLimits, EncodedVideoFrameKind, EncodedVideoSink,
    EncodedVideoSinkError, PresentationTimestamp, SessionFrame, SessionFrameCodec,
    SessionFrameDecodeError, SessionFramePayload, VideoFrameKind,
};

pub const USB_SESSION_FRAME_STREAM_ID: u32 = 0x0102_0304;
const MAX_USB_TOTAL_PACKET_BYTES: usize = 65_536;
const BULK_FRAME_HEADER_BYTES: usize = 8;
const MAX_USB_SESSION_FRAME_BYTES: usize = MAX_USB_TOTAL_PACKET_BYTES - BULK_FRAME_HEADER_BYTES;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopReceiverError {
    UnexpectedBulkStream { actual: u32, expected: u32 },
    MalformedSessionFrame(SessionFrameDecodeError),
    UnexpectedSessionPayload { type_id: u8 },
    NegativePresentationTimestamp(i64),
    UnknownFrameKind,
    SinkRejected(EncodedVideoSinkError),
}

pub trait VideoFrameKindClassifier {
    fn classify_frame_kind(
        &mut self,
        frame: &SessionFrame,
    ) -> Result<Option<EncodedVideoFrameKind>, DesktopReceiverError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StaticFrameKindClassifier {
    frame_kind: Option<EncodedVideoFrameKind>,
}

impl StaticFrameKindClassifier {
    pub fn known(frame_kind: EncodedVideoFrameKind) -> Self {
        Self {
            frame_kind: Some(frame_kind),
        }
    }

    pub fn unknown() -> Self {
        Self { frame_kind: None }
    }
}

impl VideoFrameKindClassifier for StaticFrameKindClassifier {
    fn classify_frame_kind(
        &mut self,
        _frame: &SessionFrame,
    ) -> Result<Option<EncodedVideoFrameKind>, DesktopReceiverError> {
        Ok(self.frame_kind)
    }
}

pub fn receive_desktop_video_frame<C, S>(
    bulk_frame: &BulkFrame,
    classifier: &mut C,
    sink: &mut S,
) -> Result<(), DesktopReceiverError>
where
    C: VideoFrameKindClassifier,
    S: EncodedVideoSink,
{
    if bulk_frame.stream_id() != USB_SESSION_FRAME_STREAM_ID {
        return Err(DesktopReceiverError::UnexpectedBulkStream {
            actual: bulk_frame.stream_id(),
            expected: USB_SESSION_FRAME_STREAM_ID,
        });
    }

    let session_frame =
        SessionFrameCodec::decode_with_limit(bulk_frame.payload(), MAX_USB_SESSION_FRAME_BYTES)
            .map_err(DesktopReceiverError::MalformedSessionFrame)?;

    let (presentation_time_us, h264_bytes, frame_kind) = match session_frame.payload() {
        SessionFramePayload::VideoChunk {
            presentation_time_us,
            h264_bytes,
            ..
        } => {
            if *presentation_time_us < 0 {
                return Err(DesktopReceiverError::NegativePresentationTimestamp(
                    *presentation_time_us,
                ));
            }
            let frame_kind = classifier
                .classify_frame_kind(&session_frame)?
                .ok_or(DesktopReceiverError::UnknownFrameKind)?;
            (*presentation_time_us, h264_bytes, frame_kind)
        }
        SessionFramePayload::VideoChunkV2 {
            presentation_time_us,
            kind,
            h264_bytes,
            ..
        } => {
            if *presentation_time_us < 0 {
                return Err(DesktopReceiverError::NegativePresentationTimestamp(
                    *presentation_time_us,
                ));
            }
            (*presentation_time_us, h264_bytes, encoded_frame_kind(*kind))
        }
        other => {
            return Err(DesktopReceiverError::UnexpectedSessionPayload {
                type_id: session_payload_type_id(other),
            });
        }
    };
    let limits = EncodedVideoChunkLimits::new(SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE)
        .expect("non-zero fixed desktop receiver payload limit");
    let chunk = EncodedVideoChunk::new(
        bulk_frame.stream_id(),
        PresentationTimestamp::from_micros(presentation_time_us as u64),
        frame_kind,
        h264_bytes.clone(),
        &limits,
    )
    .map_err(DesktopReceiverError::SinkRejected)?;

    sink.push_encoded_video(chunk)
        .map_err(DesktopReceiverError::SinkRejected)
}

fn encoded_frame_kind(kind: VideoFrameKind) -> EncodedVideoFrameKind {
    match kind {
        VideoFrameKind::Delta => EncodedVideoFrameKind::Delta,
        VideoFrameKind::Key => EncodedVideoFrameKind::Key,
        VideoFrameKind::CodecConfig => EncodedVideoFrameKind::CodecConfig,
    }
}

fn session_payload_type_id(payload: &SessionFramePayload) -> u8 {
    match payload {
        SessionFramePayload::HandshakeHello { .. } => 1,
        SessionFramePayload::HandshakeAccept { .. } => 2,
        SessionFramePayload::HandshakeReject { .. } => 3,
        SessionFramePayload::StreamMetadata { .. } => 4,
        SessionFramePayload::VideoChunk { .. } => 5,
        SessionFramePayload::MetricsSnapshot { .. } => 6,
        SessionFramePayload::CameraControlCommand { .. } => 7,
        SessionFramePayload::VideoChunkV2 { .. } => 8,
    }
}
