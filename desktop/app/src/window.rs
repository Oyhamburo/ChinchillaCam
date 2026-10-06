use crate::{
    bootstrap::start_production_worker,
    commands::{command_for, forget_command},
    paths::AppPaths,
    qr_image::qr_rgba,
    view_model::AppState,
};
use eframe::egui;
use std::{
    sync::mpsc::{self, Receiver},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use usb_probe::{DesktopEvent, DesktopWorkerHandle};

pub struct ChinchillaCamWindow {
    state: AppState,
    events: Receiver<DesktopEvent>,
    handle: Option<DesktopWorkerHandle>,
    qr_texture: Option<(String, egui::TextureHandle)>,
    pending_forget: Option<String>,
    bootstrap_message: Option<String>,
}

impl ChinchillaCamWindow {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let (sender, events) = mpsc::channel();
        let ctx = cc.egui_ctx.clone();
        let result = AppPaths::macos_default()
            .map_err(|_| "No se pudo encontrar tu carpeta de usuario.".to_owned())
            .and_then(|paths| {
                start_production_worker(&paths, move |event| {
                    let _ = sender.send(event);
                    ctx.request_repaint();
                })
                .map_err(|error| error.user_message().to_owned())
            });
        let (handle, bootstrap_message) = match result {
            Ok(handle) => (Some(handle), None),
            Err(message) => (None, Some(message)),
        };
        Self {
            state: AppState::default(),
            events,
            handle,
            qr_texture: None,
            pending_forget: None,
            bootstrap_message,
        }
    }

    fn drain_events(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            if matches!(event, DesktopEvent::TrustedPhones(_)) {
                self.pending_forget = None;
            }
            self.state.apply(event);
        }
    }

    fn stop_worker(&mut self) {
        if let Some(handle) = self.handle.take() {
            let _ = handle.shutdown();
        }
    }
}

impl eframe::App for ChinchillaCamWindow {
    fn logic(&mut self, _ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain_events();
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.drain_events();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let view = self.state.view(now);
        ui.heading("ChinchillaCam");
        ui.separator();
        ui.label(&view.status);
        if let Some(message) = self.bootstrap_message.as_ref().or(view.notice.as_ref()) {
            ui.colored_label(ui.visuals().error_fg_color, message);
        }
        if let Some(text) = &view.qr_text {
            if self
                .qr_texture
                .as_ref()
                .map(|(cached, _)| cached != text)
                .unwrap_or(true)
            {
                match qr_rgba(text, 6, 4) {
                    Ok((width, height, pixels)) => {
                        let image =
                            egui::ColorImage::from_rgba_unmultiplied([width, height], &pixels);
                        self.qr_texture = Some((
                            text.clone(),
                            ui.ctx().load_texture(
                                "pairing-qr",
                                image,
                                egui::TextureOptions::NEAREST,
                            ),
                        ));
                    }
                    Err(_) => {
                        self.qr_texture = None;
                        ui.label("No se pudo dibujar el QR.");
                    }
                }
            }
            if let Some((_, texture)) = &self.qr_texture {
                ui.add(egui::Image::new(texture).max_width(ui.available_width()));
            }
            ui.label(format!("Vence en {} s", view.qr_seconds_left.unwrap_or(0)));
            ui.add(egui::Label::new(egui::RichText::new(text).monospace()).selectable(true));
        } else {
            self.qr_texture = None;
        }
        if let Some(code) = &view.confirm_code {
            ui.heading(code);
        }
        for action in view.actions {
            if ui.button(action.label()).clicked() {
                if let Some(handle) = &self.handle {
                    handle.send(command_for(action, &self.state));
                }
            }
        }
        ui.separator();
        ui.heading("Teléfonos confiables");
        if view.phones.is_empty() {
            ui.label(view.empty_phones_text);
        }
        for phone in view.phones {
            ui.horizontal(|ui| {
                ui.label(if phone.revoked {
                    format!("{} (revocado)", phone.label)
                } else {
                    phone.label.clone()
                });
                if self.pending_forget.as_deref() == Some(&phone.phone_id) {
                    if ui.button("¿Olvidar?").clicked() {
                        if let Some(handle) = &self.handle {
                            handle.send(forget_command(&phone.phone_id));
                        }
                        self.pending_forget = None;
                    }
                    if ui.button("Cancelar").clicked() {
                        self.pending_forget = None;
                    }
                } else if phone.can_forget && ui.button("Olvidar").clicked() {
                    self.pending_forget = Some(phone.phone_id.clone());
                }
            });
        }
        if let Some(metrics) = view.metrics {
            ui.separator();
            egui::Grid::new("video-metrics").show(ui, |ui| {
                for (label, value) in [
                    ("Recibidos", metrics.received),
                    ("Descartados", metrics.dropped),
                    ("FPS de llegada", metrics.fps),
                    ("FPS decodificados", metrics.decoded_fps),
                ] {
                    ui.label(label);
                    ui.label(value);
                    ui.end_row();
                }
            });
        }
        ui.ctx().request_repaint_after(Duration::from_millis(250));
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.stop_worker();
    }
}

impl Drop for ChinchillaCamWindow {
    fn drop(&mut self) {
        self.stop_worker();
    }
}
