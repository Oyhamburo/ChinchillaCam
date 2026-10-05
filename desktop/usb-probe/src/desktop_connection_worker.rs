//! Task d4a (`odd/tasks/desktop-production-app.md`, contract section 4.4): the desktop
//! connection worker. One thread owns the phone link, the TLS identity, the trusted-phone
//! store, the pairing QR issuer and the per-session pipeline. The UI talks to it only through
//! [`DesktopCommand`]s and receives typed [`DesktopEvent`]s (user-facing copy lives in the app).
//!
//! Per connection the mode is chosen from the worker's own state (section 3.3): while a
//! pairing QR is active the connection goes to the pairing step, otherwise to the trusted
//! reconnect path. The pairing step (task d4b) shows the SAS code, waits for the user's
//! confirm/reject within a deadline and, on confirm, answers the phone's HELLO
//! (`confirm_and_start`). Both paths then run the same [`DesktopSessionPipeline`] loop. While
//! idle in pairing mode the QR is reissued shortly before it expires.

use std::{
    io::{Read, Write},
    sync::{
        mpsc::{self, Receiver, RecvTimeoutError, Sender, TryRecvError},
        Arc,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant, SystemTime},
};

use crate::{
    accept_phone_pairing_connection, accept_phone_reconnect_connection, AuthenticatedPhoneSession,
    DecodedFrameCounter, DesktopMetricsSnapshot, DesktopSessionPipeline,
    DesktopSessionPipelineError, DesktopTlsIdentity, DesktopVideoSessionReceiver,
    FileTrustedPhoneStore, PairingQrIssuer, PairingQrIssuerError, PairingShortCode,
    PendingPairedPhoneSession, PhoneConnectionError, SessionEnd, SessionRuntimeConfig,
    ShortCodeError, StaticFrameKindClassifier, TrustedPhoneStoreError, TrustedPhoneSummary,
    UsbProbeError, VideoDecoder, DEFAULT_METRICS_WINDOW,
};

/// The QR is reissued once fewer than this many seconds of validity remain.
const QR_REFRESH_MARGIN_SECONDS: u64 = 5;

/// Lets the worker arm the short idle read timeout once a session has started.
pub trait IdleReadTimeoutControl {
    fn set_idle_read_timeout(
        &mut self,
        idle_read_timeout: Option<Duration>,
    ) -> Result<(), UsbProbeError>;
}

/// Why a phone link could not be polled.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PhoneLinkError {
    Usb(UsbProbeError),
}

/// The transport that yields one fresh channel per phone connection (production USB in d5).
pub trait PhoneLink: Send + 'static {
    type Stream: Read + Write + IdleReadTimeoutControl + Send + 'static;

    /// Returns a channel when a phone is available, without blocking for long.
    fn poll_phone(&mut self) -> Result<Option<Self::Stream>, PhoneLinkError>;
}

#[derive(Debug, Clone, Copy)]
pub struct DesktopWorkerConfig {
    /// Wait between link polls while no session runs.
    pub poll_interval: Duration,
    /// Budget of the reconnect TLS handshake and of the HELLO read.
    pub handshake_timeout: Duration,
    /// Idle bulk read timeout armed after HELLO/ACCEPT; must be below `session.poll_slice`.
    pub idle_read_timeout: Duration,
    pub session: SessionRuntimeConfig,
    pub metrics_window: Duration,
    /// How often a running session reports [`DesktopEvent::Metrics`].
    pub metrics_interval: Duration,
    /// How long a pending pairing waits for `ConfirmPairing`/`RejectPairing`.
    pub pairing_confirm_timeout: Duration,
    /// Budget of the phone's HELLO after a confirmed pairing.
    pub hello_timeout: Duration,
    /// Wall clock in epoch seconds used to issue and refresh the pairing QR.
    pub epoch_clock: fn() -> u64,
}

