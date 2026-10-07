use chinchillacam_app::window::ChinchillaCamWindow;
use eframe::egui;

fn main() -> eframe::Result {
    eframe::run_native(
        "ChinchillaCam",
        eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default()
                .with_title("ChinchillaCam")
                .with_inner_size([480.0, 720.0]),
            ..Default::default()
        },
        Box::new(|cc| Ok(Box::new(ChinchillaCamWindow::new(cc)))),
    )
}
