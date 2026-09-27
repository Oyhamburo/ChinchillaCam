use crate::{EncodedVideoChunk, EncodedVideoSink, EncodedVideoSinkError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VideoDecoderError {
    Backpressure,
    Failure(String),
}

pub trait VideoDecoder {
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError>;
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
