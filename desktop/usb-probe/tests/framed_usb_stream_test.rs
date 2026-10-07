use std::time::Duration;

use usb_probe::{
    map_rusb_read_error, BulkFrame, FrameTransferBudget, FramedUsbStream, TransferExactBulkIo,
    UsbProbeError, DEFAULT_BULK_READ_TRANSFER_LEN,
};

const READ_TRANSFER_LEN: usize = DEFAULT_BULK_READ_TRANSFER_LEN;

#[test]
fn reads_frame_delivered_in_single_transfer() {
    let frame = BulkFrame::new(7, b"hello".to_vec()).unwrap();
    let mut stream = stream(vec![frame.encode()]);

    assert_eq!(stream.read_frame().unwrap(), frame);
    assert_eq!(stream.io().read_buffer_lens().len(), 1);
    assert_eq!(stream.io().pending_transfers(), 0);
}

#[test]
fn reads_multiple_frames_from_one_transfer() {
    let first = BulkFrame::new(1, b"one".to_vec()).unwrap();
    let second = BulkFrame::new(2, b"two".to_vec()).unwrap();
    let third = BulkFrame::new(3, vec![9; 40]).unwrap();
    let mut coalesced = first.encode();
    coalesced.extend_from_slice(&second.encode());
    let third_encoded = third.encode();
    coalesced.extend_from_slice(&third_encoded[..5]);
    let mut stream = stream(vec![coalesced, third_encoded[5..].to_vec()]);

    assert_eq!(stream.read_frame().unwrap(), first);
    assert_eq!(stream.read_frame().unwrap(), second);
    assert_eq!(stream.io().read_buffer_lens().len(), 1);
    assert_eq!(stream.read_frame().unwrap(), third);
    assert_eq!(stream.io().read_buffer_lens().len(), 2);
}

#[test]
fn reads_frame_split_across_transfers() {
    let large = BulkFrame::new(4, (0..20_000).map(|i| i as u8).collect()).unwrap();
    let encoded = large.encode();
    let small = BulkFrame::new(5, b"tail".to_vec()).unwrap();
    let small_encoded = small.encode();
    let mut stream = stream(vec![
        encoded[..READ_TRANSFER_LEN].to_vec(),
        encoded[READ_TRANSFER_LEN..].to_vec(),
        small_encoded[..3].to_vec(),
        small_encoded[3..].to_vec(),
    ]);

    assert_eq!(stream.read_frame().unwrap(), large);
    assert_eq!(stream.read_frame().unwrap(), small);
    assert_eq!(stream.io().read_buffer_lens().len(), 4);
    assert_eq!(stream.io().pending_transfers(), 0);
}

#[test]
fn read_buffer_never_smaller_than_transfer() {
    let full = BulkFrame::new(6, vec![1; READ_TRANSFER_LEN - 8]).unwrap();
    let tiny = BulkFrame::new(6, vec![2]).unwrap();
    let mut stream = stream(vec![full.encode(), tiny.encode()]);

    assert_eq!(stream.read_frame().unwrap(), full);
    assert_eq!(stream.read_frame().unwrap(), tiny);
    assert_eq!(
        stream.io().read_buffer_lens(),
        &[READ_TRANSFER_LEN, READ_TRANSFER_LEN]
    );
}

