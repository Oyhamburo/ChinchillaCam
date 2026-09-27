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
    PayloadTooLarge {
        length: usize,
        max: usize,
    },
    InvalidQueueCapacity,
    QueueCapacityTooLarge {
        capacity: usize,
        max: usize,
    },
    InvalidQueueByteLimit,
    QueueByteLimitTooLarge {
        max_queued_bytes: usize,
        max: usize,
    },
    QueueFull {
        capacity: usize,
    },
    QueueBytesFull {
        queued: usize,
        incoming: usize,
        max: usize,
    },
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
    max_queued_bytes: usize,
    queued_bytes: usize,
    chunks: VecDeque<EncodedVideoChunk>,
}

impl BoundedEncodedVideoQueue {
    pub const MAX_CAPACITY: usize = 4096;
    pub const DEFAULT_MAX_QUEUED_BYTES: usize = 16 * 1024 * 1024;
    pub const MAX_QUEUED_BYTES: usize = 64 * 1024 * 1024;

    pub fn new(capacity: usize) -> Result<Self, EncodedVideoSinkError> {
        Self::with_limits(capacity, Self::DEFAULT_MAX_QUEUED_BYTES)
    }

    pub fn with_limits(
        capacity: usize,
        max_queued_bytes: usize,
    ) -> Result<Self, EncodedVideoSinkError> {
        if capacity == 0 {
            return Err(EncodedVideoSinkError::InvalidQueueCapacity);
        }
        if capacity > Self::MAX_CAPACITY {
            return Err(EncodedVideoSinkError::QueueCapacityTooLarge {
                capacity,
                max: Self::MAX_CAPACITY,
            });
        }
        if max_queued_bytes == 0 {
            return Err(EncodedVideoSinkError::InvalidQueueByteLimit);
        }
        if max_queued_bytes > Self::MAX_QUEUED_BYTES {
            return Err(EncodedVideoSinkError::QueueByteLimitTooLarge {
                max_queued_bytes,
                max: Self::MAX_QUEUED_BYTES,
            });
        }

        Ok(Self {
            capacity,
            max_queued_bytes,
            queued_bytes: 0,
            chunks: VecDeque::new(),
        })
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn len(&self) -> usize {
        self.chunks.len()
    }

    pub fn max_queued_bytes(&self) -> usize {
        self.max_queued_bytes
    }

    pub fn queued_bytes(&self) -> usize {
        self.queued_bytes
    }

    pub fn is_empty(&self) -> bool {
        self.chunks.is_empty()
    }

    pub fn pop_front(&mut self) -> Option<EncodedVideoChunk> {
        let chunk = self.chunks.pop_front()?;
        self.queued_bytes -= chunk.payload().len();
        Some(chunk)
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

        let incoming = chunk.payload().len();
        let Some(next_queued_bytes) = self.queued_bytes.checked_add(incoming) else {
            return Err(EncodedVideoSinkError::QueueBytesFull {
                queued: self.queued_bytes,
                incoming,
                max: self.max_queued_bytes,
            });
        };
        if next_queued_bytes > self.max_queued_bytes {
            return Err(EncodedVideoSinkError::QueueBytesFull {
                queued: self.queued_bytes,
                incoming,
                max: self.max_queued_bytes,
            });
        }

        self.queued_bytes = next_queued_bytes;
        self.chunks.push_back(chunk);
        Ok(())
    }
}
