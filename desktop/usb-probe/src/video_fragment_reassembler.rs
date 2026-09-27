use crate::{SessionFrame, SessionFramePayload, VideoFrameKind};

const MAX_REASSEMBLED_H264_BYTES: i32 = 4 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReassembledVideoChunk {
    session_id: String,
    chunk_index: i32,
    presentation_time_us: i64,
    kind: VideoFrameKind,
    h264_bytes: Vec<u8>,
}

impl ReassembledVideoChunk {
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    pub fn chunk_index(&self) -> i32 {
        self.chunk_index
    }

    pub fn presentation_time_us(&self) -> i64 {
        self.presentation_time_us
    }

    pub fn kind(&self) -> VideoFrameKind {
        self.kind
    }

    pub fn h264_bytes(&self) -> &[u8] {
        &self.h264_bytes
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VideoFragmentReassemblerError {
    UnexpectedPayloadType {
        type_id: u8,
    },
    ConcurrentSession {
        active_session_id: String,
        incoming_session_id: String,
    },
    UnexpectedFragmentIndex {
        expected_index: i32,
        actual_index: i32,
    },
    InvalidFragment,
    MetadataMismatch,
    TotalBytesExceeded {
        total_h264_bytes: i32,
        actual_bytes: usize,
    },
    CompleteBeforeLastFragment,
    IncompleteTotalBytes {
        expected_bytes: i32,
        actual_bytes: usize,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoFragmentReassembler {
    active: Option<ActiveAccessUnit>,
}

impl VideoFragmentReassembler {
    pub fn new() -> Self {
        Self { active: None }
    }

    pub fn push_frame(
        &mut self,
        frame: &SessionFrame,
    ) -> Result<Option<ReassembledVideoChunk>, VideoFragmentReassemblerError> {
        let SessionFramePayload::VideoChunkFragmentV1 {
            chunk_index,
            presentation_time_us,
            kind,
            fragment_index,
            fragment_count,
            total_h264_bytes,
            fragment_h264_bytes,
        } = frame.payload()
        else {
            self.active = None;
            return Err(VideoFragmentReassemblerError::UnexpectedPayloadType {
                type_id: payload_type_id(frame.payload()),
            });
        };

        let fragment = IncomingFragment {
            session_id: frame.session_id(),
            chunk_index: *chunk_index,
            presentation_time_us: *presentation_time_us,
            kind: *kind,
            fragment_index: *fragment_index,
            fragment_count: *fragment_count,
            total_h264_bytes: *total_h264_bytes,
            fragment_h264_bytes,
        };
        self.push_fragment(fragment)
    }

    pub fn reset_session(&mut self, session_id: &str) {
        if self
            .active
            .as_ref()
            .is_some_and(|active| active.session_id == session_id)
        {
            self.active = None;
        }
    }

    pub fn reset_all(&mut self) {
        self.active = None;
    }

    fn push_fragment(
        &mut self,
        fragment: IncomingFragment<'_>,
    ) -> Result<Option<ReassembledVideoChunk>, VideoFragmentReassemblerError> {
        if let Err(error) = validate_fragment_bounds(&fragment) {
            self.active = None;
            return Err(error);
        }

        if let Some(active) = self.active.as_mut() {
            if active.session_id != fragment.session_id {
                let error = VideoFragmentReassemblerError::ConcurrentSession {
                    active_session_id: active.session_id.clone(),
                    incoming_session_id: fragment.session_id.to_string(),
                };
                self.active = None;
                return Err(error);
            }

            if !active.matches_metadata(&fragment) {
                self.active = None;
                return Err(VideoFragmentReassemblerError::MetadataMismatch);
            }

            if fragment.fragment_index != active.next_fragment_index {
                let error = VideoFragmentReassemblerError::UnexpectedFragmentIndex {
                    expected_index: active.next_fragment_index,
                    actual_index: fragment.fragment_index,
                };
                self.active = None;
                return Err(error);
            }

            if let Err(error) = active.push_bytes(fragment.fragment_h264_bytes) {
                self.active = None;
                return Err(error);
            }
            active.next_fragment_index += 1;
            return self.finish_if_complete();
        }

        if fragment.fragment_index != 0 {
            return Err(VideoFragmentReassemblerError::UnexpectedFragmentIndex {
                expected_index: 0,
                actual_index: fragment.fragment_index,
            });
        }
        let mut active = ActiveAccessUnit::from_first(fragment);
        active.push_bytes(fragment.fragment_h264_bytes)?;
        active.next_fragment_index = 1;
        self.active = Some(active);
        self.finish_if_complete()
    }

    fn finish_if_complete(
        &mut self,
    ) -> Result<Option<ReassembledVideoChunk>, VideoFragmentReassemblerError> {
        let Some(active) = self.active.as_ref() else {
            return Ok(None);
        };
        let actual_bytes = active.h264_bytes.len();
        let total_h264_bytes = active.total_h264_bytes;

        if actual_bytes > total_h264_bytes as usize {
            let error = VideoFragmentReassemblerError::TotalBytesExceeded {
                total_h264_bytes,
                actual_bytes,
            };
            self.active = None;
            return Err(error);
        }
        if active.next_fragment_index < active.fragment_count
            && actual_bytes == total_h264_bytes as usize
        {
            self.active = None;
            return Err(VideoFragmentReassemblerError::CompleteBeforeLastFragment);
        }
        if active.next_fragment_index == active.fragment_count {
            if actual_bytes != total_h264_bytes as usize {
                let error = VideoFragmentReassemblerError::IncompleteTotalBytes {
                    expected_bytes: total_h264_bytes,
                    actual_bytes,
                };
                self.active = None;
                return Err(error);
            }
            let active = self.active.take().expect("active checked above");
            return Ok(Some(active.finish()));
        }
        Ok(None)
    }
}

impl Default for VideoFragmentReassembler {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ActiveAccessUnit {
    session_id: String,
    chunk_index: i32,
    presentation_time_us: i64,
    kind: VideoFrameKind,
    fragment_count: i32,
    total_h264_bytes: i32,
    next_fragment_index: i32,
    h264_bytes: Vec<u8>,
}

impl ActiveAccessUnit {
    fn from_first(fragment: IncomingFragment<'_>) -> Self {
        Self {
            session_id: fragment.session_id.to_string(),
            chunk_index: fragment.chunk_index,
            presentation_time_us: fragment.presentation_time_us,
            kind: fragment.kind,
            fragment_count: fragment.fragment_count,
            total_h264_bytes: fragment.total_h264_bytes,
            next_fragment_index: 0,
            h264_bytes: Vec::with_capacity(fragment.total_h264_bytes as usize),
        }
    }

    fn matches_metadata(&self, fragment: &IncomingFragment<'_>) -> bool {
        self.chunk_index == fragment.chunk_index
            && self.presentation_time_us == fragment.presentation_time_us
            && self.kind == fragment.kind
            && self.fragment_count == fragment.fragment_count
            && self.total_h264_bytes == fragment.total_h264_bytes
    }

    fn push_bytes(&mut self, bytes: &[u8]) -> Result<(), VideoFragmentReassemblerError> {
        let actual_bytes = self.h264_bytes.len() + bytes.len();
        if self.total_h264_bytes > MAX_REASSEMBLED_H264_BYTES
            || actual_bytes > self.total_h264_bytes as usize
        {
            return Err(VideoFragmentReassemblerError::TotalBytesExceeded {
                total_h264_bytes: self.total_h264_bytes,
                actual_bytes,
            });
        }
        self.h264_bytes.extend_from_slice(bytes);
        Ok(())
    }

    fn finish(self) -> ReassembledVideoChunk {
        ReassembledVideoChunk {
            session_id: self.session_id,
            chunk_index: self.chunk_index,
            presentation_time_us: self.presentation_time_us,
            kind: self.kind,
            h264_bytes: self.h264_bytes,
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct IncomingFragment<'a> {
    session_id: &'a str,
    chunk_index: i32,
    presentation_time_us: i64,
    kind: VideoFrameKind,
    fragment_index: i32,
    fragment_count: i32,
    total_h264_bytes: i32,
    fragment_h264_bytes: &'a [u8],
}

fn validate_fragment_bounds(
    fragment: &IncomingFragment<'_>,
) -> Result<(), VideoFragmentReassemblerError> {
    if fragment.session_id.is_empty()
        || fragment.chunk_index < 0
        || fragment.presentation_time_us < 0
        || fragment.fragment_index < 0
        || fragment.fragment_count <= 0
        || fragment.fragment_count > 1024
        || fragment.fragment_index >= fragment.fragment_count
        || fragment.total_h264_bytes <= 0
        || fragment.total_h264_bytes > MAX_REASSEMBLED_H264_BYTES
        || fragment.fragment_h264_bytes.is_empty()
        || fragment.fragment_h264_bytes.len() > fragment.total_h264_bytes as usize
    {
        return Err(VideoFragmentReassemblerError::InvalidFragment);
    }
    Ok(())
}

fn payload_type_id(payload: &SessionFramePayload) -> u8 {
    match payload {
        SessionFramePayload::HandshakeHello { .. } => 1,
        SessionFramePayload::HandshakeAccept { .. } => 2,
        SessionFramePayload::HandshakeReject { .. } => 3,
        SessionFramePayload::StreamMetadata { .. } => 4,
        SessionFramePayload::VideoChunk { .. } => 5,
        SessionFramePayload::MetricsSnapshot { .. } => 6,
        SessionFramePayload::CameraControlCommand { .. } => 7,
        SessionFramePayload::VideoChunkV2 { .. } => 8,
        SessionFramePayload::VideoChunkFragmentV1 { .. } => 9,
    }
}