#[test]
fn rejects_invalid_read_transfer_len() {
    let budget = FrameTransferBudget::new(Duration::from_millis(5), 1024, 4).unwrap();
    assert_eq!(budget.read_transfer_len(), READ_TRANSFER_LEN);

    for invalid in [0, 8, 511, 513, 1000] {
        assert_eq!(
            budget.clone().with_read_transfer_len(invalid).unwrap_err(),
            UsbProbeError::InvalidBulkReadTransferLen(invalid)
        );
    }

    let custom = budget.with_read_transfer_len(512).unwrap();
    assert_eq!(custom.read_transfer_len(), 512);
    let frame = BulkFrame::new(3, vec![4; 600]).unwrap();
    let encoded = frame.encode();
    let mut stream = FramedUsbStream::new(
        TransferExactBulkIo::with_transfers(vec![encoded[..512].to_vec(), encoded[512..].to_vec()]),
        custom.clone(),
    );
    let mut too_small = FramedUsbStream::new(
        TransferExactBulkIo::with_transfers(vec![encoded.clone()]),
        custom,
    );

    assert_eq!(stream.read_frame().unwrap(), frame);
    assert_eq!(stream.io().read_buffer_lens(), &[512, 512]);
    assert_eq!(
        too_small.read_frame().unwrap_err(),
        UsbProbeError::UsbBulkTransferFailed("Overflow".to_string())
    );
}

#[test]
fn reports_truncation_when_transfers_end_mid_frame() {
    let frame = BulkFrame::new(8, b"hello".to_vec()).unwrap();
    let encoded = frame.encode();

    let mut header_cut = stream(vec![encoded[..3].to_vec()]);
    assert_eq!(
        header_cut.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameHeaderTruncated
    );

    let mut payload_cut = stream(vec![encoded[..10].to_vec()]);
    assert_eq!(
        payload_cut.read_frame().unwrap_err(),
        UsbProbeError::BulkFramePayloadTruncated {
            expected: 5,
            actual: 2
        }
    );
}

#[test]
fn bounds_transfers_per_frame_by_max_io_attempts() {
    let frame = BulkFrame::new(9, b"abcdef".to_vec()).unwrap();
    let transfers = frame.encode().chunks(1).map(<[u8]>::to_vec).collect();
    let budget = FrameTransferBudget::new(Duration::from_millis(5), 64, 4).unwrap();
    let mut stream = FramedUsbStream::new(TransferExactBulkIo::with_transfers(transfers), budget);

    assert_eq!(
        stream.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameHeaderTruncated
    );
    assert_eq!(stream.io().read_buffer_lens().len(), 4);
}

#[test]
fn rusb_read_timeout_maps_to_bulk_read_timeout() {
    assert_eq!(
        map_rusb_read_error(rusb::Error::Timeout),
        UsbProbeError::BulkReadTimeout
    );
    for error in [
        rusb::Error::Overflow,
        rusb::Error::Pipe,
        rusb::Error::NoDevice,
        rusb::Error::Io,
    ] {
        assert_eq!(
            map_rusb_read_error(error),
            UsbProbeError::UsbBulkTransferFailed(error.to_string())
        );
    }
}

#[test]
fn idle_timeout_before_frame_is_not_fatal() {
    let frame = BulkFrame::new(7, b"after idle".to_vec()).unwrap();
    let mut stream = stream_with_reads(vec![
        Err(UsbProbeError::BulkReadTimeout),
        Err(UsbProbeError::BulkReadTimeout),
        Ok(frame.encode()),
    ]);

    assert_eq!(
        stream.read_frame().unwrap_err(),
        UsbProbeError::BulkReadTimeout
    );
    assert_eq!(
        stream.read_frame().unwrap_err(),
        UsbProbeError::BulkReadTimeout
    );
    assert_eq!(stream.read_frame().unwrap(), frame);
    assert_eq!(stream.io().pending_transfers(), 0);
}

#[test]
fn timeout_mid_frame_is_fatal_stall() {
    let frame = BulkFrame::new(7, b"hello".to_vec()).unwrap();
    let encoded = frame.encode();

    let mut header_stall = stream_with_reads(vec![
        Ok(encoded[..3].to_vec()),
        Err(UsbProbeError::BulkReadTimeout),
    ]);
    assert_eq!(
        header_stall.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameStalled { consumed: 3 }
    );

    let mut payload_stall = stream_with_reads(vec![
        Ok(encoded[..10].to_vec()),
        Err(UsbProbeError::BulkReadTimeout),
    ]);
    assert_eq!(
        payload_stall.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameStalled { consumed: 10 }
    );

    let mut coalesced = frame.encode();
    coalesced.extend_from_slice(&encoded[..2]);
    let mut residual_stall =
        stream_with_reads(vec![Ok(coalesced), Err(UsbProbeError::BulkReadTimeout)]);
    assert_eq!(residual_stall.read_frame().unwrap(), frame);
    assert_eq!(
        residual_stall.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameStalled { consumed: 2 }
    );
}

