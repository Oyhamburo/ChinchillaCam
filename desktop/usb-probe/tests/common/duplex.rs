//! Task d1 (`odd/tasks/wifi-loopback-transport.md`, contract section 4): a shared in-memory
//! duplex byte pair implementing `Read + Write` with NO USB framing. It models a raw
//! bidirectional byte transport (the shape a Wi-Fi/TCP stream has) so the TLS
//! pairing/reconnection stack can be exercised over something that is not
//! `UsbTlsCiphertextStream`.
//!
//! Two crossed byte pipes (`Mutex<VecDeque<u8>>` + `Condvar`). A read blocks until data
//! arrives, the peer closes, or the per-stream read timeout elapses:
//! - no data before the timeout -> `io::ErrorKind::TimedOut`;
//! - peer dropped/closed and no buffered data -> `Ok(0)` (EOF).
//!
//! Timeouts are generous on purpose (local, in-memory, deterministic): this double must not
//! depend on tight timing.

use std::{
    collections::VecDeque,
    io::{self, Read, Write},
    sync::{Arc, Condvar, Mutex},
    time::{Duration, Instant},
};

#[derive(Default)]
struct PipeState {
    buffer: VecDeque<u8>,
    writer_closed: bool,
}

#[derive(Default)]
struct Pipe {
    state: Mutex<PipeState>,
    ready: Condvar,
}

/// One end of an in-memory duplex byte stream. Bytes written here reach the peer's reader;
/// bytes the peer writes are read here. Dropping this end signals EOF to the peer's reader.
pub struct InMemoryDuplex {
    outgoing: Arc<Pipe>,
    incoming: Arc<Pipe>,
    read_timeout: Duration,
}

impl InMemoryDuplex {
    /// Creates a crossed pair of duplex ends. Each end's writes land in the other end's
    /// read buffer. Both share the same `read_timeout` budget per blocking read.
    pub fn pair(read_timeout: Duration) -> (InMemoryDuplex, InMemoryDuplex) {
        let a = Arc::new(Pipe::default());
        let b = Arc::new(Pipe::default());
        (
            InMemoryDuplex {
                outgoing: a.clone(),
                incoming: b.clone(),
                read_timeout,
            },
            InMemoryDuplex {
                outgoing: b,
                incoming: a,
                read_timeout,
            },
        )
    }
}

impl Read for InMemoryDuplex {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let deadline = Instant::now() + self.read_timeout;
        let mut state = self.incoming.state.lock().unwrap();
        while state.buffer.is_empty() {
            if state.writer_closed {
                return Ok(0);
            }
            let now = Instant::now();
            if now >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "in-memory duplex read timed out",
                ));
            }
            let (guard, result) = self
                .incoming
                .ready
                .wait_timeout(state, deadline - now)
                .unwrap();
            state = guard;
            if result.timed_out() && state.buffer.is_empty() && !state.writer_closed {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "in-memory duplex read timed out",
                ));
            }
        }

        let mut read = 0;
        while read < buf.len() {
            let Some(byte) = state.buffer.pop_front() else {
                break;
            };
            buf[read] = byte;
            read += 1;
        }
        Ok(read)
    }
}

impl Write for InMemoryDuplex {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let mut state = self.outgoing.state.lock().unwrap();
        state.buffer.extend(bytes.iter().copied());
        self.outgoing.ready.notify_all();
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Drop for InMemoryDuplex {
    fn drop(&mut self) {
        let mut state = self.outgoing.state.lock().unwrap();
        state.writer_closed = true;
        self.outgoing.ready.notify_all();
    }
}
