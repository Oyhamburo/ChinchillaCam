use usb_probe::{
    parse_h264_config, H264ConfigError, H264InputFraming, MAX_H264_CONFIG_BYTES,
    MAX_H264_CONFIG_NAL_UNITS, MAX_H264_PARAMETER_SET_BYTES,
};

fn android_csd() -> Vec<u8> {
    vec![
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f, 0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x06,
        0xe2,
    ]
}

#[test]
fn h264_config_android_annex_b_csd_extracts_sps_and_pps_without_start_codes() {
    let config = parse_h264_config(H264InputFraming::AnnexB, &android_csd()).unwrap();

    assert_eq!(config.sps(), &[0x67, 0x42, 0x00, 0x1f]);
    assert_eq!(config.pps(), &[0x68, 0xce, 0x06, 0xe2]);
}

#[test]
fn h264_config_mixed_three_and_four_byte_start_codes_parse() {
    let bytes = [
        0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x1f, 0x00, 0x00, 0x00, 0x01, 0x68, 0xeb, 0xec, 0xb2,
    ];

    let config = parse_h264_config(H264InputFraming::AnnexB, &bytes).unwrap();

    assert_eq!(config.sps(), &[0x67, 0x64, 0x00, 0x1f]);
    assert_eq!(config.pps(), &[0x68, 0xeb, 0xec, 0xb2]);
}

#[test]
fn h264_config_rejects_non_annex_b_framing_explicitly() {
    assert_eq!(
        parse_h264_config(H264InputFraming::AvccLengthPrefixed, &android_csd()),
        Err(H264ConfigError::UnsupportedFraming(
            H264InputFraming::AvccLengthPrefixed
        ))
    );
    assert_eq!(
        parse_h264_config(H264InputFraming::Unknown, &android_csd()),
        Err(H264ConfigError::UnsupportedFraming(
            H264InputFraming::Unknown
        ))
    );
}

#[test]
fn h264_config_rejects_missing_and_duplicate_required_parameter_sets() {
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[0, 0, 1, 0x68, 1]),
        Err(H264ConfigError::MissingSps)
    );
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[0, 0, 1, 0x67, 1]),
        Err(H264ConfigError::MissingPps)
    );
    assert_eq!(
        parse_h264_config(
            H264InputFraming::AnnexB,
            &[0, 0, 1, 0x67, 1, 0, 0, 1, 0x67, 2, 0, 0, 1, 0x68, 3]
        ),
        Err(H264ConfigError::DuplicateSps)
    );
    assert_eq!(
        parse_h264_config(
            H264InputFraming::AnnexB,
            &[0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x68, 3]
        ),
        Err(H264ConfigError::DuplicatePps)
    );
}

#[test]
fn h264_config_rejects_malformed_and_bounded_inputs() {
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[]),
        Err(H264ConfigError::EmptyInput)
    );
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[0, 0, 0, 1]),
        Err(H264ConfigError::EmptyNalUnit)
    );
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[0, 0, 1, 0x80, 0, 0, 1, 0x68]),
        Err(H264ConfigError::InvalidNalHeader { header: 0x80 })
    );
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &[0, 0, 1, 0x60, 0, 0, 1, 0x68]),
        Err(H264ConfigError::InvalidNalHeader { header: 0x60 })
    );

    let mut oversized_sps = vec![0, 0, 1, 0x67];
    oversized_sps.extend(std::iter::repeat_n(0x11, MAX_H264_PARAMETER_SET_BYTES));
    oversized_sps.extend_from_slice(&[0, 0, 1, 0x68, 0x22]);
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &oversized_sps),
        Err(H264ConfigError::ParameterSetTooLarge {
            nal_unit_type: 7,
            length: MAX_H264_PARAMETER_SET_BYTES + 1,
            max: MAX_H264_PARAMETER_SET_BYTES,
        })
    );

    assert_eq!(
        parse_h264_config(
            H264InputFraming::AnnexB,
            &[0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 0]
        ),
        Err(H264ConfigError::TrailingZeroBytes)
    );

    let oversized = vec![0; MAX_H264_CONFIG_BYTES + 1];
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &oversized),
        Err(H264ConfigError::ConfigTooLarge {
            length: MAX_H264_CONFIG_BYTES + 1,
            max: MAX_H264_CONFIG_BYTES,
        })
    );

    let mut too_many = Vec::new();
    for _ in 0..=MAX_H264_CONFIG_NAL_UNITS {
        too_many.extend_from_slice(&[0, 0, 1, 0x65]);
    }
    assert_eq!(
        parse_h264_config(H264InputFraming::AnnexB, &too_many),
        Err(H264ConfigError::TooManyNalUnits {
            count: MAX_H264_CONFIG_NAL_UNITS + 1,
            max: MAX_H264_CONFIG_NAL_UNITS,
        })
    );
}
