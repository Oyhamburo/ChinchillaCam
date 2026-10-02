use std::ffi::{c_int, c_void};
use std::fmt;
use std::panic::{self, AssertUnwindSafe};
use std::ptr::{self, NonNull};
use std::slice;

use objc2_core_foundation::{CFDictionary, CFNumber, CFNumberType, CFRetained, Type};
use objc2_core_media::{
    kCMBlockBufferAssureMemoryNowFlag, kCMTimeInvalid, CMBlockBuffer, CMFormatDescription,
    CMSampleBuffer, CMSampleTimingInfo, CMTime,
    CMVideoFormatDescriptionCreateFromH264ParameterSets, CMVideoFormatDescriptionGetDimensions,
};
use objc2_core_video::{
    kCVPixelBufferPixelFormatTypeKey, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
    kCVReturnSuccess, CVImageBuffer, CVPixelBuffer, CVPixelBufferGetBaseAddressOfPlane,
    CVPixelBufferGetBytesPerRowOfPlane, CVPixelBufferGetHeight, CVPixelBufferGetHeightOfPlane,
    CVPixelBufferGetPixelFormatType, CVPixelBufferGetPlaneCount, CVPixelBufferGetWidth,
    CVPixelBufferGetWidthOfPlane, CVPixelBufferLockBaseAddress, CVPixelBufferLockFlags,
    CVPixelBufferUnlockBaseAddress,
};
use objc2_video_toolbox::{
    VTDecodeFrameFlags, VTDecodeInfoFlags, VTDecompressionOutputCallbackRecord,
    VTDecompressionSession,
};

use crate::{
    convert_h264_access_unit_to_length_prefixed, parse_h264_config, DecodedFrameCounter,
    DecodedFrameSink, DecodedFrameSinkError, DecodedVideoFrame, EncodedVideoChunk,
    H264InputFraming, H264ParameterSets, PixelFormat, VideoDecoder, VideoDecoderError,
};

const NAL_UNIT_HEADER_LENGTH: c_int = 4;
const MICROS_PER_SECOND: i32 = 1_000_000;
const NV12_PLANE_COUNT: usize = 2;

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

    pub(crate) fn description(&self) -> &CMFormatDescription {
        &self.description
    }
}

fn nonnull_bytes(bytes: &[u8]) -> NonNull<u8> {
    NonNull::from(bytes).cast()
}

/// A synchronous H.264 [`VideoDecoder`] backed by a `VTDecompressionSession` that emits
/// NV12 frames (luma plane followed by the interleaved chroma plane, without stride padding)
/// to its [`DecodedFrameSink`].
///
/// It is not `Send`: it owns CoreFoundation objects and is meant to be driven from the one
/// runtime thread that feeds it.
#[derive(Debug)]
pub struct VideoToolboxDecoder<F> {
    sink: F,
    session: Option<DecompressionSession>,
    frames_emitted: u64,
}

impl<F> VideoToolboxDecoder<F> {
    pub fn new(sink: F) -> Self {
        Self {
            sink,
            session: None,
            frames_emitted: 0,
        }
    }

    pub fn sink(&self) -> &F {
        &self.sink
    }

    pub fn sink_mut(&mut self) -> &mut F {
        &mut self.sink
    }

    pub fn into_sink(self) -> F {
        self.sink
    }

    fn configure(&mut self, chunk: &EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        self.session = None;
        let parameter_sets = parse_h264_config(H264InputFraming::AnnexB, chunk.payload())
            .map_err(|error| failure(format!("invalid H.264 codec config: {error:?}")))?;
        let format = VideoToolboxFormat::from_parameter_sets(&parameter_sets)
            .map_err(|error| failure(error.to_string()))?;
        self.session = Some(DecompressionSession::create(format)?);
        Ok(())
    }
}

impl<F> VideoDecoder for VideoToolboxDecoder<F>
where
    F: DecodedFrameSink,
{
    fn decode_encoded_video(&mut self, chunk: EncodedVideoChunk) -> Result<(), VideoDecoderError> {
        if chunk.is_codec_config() {
            return self.configure(&chunk);
        }
        let session = self.session.as_ref().ok_or_else(|| {
            failure("codec config required before the first decoded frame".to_string())
        })?;
        let frames = session.decode(&chunk)?;
        for frame in frames {
            self.sink
                .push_decoded_frame(frame)
                .map_err(|error| match error {
                    DecodedFrameSinkError::Backpressure => VideoDecoderError::Backpressure,
                    other => failure(format!("decoded frame sink rejected frame: {other:?}")),
                })?;
            self.frames_emitted += 1;
        }
        Ok(())
    }
}

