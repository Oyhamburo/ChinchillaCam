//! Task s1 (`odd/tasks/usb-authenticated-session.md`, contract section 4.3): framing of
//! `SessionFrame` over the live TLS application-data stream. Unlike
//! `usb_tls_pairing_proof_test.rs` (which exercises the CCP1 pairing-proof handshake) this
//! file only exercises the framing layer that sits on top of an already-completed TLS
//! connection, so the handshake helpers below deliberately skip the CCP1/pairing-proof and
//! trusted-store machinery and only set up a plain mutually authenticated TLS pair.

use std::{
    collections::{BTreeMap, VecDeque},
    io::{self, Cursor, Read},
    sync::{Arc, Condvar, Mutex},
    thread,
    time::{Duration, Instant},
};

use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName},
    ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned,
};
use usb_probe::{
    read_session_frame, read_session_frame_with_budgets, write_session_frame, DesktopTlsIdentity,
    FrameTransferBudget, FramedUsbStream, PhoneClientCertVerifier, SessionFrame, SessionFrameCodec,
    SessionFramePayload, TlsSessionFrameError, UsbBulkIo, UsbProbeError, UsbTlsCiphertextStream,
};

#[test]
fn round_trips_session_frame_over_tls() {
    let (mut server_tls, mut client_tls) = connected_tls_pair();

    let server_frame = SessionFrame::new(
        1,
        "session-01",
        SessionFramePayload::HandshakeAccept {
            desktop_id: "desktop-01".to_string(),
            message: "welcome".to_string(),
        },
    );
    write_session_frame(&mut server_tls, &server_frame).unwrap();
    let received = read_session_frame(&mut client_tls, test_deadline()).unwrap();
    assert_eq!(received, server_frame);

    let client_frame = SessionFrame::new(
        2,
        "session-01",
        SessionFramePayload::HandshakeHello {
            device_id: "phone-01".to_string(),
            app_name: "ChinchillaCam".to_string(),
            capabilities: vec!["video".to_string()],
        },
    );
    write_session_frame(&mut client_tls, &client_frame).unwrap();
    let received = read_session_frame(&mut server_tls, test_deadline()).unwrap();
    assert_eq!(received, client_frame);
}

#[test]
fn rejects_oversized_length_before_allocating() {
    let oversized_length = SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE as u32 + 1;
    // Only the 4-byte length prefix is provided: if the implementation allocated (or
    // tried to read) `oversized_length` bytes before validating it, this would fail with
    // a truncation/IO error instead of the expected `InvalidLength`.
    let mut source = Cursor::new(oversized_length.to_be_bytes().to_vec());

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(
        result,
        Err(TlsSessionFrameError::InvalidLength(oversized_length))
    );
}

#[test]
fn rejects_zero_length() {
    let mut source = Cursor::new(0u32.to_be_bytes().to_vec());

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(result, Err(TlsSessionFrameError::InvalidLength(0)));
}

#[test]
fn truncated_frame_fails_closed() {
    let mut bytes = 20u32.to_be_bytes().to_vec();
    bytes.extend_from_slice(&[0u8; 5]); // declares 20 payload bytes, provides only 5
    let mut source = Cursor::new(bytes);

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(result, Err(TlsSessionFrameError::TruncatedFrame("payload")));
}

#[test]
fn read_times_out_when_deadline_already_passed() {
    let mut source: &[u8] = &[];
    let already_passed_deadline = Instant::now() - Duration::from_millis(1);

    let result = read_session_frame(&mut source, already_passed_deadline);

    assert_eq!(result, Err(TlsSessionFrameError::Timeout));
}

/// Task s1b (native review of s1, finding R3-missing-boundary-coverage): s1 tested a
/// truncated PAYLOAD (`truncated_frame_fails_closed` above) but never a truncated length
/// PREFIX itself.
#[test]
fn truncated_length_prefix_fails_closed() {
    let mut source = Cursor::new(vec![0x00, 0x01]); // only 2 of the 4 length-prefix bytes

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(
        result,
        Err(TlsSessionFrameError::TruncatedFrame("length prefix"))
    );
}

