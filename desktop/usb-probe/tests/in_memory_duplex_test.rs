//! Task f1 (`odd/tasks/review-followups.md`, finding `R3-duplex-failure-paths-unexercised`):
//! characterization tests for the failure paths of the shared in-memory duplex double
//! (`common::duplex::InMemoryDuplex`). The session tests only drive its happy path; these pin
//! the documented contract:
//! - no data before the per-read timeout -> `io::ErrorKind::TimedOut`;
//! - peer dropped -> buffered bytes are drained first, then `Ok(0)` (EOF).
//!
//! Timing bounds are deliberately generous (lower bound = timeout minus a tolerance, upper
//! bound = several seconds) so the tests do not depend on scheduler precision.

mod common;

use std::{
    io::{ErrorKind, Read, Write},
    sync::mpsc,
    time::{Duration, Instant},
};

use common::duplex::InMemoryDuplex;

const TOLERANCE: Duration = Duration::from_millis(50);
const UPPER_BOUND: Duration = Duration::from_secs(5);

#[test]
fn duplex_read_times_out_without_data() {
    let read_timeout = Duration::from_millis(200);
    let (mut reader, peer) = InMemoryDuplex::pair(read_timeout);

    let mut buf = [0u8; 16];
    let start = Instant::now();
    let result = reader.read(&mut buf);
    let elapsed = start.elapsed();

    // The peer is still alive, so this must be a timeout, not EOF.
    drop(peer);
    let error = result.expect_err("read with no data and a live peer must time out");
    assert_eq!(
        error.kind(),
        ErrorKind::TimedOut,
        "unexpected error {error:?}"
    );
    assert!(
        elapsed >= read_timeout - TOLERANCE,
        "read must wait for the read timeout before failing, took {elapsed:?}"
    );
    assert!(
        elapsed < UPPER_BOUND,
        "read timeout must be bounded, took {elapsed:?}"
    );
}

#[test]
fn duplex_read_returns_eof_after_peer_drop() {
    // A long read timeout proves EOF is reported because the peer closed, not because the
    // read budget elapsed.
    let (mut reader, mut peer) = InMemoryDuplex::pair(UPPER_BOUND);
    peer.write_all(b"tail").unwrap();
    drop(peer);

    let mut buf = [0u8; 16];
    let start = Instant::now();
    let read = reader
        .read(&mut buf)
        .expect("buffered data after peer drop");
    assert_eq!(&buf[..read], b"tail", "buffered data must be drained first");

    assert_eq!(reader.read(&mut buf).expect("EOF after drain"), 0);
    assert_eq!(
        reader.read(&mut buf).expect("EOF stays EOF"),
        0,
        "EOF must be sticky"
    );
    let elapsed = start.elapsed();
    assert!(
        elapsed < Duration::from_secs(1),
        "EOF must be immediate, not wait for the read timeout, took {elapsed:?}"
    );
}

#[test]
fn duplex_blocked_read_wakes_with_eof_when_peer_drops() {
    let (mut reader, peer) = InMemoryDuplex::pair(UPPER_BOUND);
    let (tx, rx) = mpsc::channel();

    // The helper owns the reader and its own read is bounded by the read timeout, so it can
    // never outlive the test; the result is collected with a bounded `recv_timeout` and the
    // assertions run only after the helper has finished.
    let helper = std::thread::spawn(move || {
        let mut buf = [0u8; 16];
        let start = Instant::now();
        let result = reader.read(&mut buf).map_err(|error| error.kind());
        let _ = tx.send((result, start.elapsed()));
    });

    std::thread::sleep(Duration::from_millis(100));
    drop(peer);

    let outcome = rx.recv_timeout(UPPER_BOUND + Duration::from_secs(1));
    helper.join().expect("reader helper panicked");
    let (result, elapsed) = outcome.expect("blocked reader must report within the bound");
    assert_eq!(
        result,
        Ok(0),
        "peer drop must wake a blocked reader with EOF"
    );
    assert!(
        elapsed < Duration::from_secs(2),
        "EOF must wake the reader promptly, took {elapsed:?}"
    );
}

#[test]
fn duplex_buffered_data_is_read_before_eof() {
    let (mut reader, mut peer) = InMemoryDuplex::pair(Duration::from_millis(500));
    peer.write_all(b"first-").unwrap();
    peer.write_all(b"second-").unwrap();
    peer.write_all(b"third").unwrap();
    drop(peer);

    // A small buffer forces several reads; every byte must arrive, in order, before EOF.
    let mut received = Vec::new();
    let mut buf = [0u8; 4];
    loop {
        let read = reader.read(&mut buf).expect("read before EOF");
        if read == 0 {
            break;
        }
        assert!(read <= buf.len());
        received.extend_from_slice(&buf[..read]);
    }
    assert_eq!(received, b"first-second-third");
}
