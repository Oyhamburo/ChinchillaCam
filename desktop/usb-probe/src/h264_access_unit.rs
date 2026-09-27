use crate::h264_config::{find_start_code, validate_nal_header, H264InputFraming};

pub const MAX_H264_ACCESS_UNIT_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_H264_ACCESS_UNIT_NAL_UNITS: usize = 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum H264AccessUnitError {
    UnsupportedFraming(H264InputFraming),
    EmptyInput,
    AccessUnitTooLarge { length: usize, max: usize },
    TooManyNalUnits { count: usize, max: usize },
    AnnexBStartCodeMissing,
    EmptyNalUnit,
    InvalidNalHeader { header: u8 },
    NalUnitTooLarge { length: usize, max: u32 },
    TrailingZeroBytes,
}

pub fn convert_h264_access_unit_to_length_prefixed(
    framing: H264InputFraming,
    bytes: &[u8],
) -> Result<Vec<u8>, H264AccessUnitError> {
    if framing != H264InputFraming::AnnexB {
        return Err(H264AccessUnitError::UnsupportedFraming(framing));
    }
    if bytes.is_empty() {
        return Err(H264AccessUnitError::EmptyInput);
    }
    if bytes.len() > MAX_H264_ACCESS_UNIT_BYTES {
        return Err(H264AccessUnitError::AccessUnitTooLarge {
            length: bytes.len(),
            max: MAX_H264_ACCESS_UNIT_BYTES,
        });
    }

    let mut cursor = match find_start_code(bytes, 0) {
        Some((0, length)) => length,
        _ => return Err(H264AccessUnitError::AnnexBStartCodeMissing),
    };
    let mut nal_count = 0;
    let mut converted = Vec::with_capacity(bytes.len());

    loop {
        let next_start = find_start_code(bytes, cursor);
        let nal_end = next_start.map(|(index, _)| index).unwrap_or(bytes.len());
        if nal_end == cursor {
            return Err(H264AccessUnitError::EmptyNalUnit);
        }

        nal_count += 1;
        if nal_count > MAX_H264_ACCESS_UNIT_NAL_UNITS {
            return Err(H264AccessUnitError::TooManyNalUnits {
                count: nal_count,
                max: MAX_H264_ACCESS_UNIT_NAL_UNITS,
            });
        }

        let nal = &bytes[cursor..nal_end];
        if next_start.is_none() && nal.ends_with(&[0]) {
            return Err(H264AccessUnitError::TrailingZeroBytes);
        }
        validate_nal_header(nal[0])
            .map_err(|_| H264AccessUnitError::InvalidNalHeader { header: nal[0] })?;
        let nal_length =
            u32::try_from(nal.len()).map_err(|_| H264AccessUnitError::NalUnitTooLarge {
                length: nal.len(),
                max: u32::MAX,
            })?;
        converted.extend_from_slice(&nal_length.to_be_bytes());
        converted.extend_from_slice(nal);

        let Some((_, start_code_length)) = next_start else {
            break;
        };
        cursor = nal_end + start_code_length;
        if cursor == bytes.len() {
            return Err(H264AccessUnitError::EmptyNalUnit);
        }
    }

    Ok(converted)
}
