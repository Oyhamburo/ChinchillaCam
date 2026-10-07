//! A loopback-only TCP listener that models a "fake Wi-Fi LAN" transport (contract section
//! 4.2): it binds ONLY `127.0.0.1` with an ephemeral port and hands each accepted
//! `TcpStream` to the existing mTLS pairing/reconnection accept functions unchanged. TLS
//! records travel directly over the byte stream, with no `AccessoryFrame`/`BulkFrame`
//! envelope, exactly as they would over real Wi-Fi.
//!
//! This is deliberately NOT the `LoopbackPairingProofServer`: that server configures TLS with
//! no client auth, whereas the pairing/reconnection stack requires a client certificate. The
//! listener here only owns the loopback socket and the accepted-stream timeouts; the TLS and
//! session logic stays in `phone_connection`/`usb_tls_pairing_proof`.

use std::{
    fmt,
    net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream},
    time::{Duration, Instant},
};

/// The only address the listener is ever allowed to bind. Loopback IPv4, ephemeral port; not
/// configurable (the gate M3 forbids any non-loopback listener until T17).
const LOOPBACK_BIND_IP: Ipv4Addr = Ipv4Addr::LOCALHOST;
const LOOPBACK_BIND_PORT: u16 = 0;

/// How long `accept` polls the nonblocking listener between retries.
const ACCEPT_POLL_INTERVAL: Duration = Duration::from_millis(5);

/// Default bounded read/write timeouts applied to each accepted stream so a stalled or silent
/// peer cannot block the handshake indefinitely (contract section 5).
pub const DEFAULT_STREAM_READ_TIMEOUT: Duration = Duration::from_millis(1500);
pub const DEFAULT_STREAM_WRITE_TIMEOUT: Duration = Duration::from_millis(1500);

/// Bounded timeouts set on every accepted stream before it is returned.
#[derive(Debug, Clone, Copy)]
pub struct LoopbackLanOptions {
    pub read_timeout: Duration,
    pub write_timeout: Duration,
}

impl Default for LoopbackLanOptions {
    fn default() -> Self {
        Self {
            read_timeout: DEFAULT_STREAM_READ_TIMEOUT,
            write_timeout: DEFAULT_STREAM_WRITE_TIMEOUT,
        }
    }
}

#[derive(Debug)]
pub enum LoopbackLanError {
    /// Binding the loopback listener failed.
    Bind(String),
    /// A socket operation on the listener or an accepted stream failed.
    Io(String),
    /// No connection arrived within the accept deadline.
    Timeout,
    /// Defensive guard: an accepted peer's address was not loopback. The connection is closed
    /// and rejected rather than handed to the TLS stack.
    NonLoopbackPeer(SocketAddr),
}

impl fmt::Display for LoopbackLanError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Bind(error) => write!(formatter, "loopback LAN listener bind error: {error}"),
            Self::Io(error) => write!(formatter, "loopback LAN listener io error: {error}"),
            Self::Timeout => write!(formatter, "loopback LAN listener accept timed out"),
            Self::NonLoopbackPeer(addr) => {
                write!(formatter, "rejected non-loopback peer {addr}")
            }
        }
    }
}

impl std::error::Error for LoopbackLanError {}

/// A loopback-only TCP listener. Binds `127.0.0.1` with an ephemeral port on construction and
/// accepts one connection at a time within a caller-supplied deadline.
pub struct LoopbackLanListener {
    listener: TcpListener,
    options: LoopbackLanOptions,
}

impl LoopbackLanListener {
    /// Binds the loopback listener with default stream timeouts.
    pub fn bind() -> Result<Self, LoopbackLanError> {
        Self::bind_with_options(LoopbackLanOptions::default())
    }

    /// Binds the loopback listener with explicit stream timeouts. The bind address is fixed to
    /// loopback regardless of the options.
    pub fn bind_with_options(options: LoopbackLanOptions) -> Result<Self, LoopbackLanError> {
        let listener = TcpListener::bind(SocketAddr::from((LOOPBACK_BIND_IP, LOOPBACK_BIND_PORT)))
            .map_err(|error| LoopbackLanError::Bind(error.to_string()))?;
        Ok(Self { listener, options })
    }

    /// The effective bound address (loopback IP, ephemeral port).
    pub fn local_addr(&self) -> SocketAddr {
        self.listener
            .local_addr()
            .expect("bound listener has local addr")
    }

    /// Accepts a single connection within `deadline`, then switches it back to blocking mode
    /// and applies the bounded read/write timeouts. A peer whose address is not loopback is
    /// closed and rejected defensively (it should be impossible on a loopback-only bind, but
    /// the TLS stack must never receive a non-loopback stream from here).
    pub fn accept(&self, deadline: Duration) -> Result<TcpStream, LoopbackLanError> {
        let (tcp, peer) = accept_with_deadline(&self.listener, deadline)?;
        if !peer.ip().is_loopback() {
            drop(tcp);
            return Err(LoopbackLanError::NonLoopbackPeer(peer));
        }
        tcp.set_nonblocking(false)
            .map_err(|error| LoopbackLanError::Io(error.to_string()))?;
        tcp.set_read_timeout(Some(self.options.read_timeout))
            .map_err(|error| LoopbackLanError::Io(error.to_string()))?;
        tcp.set_write_timeout(Some(self.options.write_timeout))
            .map_err(|error| LoopbackLanError::Io(error.to_string()))?;
        Ok(tcp)
    }
}

/// Nonblocking accept bounded by a deadline, mirroring `loopback_pairing_proof_server`'s own
/// approach without touching that server. Returns the peer address so the caller can enforce
/// the loopback guard.
fn accept_with_deadline(
    listener: &TcpListener,
    timeout: Duration,
) -> Result<(TcpStream, SocketAddr), LoopbackLanError> {
    listener
        .set_nonblocking(true)
        .map_err(|error| LoopbackLanError::Io(error.to_string()))?;
    let deadline = Instant::now() + timeout;
    loop {
        match listener.accept() {
            Ok((tcp, peer)) => return Ok((tcp, peer)),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    return Err(LoopbackLanError::Timeout);
                }
                std::thread::sleep(ACCEPT_POLL_INTERVAL);
            }
            Err(error) => return Err(LoopbackLanError::Io(error.to_string())),
        }
    }
}