/// Task s1b (native review of s1, finding R3-missing-boundary-coverage): the inclusive
/// upper bound (`declared_len == MAX_TLS_SESSION_FRAME_LEN`, i.e. exactly 1_048_576) was
/// never exercised, only the oversized (`+1`) and zero-length rejections above. This closes
/// that gap with a real frame at the exact boundary, round-tripped over TLS.
#[test]
fn accepts_frame_at_exact_max_length_boundary() {
    let frame = max_length_session_frame();
    let encoded = SessionFrameCodec::encode(&frame).unwrap();
    assert_eq!(
        encoded.len(),
        SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE,
        "fixture must hit the exact boundary for this test to be meaningful"
    );

    let (mut server_tls, mut client_tls) = connected_tls_pair();
    write_session_frame(&mut server_tls, &frame).unwrap();
    let received = read_session_frame(&mut client_tls, test_deadline()).unwrap();

    assert_eq!(received, frame);
}

/// Task s1b (native review finding R3-error-kind-mapping): `std::io::Read::read_exact`
/// retries `ErrorKind::Interrupted` transparently (it never reaches a caller as an error);
/// `read_session_frame` must do the same instead of surfacing it as `TlsSessionFrameError::Io`.
#[test]
fn retries_interrupted_read_like_read_exact() {
    let bytes = encoded_frame_with_length_prefix(&sample_session_frame());
    let mut source = InterruptOnceThenRead {
        interrupted: false,
        inner: Cursor::new(bytes),
    };

    let result = read_session_frame(&mut source, test_deadline());

    assert_eq!(
        result,
        Ok(sample_session_frame()),
        "an Interrupted read must be retried like std::io::Read::read_exact, not surfaced as an error"
    );
}

/// Task s1b (native review finding R3-error-kind-mapping): a `WouldBlock`/`TimedOut` read
/// reported well before the absolute deadline is not necessarily a real timeout (a
/// non-blocking transport can report it long before `deadline`); it must be retried, not
/// mapped straight to `TlsSessionFrameError::Timeout`.
#[test]
fn retries_would_block_before_the_deadline_instead_of_timing_out_immediately() {
    let bytes = encoded_frame_with_length_prefix(&sample_session_frame());
    let mut source = WouldBlockThenRead {
        would_block_remaining: 3,
        inner: Cursor::new(bytes),
    };
    let generous_deadline = Instant::now() + Duration::from_millis(500);

    let result = read_session_frame(&mut source, generous_deadline);

    assert_eq!(
        result,
        Ok(sample_session_frame()),
        "a transient WouldBlock well before the deadline must be retried, not surfaced as Timeout immediately"
    );
}

/// Companion to the test above: NOT a RED test (see "Evidencia s1b" in
/// `odd/tasks/usb-authenticated-session.md` -- this already passed before the s1b fix too,
/// since the old code also mapped `WouldBlock` straight to `Timeout`). It guards against a
/// regression where retrying `WouldBlock` until the deadline could turn into an unbounded
/// spin: this reader NEVER produces data, so the retry loop must still fail closed with
/// `Timeout` once `deadline` actually passes.
#[test]
fn would_block_past_the_deadline_still_times_out() {
    let mut source = AlwaysWouldBlockRead;
    let short_deadline = Instant::now() + Duration::from_millis(30);

    let result = read_session_frame(&mut source, short_deadline);

    assert_eq!(result, Err(TlsSessionFrameError::Timeout));
}

/// Task l1 (`odd/tasks/session-liveness.md`, contract section 4.1): the wait for the FIRST
/// byte of the next frame must be bounded by `idle_budget`, not `frame_budget`. A peer that
/// starts its next frame well after a short `frame_budget` but still within a generous
/// `idle_budget` must still succeed.
#[test]
fn idle_session_does_not_time_out_waiting_for_next_frame() {
    let bytes = encoded_frame_with_length_prefix(&sample_session_frame());
    let mut source = DelayedRead {
        release_at: Instant::now() + Duration::from_millis(150),
        inner: Cursor::new(bytes),
    };
    let idle_budget = Duration::from_millis(600);
    let frame_budget = Duration::from_millis(50);

    let result = read_session_frame_with_budgets(&mut source, idle_budget, frame_budget);

    assert_eq!(
        result,
        Ok(sample_session_frame()),
        "the wait for the first byte must be bounded by idle_budget, not frame_budget"
    );
}

/// Task l1: no byte of the next frame ever arrives, so the idle-wait phase must fail closed
/// with `PeerIdle` (not `Timeout`) once `idle_budget` passes.
#[test]
fn idle_beyond_threshold_fails_as_peer_idle() {
    let mut source = AlwaysWouldBlockRead;
    let idle_budget = Duration::from_millis(30);
    let frame_budget = Duration::from_millis(500);

    let result = read_session_frame_with_budgets(&mut source, idle_budget, frame_budget);

    assert_eq!(result, Err(TlsSessionFrameError::PeerIdle));
}

