use std::collections::VecDeque;

use crate::{EncodedVideoChunk, EncodedVideoSink, EncodedVideoSinkError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VideoDecoderError {
    Backpressure,
    Failure(String),
}

pub trait VideoDecoder {
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError>;
}

/// The raw pixel layout a decoder produced. Kept minimal: the two layouts the native
/// backends emit first (`Nv12` from VideoToolbox/Media Foundation, `Bgra` after conversion)
/// plus `Unknown` for anything a decoder cannot label yet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelFormat {
    Nv12,
    Bgra,
    Unknown,
}

/// One decoded frame handed across the decode boundary (contract section 4.3). Minimal by
/// design: the fields a sink needs to present or measure a frame, carrying owned pixel bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedVideoFrame {
    stream_id: u32,
    pts_us: u64,
    width: u32,
    height: u32,
    pixel_format: PixelFormat,
    data: Vec<u8>,
}

impl DecodedVideoFrame {
    pub fn new(
        stream_id: u32,
        pts_us: u64,
        width: u32,
        height: u32,
        pixel_format: PixelFormat,
        data: Vec<u8>,
    ) -> Self {
        Self {
            stream_id,
            pts_us,
            width,
            height,
            pixel_format,
            data,
        }
    }

    pub fn stream_id(&self) -> u32 {
        self.stream_id
    }

    pub fn pts_us(&self) -> u64 {
        self.pts_us
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    pub fn pixel_format(&self) -> PixelFormat {
        self.pixel_format
    }

    pub fn data(&self) -> &[u8] {
        &self.data
    }
}

/// Why a [`DecodedFrameSink`] refused a decoded frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedFrameSinkError {
    Rejected(String),
}

/// Where a decoder emits decoded frames (contract section 4.3). A decoder owns its sink and
/// pushes one frame at a time; the sink decides what to do with it (present, measure, drop).
pub trait DecodedFrameSink {
    fn push_decoded_frame(&mut self, frame: DecodedVideoFrame)
        -> Result<(), DecodedFrameSinkError>;
}

/// Exposes how many decoded frames a decoder has emitted so far, so a pipeline can measure the
/// decoded rate from the lifetime counter deltas without inspecting the decoder's private sink
/// (contract section 4.5). Additive: a real backend implements this alongside [`VideoDecoder`].
pub trait DecodedFrameCounter {
    fn frames_emitted(&self) -> u64;
}

/// A [`DecodedFrameSink`] that records every frame it is given, for tests and pipeline wiring.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RecordingDecodedFrameSink {
    frames: Vec<DecodedVideoFrame>,
}

impl RecordingDecodedFrameSink {
    pub fn frames(&self) -> &[DecodedVideoFrame] {
        &self.frames
    }
}

impl DecodedFrameSink for RecordingDecodedFrameSink {
    fn push_decoded_frame(
        &mut self,
        frame: DecodedVideoFrame,
    ) -> Result<(), DecodedFrameSinkError> {
        self.frames.push(frame);
        Ok(())
    }
}

/// Default decoded-frame geometry the fake decoder reports when it has no real backend.
const FAKE_DECODER_FRAME_WIDTH: u32 = 1280;
const FAKE_DECODER_FRAME_HEIGHT: u32 = 720;

/// A deterministic in-memory [`VideoDecoder`] for tests and later pipeline tests. It keeps the
/// encoded-in `VideoDecoder` contract unchanged and owns a [`DecodedFrameSink`] output: it
/// emits exactly one [`DecodedVideoFrame`] per non-`CodecConfig` chunk (carrying the chunk's
/// presentation timestamp), requires a `CodecConfig` before the first frame (otherwise
/// [`VideoDecoderError::Failure`]), and can be scripted to return errors in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FakeVideoDecoder<F> {
    sink: F,
    codec_config_seen: bool,
    scripted_errors: VecDeque<VideoDecoderError>,
    pixel_format: PixelFormat,
    width: u32,
    height: u32,
    frames_emitted: u64,
}

impl<F> FakeVideoDecoder<F> {
    pub fn new(sink: F) -> Self {
        Self {
            sink,
            codec_config_seen: false,
            scripted_errors: VecDeque::new(),
            pixel_format: PixelFormat::Nv12,
            width: FAKE_DECODER_FRAME_WIDTH,
            height: FAKE_DECODER_FRAME_HEIGHT,
            frames_emitted: 0,
        }
    }

    /// Queues one error to return (in order) on the next decode calls, before any frame is
    /// emitted. Chainable so tests can script a `Backpressure` or `Failure` sequence.
    pub fn script_error(mut self, error: VideoDecoderError) -> Self {
        self.scripted_errors.push_back(error);
        self
    }

    pub fn sink(&self) -> &F {
        &self.sink
    }

    pub fn sink_mut(&mut self) -> &mut F {
        &mut self.sink
    }

    pub fn into_sink(self) -> F {
        self.sink
    }
}

impl<F> VideoDecoder for FakeVideoDecoder<F>
where
    F: DecodedFrameSink,
{
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        if let Some(error) = self.scripted_errors.pop_front() {
            return Err(error);
        }

        if chunk.is_codec_config() {
            self.codec_config_seen = true;
            return Ok(());
        }

        if !self.codec_config_seen {
            return Err(VideoDecoderError::Failure(
                "codec config required before the first decoded frame".to_string(),
            ));
        }

        let frame = DecodedVideoFrame::new(
            chunk.stream_id(),
            chunk.presentation_timestamp().as_micros(),
            self.width,
            self.height,
            self.pixel_format,
            chunk.payload().to_vec(),
        );
        self.sink.push_decoded_frame(frame).map_err(|error| {
            VideoDecoderError::Failure(format!("decoded frame sink rejected frame: {error:?}"))
        })?;
        self.frames_emitted += 1;
        Ok(())
    }
}

impl<F> DecodedFrameCounter for FakeVideoDecoder<F> {
    fn frames_emitted(&self) -> u64 {
        self.frames_emitted
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodingEncodedVideoSink<D> {
    decoder: D,
}

impl<D> DecodingEncodedVideoSink<D> {
    pub fn new(decoder: D) -> Self {
        Self { decoder }
    }

    pub fn decoder(&self) -> &D {
        &self.decoder
    }

    pub fn decoder_mut(&mut self) -> &mut D {
        &mut self.decoder
    }

    pub fn into_decoder(self) -> D {
        self.decoder
    }
}

impl<D> EncodedVideoSink for DecodingEncodedVideoSink<D>
where
    D: VideoDecoder,
{
    fn push_encoded_video(
        &mut self,
        chunk: EncodedVideoChunk,
    ) -> Result<(), EncodedVideoSinkError> {
        self.decoder
            .decode_encoded_video(chunk)
            .map_err(EncodedVideoSinkError::Decoder)
    }
}