impl<F> DecodedFrameCounter for VideoToolboxDecoder<F> {
    fn frames_emitted(&self) -> u64 {
        self.frames_emitted
    }
}

#[derive(Debug)]
struct DecompressionSession {
    format: VideoToolboxFormat,
    session: CFRetained<VTDecompressionSession>,
}

impl DecompressionSession {
    fn create(format: VideoToolboxFormat) -> Result<Self, VideoDecoderError> {
        let attributes = nv12_destination_attributes()?;
        let callback = VTDecompressionOutputCallbackRecord {
            decompressionOutputCallback: Some(decompression_output_callback),
            decompressionOutputRefCon: ptr::null_mut(),
        };
        let mut session_out: *mut VTDecompressionSession = ptr::null_mut();
        // SAFETY: the format description and attribute dictionary are live, retained CF
        // objects; the callback record is valid for the call and copied by VideoToolbox; and
        // `session_out` is a valid, writable out-pointer.
        let status = unsafe {
            VTDecompressionSession::create(
                None,
                format.description(),
                None,
                Some(attributes.as_ref()),
                &callback,
                NonNull::from(&mut session_out),
            )
        };
        check_status(status, "VTDecompressionSessionCreate")?;
        // SAFETY: on success the session follows the Create rule (+1 retain count).
        let session = unsafe { retained_from_create(session_out, "VTDecompressionSessionCreate") }?;
        Ok(Self { format, session })
    }

    fn decode(
        &self,
        chunk: &EncodedVideoChunk,
    ) -> Result<Vec<DecodedVideoFrame>, VideoDecoderError> {
        let sample_data =
            convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, chunk.payload())
                .map_err(|error| failure(format!("invalid H.264 access unit: {error:?}")))?;
        let pts_us = chunk.presentation_timestamp().as_micros();
        let sample = create_sample_buffer(&self.format, &sample_data, pts_us)?;
        let mut collector = DecodeCollector {
            stream_id: chunk.stream_id(),
            pts_us,
            frames: Vec::new(),
            error: None,
        };
        let collector_ptr: *mut DecodeCollector = &mut collector;
        // SAFETY: the session and sample buffer are live. `collector_ptr` points to a local
        // that is not touched again until both the synchronous decode and the wait below have
        // returned, so the output callback has exclusive access to it while it can run.
        let decode_status = unsafe {
            self.session.decode_frame(
                &sample,
                VTDecodeFrameFlags(0),
                collector_ptr.cast(),
                ptr::null_mut(),
            )
        };
        // SAFETY: the session is live; waiting guarantees no callback still holds the
        // collector pointer once this returns, even when the decode call itself failed.
        let wait_status = unsafe { self.session.wait_for_asynchronous_frames() };
        check_status(decode_status, "VTDecompressionSessionDecodeFrame")?;
        check_status(
            wait_status,
            "VTDecompressionSessionWaitForAsynchronousFrames",
        )?;
        match collector.error {
            Some(error) => Err(failure(error)),
            None => Ok(collector.frames),
        }
    }
}

impl Drop for DecompressionSession {
    fn drop(&mut self) {
        // SAFETY: the session is live and retained; invalidating it before the final release
        // is the documented teardown and stops any further callbacks.
        unsafe { self.session.invalidate() };
    }
}

/// Collects the output of one decode call; reached by the output callback through the
/// per-frame `sourceFrameRefCon` pointer.
struct DecodeCollector {
    stream_id: u32,
    pts_us: u64,
    frames: Vec<DecodedVideoFrame>,
    error: Option<String>,
}

struct Nv12Image {
    width: u32,
    height: u32,
    data: Vec<u8>,
}

