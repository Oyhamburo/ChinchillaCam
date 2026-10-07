use crate::{
    bootstrap::start_production_worker,
    commands::{
        choose_automatic, choose_camera, choose_fps, choose_resolution, command_for, forget_command,
    },
    paths::AppPaths,
    qr_image::qr_rgba,
    video_output::LatestVideoFrame,
    video_view::{fit_size, initial_size},
    view_model::{AppState, QualityView},
};
use eframe::egui;
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver},
        Arc, LazyLock, Mutex,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use usb_probe::{DesktopCommand, DesktopEvent, DesktopWorkerHandle};

static VIDEO_VIEWPORT_ID: LazyLock<egui::ViewportId> =
    LazyLock::new(|| egui::ViewportId::from_hash_of("chinchillacam-video"));

struct VideoViewportState {
    slot: LatestVideoFrame,
    texture: Mutex<Option<(u64, egui::TextureHandle)>>,
    initial_size: Mutex<Option<egui::Vec2>>,
    close_requested: AtomicBool,
}

impl VideoViewportState {
    fn remember_initial_size(&self, width: u32, height: u32) {
        let mut size = self
            .initial_size
            .lock()
            .unwrap_or_else(|err| err.into_inner());
        size.get_or_insert_with(|| {
            let (w, h) = initial_size(width, height);
            egui::vec2(w, h)
        });
    }

    fn render(&self, ui: &mut egui::Ui) {
        if ui.input(|input| input.viewport().close_requested()) {
            self.close_requested.store(true, Ordering::Release);
            // The root may be minimized and skip its UI pass; close this viewport directly.
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
            ui.ctx().request_repaint_of(egui::ViewportId::ROOT);
        }
        let (sequence, frame) = self.slot.snapshot();
        let texture = {
            let mut cached = self.texture.lock().unwrap_or_else(|err| err.into_inner());
            if let Some(frame) = &frame {
                self.remember_initial_size(frame.width, frame.height);
                if cached.as_ref().map(|(sequence, _)| *sequence) != Some(sequence) {
                    let image = egui::ColorImage::from_rgba_unmultiplied(
                        [frame.width as usize, frame.height as usize],
                        &frame.pixels,
                    );
                    if let Some((old_sequence, texture)) = cached.as_mut() {
                        texture.set(image, egui::TextureOptions::LINEAR);
                        *old_sequence = sequence;
                    } else {
                        *cached = Some((
                            sequence,
                            ui.ctx().load_texture(
                                "chinchillacam-video",
                                image,
                                egui::TextureOptions::LINEAR,
                            ),
                        ));
                    }
                }
            } else {
                *cached = None;
            }
            cached.as_ref().map(|(_, texture)| texture.clone())
        };
        egui::CentralPanel::default()
            .frame(
                egui::Frame::new()
                    .fill(egui::Color32::BLACK)
                    .inner_margin(0),
            )
            .show(ui, |ui| {
                let available = ui.available_size();
                ui.vertical_centered(|ui| {
                    if let (Some(frame), Some(texture)) = (&frame, &texture) {
                        let (w, h) = fit_size(
                            frame.width as f32,
                            frame.height as f32,
                            available.x,
                            available.y,
                        );
                        ui.add_space(((available.y - h) / 2.0).max(0.0));
                        ui.add(egui::Image::new(texture).fit_to_exact_size(egui::vec2(w, h)));
                    } else {
                        ui.add_space((available.y / 2.0 - 12.0).max(0.0));
                        ui.label(
                            egui::RichText::new("Esperando video…").color(egui::Color32::WHITE),
                        );
                    }
                });
            });
    }
}

pub struct ChinchillaCamWindow {
    state: AppState,
    events: Receiver<DesktopEvent>,
    handle: Option<DesktopWorkerHandle>,
    qr_texture: Option<(String, egui::TextureHandle)>,
    video: Arc<VideoViewportState>,
    show_video: bool,
    pending_forget: Option<String>,
    bootstrap_message: Option<String>,
}

impl ChinchillaCamWindow {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let (sender, events) = mpsc::channel();
        let ctx = cc.egui_ctx.clone();
        let video = Arc::new(VideoViewportState {
            slot: LatestVideoFrame::default(),
            texture: Mutex::new(None),
            initial_size: Mutex::new(None),
            close_requested: AtomicBool::new(false),
        });
        let result = AppPaths::macos_default()
            .map_err(|_| "No se pudo encontrar tu carpeta de usuario.".to_owned())
            .and_then(|paths| {
                let video_ctx = ctx.clone();
                start_production_worker(
                    &paths,
                    video.slot.clone(),
                    move || {
                        video_ctx.request_repaint_of(*VIDEO_VIEWPORT_ID);
                        video_ctx.request_repaint_of(egui::ViewportId::ROOT);
                    },
                    move |event| {
                        let _ = sender.send(event);
                        ctx.request_repaint();
                    },
                )
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
            video,
            show_video: false,
            pending_forget: None,
            bootstrap_message,
        }
    }

