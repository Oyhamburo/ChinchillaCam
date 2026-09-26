use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PresentationTimestamp {
    micros: u64,
}

impl PresentationTimestamp {
    pub fn from_micros(micros: u64) -> Self {
        Self { micros }
    }

    pub fn as_micros(self) -> u64 {
        self.micros
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EncodedVideoFrameKind {
    Delta,
    Key,
    CodecConfig,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EncodedVideoSinkError {
    InvalidPayloadLimit,
    EmptyPayload,
    PayloadTooLarge { length: usize, max: usize },
    InvalidQueueCapacity,
    QueueFull { capacity: usize },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodedVideoChunkLimits {
    max_payload_len: usize,
}

impl EncodedVideoChunkLimits {
    pub fn new(max_payload_len: usize) -> Result<Self, EncodedVideoSinkError> {
        if max_payload_len == 0 {
            return Err(EncodedVideoSinkError::InvalidPayloadLimit);
        }

        Ok(Self { max_payload_len })
    }

    pub fn max_payload_len(&self) -> usize {
        self.max_payload_len
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodedVideoChunk {
    stream_id: u32,
    presentation_timestamp: PresentationTimestamp,
    frame_kind: EncodedVideoFrameKind,
    payload: Vec<u8>,
}

impl EncodedVideoChunk {
    pub fn new(
        stream_id: u32,
        presentation_timestamp: PresentationTimestamp,
        frame_kind: EncodedVideoFrameKind,
        payload: Vec<u8>,
        limits: &EncodedVideoChunkLimits,
    ) -> Result<Self, EncodedVideoSinkError> {
        if payload.is_empty() {
            return Err(EncodedVideoSinkError::EmptyPayload);
        }
        if payload.len() > limits.max_payload_len() {
            return Err(EncodedVideoSinkError::PayloadTooLarge {
                length: payload.len(),
                max: limits.max_payload_len(),
            });
        }

        Ok(Self {
            stream_id,
            presentation_timestamp,
            frame_kind,
            payload,
        })
    }

    pub fn stream_id(&self) -> u32 {
        self.stream_id
    }

    pub fn presentation_timestamp(&self) -> PresentationTimestamp {
        self.presentation_timestamp
    }

    pub fn frame_kind(&self) -> EncodedVideoFrameKind {
        self.frame_kind
    }

    pub fn payload(&self) -> &[u8] {
        &self.payload
    }

    pub fn into_payload(self) -> Vec<u8> {
        self.payload
    }

    pub fn is_keyframe(&self) -> bool {
        self.frame_kind == EncodedVideoFrameKind::Key
    }

    pub fn is_codec_config(&self) -> bool {
        self.frame_kind == EncodedVideoFrameKind::CodecConfig
    }
}

pub trait EncodedVideoSink {
    fn push_encoded_video(&mut self, chunk: EncodedVideoChunk)
        -> Result<(), EncodedVideoSinkError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoundedEncodedVideoQueue {
    capacity: usize,
    chunks: VecDeque<EncodedVideoChunk>,
}

impl BoundedEncodedVideoQueue {
    pub fn new(capacity: usize) -> Result<Self, EncodedVideoSinkError> {
        if capacity == 0 {
            return Err(EncodedVideoSinkError::InvalidQueueCapacity);
        }

        Ok(Self {
            capacity,
            chunks: VecDeque::with_capacity(capacity),
        })
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn len(&self) -> usize {
        self.chunks.len()
    }

    pub fn is_empty(&self) -> bool {
        self.chunks.is_empty()
    }

    pub fn pop_front(&mut self) -> Option<EncodedVideoChunk> {
        self.chunks.pop_front()
    }
}

impl EncodedVideoSink for BoundedEncodedVideoQueue {
    fn push_encoded_video(
        &mut self,
        chunk: EncodedVideoChunk,
    ) -> Result<(), EncodedVideoSinkError> {
        if self.chunks.len() == self.capacity {
            return Err(EncodedVideoSinkError::QueueFull {
                capacity: self.capacity,
            });
        }

        self.chunks.push_back(chunk);
        Ok(())
    }
}