/// Output callback registered with every session; it never unwinds into VideoToolbox.
///
/// # Safety
///
/// `source_frame_ref_con` must be null or the exclusive `DecodeCollector` pointer passed to
/// `decode_frame`, and `image_buffer` must be null or valid for the duration of the call.
unsafe extern "C-unwind" fn decompression_output_callback(
    _output_ref_con: *mut c_void,
    source_frame_ref_con: *mut c_void,
    status: i32,
    info_flags: VTDecodeInfoFlags,
    image_buffer: *mut CVImageBuffer,
    _presentation_time: CMTime,
    _presentation_duration: CMTime,
) {
    // SAFETY: `source_frame_ref_con` is the `DecodeCollector` pointer handed to
    // `decode_frame`, exclusively reserved for this callback until the decode returns.
    let Some(collector) = (unsafe { source_frame_ref_con.cast::<DecodeCollector>().as_mut() })
    else {
        return;
    };
    let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
        copy_nv12_image(status, info_flags, image_buffer)
    }));
    match outcome {
        Ok(Ok(Some(image))) => collector.frames.push(DecodedVideoFrame::new(
            collector.stream_id,
            collector.pts_us,
            image.width,
            image.height,
            PixelFormat::Nv12,
            image.data,
        )),
        Ok(Ok(None)) => {}
        Ok(Err(error)) => {
            collector.error.get_or_insert(error);
        }
        Err(_) => {
            collector
                .error
                .get_or_insert_with(|| "decode output callback panicked".to_string());
        }
    }
}

fn copy_nv12_image(
    status: i32,
    info_flags: VTDecodeInfoFlags,
    image_buffer: *mut CVImageBuffer,
) -> Result<Option<Nv12Image>, String> {
    if status != 0 {
        return Err(format!("VideoToolbox decode callback: OSStatus {status}"));
    }
    if info_flags.contains(VTDecodeInfoFlags::FrameDropped) {
        return Ok(None);
    }
    // SAFETY: VideoToolbox passes null or an image buffer kept alive for the callback.
    let Some(image) = (unsafe { image_buffer.as_ref() }) else {
        return Ok(None);
    };
    let pixel_format = CVPixelBufferGetPixelFormatType(image);
    if pixel_format != kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        || CVPixelBufferGetPlaneCount(image) != NV12_PLANE_COUNT
    {
        return Err(format!("unexpected decoded pixel format {pixel_format:#x}"));
    }
    let (Ok(width), Ok(height)) = (
        u32::try_from(CVPixelBufferGetWidth(image)),
        u32::try_from(CVPixelBufferGetHeight(image)),
    ) else {
        return Err("decoded image dimensions overflow u32".to_string());
    };

    // SAFETY: `image` is a live pixel buffer; the read-only lock is balanced below.
    let lock_status =
        unsafe { CVPixelBufferLockBaseAddress(image, CVPixelBufferLockFlags::ReadOnly) };
    if lock_status != kCVReturnSuccess {
        return Err(format!(
            "CVPixelBufferLockBaseAddress: OSStatus {lock_status}"
        ));
    }
    let copied = copy_locked_planes(image);
    // SAFETY: balances the successful read-only lock above with the same flags.
    let unlock_status =
        unsafe { CVPixelBufferUnlockBaseAddress(image, CVPixelBufferLockFlags::ReadOnly) };
    let data = copied?;
    if unlock_status != kCVReturnSuccess {
        return Err(format!(
            "CVPixelBufferUnlockBaseAddress: OSStatus {unlock_status}"
        ));
    }
    Ok(Some(Nv12Image {
        width,
        height,
        data,
    }))
}

/// Copies the luma plane (one byte per pixel) and then the interleaved chroma plane (one
/// Cb/Cr byte pair per chroma sample), dropping any per-row stride padding.
fn copy_locked_planes(image: &CVPixelBuffer) -> Result<Vec<u8>, String> {
    let mut data = Vec::new();
    for plane in 0..NV12_PLANE_COUNT {
        let rows = CVPixelBufferGetHeightOfPlane(image, plane);
        let bytes_per_sample = plane + 1;
        let row_bytes = CVPixelBufferGetWidthOfPlane(image, plane)
            .checked_mul(bytes_per_sample)
            .ok_or("decoded plane row size overflows")?;
        let stride = CVPixelBufferGetBytesPerRowOfPlane(image, plane);
        let base = CVPixelBufferGetBaseAddressOfPlane(image, plane).cast::<u8>();
        if base.is_null() || stride < row_bytes || stride.checked_mul(rows).is_none() {
            return Err(format!("decoded plane {plane} has an invalid layout"));
        }
        for row in 0..rows {
            // SAFETY: the buffer is locked, so the plane base address is valid for `rows`
            // rows of `stride` bytes each; `row * stride` cannot overflow (checked above) and
            // `row_bytes <= stride` keeps the slice inside the row.
            let row_data =
                unsafe { slice::from_raw_parts(base.cast_const().add(row * stride), row_bytes) };
            data.extend_from_slice(row_data);
        }
    }
    Ok(data)
}

