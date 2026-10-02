use crate::{
    EncodedVideoChunk, EncodedVideoFrameKind, EncodedVideoSink, EncodedVideoSinkError,
    VideoDecoderError,
};

/// Keeps the desktop session alive under video saturation (contract section 4.4). It wraps an
/// inner [`EncodedVideoSink`] and absorbs backpressure-class rejections (`QueueFull`,
/// `QueueBytesFull`, `Decoder(Backpressure)`) instead of letting them end the session: once the
/// inner sink reports backpressure the gate closes, every following `Delta` is dropped and
/// counted until a `Key` re-opens it, and the push still returns `Ok` so the receiver stays
/// open. A `CodecConfig` is never dropped: a `CodecConfig` the inner sink backpressured is held
/// as the latest pending config and re-pushed before the next `Key`. A non-backpressure error is
/// returned unchanged, so the runtime ends the session with its own decoder cause.
///
/// The receiver hands the sink already-reassembled whole chunks, so gating on whole chunks here
/// is exactly the contract's "drop frames until the next `Key`".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyframeGatedSink<K> {
    inner: K,
    gated: bool,
    pending_config: Option<EncodedVideoChunk>,
    dropped_chunks: u64,
    dropped_bytes: u64,
    saturation_events: u64,
}

impl<K> KeyframeGatedSink<K> {
    pub fn new(inner: K) -> Self {
        Self {
            inner,
            gated: false,
            pending_config: None,
            dropped_chunks: 0,
            dropped_bytes: 0,
            saturation_events: 0,
        }
    }

    pub fn inner(&self) -> &K {
        &self.inner
    }

    pub fn inner_mut(&mut self) -> &mut K {
        &mut self.inner
    }

    pub fn into_inner(self) -> K {
        self.inner
    }

    /// Whether the gate is closed: `Delta` frames are dropped until the next `Key`.
    pub fn is_gated(&self) -> bool {
        self.gated
    }

    /// Total chunks dropped under saturation (never includes `CodecConfig`).
    pub fn dropped_chunks(&self) -> u64 {
        self.dropped_chunks
    }

    /// Total payload bytes of the dropped chunks.
    pub fn dropped_bytes(&self) -> u64 {
        self.dropped_bytes
    }

    /// How many times the inner sink reported a backpressure-class rejection.
    pub fn saturation_events(&self) -> u64 {
        self.saturation_events
    }

    fn record_drop(&mut self, payload_len: usize) {
        self.dropped_chunks += 1;
        self.dropped_bytes += payload_len as u64;
    }
}

/// Whether an inner rejection is a transient saturation signal (which the gate absorbs) rather
/// than a hard failure (which ends the session).
fn is_backpressure(error: &EncodedVideoSinkError) -> bool {
    matches!(
        error,
        EncodedVideoSinkError::QueueFull { .. }
            | EncodedVideoSinkError::QueueBytesFull { .. }
            | EncodedVideoSinkError::Decoder(VideoDecoderError::Backpressure)
    )
}

impl<K> EncodedVideoSink for KeyframeGatedSink<K>
where
    K: EncodedVideoSink,
{
    fn push_encoded_video(
        &mut self,
        chunk: EncodedVideoChunk,
    ) -> Result<(), EncodedVideoSinkError> {
        match chunk.frame_kind() {
            EncodedVideoFrameKind::CodecConfig => self.push_codec_config(chunk),
            EncodedVideoFrameKind::Delta => self.push_delta(chunk),
            EncodedVideoFrameKind::Key => self.push_key(chunk),
        }
    }
}

impl<K> KeyframeGatedSink<K>
where
    K: EncodedVideoSink,
{
    fn push_codec_config(&mut self, chunk: EncodedVideoChunk) -> Result<(), EncodedVideoSinkError> {
        // Never drop a CodecConfig. Clone only so a backpressured config can be retained and
        // re-pushed before the next Key.
        match self.inner.push_encoded_video(chunk.clone()) {
            Ok(()) => {
                self.pending_config = None;
                Ok(())
            }
            Err(error) if is_backpressure(&error) => {
                self.pending_config = Some(chunk);
                self.saturation_events += 1;
                self.gated = true;
                Ok(())
            }
            Err(error) => Err(error),
        }
    }

    fn push_delta(&mut self, chunk: EncodedVideoChunk) -> Result<(), EncodedVideoSinkError> {
        if self.gated {
            self.record_drop(chunk.payload().len());
            return Ok(());
        }
        let payload_len = chunk.payload().len();
        match self.inner.push_encoded_video(chunk) {
            Ok(()) => Ok(()),
            Err(error) if is_backpressure(&error) => {
                self.record_drop(payload_len);
                self.saturation_events += 1;
                self.gated = true;
                Ok(())
            }
            Err(error) => Err(error),
        }
    }

    fn push_key(&mut self, chunk: EncodedVideoChunk) -> Result<(), EncodedVideoSinkError> {
        // A Key is the only frame that re-opens the gate, but the decoder needs its codec config
        // first: re-push any pending config, and if that still backpressures, drop this Key and
        // stay gated.
        if let Some(config) = self.pending_config.take() {
            match self.inner.push_encoded_video(config.clone()) {
                Ok(()) => {}
                Err(error) if is_backpressure(&error) => {
                    self.pending_config = Some(config);
                    self.record_drop(chunk.payload().len());
                    self.saturation_events += 1;
                    self.gated = true;
                    return Ok(());
                }
                Err(error) => return Err(error),
            }
        }

        let payload_len = chunk.payload().len();
        match self.inner.push_encoded_video(chunk) {
            Ok(()) => {
                self.gated = false;
                Ok(())
            }
            Err(error) if is_backpressure(&error) => {
                self.record_drop(payload_len);
                self.saturation_events += 1;
                self.gated = true;
                Ok(())
            }
            Err(error) => Err(error),
        }
    }
}
