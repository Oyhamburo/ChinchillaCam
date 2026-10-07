use crate::messages::{connection_failure_notice, session_end_notice, UserNotice};
use crate::video_watchdog::{VideoWarning, VideoWatchdog};
use std::time::Instant;
use usb_probe::{DesktopEvent, DesktopMetricsSnapshot, TrustedPhoneSummary};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppAction {
    StartPairing,
    CancelPairing,
    ConfirmPairing,
    RejectPairing,
    Disconnect,
}

impl AppAction {
    pub fn label(self) -> &'static str {
        match self {
            Self::StartPairing => "Vincular un teléfono",
            Self::CancelPairing => "Cancelar",
            Self::ConfirmPairing => "Coincide",
            Self::RejectPairing => "No coincide",
            Self::Disconnect => "Desconectar",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhoneRow {
    pub phone_id: String,
    pub label: String,
    pub revoked: bool,
    pub can_forget: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetricsView {
    pub fps: String,
    pub decoded_fps: String,
    pub received: String,
    pub dropped: String,
}

impl From<DesktopMetricsSnapshot> for MetricsView {
    fn from(snapshot: DesktopMetricsSnapshot) -> Self {
        fn rate(fps: Option<f64>) -> String {
            fps.filter(|fps| fps.is_finite())
                .map_or_else(|| "—".into(), |fps| format!("{fps:.1}"))
        }
        Self {
            fps: rate(snapshot.arrival_fps),
            decoded_fps: rate(snapshot.decoded_fps),
            received: snapshot.total_chunks.to_string(),
            dropped: snapshot.dropped_chunks.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppView {
    pub status: String,
    pub notice: Option<String>,
    pub hint: Option<String>,
    pub qr_text: Option<String>,
    pub qr_seconds_left: Option<u64>,
    pub confirm_code: Option<String>,
    pub actions: Vec<AppAction>,
    pub phones: Vec<PhoneRow>,
    pub empty_phones_text: String,
    pub metrics: Option<MetricsView>,
}

#[derive(Debug, Clone)]
enum Phase {
    Waiting,
    PairingQr { text: String, expires_at: u64 },
    ConfirmCode { code: String },
    Connecting,
    Connected { label: Option<String> },
}

#[derive(Debug, Clone)]
pub struct AppState {
    phase: Phase,
    notice: Option<UserNotice>,
    video_watchdog: VideoWatchdog,
    video_warning: Option<VideoWarning>,
    presenter_failed: bool,
    phones: Vec<TrustedPhoneSummary>,
    metrics: Option<DesktopMetricsSnapshot>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            phase: Phase::Waiting,
            notice: None,
            video_watchdog: VideoWatchdog::default(),
            video_warning: None,
            presenter_failed: false,
            phones: Vec::new(),
            metrics: None,
        }
    }
}

impl AppState {
    pub fn confirm_label(&self) -> &'static str {
        "Teléfono"
    }

    pub fn observe_video(&mut self, now: Instant, frames_published: u64, presenter_failed: bool) {
        let connected = matches!(self.phase, Phase::Connected { .. });
        self.video_warning = self
            .video_watchdog
            .observe(now, connected, frames_published);
        self.presenter_failed = connected && presenter_failed;
    }

    pub fn apply(&mut self, event: DesktopEvent) {
        match event {
            DesktopEvent::TrustedPhones(phones) => self.phones = phones,
            DesktopEvent::WaitingForPhone => {
                self.phase = Phase::Waiting;
                self.metrics = None;
            }
            DesktopEvent::PairingQr {
                text,
                expires_at_epoch_seconds,
            } => {
                self.phase = Phase::PairingQr {
                    text,
                    expires_at: expires_at_epoch_seconds,
                };
                self.notice = None;
            }
            DesktopEvent::PairingCancelled => {
                self.phase = Phase::Waiting;
                self.notice = None;
            }
            DesktopEvent::ConfirmCode { code, .. } => {
                self.phase = Phase::ConfirmCode {
                    code: code.display(),
                };
                self.notice = None;
            }
            DesktopEvent::Connecting => {
                self.phase = Phase::Connecting;
                self.notice = None;
            }
            DesktopEvent::Connected { label, .. } => {
                self.video_watchdog = VideoWatchdog::default();
                self.video_warning = None;
                self.presenter_failed = false;
                self.phase = Phase::Connected { label };
                self.metrics = None;
                self.notice = None;
            }
            DesktopEvent::Metrics(metrics) => self.metrics = Some(metrics),
            DesktopEvent::SessionEnded(reason) => {
                self.phase = Phase::Waiting;
                self.metrics = None;
                self.notice = Some(session_end_notice(&reason));
            }
            DesktopEvent::ConnectionFailed(failure) => {
                // The worker stays in pairing mode after link errors and failed pairing
                // attempts, so a shown QR stays usable; pending flows end.
                if !matches!(self.phase, Phase::PairingQr { .. }) {
                    self.phase = Phase::Waiting;
                }
                self.metrics = None;
                let notice = connection_failure_notice(&failure);
                if self.notice != Some(notice) {
                    self.notice = Some(notice);
                }
            }
        }
    }

    pub fn view(&self, now_epoch_seconds: u64) -> AppView {
        let (status, qr_text, qr_seconds_left, confirm_code, actions) = match &self.phase {
            Phase::Waiting => (
                "Esperando el teléfono por USB.".into(),
                None,
                None,
                None,
                vec![AppAction::StartPairing],
            ),
            Phase::PairingQr { text, expires_at } => (
                "Escaneá este código con ChinchillaCam en el teléfono.".into(),
                Some(text.clone()),
                Some(expires_at.saturating_sub(now_epoch_seconds)),
                None,
                vec![AppAction::CancelPairing],
            ),
            Phase::ConfirmCode { code } => (
                "¿El código coincide con el del teléfono?".into(),
                None,
                None,
                Some(code.clone()),
                vec![AppAction::ConfirmPairing, AppAction::RejectPairing],
            ),
            Phase::Connecting => ("Conectando…".into(), None, None, None, vec![]),
            Phase::Connected { label } => (
                format!(
                    "Conectado a «{}».",
                    label
                        .as_deref()
                        .filter(|label| !label.trim().is_empty())
                        .unwrap_or("el teléfono")
                ),
                None,
                None,
                None,
                vec![AppAction::Disconnect],
            ),
        };
        let video_notice = if matches!(self.phase, Phase::Connected { .. }) {
            if self.presenter_failed {
                Some(UserNotice {
                    message: "No se pudo mostrar el video.",
                    hint: Some("Cerrá y volvé a abrir ChinchillaCam."),
                })
            } else {
                self.video_warning.map(|warning| match warning {
                    VideoWarning::NoFrames => UserNotice {
                        message: "No llega video del teléfono.",
                        hint: Some("Revisá que la cámara esté transmitiendo en el teléfono (desbloqueado y con ChinchillaCam abierta)."),
                    },
                })
            }
        } else {
            None
        };
        let notice = self.notice.or(video_notice);
        AppView {
            status,
            notice: notice.map(|notice| notice.message.into()),
            hint: notice.and_then(|notice| notice.hint.map(Into::into)),
            qr_text,
            qr_seconds_left,
            confirm_code,
            actions,
            phones: self
                .phones
                .iter()
                .map(|phone| PhoneRow {
                    phone_id: phone.phone_id.clone(),
                    label: phone.label.clone(),
                    revoked: phone.revoked,
                    can_forget: true,
                })
                .collect(),
            empty_phones_text: "Todavía no hay teléfonos vinculados.".into(),
            metrics: if matches!(self.phase, Phase::Connected { .. }) {
                self.metrics.map(Into::into)
            } else {
                None
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn connected_failure_notice_beats_both_video_notices() {
        let mut state = AppState::default();
        state.apply(DesktopEvent::Connected {
            phone_id: "id".into(),
            label: None,
        });
        state.notice = Some(UserNotice {
            message: "Falla específica.",
            hint: Some("Acción específica."),
        });
        let now = Instant::now();
        state.observe_video(now, 0, false);
        state.observe_video(now + Duration::from_secs(5), 0, true);
        assert_eq!(state.view(0).notice.as_deref(), Some("Falla específica."));
        assert_eq!(state.view(0).hint.as_deref(), Some("Acción específica."));
    }
}
