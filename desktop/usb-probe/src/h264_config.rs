pub const MAX_H264_CONFIG_BYTES: usize = 256 * 1024;
pub const MAX_H264_CONFIG_NAL_UNITS: usize = 64;
pub const MAX_H264_PARAMETER_SET_BYTES: usize = 128 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum H264InputFraming {
    AnnexB,
    AvccLengthPrefixed,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct H264ParameterSets {
    sps: Vec<u8>,
    pps: Vec<u8>,
}

impl H264ParameterSets {
    pub fn sps(&self) -> &[u8] {
        &self.sps
    }

    pub fn pps(&self) -> &[u8] {
        &self.pps
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum H264ConfigError {
    UnsupportedFraming(H264InputFraming),
    EmptyInput,
    ConfigTooLarge {
        length: usize,
        max: usize,
    },
    TooManyNalUnits {
        count: usize,
        max: usize,
    },
    AnnexBStartCodeMissing,
    EmptyNalUnit,
    InvalidNalHeader {
        header: u8,
    },
    ParameterSetTooLarge {
        nal_unit_type: u8,
        length: usize,
        max: usize,
    },
    TrailingZeroBytes,
    MissingSps,
    MissingPps,
    DuplicateSps,
    DuplicatePps,
}

pub fn parse_h264_config(
    framing: H264InputFraming,
    bytes: &[u8],
) -> Result<H264ParameterSets, H264ConfigError> {
    if framing != H264InputFraming::AnnexB {
        return Err(H264ConfigError::UnsupportedFraming(framing));
    }
    if bytes.is_empty() {
        return Err(H264ConfigError::EmptyInput);
    }
    if bytes.len() > MAX_H264_CONFIG_BYTES {
        return Err(H264ConfigError::ConfigTooLarge {
            length: bytes.len(),
            max: MAX_H264_CONFIG_BYTES,
        });
    }

    let mut cursor = match find_start_code(bytes, 0) {
        Some((0, length)) => length,
        _ => return Err(H264ConfigError::AnnexBStartCodeMissing),
    };
    let mut nal_count = 0;
    let mut sps = None;
    let mut pps = None;

    loop {
        let next_start = find_start_code(bytes, cursor);
        let nal_end = next_start.map(|(index, _)| index).unwrap_or(bytes.len());
        if nal_end == cursor {
            return Err(H264ConfigError::EmptyNalUnit);
        }

        nal_count += 1;
        if nal_count > MAX_H264_CONFIG_NAL_UNITS {
            return Err(H264ConfigError::TooManyNalUnits {
                count: nal_count,
                max: MAX_H264_CONFIG_NAL_UNITS,
            });
        }

        let nal = &bytes[cursor..nal_end];
        if next_start.is_none() && nal.ends_with(&[0]) {
            return Err(H264ConfigError::TrailingZeroBytes);
        }
        let header = nal[0];
        validate_nal_header(header)?;
        match header & 0x1f {
            7 => {
                if sps.is_some() {
                    return Err(H264ConfigError::DuplicateSps);
                }
                validate_parameter_set_len(7, nal.len())?;
                sps = Some(nal.to_vec());
            }
            8 => {
                if pps.is_some() {
                    return Err(H264ConfigError::DuplicatePps);
                }
                validate_parameter_set_len(8, nal.len())?;
                pps = Some(nal.to_vec());
            }
            _ => {}
        }

        let Some((_, start_code_length)) = next_start else {
            break;
        };
        cursor = nal_end + start_code_length;
        if cursor == bytes.len() {
            return Err(H264ConfigError::EmptyNalUnit);
        }
    }

    let sps = sps.ok_or(H264ConfigError::MissingSps)?;
    let pps = pps.ok_or(H264ConfigError::MissingPps)?;
    Ok(H264ParameterSets { sps, pps })
}

fn validate_parameter_set_len(nal_unit_type: u8, length: usize) -> Result<(), H264ConfigError> {
    if length > MAX_H264_PARAMETER_SET_BYTES {
        return Err(H264ConfigError::ParameterSetTooLarge {
            nal_unit_type,
            length,
            max: MAX_H264_PARAMETER_SET_BYTES,
        });
    }
    Ok(())
}

pub(crate) fn validate_nal_header(header: u8) -> Result<(), H264ConfigError> {
    if header & 0x80 != 0 || header & 0x1f == 0 {
        return Err(H264ConfigError::InvalidNalHeader { header });
    }
    Ok(())
}

pub(crate) fn find_start_code(bytes: &[u8], from: usize) -> Option<(usize, usize)> {
    if bytes.len().saturating_sub(from) < 3 {
        return None;
    }

    for index in from..=bytes.len() - 3 {
        if bytes[index] == 0 && bytes[index + 1] == 0 {
            if index + 3 < bytes.len() && bytes[index + 2] == 0 && bytes[index + 3] == 1 {
                return Some((index, 4));
            }
            if bytes[index + 2] == 1 {
                return Some((index, 3));
            }
        }
    }
    None
}
