use qrcode::{types::QrError, Color, EcLevel, QrCode};

pub struct QrModules {
    pub size: usize,
    pub dark: Vec<bool>,
}

#[derive(Debug)]
pub enum QrImageError {
    Encode(QrError),
    InvalidDimensions,
}

impl From<QrError> for QrImageError {
    fn from(error: QrError) -> Self {
        Self::Encode(error)
    }
}

pub fn qr_modules(text: &str) -> Result<QrModules, QrImageError> {
    let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::M)?;
    Ok(QrModules {
        size: code.width(),
        dark: code
            .to_colors()
            .into_iter()
            .map(|color| color == Color::Dark)
            .collect(),
    })
}

pub fn qr_rgba(
    text: &str,
    scale: usize,
    quiet_zone_modules: usize,
) -> Result<(usize, usize, Vec<u8>), QrImageError> {
    let modules = qr_modules(text)?;
    let width = modules
        .size
        .checked_add(
            quiet_zone_modules
                .checked_mul(2)
                .ok_or(QrImageError::InvalidDimensions)?,
        )
        .and_then(|width| width.checked_mul(scale))
        .ok_or(QrImageError::InvalidDimensions)?;
    let len = width
        .checked_mul(width)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or(QrImageError::InvalidDimensions)?;
    if scale == 0 || len > 64 * 1024 * 1024 {
        return Err(QrImageError::InvalidDimensions);
    }
    let mut rgba = vec![255; len];
    for y in 0..modules.size {
        for x in 0..modules.size {
            if modules.dark[y * modules.size + x] {
                for dy in 0..scale {
                    for dx in 0..scale {
                        let offset = (((y + quiet_zone_modules) * scale + dy) * width
                            + (x + quiet_zone_modules) * scale
                            + dx)
                            * 4;
                        rgba[offset..offset + 3].fill(0);
                    }
                }
            }
        }
    }
    Ok((width, width, rgba))
}
