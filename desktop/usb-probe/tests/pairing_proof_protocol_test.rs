use usb_probe::{
    PairingProofFrame, PairingProofProtocolError, PairingProofRequest, PairingProofResponse,
};

const REQUEST: &str =
    "4343503101010000001f01000470632d3102000201020300041011121304000973657373696f6e2d31";
const RESPONSE: &str =
    "434350310102000000230000010001000470632d3102000201020300041011121304000973657373696f6e2d31";

#[test]
#[rustfmt::skip]
fn request_and_response_match_frozen_ccp1_goldens() {
    let request = PairingProofRequest::new("pc-1", vec![1, 2], vec![0x10, 0x11, 0x12, 0x13], "session-1").unwrap();
    let encoded_request = PairingProofFrame::request(request.clone()).encode().unwrap();
    assert_eq!(hex(&encoded_request), REQUEST);
    assert_eq!(PairingProofFrame::decode(&encoded_request).unwrap(), PairingProofFrame::request(request.clone()));

    let response = PairingProofResponse::ok(request.clone());
    let encoded_response = PairingProofFrame::response(response.clone()).encode().unwrap();
    assert_eq!(hex(&encoded_response), RESPONSE);
    assert_eq!(PairingProofFrame::decode(&encoded_response).unwrap(), PairingProofFrame::response(response));
    let rejection = PairingProofFrame::response(PairingProofResponse::new(1, request));
    assert_eq!(PairingProofFrame::decode(&rejection.encode().unwrap()).unwrap(), rejection);
}

#[test]
#[rustfmt::skip]
fn rejects_malformed_headers_and_payload_sizes() {
    assert_eq!(PairingProofRequest::new(&"x".repeat(65), vec![1], vec![2], "session"), Err(PairingProofProtocolError::PayloadTooLarge));
    assert_eq!(PairingProofRequest::new("pc-1", vec![1; 65], vec![2], "session"), Err(PairingProofProtocolError::PayloadTooLarge));
    assert_eq!(PairingProofRequest::new("pc 1", vec![1], vec![2], "session"), Err(PairingProofProtocolError::InvalidDesktopId));
    assert_eq!(PairingProofRequest::new("pc-ñ", vec![1], vec![2], "session"), Err(PairingProofProtocolError::InvalidDesktopId));
    let request = from_hex(REQUEST);
    let mut bad_magic = request.clone();
    bad_magic[0] = 0;
    assert_decode_err(&bad_magic, PairingProofProtocolError::InvalidMagic);
    let mut bad_version = request.clone();
    bad_version[4] = 2;
    assert_decode_err(&bad_version, PairingProofProtocolError::UnsupportedVersion);
    let mut truncated = request.clone();
    truncated.pop();
    assert_decode_err(&truncated, PairingProofProtocolError::Truncated);
    let mut oversize = request;
    oversize[6..10].copy_from_slice(&1025u32.to_be_bytes());
    assert_decode_err(&oversize, PairingProofProtocolError::PayloadTooLarge);
}

#[test]
#[rustfmt::skip]
fn rejects_noncanonical_tlvs_and_bad_response_status() {
    let mut duplicate = from_hex("4343503101010000002401000470632d31020002010202000201020300041011121304000973657373696f6e2d31");
    assert_decode_err(&duplicate, PairingProofProtocolError::DuplicateTlv);
    duplicate[10] = 9;
    assert_decode_err(&duplicate, PairingProofProtocolError::UnknownTlv);
    assert_decode_err(&from_hex("4343503101010000001f020002010201000470632d310300041011121304000973657373696f6e2d31"), PairingProofProtocolError::OutOfOrderTlv);
    assert_decode_err(&from_hex("4343503101010000001301000470632d31020002010203000410111213"), PairingProofProtocolError::MissingTlv);
    assert_decode_err(&from_hex("4343503101010000001c010001ff02000201020300041011121304000973657373696f6e2d31"), PairingProofProtocolError::InvalidUtf8);
}

fn assert_decode_err(bytes: &[u8], error: PairingProofProtocolError) {
    assert_eq!(PairingProofFrame::decode(bytes), Err(error));
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[rustfmt::skip]
fn from_hex(input: &str) -> Vec<u8> {
    input.as_bytes().chunks(2).map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap()).collect()
}