impl Default for DesktopWorkerConfig {
    fn default() -> Self {
        Self {
            poll_interval: Duration::from_millis(250),
            handshake_timeout: Duration::from_secs(10),
            idle_read_timeout: Duration::from_millis(20),
            session: SessionRuntimeConfig::default(),
            metrics_window: DEFAULT_METRICS_WINDOW,
            metrics_interval: Duration::from_secs(1),
            pairing_confirm_timeout: Duration::from_secs(120),
            hello_timeout: Duration::from_secs(120),
            epoch_clock: system_epoch_seconds,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopWorkerSpawnError {
    IdleReadTimeoutNotBelowPollSlice {
        idle_read_timeout: Duration,
        poll_slice: Duration,
    },
    Thread(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopCommand {
    StartPairing,
    CancelPairing,
    ConfirmPairing { label: String },
    RejectPairing,
    Disconnect,
    ForgetPhone { phone_id: String },
    Shutdown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopSessionEndReason {
    /// The desktop closed the session (disconnect, forget or shutdown).
    LocalClose,
    /// The phone stopped sending within the dead threshold.
    PeerDead,
    /// Any other end cause, kept verbatim.
    Failed(SessionEnd),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopConnectionFailure {
    /// The reconnect handshake or HELLO failed (for example, an unknown phone).
    Reconnect(PhoneConnectionError),
    /// The pairing proof, the confirm or the HELLO after it failed.
    Pairing(PhoneConnectionError),
    /// Nobody confirmed or rejected the pending pairing in time.
    PairingConfirmTimedOut,
    ShortCode(ShortCodeError),
    Link(PhoneLinkError),
    Pipeline(DesktopSessionPipelineError),
    PairingQr(PairingQrIssuerError),
    TrustedPhoneStore(TrustedPhoneStoreError),
}

#[derive(Debug, Clone, PartialEq)]
pub enum DesktopEvent {
    TrustedPhones(Vec<TrustedPhoneSummary>),
    WaitingForPhone,
    PairingQr {
        text: String,
        expires_at_epoch_seconds: u64,
    },
    PairingCancelled,
    /// Emitted by the pairing step (task d4b).
    ConfirmCode {
        phone_id: String,
        code: PairingShortCode,
    },
    Connecting,
    Connected {
        phone_id: String,
        label: Option<String>,
    },
    Metrics(DesktopMetricsSnapshot),
    SessionEnded(DesktopSessionEndReason),
    ConnectionFailed(DesktopConnectionFailure),
}

pub struct DesktopConnectionWorker;

impl DesktopConnectionWorker {
    /// Starts the worker thread. `decoder_factory` builds one decoder per session inside it.
    pub fn spawn<L, F, D, E>(
        config: DesktopWorkerConfig,
        link: L,
        identity: DesktopTlsIdentity,
        store: Arc<FileTrustedPhoneStore>,
        issuer: PairingQrIssuer,
        decoder_factory: F,
        events: E,
    ) -> Result<DesktopWorkerHandle, DesktopWorkerSpawnError>
    where
        L: PhoneLink,
        F: FnMut() -> D + Send + 'static,
        D: VideoDecoder + DecodedFrameCounter,
        E: Fn(DesktopEvent) + Send + 'static,
    {
        if config.idle_read_timeout >= config.session.poll_slice {
            return Err(DesktopWorkerSpawnError::IdleReadTimeoutNotBelowPollSlice {
                idle_read_timeout: config.idle_read_timeout,
                poll_slice: config.session.poll_slice,
            });
        }
        let (commands, receiver) = mpsc::channel();
        let worker = Worker {
            config,
            link,
            identity,
            store,
            issuer,
            decoder_factory,
            events,
            commands: receiver,
            pairing_expires_at: None,
        };
        let thread = thread::Builder::new()
            .name("desktop-connection-worker".to_string())
            .spawn(move || worker.run())
            .map_err(|error| DesktopWorkerSpawnError::Thread(error.to_string()))?;
        Ok(DesktopWorkerHandle {
            commands,
            thread,
            join_bound: config.handshake_timeout + config.poll_interval + Duration::from_secs(1),
        })
    }
}

pub struct DesktopWorkerHandle {
    commands: Sender<DesktopCommand>,
    thread: JoinHandle<()>,
    join_bound: Duration,
}

impl DesktopWorkerHandle {
    pub fn send(&self, command: DesktopCommand) {
        let _ = self.commands.send(command);
    }

    /// Asks the worker to stop and waits a bounded time; `false` if it did not finish in time
    /// (the thread is then detached).
    pub fn shutdown(self) -> bool {
        let _ = self.commands.send(DesktopCommand::Shutdown);
        let deadline = Instant::now() + self.join_bound;
        while !self.thread.is_finished() {
            if Instant::now() >= deadline {
                return false;
            }
            thread::sleep(Duration::from_millis(5));
        }
        self.thread.join().is_ok()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Flow {
    Continue,
    EndSession,
    Stop,
}

struct Worker<L, F, E> {
    config: DesktopWorkerConfig,
    link: L,
    identity: DesktopTlsIdentity,
    store: Arc<FileTrustedPhoneStore>,
    issuer: PairingQrIssuer,
    decoder_factory: F,
    events: E,
    commands: Receiver<DesktopCommand>,
    pairing_expires_at: Option<u64>,
}

impl<L, F, D, E> Worker<L, F, E>
where
    L: PhoneLink,
    F: FnMut() -> D,
    D: VideoDecoder + DecodedFrameCounter,
    E: Fn(DesktopEvent),
{
    fn run(mut self) {
        self.emit_trusted_phones();
        (self.events)(DesktopEvent::WaitingForPhone);
        loop {
            self.refresh_pairing_qr();
            let flow = match self.link.poll_phone() {
                Ok(Some(stream)) => self.serve(stream),
                Ok(None) => self.wait_for_command(),
                Err(error) => {
                    self.fail(DesktopConnectionFailure::Link(error));
                    self.wait_for_command()
                }
            };
            if flow == Flow::Stop {
                return;
            }
        }
    }

    fn wait_for_command(&mut self) -> Flow {
        match self.commands.recv_timeout(self.config.poll_interval) {
            Ok(command) => self.handle_command(command, None),
            Err(RecvTimeoutError::Timeout) => Flow::Continue,
            Err(RecvTimeoutError::Disconnected) => Flow::Stop,
        }
    }

    /// Every command is handled both idle and during a session; `connected` is the phone of
    /// the running session, if any.
    fn handle_command(&mut self, command: DesktopCommand, connected: Option<&str>) -> Flow {
        match command {
            DesktopCommand::StartPairing => self.start_pairing(),
            DesktopCommand::CancelPairing => {
                if self.pairing_expires_at.take().is_some() {
                    (self.events)(DesktopEvent::PairingCancelled);
                }
            }
            // Only meaningful while a pairing candidate is pending, which happens inside the
            // pairing step (d4b); outside of it there is nothing to confirm or reject.
            DesktopCommand::ConfirmPairing { .. } | DesktopCommand::RejectPairing => {}
            DesktopCommand::Disconnect => {
                if connected.is_some() {
                    return Flow::EndSession;
                }
            }
            DesktopCommand::ForgetPhone { phone_id } => {
                if let Err(error) = self.store.forget(&phone_id) {
                    self.fail(DesktopConnectionFailure::TrustedPhoneStore(error));
                }
                self.emit_trusted_phones();
                if connected == Some(phone_id.as_str()) {
                    return Flow::EndSession;
                }
            }
            DesktopCommand::Shutdown => return Flow::Stop,
        }
        Flow::Continue
    }

    fn start_pairing(&mut self) {
        match self.issuer.issue_at((self.config.epoch_clock)()) {
            Ok(issued) => {
                let expires_at_epoch_seconds = issued.payload().expires_at_epoch_seconds();
                self.pairing_expires_at = Some(expires_at_epoch_seconds);
                (self.events)(DesktopEvent::PairingQr {
                    text: String::from_utf8_lossy(issued.qr_wire()).into_owned(),
                    expires_at_epoch_seconds,
                });
            }
            Err(error) => {
                self.pairing_expires_at = None;
                self.fail(DesktopConnectionFailure::PairingQr(error));
            }
        }
    }

    fn refresh_pairing_qr(&mut self) {
        if let Some(expires_at) = self.pairing_expires_at {
            if (self.config.epoch_clock)() + QR_REFRESH_MARGIN_SECONDS >= expires_at {
                self.start_pairing();
            }
        }
    }

    fn serve(&mut self, stream: L::Stream) -> Flow {
        let flow = if self.pairing_expires_at.is_some() {
            self.pairing_session(stream)
        } else {
            self.reconnect_session(stream)
        };
        if flow != Flow::Stop {
            (self.events)(DesktopEvent::WaitingForPhone);
        }
        flow
    }

    fn reconnect_session(&mut self, stream: L::Stream) -> Flow {
        (self.events)(DesktopEvent::Connecting);
        let lookup = self.store.clone();
        let timeout = self.config.handshake_timeout;
        match accept_phone_reconnect_connection(stream, &self.identity, lookup, timeout) {
            Ok(session) => self.run_session(session),
            Err(error) => {
                self.fail(DesktopConnectionFailure::Reconnect(error));
                Flow::Continue
            }
        }
    }

    /// Pairing proof, SAS code, then the user's decision within `pairing_confirm_timeout`.
    /// A failed proof keeps pairing mode (the QR stays valid); any decision leaves it.
    fn pairing_session(&mut self, stream: L::Stream) -> Flow {
        let timeout = self.config.handshake_timeout;
        let pending = match accept_phone_pairing_connection(
            stream,
            &self.identity,
            &mut self.issuer,
            timeout,
        ) {
            Ok(pending) => pending,
            Err(error) => {
                self.fail(DesktopConnectionFailure::Pairing(error));
                return Flow::Continue;
            }
        };
        match pending.short_code(&self.identity) {
            Ok(code) => (self.events)(DesktopEvent::ConfirmCode {
                phone_id: pending.candidate.phone_id.clone(),
                code,
            }),
            Err(error) => {
                pending.reject();
                self.fail(DesktopConnectionFailure::ShortCode(error));
                return Flow::Continue;
            }
        }
        let deadline = Instant::now() + self.config.pairing_confirm_timeout;
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            let command = match self.commands.recv_timeout(left) {
                Ok(command) => command,
                Err(RecvTimeoutError::Timeout) => {
                    pending.reject();
                    self.pairing_expires_at = None;
                    self.fail(DesktopConnectionFailure::PairingConfirmTimedOut);
                    return Flow::Continue;
                }
                Err(RecvTimeoutError::Disconnected) => {
                    pending.reject();
                    return Flow::Stop;
                }
            };
            match command {
                DesktopCommand::ConfirmPairing { label } => {
                    return self.confirm_pairing(pending, &label);
                }
                DesktopCommand::RejectPairing | DesktopCommand::CancelPairing => {
                    pending.reject();
                    self.pairing_expires_at = None;
                    (self.events)(DesktopEvent::PairingCancelled);
                    return Flow::Continue;
                }
                DesktopCommand::Shutdown => {
                    pending.reject();
                    return Flow::Stop;
                }
                command @ DesktopCommand::ForgetPhone { .. } => {
                    self.handle_command(command, None);
                }
                // No session to disconnect, and the pending pairing already used its QR.
                DesktopCommand::Disconnect | DesktopCommand::StartPairing => {}
            }
        }
    }

    /// `confirm_and_start`; if the HELLO fails after the store trusted a new phone, the
    /// trust is rolled back with `forget`, symmetric with the phone's own rollback.
    fn confirm_pairing(
        &mut self,
        pending: PendingPairedPhoneSession<L::Stream>,
        label: &str,
    ) -> Flow {
        self.pairing_expires_at = None;
        let phone_id = pending.candidate.phone_id.clone();
        let was_trusted = matches!(self.store.trusted_identity(&phone_id), Ok(Some(_)));
        (self.events)(DesktopEvent::Connecting);
        let timeout = self.config.hello_timeout;
        match pending.confirm_and_start(label, &self.store, &self.identity, timeout) {
            Ok(session) => {
                self.emit_trusted_phones();
                self.run_session(session)
            }
            Err(error) => {
                let confirmed = !matches!(error, PhoneConnectionError::PairingConfirm(_));
                self.fail(DesktopConnectionFailure::Pairing(error));
                if confirmed && !was_trusted {
                    if let Err(error) = self.store.forget(&phone_id) {
                        self.fail(DesktopConnectionFailure::TrustedPhoneStore(error));
                    }
                    self.emit_trusted_phones();
                }
                Flow::Continue
            }
        }
    }

    /// Shared by both paths: idle read timeout, `Connected`, then the session pipeline until
    /// it ends or a command closes it. Never returns [`Flow::EndSession`].
    fn run_session(&mut self, mut session: AuthenticatedPhoneSession<L::Stream>) -> Flow {
        let idle = Some(self.config.idle_read_timeout);
        if let Err(error) = session.tls.sock.set_idle_read_timeout(idle) {
            self.fail(DesktopConnectionFailure::Link(PhoneLinkError::Usb(error)));
            return Flow::Continue;
        }
        let phone_id = session.phone_id.clone();
        let label = self.store.list().ok().and_then(|phones| {
            phones
                .into_iter()
                .find(|phone| phone.phone_id == phone_id)
                .map(|phone| phone.label)
        });
        (self.events)(DesktopEvent::Connected {
            phone_id: phone_id.clone(),
            label,
        });

        let start = Instant::now();
        let mut pipeline = match DesktopSessionPipeline::new(
            session,
            DesktopVideoSessionReceiver::new(StaticFrameKindClassifier::unknown()),
            (self.decoder_factory)(),
            self.config.session,
            self.config.metrics_window,
            start,
        ) {
            Ok(pipeline) => pipeline,
            Err(error) => {
                self.fail(DesktopConnectionFailure::Pipeline(error));
                return Flow::Continue;
            }
        };
        let mut next_metrics = start + self.config.metrics_interval;
        loop {
            let flow = self.drain_session_commands(&phone_id);
            if flow != Flow::Continue {
                let (_end, snapshot) = pipeline.shutdown();
                (self.events)(DesktopEvent::Metrics(snapshot));
                (self.events)(DesktopEvent::SessionEnded(
                    DesktopSessionEndReason::LocalClose,
                ));
                return if flow == Flow::Stop {
                    Flow::Stop
                } else {
                    Flow::Continue
                };
            }
            let now = Instant::now();
            if let Err(end) = pipeline.step(now) {
                (self.events)(DesktopEvent::Metrics(pipeline.metrics(now)));
                (self.events)(DesktopEvent::SessionEnded(end_reason(end)));
                return Flow::Continue;
            }
            if now >= next_metrics {
                (self.events)(DesktopEvent::Metrics(pipeline.metrics(now)));
                next_metrics = now + self.config.metrics_interval;
            }
        }
    }

    fn drain_session_commands(&mut self, phone_id: &str) -> Flow {
        loop {
            let flow = match self.commands.try_recv() {
                Ok(command) => self.handle_command(command, Some(phone_id)),
                Err(TryRecvError::Empty) => return Flow::Continue,
                Err(TryRecvError::Disconnected) => Flow::Stop,
            };
            if flow != Flow::Continue {
                return flow;
            }
        }
    }

    fn emit_trusted_phones(&self) {
        match self.store.list() {
            Ok(phones) => (self.events)(DesktopEvent::TrustedPhones(phones)),
            Err(error) => self.fail(DesktopConnectionFailure::TrustedPhoneStore(error)),
        }
    }

    fn fail(&self, failure: DesktopConnectionFailure) {
        (self.events)(DesktopEvent::ConnectionFailed(failure));
    }
}

fn end_reason(end: SessionEnd) -> DesktopSessionEndReason {
    match end {
        SessionEnd::LocalClose => DesktopSessionEndReason::LocalClose,
        SessionEnd::PeerDead => DesktopSessionEndReason::PeerDead,
        other => DesktopSessionEndReason::Failed(other),
    }
}

fn system_epoch_seconds() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or(0)
}