    fn show_video_viewport(&self, ctx: &egui::Context) {
        if let Some(frame) = self.video.slot.snapshot().1 {
            self.video.remember_initial_size(frame.width, frame.height);
        }
        let size = self
            .video
            .initial_size
            .lock()
            .unwrap_or_else(|err| err.into_inner());
        let builder = egui::ViewportBuilder::default()
            .with_title("ChinchillaCam — Video")
            .with_inner_size(size.unwrap_or(egui::vec2(640.0, 360.0)))
            .with_min_inner_size(egui::vec2(320.0, 180.0));
        drop(size);
        let video = Arc::clone(&self.video);
        ctx.show_viewport_deferred(*VIDEO_VIEWPORT_ID, builder, move |ui, _class| {
            video.render(ui)
        });
    }

    fn hide_closed_video(&mut self) {
        if self.video.close_requested.swap(false, Ordering::AcqRel) {
            self.show_video = false;
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

    fn render_quality(&mut self, ui: &mut egui::Ui, quality: &QualityView) {
        let Some(state) = self.state.quality_state() else {
            return;
        };
        ui.separator();
        ui.heading("Cámara y calidad");
        ui.label(&quality.summary);
        if let Some(error) = &quality.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        if quality.pending {
            ui.label("Aplicando…");
        }
        let mut command: Option<DesktopCommand> = None;
        ui.horizontal_wrapped(|ui| {
            ui.label("Cámara:");
            for camera in &quality.cameras {
                if ui
                    .selectable_label(camera.selected, &camera.label)
                    .clicked()
                {
                    command = Some(choose_camera(state, camera.selection.clone()));
                }
            }
        });
        ui.horizontal_wrapped(|ui| {
            ui.label("Calidad:");
            if ui
                .selectable_label(quality.automatic, "Automático")
                .clicked()
            {
                command = Some(choose_automatic(state));
            }
        });
        ui.horizontal_wrapped(|ui| {
            ui.label("Resolución:");
            for resolution in &quality.resolutions {
                let response = ui.add_enabled(
                    resolution.enabled,
                    egui::Button::selectable(resolution.applied, &resolution.label),
                );
                if !resolution.enabled {
                    if let Some(reason) = &resolution.reason {
                        response.clone().on_disabled_hover_text(reason);
                    }
                }
                if response.clicked() {
                    command = Some(choose_resolution(
                        state,
                        resolution.width,
                        resolution.height,
                    ));
                }
            }
        });
        ui.horizontal_wrapped(|ui| {
            ui.label("FPS:");
            for fps in &quality.frame_rates {
                let response = ui.add_enabled(
                    fps.enabled,
                    egui::Button::selectable(fps.applied, &fps.label),
                );
                if !fps.enabled {
                    if let Some(reason) = &fps.reason {
                        response.clone().on_disabled_hover_text(reason);
                    }
                }
                if response.clicked() {
                    command = Some(choose_fps(state, fps.fps));
                }
            }
        });
        if let (Some(command), Some(handle)) = (command, &self.handle) {
            handle.send(command);
            self.state.mark_quality_pending();
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
        self.hide_closed_video();
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.drain_events();
        self.hide_closed_video();
        self.state.observe_video(
            Instant::now(),
            self.video.slot.frames_published(),
            self.video.slot.presenter_failed(),
        );
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let view = self.state.view(now);
        ui.heading("ChinchillaCam");
        ui.separator();
        ui.label(&view.status);
        if let Some(quality) = &view.quality {
            self.render_quality(ui, quality);
        }
        if ui
            .button(if self.show_video {
                "Ocultar video"
            } else {
                "Mostrar video para OBS"
            })
            .clicked()
        {
            self.show_video = !self.show_video;
        }
        if self.show_video {
            self.show_video_viewport(ui.ctx());
        }
        if let Some(message) = self.bootstrap_message.as_ref().or(view.notice.as_ref()) {
            ui.colored_label(ui.visuals().error_fg_color, message);
            if self.bootstrap_message.is_none() {
                if let Some(hint) = &view.hint {
                    ui.label(hint);
                }
            }
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
