use usb_probe::{
    convert_h264_access_unit_to_length_prefixed, EncodedVideoChunk, EncodedVideoChunkLimits,
    EncodedVideoFrameKind, H264AccessUnitError, H264InputFraming, PresentationTimestamp,
    MAX_H264_ACCESS_UNIT_BYTES, MAX_H264_ACCESS_UNIT_NAL_UNITS,
};

fn nal_units_from_length_prefixed(bytes: &[u8]) -> Vec<&[u8]> {
    let mut cursor = 0;
    let mut nals = Vec::new();
    while cursor < bytes.len() {
        let length = u32::from_be_bytes(bytes[cursor..cursor + 4].try_into().unwrap()) as usize;
        cursor += 4;
        nals.push(&bytes[cursor..cursor + length]);
        cursor += length;
    }
    nals
}

#[test]
fn converts_fixture_sps_pps_sei_idr_to_four_byte_big_endian_lengths() {
    let annex_b = include_bytes!("fixtures/t21b1-16x16-idr.h264");

    let converted =
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, annex_b).unwrap();

    assert_eq!(annex_b.len(), 873);
    let nals = nal_units_from_length_prefixed(&converted);
    assert_eq!(
        nals.iter().map(|nal| nal[0] & 0x1f).collect::<Vec<_>>(),
        vec![7, 8, 6, 5]
    );
    assert_eq!(&converted[0..4], &(nals[0].len() as u32).to_be_bytes());
    assert_eq!(nals[0][0] & 0x1f, 7);
    assert_eq!(nals[1][0] & 0x1f, 8);
    assert_eq!(nals[2][0] & 0x1f, 6);
    assert_eq!(nals[3][0] & 0x1f, 5);
}

#[test]
fn preserves_nal_bytes_and_leaves_pts_and_kind_to_caller() {
    let annex_b = [
        &[0, 0, 1, 0x65, 0xaa, 0xbb][..],
        &[0, 0, 0, 1, 0x41, 0xcc][..],
    ]
    .concat();
    let limits = EncodedVideoChunkLimits::new(1024).unwrap();
    let pts = PresentationTimestamp::from_micros(42);

    let payload =
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &annex_b).unwrap();
    let chunk =
        EncodedVideoChunk::new(9, pts, EncodedVideoFrameKind::Key, payload, &limits).unwrap();

    assert_eq!(chunk.stream_id(), 9);
    assert_eq!(chunk.presentation_timestamp(), pts);
    assert_eq!(chunk.frame_kind(), EncodedVideoFrameKind::Key);
    assert_eq!(
        nal_units_from_length_prefixed(chunk.payload()),
        vec![&[0x65, 0xaa, 0xbb][..], &[0x41, 0xcc][..]]
    );
}

#[test]
fn supports_mixed_three_and_four_byte_start_codes() {
    let annex_b = [0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3];

    let converted =
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &annex_b).unwrap();

    assert_eq!(converted, vec![0, 0, 0, 3, 0x67, 1, 2, 0, 0, 0, 2, 0x68, 3]);
}

#[test]
fn rejects_unsupported_framing_without_autodetecting() {
    let avcc_like = [0, 0, 0, 2, 0x67, 1];

    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(
            H264InputFraming::AvccLengthPrefixed,
            &avcc_like
        ),
        Err(H264AccessUnitError::UnsupportedFraming(
            H264InputFraming::AvccLengthPrefixed
        ))
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::Unknown, &[0, 0, 1, 0x67]),
        Err(H264AccessUnitError::UnsupportedFraming(
            H264InputFraming::Unknown
        ))
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &avcc_like),
        Err(H264AccessUnitError::AnnexBStartCodeMissing)
    );
}

#[test]
fn rejects_adversarial_inputs_and_limits() {
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[]),
        Err(H264AccessUnitError::EmptyInput)
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[0x67, 1]),
        Err(H264AccessUnitError::AnnexBStartCodeMissing)
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[0, 0, 1]),
        Err(H264AccessUnitError::EmptyNalUnit)
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(
            H264InputFraming::AnnexB,
            &[0, 0, 1, 0x67, 0, 0, 1]
        ),
        Err(H264AccessUnitError::EmptyNalUnit)
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[0, 0, 1, 0x80]),
        Err(H264AccessUnitError::InvalidNalHeader { header: 0x80 })
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[0, 0, 1, 0x60]),
        Err(H264AccessUnitError::InvalidNalHeader { header: 0x60 })
    );
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &[0, 0, 1, 0x67, 0]),
        Err(H264AccessUnitError::TrailingZeroBytes)
    );

    let too_large = vec![0; MAX_H264_ACCESS_UNIT_BYTES + 1];
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &too_large),
        Err(H264AccessUnitError::AccessUnitTooLarge {
            length: MAX_H264_ACCESS_UNIT_BYTES + 1,
            max: MAX_H264_ACCESS_UNIT_BYTES,
        })
    );

    let mut too_many = Vec::new();
    for _ in 0..=MAX_H264_ACCESS_UNIT_NAL_UNITS {
        too_many.extend_from_slice(&[0, 0, 1, 0x67]);
    }
    assert_eq!(
        convert_h264_access_unit_to_length_prefixed(H264InputFraming::AnnexB, &too_many),
        Err(H264AccessUnitError::TooManyNalUnits {
            count: MAX_H264_ACCESS_UNIT_NAL_UNITS + 1,
            max: MAX_H264_ACCESS_UNIT_NAL_UNITS,
        })
    );
}