/// Task l1: the peer sends the first byte of a frame immediately (satisfying the idle
/// wait), then stalls indefinitely. The remainder of the frame must still fail closed with
/// `Timeout` once `frame_budget` passes, exactly like `read_session_frame`.
#[test]
fn stalled_frame_after_first_byte_times_out() {
    let mut source = StallsAfterFirstByte {
        first_byte: Some(0),
    };
    let idle_budget = Duration::from_millis(500);
    let frame_budget = Duration::from_millis(30);

    let result = read_session_frame_with_budgets(&mut source, idle_budget, frame_budget);

    assert_eq!(result, Err(TlsSessionFrameError::Timeout));
}

/// Task s1 (`odd/tasks/session-runtime.md`, contract section 4.2 / problem statement): the
/// idle-wait phase must never lose a first byte that arrives (or whose read returns) only
/// after the idle deadline has already elapsed. The reader below blocks inside its single
/// `read` call until past the idle deadline, then returns a real first byte; the old
/// post-read deadline check consumed that byte but still reported `PeerIdle`, desyncing the
/// stream. The read must instead continue into phase (b) and complete the frame.
#[test]
fn first_byte_after_idle_deadline_is_not_lost() {
    let bytes = encoded_frame_with_length_prefix(&sample_session_frame());
    let idle_budget = Duration::from_millis(30);
    let frame_budget = Duration::from_millis(500);
    let mut source = ReleasesFirstByteAfterDeadline {
        release_at: Instant::now() + Duration::from_millis(80),
        first_byte: bytes[0],
        first_byte_sent: false,
        rest: Cursor::new(bytes[1..].to_vec()),
    };

    let result = read_session_frame_with_budgets(&mut source, idle_budget, frame_budget);

    assert_eq!(
        result,
        Ok(sample_session_frame()),
        "a first byte whose read returns after the idle deadline must continue into phase (b), not be lost as PeerIdle"
    );
}

/// Task s1 (`odd/tasks/session-runtime.md`): a zero `idle_budget` still attempts exactly one
/// read (the chosen behaviour over rejecting zero with a typed error). An immediately
/// available first byte is therefore read and the frame completes under `frame_budget`.
#[test]
fn zero_idle_budget_still_attempts_one_read() {
    let bytes = encoded_frame_with_length_prefix(&sample_session_frame());
    let mut source = Cursor::new(bytes);

    let result =
        read_session_frame_with_budgets(&mut source, Duration::ZERO, Duration::from_millis(500));

    assert_eq!(
        result,
        Ok(sample_session_frame()),
        "a zero idle budget must still attempt one read, not fail closed before reading"
    );
}

fn test_deadline() -> Instant {
    Instant::now() + Duration::from_millis(1500)
}

fn sample_session_frame() -> SessionFrame {
    SessionFrame::new(
        1,
        "session-01",
        SessionFramePayload::HandshakeAccept {
            desktop_id: "desktop-01".to_string(),
            message: "welcome".to_string(),
        },
    )
}

fn encoded_frame_with_length_prefix(frame: &SessionFrame) -> Vec<u8> {
    let encoded = SessionFrameCodec::encode(frame).unwrap();
    let mut bytes = (encoded.len() as u32).to_be_bytes().to_vec();
    bytes.extend_from_slice(&encoded);
    bytes
}

/// See `accepts_frame_at_exact_max_length_boundary`: builds a `SessionFrame` whose CCSF v1
/// encoding is exactly `SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE` (1_048_576) bytes -- the
/// exact upper bound the length prefix must still accept (contract section 4.3:
/// `1..=1_048_576`). A single string/bytes field is length-prefixed with `u16` (max 65_535
/// bytes), so no single field can reach the 1 MiB bound alone: this uses 16
/// `CameraControlCommand` argument entries instead (15 at the maximum 65_535-byte value,
/// plus one final entry sized to close the exact remaining gap: 16*2-byte keys + 15*65_535
/// + 1*65_433 value bytes + 4 bytes per entry of length-prefix overhead + the 16-byte CCSF
/// header + 1-byte session id + 1-byte command + 2-byte argument-count field == 1_048_576).
/// The arithmetic is asserted against the real encoder's output above rather than trusted
/// blindly.
fn max_length_session_frame() -> SessionFrame {
    let mut arguments = BTreeMap::new();
    for index in 0..15u32 {
        arguments.insert(format!("{index:02}"), "a".repeat(u16::MAX as usize));
    }
    arguments.insert("15".to_string(), "a".repeat(65_433));

    SessionFrame::new(
        1,
        "s",
        SessionFramePayload::CameraControlCommand {
            command: "c".to_string(),
            arguments,
        },
    )
}

