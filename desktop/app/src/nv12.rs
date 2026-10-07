#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum YuvMatrix {
    Bt601,
    Bt709,
}

impl YuvMatrix {
    pub fn for_height(height: u32) -> Self {
        if height >= 720 {
            Self::Bt709
        } else {
            Self::Bt601
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Nv12Error {
    EmptyDimensions,
    LengthMismatch { expected: usize, actual: usize },
    TooLarge,
}

impl std::fmt::Display for Nv12Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for Nv12Error {}

pub fn nv12_to_rgba(width: u32, height: u32, data: &[u8]) -> Result<Vec<u8>, Nv12Error> {
    nv12_to_rgba_with(width, height, data, YuvMatrix::for_height(height))
}

pub fn nv12_to_rgba_with(
    width: u32,
    height: u32,
    data: &[u8],
    matrix: YuvMatrix,
) -> Result<Vec<u8>, Nv12Error> {
    if width == 0 || height == 0 {
        return Err(Nv12Error::EmptyDimensions);
    }
    let w = usize::try_from(width).map_err(|_| Nv12Error::TooLarge)?;
    let h = usize::try_from(height).map_err(|_| Nv12Error::TooLarge)?;
    let luma_len = w.checked_mul(h).ok_or(Nv12Error::TooLarge)?;
    let cw = w / 2 + w % 2;
    let ch = h / 2 + h % 2;
    let chroma_stride = cw.checked_mul(2).ok_or(Nv12Error::TooLarge)?;
    let expected = chroma_stride
        .checked_mul(ch)
        .and_then(|n| luma_len.checked_add(n))
        .ok_or(Nv12Error::TooLarge)?;
    let rgba_len = luma_len.checked_mul(4).ok_or(Nv12Error::TooLarge)?;
    if data.len() != expected {
        return Err(Nv12Error::LengthMismatch {
            expected,
            actual: data.len(),
        });
    }

    // Video-range coefficients scaled by 2^14, rounded to the nearest integer.
    let (r_cr, g_cb, g_cr, b_cb) = match matrix {
        YuvMatrix::Bt601 => (26149, -6419, -13320, 33050),
        YuvMatrix::Bt709 => (29372, -3494, -8731, 34610),
    };
    let mut rgba = Vec::new();
    rgba.try_reserve_exact(rgba_len)
        .map_err(|_| Nv12Error::TooLarge)?;
    for y in 0..h {
        for x in 0..w {
            let luma = (i32::from(data[y * w + x]) - 16) * 19077;
            let chroma = luma_len + (y / 2) * chroma_stride + (x / 2) * 2;
            let cb = i32::from(data[chroma]) - 128;
            let cr = i32::from(data[chroma + 1]) - 128;
            for value in [
                luma + r_cr * cr,
                luma + g_cb * cb + g_cr * cr,
                luma + b_cb * cb,
            ] {
                rgba.push(((value + 8192) >> 14).clamp(0, 255) as u8);
            }
            rgba.push(255);
        }
    }
    Ok(rgba)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nv12_converts_reference_colors() {
        for (matrix, red) in [
            (YuvMatrix::Bt601, [254_u8, 0, 0, 255]),
            (YuvMatrix::Bt709, [255_u8, 24, 0, 255]),
        ] {
            for (input, expected) in [
                ([16, 128, 128], [0, 0, 0, 255]),
                ([235, 128, 128], [255, 255, 255, 255]),
                ([126, 128, 128], [128, 128, 128, 255]),
                ([81, 90, 240], red),
            ] {
                let actual = nv12_to_rgba_with(1, 1, &input, matrix).unwrap();
                for (got, want) in actual.iter().zip(expected) {
                    assert!(
                        (i16::from(*got) - i16::from(want)).abs() <= 2,
                        "{matrix:?} {input:?}: got {actual:?}, expected {expected:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn odd_dimensions_use_the_matching_chroma_pair() {
        // 3x3: top-left 2x2 uses pair 0, right edge pair 1, bottom row pairs 2/3.
        let mut data = vec![81; 9];
        data.extend_from_slice(&[90, 240, 128, 128, 128, 128, 240, 90]);
        let rgba = nv12_to_rgba_with(3, 3, &data, YuvMatrix::Bt601).unwrap();
        let (pixels, remainder) = rgba.as_chunks::<4>();
        assert!(remainder.is_empty());
        for (pixel, pair) in pixels.iter().zip([0, 0, 1, 0, 0, 1, 2, 2, 3]) {
            let expected = match pair {
                0 => [254_u8, 0, 0],
                1 | 2 => [76, 76, 76],
                _ => [15, 63, 255],
            };
            for (got, want) in pixel[..3].iter().zip(expected) {
                assert!((i16::from(*got) - i16::from(want)).abs() <= 2);
            }
            assert_eq!(pixel[3], 255);
        }
    }

    #[test]
    fn invalid_dimensions_and_lengths_are_rejected() {
        assert_eq!(nv12_to_rgba(0, 1, &[]), Err(Nv12Error::EmptyDimensions));
        assert_eq!(nv12_to_rgba(1, 0, &[]), Err(Nv12Error::EmptyDimensions));
        for actual in [12, 14] {
            assert_eq!(
                nv12_to_rgba(3, 1, &vec![0; actual]),
                Err(Nv12Error::LengthMismatch {
                    expected: 7,
                    actual
                })
            );
        }
        assert_eq!(
            nv12_to_rgba(u32::MAX, u32::MAX, &[]),
            Err(Nv12Error::TooLarge)
        );
    }

    #[test]
    fn matrix_defaults_change_at_720() {
        assert_eq!(YuvMatrix::for_height(719), YuvMatrix::Bt601);
        assert_eq!(YuvMatrix::for_height(720), YuvMatrix::Bt709);
        for (height, matrix) in [(719, YuvMatrix::Bt601), (720, YuvMatrix::Bt709)] {
            let mut data = vec![81; height as usize];
            data.extend((0..height / 2 + height % 2).flat_map(|_| [90, 240]));
            assert_eq!(
                nv12_to_rgba(1, height, &data).unwrap(),
                nv12_to_rgba_with(1, height, &data, matrix).unwrap()
            );
        }
    }
}