fn nv12_destination_attributes() -> Result<CFRetained<CFDictionary>, VideoDecoderError> {
    let pixel_format = i32::try_from(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
        .map_err(|_| failure("NV12 pixel format does not fit a CFNumber".to_string()))?;
    // SAFETY: the value pointer refers to a live `i32`, matching `SInt32Type`.
    let number = unsafe {
        CFNumber::new(
            None,
            CFNumberType::SInt32Type,
            ptr::from_ref(&pixel_format).cast(),
        )
    }
    .ok_or_else(|| failure("CFNumberCreate failed".to_string()))?;
    // SAFETY: the key is an immutable CoreVideo constant initialized by the framework.
    let key = unsafe { kCVPixelBufferPixelFormatTypeKey };
    let attributes = CFDictionary::from_slices(&[key], &[&*number]);
    Ok(attributes.as_opaque().retain())
}

fn create_sample_buffer(
    format: &VideoToolboxFormat,
    sample_data: &[u8],
    pts_us: u64,
) -> Result<CFRetained<CMSampleBuffer>, VideoDecoderError> {
    let pts = i64::try_from(pts_us)
        .map_err(|_| failure(format!("presentation timestamp {pts_us} overflows CMTime")))?;
    let length = sample_data.len();
    let mut block_out: *mut CMBlockBuffer = ptr::null_mut();
    // SAFETY: a null memory block asks CoreMedia to allocate and own `length` bytes now with
    // the default allocator; no custom block source is used and `block_out` is writable.
    let status = unsafe {
        CMBlockBuffer::create_with_memory_block(
            None,
            ptr::null_mut(),
            length,
            None,
            ptr::null(),
            0,
            length,
            kCMBlockBufferAssureMemoryNowFlag,
            NonNull::from(&mut block_out),
        )
    };
    check_status(status, "CMBlockBufferCreateWithMemoryBlock")?;
    // SAFETY: on success the block buffer follows the Create rule (+1 retain count).
    let block = unsafe { retained_from_create(block_out, "CMBlockBufferCreateWithMemoryBlock") }?;
    // SAFETY: the source is a live slice of `length` bytes and the block owns exactly
    // `length` writable bytes allocated above; the bytes are copied.
    let status = unsafe {
        CMBlockBuffer::replace_data_bytes(nonnull_bytes(sample_data).cast(), &block, 0, length)
    };
    check_status(status, "CMBlockBufferReplaceDataBytes")?;

    // SAFETY: reading an immutable CoreMedia constant; `CMTimeMake` has no preconditions.
    let (invalid, presentation) = unsafe { (kCMTimeInvalid, CMTime::new(pts, MICROS_PER_SECOND)) };
    let timing = CMSampleTimingInfo {
        duration: invalid,
        presentationTimeStamp: presentation,
        decodeTimeStamp: invalid,
    };
    let sample_sizes = [length];
    let mut sample_out: *mut CMSampleBuffer = ptr::null_mut();
    // SAFETY: the block buffer and format description are live; the timing and size arrays
    // hold exactly one entry each and outlive the call; `sample_out` is writable.
    let status = unsafe {
        CMSampleBuffer::create_ready(
            None,
            Some(&block),
            Some(format.description()),
            1,
            1,
            &timing,
            1,
            sample_sizes.as_ptr(),
            NonNull::from(&mut sample_out),
        )
    };
    check_status(status, "CMSampleBufferCreateReady")?;
    // SAFETY: on success the sample buffer follows the Create rule (+1 retain count).
    unsafe { retained_from_create(sample_out, "CMSampleBufferCreateReady") }
}

/// Takes ownership of a pointer returned by a successful CoreFoundation Create call.
///
/// # Safety
///
/// `raw` must be null or point to a live object of type `T` carrying a +1 retain count that
/// the caller transfers to the returned `CFRetained`.
unsafe fn retained_from_create<T: Type>(
    raw: *mut T,
    operation: &str,
) -> Result<CFRetained<T>, VideoDecoderError> {
    let raw = NonNull::new(raw).ok_or_else(|| failure(format!("{operation} returned null")))?;
    // SAFETY: guaranteed by the caller contract above.
    Ok(unsafe { CFRetained::from_raw(raw) })
}

fn check_status(status: i32, operation: &str) -> Result<(), VideoDecoderError> {
    if status == 0 {
        Ok(())
    } else {
        Err(failure(format!("{operation} failed: OSStatus {status}")))
    }
}

fn failure(detail: String) -> VideoDecoderError {
    VideoDecoderError::Failure(detail)
}
