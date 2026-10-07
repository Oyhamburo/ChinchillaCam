/// Size of a frame fitted into the available region without distortion.
pub fn fit_size(frame_w: f32, frame_h: f32, avail_w: f32, avail_h: f32) -> (f32, f32) {
    if frame_w <= 0.0 || frame_h <= 0.0 || avail_w <= 0.0 || avail_h <= 0.0 {
        return (0.0, 0.0);
    }
    let scale = (avail_w / frame_w).min(avail_h / frame_h);
    (frame_w * scale, frame_h * scale)
}

/// Initial window size: preserve the video aspect ratio within a 1280×720 bound.
pub fn initial_size(width: u32, height: u32) -> (f32, f32) {
    let (w, h) = (width as f32, height as f32);
    if w == 0.0 || h == 0.0 {
        return (320.0, 180.0);
    }
    // Keep aspect ratio. For extreme aspect ratios the maximum bound wins over the minimum.
    let scale = (320.0 / w)
        .max(180.0 / h)
        .max(1.0)
        .min((1280.0 / w).min(720.0 / h));
    (w * scale, h * scale)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fit_preserves_aspect_and_centers_within_bounds() {
        assert_eq!(fit_size(1920.0, 1080.0, 800.0, 600.0), (800.0, 450.0));
        assert_eq!(fit_size(400.0, 800.0, 800.0, 400.0), (200.0, 400.0));
        assert_eq!(fit_size(320.0, 180.0, 1000.0, 1000.0), (1000.0, 562.5));
        assert_eq!(fit_size(0.0, 180.0, 1000.0, 1000.0), (0.0, 0.0));
    }

    #[test]
    fn initial_size_clamps_large_frames_and_small_frames() {
        assert_eq!(initial_size(1920, 1080), (1280.0, 720.0));
        assert_eq!(initial_size(640, 480), (640.0, 480.0));
        assert_eq!(initial_size(160, 90), (320.0, 180.0));
        assert_eq!(initial_size(0, 0), (320.0, 180.0));
    }
}