/// Returns `Interrupted` on the very first `read` call (consuming no bytes), then
/// delegates to `inner` for every subsequent call.
struct InterruptOnceThenRead<R> {
    interrupted: bool,
    inner: R,
}

impl<R: Read> Read for InterruptOnceThenRead<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if !self.interrupted {
            self.interrupted = true;
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "simulated interrupt",
            ));
        }
        self.inner.read(buf)
    }
}

/// Returns `WouldBlock` for the first `would_block_remaining` calls (consuming no bytes),
/// then delegates to `inner` for every subsequent call.
struct WouldBlockThenRead<R> {
    would_block_remaining: usize,
    inner: R,
}

impl<R: Read> Read for WouldBlockThenRead<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.would_block_remaining > 0 {
            self.would_block_remaining -= 1;
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "simulated would-block",
            ));
        }
        self.inner.read(buf)
    }
}

/// Always returns `WouldBlock`, never any data.
struct AlwaysWouldBlockRead;

impl Read for AlwaysWouldBlockRead {
    fn read(&mut self, _buf: &mut [u8]) -> io::Result<usize> {
        Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "simulated would-block",
        ))
    }
}

/// Reports `WouldBlock` until `release_at`, then delegates every call to `inner`. Simulates
/// a peer that starts sending its next frame only after a delay.
struct DelayedRead<R> {
    release_at: Instant,
    inner: R,
}

impl<R: Read> Read for DelayedRead<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if Instant::now() < self.release_at {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "simulated pre-release delay",
            ));
        }
        self.inner.read(buf)
    }
}

/// Delivers exactly one byte on the first `read` call (regardless of how many bytes were
/// requested), then reports `WouldBlock` forever afterward -- simulating a peer that starts
/// a frame (satisfying an idle wait) but then stalls indefinitely partway through it.
struct StallsAfterFirstByte {
    first_byte: Option<u8>,
}

impl Read for StallsAfterFirstByte {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self.first_byte.take() {
            Some(byte) => {
                buf[0] = byte;
                Ok(1)
            }
            None => Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "simulated stall after first byte",
            )),
        }
    }
}

/// Blocks inside its single first `read` call until `release_at` (which the caller sets
/// past the idle deadline), then returns exactly one byte; every later call delegates to
/// `rest`. Simulates a blocking read that returns a real first byte only after the idle
/// deadline has elapsed.
struct ReleasesFirstByteAfterDeadline {
    release_at: Instant,
    first_byte: u8,
    first_byte_sent: bool,
    rest: Cursor<Vec<u8>>,
}

impl Read for ReleasesFirstByteAfterDeadline {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if !self.first_byte_sent {
            let now = Instant::now();
            if now < self.release_at {
                thread::sleep(self.release_at - now);
            }
            self.first_byte_sent = true;
            buf[0] = self.first_byte;
            return Ok(1);
        }
        self.rest.read(buf)
    }
}

/// Builds a plain (non-pairing, non-trusted-reconnect) mutually authenticated TLS pair
/// over an in-memory `CrossedBulkIo`, so this file can exercise the framing layer without
/// pulling in the CCP1 pairing-proof exchange or the trusted-phone-store machinery those
/// other flows need.
fn connected_tls_pair() -> (
    StreamOwned<ServerConnection, UsbTlsCiphertextStream<CrossedBulkIo>>,
    StreamOwned<ClientConnection, UsbTlsCiphertextStream<CrossedBulkIo>>,
) {
    let server_identity = DesktopTlsIdentity::generate_ephemeral("Studio Desktop").unwrap();
    let phone_identity = DesktopTlsIdentity::generate_ephemeral("Test Phone Client").unwrap();
    let server_cert = server_identity.certificate_der().to_vec();
    let (desktop_io, phone_io) = crossed_bulk_pair();
    let mut desktop_stream = ciphertext_stream(desktop_io);
    let mut phone_stream = ciphertext_stream(phone_io);

    let mut server_connection = ServerConnection::new(server_config(&server_identity)).unwrap();
    let mut client_connection = ClientConnection::new(
        Arc::new(client_config(&server_cert, &phone_identity)),
        ServerName::try_from("localhost").unwrap(),
    )
    .unwrap();

    thread::scope(|scope| {
        let server_handshake = scope.spawn(|| {
            while server_connection.is_handshaking() {
                server_connection.complete_io(&mut desktop_stream).unwrap();
            }
        });
        while client_connection.is_handshaking() {
            client_connection.complete_io(&mut phone_stream).unwrap();
        }
        server_handshake.join().unwrap();
    });

    (
        StreamOwned::new(server_connection, desktop_stream),
        StreamOwned::new(client_connection, phone_stream),
    )
}

