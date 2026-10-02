use std::ffi::c_int;
use std::fmt;
use std::ptr::{self, NonNull};

use objc2_core_foundation::CFRetained;
use objc2_core_media::{
    CMFormatDescription, CMVideoFormatDescriptionCreateFromH264ParameterSets,
    CMVideoFormatDescriptionGetDimensions,
};

use crate::H264ParameterSets;

const NAL_UNIT_HEADER_LENGTH: c_int = 4;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VideoToolboxError {
    FormatDescription { status: i32 },
    InvalidDimensions { width: i32, height: i32 },
}

impl fmt::Display for VideoToolboxError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::FormatDescription { status } => write!(
                formatter,
                "CMVideoFormatDescriptionCreateFromH264ParameterSets failed with OSStatus {status}"
            ),
            Self::InvalidDimensions { width, height } => write!(
                formatter,
                "format description reported invalid dimensions {width}x{height}"
            ),
        }
    }
}

impl std::error::Error for VideoToolboxError {}

/// An H.264 video format description built from one SPS and one PPS, with
/// a 4-byte NAL unit length prefix for the sample data.
#[derive(Debug)]
pub struct VideoToolboxFormat {
    description: CFRetained<CMFormatDescription>,
    width: u32,
    height: u32,
}

impl VideoToolboxFormat {
    pub fn from_parameter_sets(
        parameter_sets: &H264ParameterSets,
    ) -> Result<Self, VideoToolboxError> {
        let sets = [parameter_sets.sps(), parameter_sets.pps()];
        let mut pointers = sets.map(nonnull_bytes);
        let mut sizes = sets.map(<[u8]>::len);
        let mut description_out: *const CMFormatDescription = ptr::null();

        // SAFETY: `pointers` and `sizes` are two-element arrays that outlive
        // the call, and each pointer refers to a live slice of the matching
        // length. `description_out` is a valid, writable out-pointer. The
        // call copies the parameter sets and does not retain the inputs.
        let status = unsafe {
            CMVideoFormatDescriptionCreateFromH264ParameterSets(
                None,
                sets.len(),
                NonNull::from(&mut pointers).cast(),
                NonNull::from(&mut sizes).cast(),
                NAL_UNIT_HEADER_LENGTH,
                NonNull::from(&mut description_out),
            )
        };
        if status != 0 {
            return Err(VideoToolboxError::FormatDescription { status });
        }
        let description = NonNull::new(description_out.cast_mut())
            .ok_or(VideoToolboxError::FormatDescription { status })?;
        // SAFETY: the function follows the Create rule, so on success the
        // returned non-null description carries a +1 retain count that the
        // `CFRetained` now owns and releases on drop.
        let description = unsafe { CFRetained::from_raw(description) };

        // SAFETY: `description` is a live, retained video format description.
        let dimensions = unsafe { CMVideoFormatDescriptionGetDimensions(&description) };
        let (Ok(width), Ok(height)) = (
            u32::try_from(dimensions.width),
            u32::try_from(dimensions.height),
        ) else {
            return Err(VideoToolboxError::InvalidDimensions {
                width: dimensions.width,
                height: dimensions.height,
            });
        };
        if width == 0 || height == 0 {
            return Err(VideoToolboxError::InvalidDimensions {
                width: dimensions.width,
                height: dimensions.height,
            });
        }

        Ok(Self {
            description,
            width,
            height,
        })
    }

    pub fn dimensions(&self) -> (u32, u32) {
        (self.width, self.height)
    }

    #[allow(dead_code)]
    pub(crate) fn description(&self) -> &CMFormatDescription {
        &self.description
    }
}

fn nonnull_bytes(bytes: &[u8]) -> NonNull<u8> {
    NonNull::from(bytes).cast()
}