#[test]
fn idle_read_timeout_applies_only_to_first_read_of_a_frame() {
    let frame_timeout = Duration::from_millis(250);
    let idle_timeout = Duration::from_millis(3);
    let budget = FrameTransferBudget::new(frame_timeout, 1024, 8).unwrap();
    assert_eq!(budget.idle_read_timeout(), None);
    assert_eq!(
        budget
            .clone()
            .with_idle_read_timeout(Duration::from_micros(999))
            .unwrap_err(),
        UsbProbeError::InvalidBulkIdleReadTimeout(Duration::from_micros(999))
    );
    let idle_budget = budget.clone().with_idle_read_timeout(idle_timeout).unwrap();
    assert_eq!(idle_budget.idle_read_timeout(), Some(idle_timeout));

    let frame = BulkFrame::new(7, b"hello".to_vec()).unwrap();
    let encoded = frame.encode();
    let reads = || {
        vec![
            Err(UsbProbeError::BulkReadTimeout),
            Ok(encoded[..3].to_vec()),
            Ok(encoded[3..].to_vec()),
            Ok(encoded.clone()),
        ]
    };

    let mut default_stream =
        FramedUsbStream::new(TransferExactBulkIo::with_reads(reads()), budget.clone());
    assert_eq!(
        default_stream.read_frame().unwrap_err(),
        UsbProbeError::BulkReadTimeout
    );
    assert_eq!(default_stream.read_frame().unwrap(), frame);
    assert_eq!(default_stream.io().read_timeouts(), &[frame_timeout; 3]);

    let mut stream = FramedUsbStream::new(TransferExactBulkIo::with_reads(reads()), budget);
    stream.set_idle_read_timeout(Some(idle_timeout)).unwrap();
    assert_eq!(
        stream
            .set_idle_read_timeout(Some(Duration::ZERO))
            .unwrap_err(),
        UsbProbeError::InvalidBulkIdleReadTimeout(Duration::ZERO)
    );
    assert_eq!(
        stream.read_frame().unwrap_err(),
        UsbProbeError::BulkReadTimeout
    );
    assert_eq!(stream.read_frame().unwrap(), frame);
    assert_eq!(stream.read_frame().unwrap(), frame);
    assert_eq!(
        stream.io().read_timeouts(),
        &[idle_timeout, idle_timeout, frame_timeout, idle_timeout]
    );

    stream.set_idle_read_timeout(None).unwrap();
    assert_eq!(
        stream.read_frame().unwrap_err(),
        UsbProbeError::BulkFrameHeaderTruncated
    );
    assert_eq!(stream.io().read_timeouts().last(), Some(&frame_timeout));
}

fn stream_with_reads(
    reads: Vec<Result<Vec<u8>, UsbProbeError>>,
) -> FramedUsbStream<TransferExactBulkIo> {
    FramedUsbStream::new(
        TransferExactBulkIo::with_reads(reads),
        FrameTransferBudget::new(Duration::from_millis(5), 65_536, 8).unwrap(),
    )
}

fn stream(transfers: Vec<Vec<u8>>) -> FramedUsbStream<TransferExactBulkIo> {
    FramedUsbStream::new(
        TransferExactBulkIo::with_transfers(transfers),
        FrameTransferBudget::new(Duration::from_millis(5), 65_536, 8).unwrap(),
    )
}
