//! Task u3 (`odd/tasks/usb-session-runtime.md`): a crossed bulk pipe that models how real USB
//! delivers bulk transfers, unlike the byte-stream `CrossedBulkIo` doubles that merge writes.
//!
//! - every `write_bulk` is queued as ONE transfer (never merged with or split from others);
//! - `read_bulk(buffer, timeout)` waits up to `timeout` for the next transfer and returns
//!   `UsbProbeError::BulkReadTimeout` if none arrives (zero bytes consumed, as rusb
//!   guarantees for `Timeout`);
//! - a transfer that fits is copied whole; a buffer smaller than the transfer fails with the
//!   rusb overflow error text, like libusb's `LIBUSB_ERROR_OVERFLOW`.
//!
//! It is a model, not a physical test: real hardware splits large writes into 512-byte
//! packets and can deliver zero-length packets.

// Each test crate that declares `mod common;` uses only part of these helpers.
#![allow(dead_code)]

use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Condvar, Mutex,
    },
    time::{Duration, Instant},
};

use usb_probe::{UsbBulkIo, UsbProbeError};

/// One direction of the crossed pair: a queue of whole transfers.
#[derive(Debug, Default)]
pub struct UsbTransferPipe {
    transfers: Mutex<VecDeque<Vec<u8>>>,
    ready: Condvar,
    read_timeouts: AtomicUsize,
    disconnected: AtomicBool,
}

impl UsbTransferPipe {
    /// Queues `bytes` as one transfer (also usable to inject raw, partial bulk frames).
    pub fn push_transfer(&self, bytes: &[u8]) {
        self.transfers.lock().unwrap().push_back(bytes.to_vec());
        self.ready.notify_all();
    }

    /// Models an unplugged device: once queued transfers are drained, reads fail at once with
    /// rusb's `NoDevice` text.
    pub fn disconnect(&self) {
        self.disconnected.store(true, Ordering::SeqCst);
        self.ready.notify_all();
    }

    /// How many reads on this pipe ended in `BulkReadTimeout`.
    pub fn read_timeouts(&self) -> usize {
        self.read_timeouts.load(Ordering::SeqCst)
    }

    fn read_transfer(&self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        let deadline = Instant::now() + timeout;
        let mut transfers = self.transfers.lock().unwrap();
        while transfers.is_empty() {
            if self.disconnected.load(Ordering::SeqCst) {
                return Err(UsbProbeError::UsbBulkTransferFailed(
                    rusb::Error::NoDevice.to_string(),
                ));
            }
            let now = Instant::now();
            if now >= deadline {
                self.read_timeouts.fetch_add(1, Ordering::SeqCst);
                return Err(UsbProbeError::BulkReadTimeout);
            }
            transfers = self
                .ready
                .wait_timeout(transfers, deadline - now)
                .unwrap()
                .0;
        }
        let len = transfers.front().expect("non-empty queue").len();
        if buffer.len() < len {
            return Err(UsbProbeError::UsbBulkTransferFailed(
                rusb::Error::Overflow.to_string(),
            ));
        }
        let transfer = transfers.pop_front().expect("non-empty queue");
        buffer[..len].copy_from_slice(&transfer);
        Ok(len)
    }
}

/// One end of the crossed pair: writes go to `outgoing`, reads come from `incoming`.
#[derive(Debug)]
pub struct CrossedTransferBulkIo {
    outgoing: Arc<UsbTransferPipe>,
    incoming: Arc<UsbTransferPipe>,
}

impl CrossedTransferBulkIo {
    pub fn outgoing(&self) -> Arc<UsbTransferPipe> {
        Arc::clone(&self.outgoing)
    }

    pub fn incoming(&self) -> Arc<UsbTransferPipe> {
        Arc::clone(&self.incoming)
    }
}

/// Creates a crossed pair: what one end writes, the other end reads.
pub fn crossed_transfer_pair() -> (CrossedTransferBulkIo, CrossedTransferBulkIo) {
    let a_to_b = Arc::new(UsbTransferPipe::default());
    let b_to_a = Arc::new(UsbTransferPipe::default());
    (
        CrossedTransferBulkIo {
            outgoing: Arc::clone(&a_to_b),
            incoming: Arc::clone(&b_to_a),
        },
        CrossedTransferBulkIo {
            outgoing: b_to_a,
            incoming: a_to_b,
        },
    )
}

impl UsbBulkIo for CrossedTransferBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.incoming.read_transfer(buffer, timeout)
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.outgoing.push_transfer(bytes);
        Ok(bytes.len())
    }
}
