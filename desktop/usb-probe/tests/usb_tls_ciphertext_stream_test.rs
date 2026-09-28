use std::io::{Read, Write};
use std::time::Duration;

use usb_probe::{
    BulkFrame, FrameTransferBudget, FramedUsbStream, RecordingUsbBulkIo, UsbProbeError,
    UsbTlsCiphertextStream, USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES, USB_TLS_CIPHERTEXT_STREAM_ID,
};

#[test]
fn reads_ciphertext_across_bulk_frame_boundaries() {
    let mut stream = ciphertext_stream(vec![
        Ok(frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID, b"abc")),
        Ok(frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID, b"def")),
    ]);

    let mut out = [0; 6];
    stream.read_exact(&mut out).unwrap();

    assert_eq!(&out, b"abcdef");
}

#[test]
fn read_returns_after_one_short_bulk_frame_without_filling_large_buffer() {
    let mut stream = ciphertext_stream(vec![
        Ok(frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID, b"abc")),
        Err(UsbProbeError::UsbBulkTransferFailed("would block".into())),
    ]);
    let mut out = vec![0; 65_536];

    let read = stream.read(&mut out).unwrap();

    assert_eq!(read, 3);
    assert_eq!(&out[..read], b"abc");
    assert_eq!(stream.framed_stream().io().source_read_attempts(), 1);
}

#[test]
fn writes_ciphertext_in_bounded_nonempty_chunks() {
    let mut stream = UsbTlsCiphertextStream::new(FramedUsbStream::new(
        RecordingUsbBulkIo::for_writes_with_max_chunk(usize::MAX),
        budget(),
    ));
    let mut bytes = vec![7; USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES + 2];

    assert_eq!(stream.write(&bytes).unwrap(), bytes.len());
    let written = stream.framed_stream().io().written_bytes();
    let first = BulkFrame::new(
        USB_TLS_CIPHERTEXT_STREAM_ID,
        bytes.drain(..USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES).collect(),
    )
    .unwrap()
    .encode();
    let second = BulkFrame::new(USB_TLS_CIPHERTEXT_STREAM_ID, bytes)
        .unwrap()
        .encode();

    assert_eq!(written, [first, second].concat());
}

#[test]
fn rejects_wrong_stream_empty_oversize_and_truncated_frames() {
    let cases = [
        frame_bytes(0x01020304, b"abc"),
        empty_frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID),
        oversize_header_bytes(USB_TLS_CIPHERTEXT_STREAM_ID),
        vec![1, 2, 3],
    ];

    for bytes in cases {
        let mut stream = ciphertext_stream(vec![Ok(bytes)]);
        let mut out = [0; 1];
        assert!(stream.read(&mut out).is_err());
    }
}

#[test]
fn read_validation_errors_poison_stream_for_later_reads_writes_and_flushes() {
    let cases = [
        frame_bytes(0x01020304, b"abc"),
        empty_frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID),
        oversize_header_bytes(USB_TLS_CIPHERTEXT_STREAM_ID),
        vec![1, 2, 3],
    ];

    for bytes in cases {
        let mut stream = ciphertext_stream(vec![
            Ok(bytes),
            Ok(frame_bytes(USB_TLS_CIPHERTEXT_STREAM_ID, b"valid")),
        ]);
        let mut out = [0; 5];

        assert!(stream.read(&mut out).is_err());
        assert!(stream.read(&mut out).is_err());
        assert!(stream.write(b"later plaintext").is_err());
        assert!(stream.flush().is_err());
    }
}

#[test]
fn wrong_stream_id_poisons_stream_for_later_write_and_flush() {
    let mut stream = ciphertext_stream(vec![Ok(frame_bytes(0x01020304, b"abc"))]);
    let mut out = [0; 1];

    assert!(stream.read(&mut out).is_err());
    assert!(stream.write(b"later plaintext").is_err());
    assert!(stream.flush().is_err());
}

#[test]
fn read_transport_error_poisons_stream_for_later_reads_writes_and_flushes() {
    let mut stream = ciphertext_stream(vec![Err(UsbProbeError::UsbBulkTransferFailed(
        "read failed".into(),
    ))]);
    let mut out = [0; 1];

    assert!(stream.read(&mut out).is_err());
    assert!(stream.read(&mut out).is_err());
    assert!(stream.write(b"later plaintext").is_err());
    assert!(stream.flush().is_err());
}

#[test]
fn accepts_max_ciphertext_chunk() {
    let payload = vec![9; USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES];
    let mut stream = ciphertext_stream(vec![Ok(frame_bytes(
        USB_TLS_CIPHERTEXT_STREAM_ID,
        &payload,
    ))]);
    let mut out = vec![0; USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES];

    stream.read_exact(&mut out).unwrap();

    assert_eq!(out, payload);
}

fn ciphertext_stream(
    chunks: Vec<Result<Vec<u8>, usb_probe::UsbProbeError>>,
) -> UsbTlsCiphertextStream<RecordingUsbBulkIo> {
    UsbTlsCiphertextStream::new(FramedUsbStream::new(
        RecordingUsbBulkIo::with_read_chunks(chunks),
        budget(),
    ))
}

fn budget() -> FrameTransferBudget {
    FrameTransferBudget::new(Duration::from_millis(10), 65_536, 16).unwrap()
}

fn frame_bytes(stream_id: u32, payload: &[u8]) -> Vec<u8> {
    BulkFrame::new(stream_id, payload.to_vec())
        .unwrap()
        .encode()
}

fn empty_frame_bytes(stream_id: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&stream_id.to_le_bytes());
    bytes.extend_from_slice(&0u32.to_le_bytes());
    bytes
}

fn oversize_header_bytes(stream_id: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&stream_id.to_le_bytes());
    bytes.extend_from_slice(&((USB_TLS_CIPHERTEXT_MAX_CHUNK_BYTES as u32) + 1).to_le_bytes());
    bytes
}
