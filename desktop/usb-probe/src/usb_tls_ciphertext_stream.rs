use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::time::Duration;

use crate::{FramedUsbStream, UsbBulkIo, UsbProbeError};

pub const USB_TLS_CIPHERTEXT_STREAM_ID: u32 = 0x0102_0305;
pub const USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES: usize = 32_768;
pub const USB_TLS_CIPHERTEXT_MAX_PENDING_READ_BYTES: usize = 65_536;

#[derive(Debug)]
pub struct UsbTlsCiphertextStream<I>
where
    I: UsbBulkIo,
{
    framed_stream: FramedUsbStream<I>,
    pending_read: VecDeque<u8>,
    poisoned: bool,
}

impl<I> UsbTlsCiphertextStream<I>
where
    I: UsbBulkIo,
{
    pub fn new(framed_stream: FramedUsbStream<I>) -> Self {
        Self {
            framed_stream,
            pending_read: VecDeque::new(),
            poisoned: false,
        }
    }

    pub fn framed_stream(&self) -> &FramedUsbStream<I> {
        &self.framed_stream
    }

    /// Sets or clears the idle read timeout of the underlying framed stream.
    /// Apply it after the TLS handshake, which keeps the full read timeout.
    pub fn set_idle_read_timeout(
        &mut self,
        idle_read_timeout: Option<Duration>,
    ) -> Result<(), UsbProbeError> {
        self.framed_stream.set_idle_read_timeout(idle_read_timeout)
    }

    fn read_next_ciphertext_chunk(&mut self) -> io::Result<()> {
        let frame = self.framed_stream.read_frame().map_err(read_frame_error)?;
        if frame.stream_id() != USB_TLS_CIPHERTEXT_STREAM_ID {
            return Err(invalid_data(
                "USB TLS ciphertext frame used the wrong stream id",
            ));
        }
        if frame.payload().is_empty() {
            return Err(invalid_data("USB TLS ciphertext frame payload was empty"));
        }
        if frame.payload().len() > USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES {
            return Err(invalid_data(
                "USB TLS ciphertext frame payload exceeded the chunk limit",
            ));
        }
        if self
            .pending_read
            .len()
            .saturating_add(frame.payload().len())
            > USB_TLS_CIPHERTEXT_MAX_PENDING_READ_BYTES
        {
            return Err(invalid_data("USB TLS ciphertext read buffer overflow"));
        }

        self.pending_read.extend(frame.payload().iter().copied());
        Ok(())
    }

    fn fail_closed<T>(&mut self, result: io::Result<T>) -> io::Result<T> {
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }

    fn poisoned_error() -> io::Error {
        invalid_data("USB TLS ciphertext stream is poisoned after a previous transport error")
    }

    fn copy_pending_read(&mut self, out: &mut [u8]) -> usize {
        let mut copied = 0;
        while copied < out.len() {
            let Some(byte) = self.pending_read.pop_front() else {
                break;
            };
            out[copied] = byte;
            copied += 1;
        }
        copied
    }
}

impl<I> Read for UsbTlsCiphertextStream<I>
where
    I: UsbBulkIo,
{
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if self.poisoned {
            return Err(Self::poisoned_error());
        }
        if out.is_empty() {
            return Ok(0);
        }

        let copied = self.copy_pending_read(out);
        if copied > 0 {
            return Ok(copied);
        }

        // Only an idle bulk timeout surfaces as `TimedOut`; pending_read is
        // empty here, so nothing is lost and the stream stays usable.
        match self.read_next_ciphertext_chunk() {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::TimedOut => return Err(error),
            Err(error) => return self.fail_closed(Err(error)),
        }

        Ok(self.copy_pending_read(out))
    }
}

impl<I> Write for UsbTlsCiphertextStream<I>
where
    I: UsbBulkIo,
{
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.poisoned {
            return Err(Self::poisoned_error());
        }
        if bytes.is_empty() {
            return Ok(0);
        }

        for chunk in bytes.chunks(USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES) {
            let frame = match crate::BulkFrame::new(USB_TLS_CIPHERTEXT_STREAM_ID, chunk.to_vec()) {
                Ok(frame) => frame,
                Err(error) => return self.fail_closed(Err(io_error(error))),
            };
            if let Err(error) = self.framed_stream.write_frame(&frame).map_err(io_error) {
                return self.fail_closed(Err(error));
            }
        }

        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        if self.poisoned {
            return Err(Self::poisoned_error());
        }

        Ok(())
    }
}

fn read_frame_error(error: UsbProbeError) -> io::Error {
    match error {
        UsbProbeError::BulkReadTimeout => io::Error::from(io::ErrorKind::TimedOut),
        other => io_error(other),
    }
}

fn io_error(error: UsbProbeError) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("{error:?}"))
}

fn invalid_data(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