fn server_config(identity: &DesktopTlsIdentity) -> Arc<ServerConfig> {
    let provider = rustls::crypto::ring::default_provider();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        identity.private_key_pkcs8_der().to_vec(),
    ));
    let cert = CertificateDer::from(identity.certificate_der().to_vec());
    Arc::new(
        ServerConfig::builder_with_provider(provider.into())
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .unwrap()
            .with_client_cert_verifier(PhoneClientCertVerifier::pairing())
            .with_single_cert(vec![cert], key)
            .unwrap(),
    )
}

fn client_config(root_cert: &[u8], phone_identity: &DesktopTlsIdentity) -> ClientConfig {
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(root_cert.to_vec())).unwrap();
    let client_cert = CertificateDer::from(phone_identity.certificate_der().to_vec());
    let client_key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
        phone_identity.private_key_pkcs8_der().to_vec(),
    ));
    ClientConfig::builder()
        .with_root_certificates(roots)
        .with_client_auth_cert(vec![client_cert], client_key)
        .unwrap()
}

fn ciphertext_stream<I: UsbBulkIo>(io: I) -> UsbTlsCiphertextStream<I> {
    UsbTlsCiphertextStream::new(FramedUsbStream::new(
        io,
        FrameTransferBudget::new(Duration::from_millis(1500), 65_536, 512).unwrap(),
    ))
}

fn crossed_bulk_pair() -> (CrossedBulkIo, CrossedBulkIo) {
    let a_to_b = Arc::new(BulkPipe::default());
    let b_to_a = Arc::new(BulkPipe::default());
    (
        CrossedBulkIo::new(a_to_b.clone(), b_to_a.clone()),
        CrossedBulkIo::new(b_to_a, a_to_b),
    )
}

#[derive(Debug)]
struct CrossedBulkIo {
    outgoing: Arc<BulkPipe>,
    incoming: Arc<BulkPipe>,
}

impl CrossedBulkIo {
    fn new(outgoing: Arc<BulkPipe>, incoming: Arc<BulkPipe>) -> Self {
        Self { outgoing, incoming }
    }
}

impl UsbBulkIo for CrossedBulkIo {
    fn read_bulk(&mut self, buffer: &mut [u8], timeout: Duration) -> Result<usize, UsbProbeError> {
        self.incoming.read(buffer, timeout).map_err(|error| {
            UsbProbeError::UsbBulkTransferFailed(format!("crossed read failed: {error}"))
        })
    }

    fn write_bulk(&mut self, bytes: &[u8], _timeout: Duration) -> Result<usize, UsbProbeError> {
        self.outgoing.write(bytes);
        Ok(bytes.len())
    }
}

#[derive(Debug, Default)]
struct BulkPipe {
    queue: Mutex<VecDeque<u8>>,
    ready: Condvar,
}

impl BulkPipe {
    fn write(&self, bytes: &[u8]) {
        let mut queue = self.queue.lock().unwrap();
        queue.extend(bytes.iter().copied());
        self.ready.notify_all();
    }

    fn read(&self, buffer: &mut [u8], timeout: Duration) -> std::io::Result<usize> {
        let deadline = Instant::now() + timeout;
        let mut queue = self.queue.lock().unwrap();
        while queue.is_empty() {
            let now = Instant::now();
            if now >= deadline {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "bulk read timed out",
                ));
            }
            let wait = deadline - now;
            let (guard, result) = self.ready.wait_timeout(queue, wait).unwrap();
            queue = guard;
            if result.timed_out() && queue.is_empty() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "bulk read timed out",
                ));
            }
        }

        let mut read = 0;
        while read < buffer.len() {
            let Some(byte) = queue.pop_front() else {
                break;
            };
            buffer[read] = byte;
            read += 1;
        }
        Ok(read)
    }
}
