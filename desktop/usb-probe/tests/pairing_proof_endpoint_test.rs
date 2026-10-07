use usb_probe::{PairingProofEndpoint, PairingProofEndpointError};

#[test]
fn endpoint_matches_frozen_ccpb_golden() {
    let endpoint = PairingProofEndpoint::loopback(38443, 1500).unwrap();
    let encoded = endpoint.encode().unwrap();

    assert_eq!(
        hex(&encoded),
        "43435042010100093132372e302e302e31020002962b030004000005dc"
    );
    assert_eq!(PairingProofEndpoint::decode(&encoded).unwrap(), endpoint);
}

#[test]
fn rejects_invalid_endpoint_bounds_and_noncanonical_tlvs() {
    assert_eq!(
        PairingProofEndpoint::loopback(0, 1500),
        Err(PairingProofEndpointError::InvalidPort)
    );
    assert_eq!(
        PairingProofEndpoint::loopback(38443, 249),
        Err(PairingProofEndpointError::InvalidTimeout)
    );
    assert_eq!(
        PairingProofEndpoint::loopback(38443, 5001),
        Err(PairingProofEndpointError::InvalidTimeout)
    );

    let mut bad_magic = from_hex("43435042010100093132372e302e302e31020002962b030004000005dc");
    bad_magic[0] = 0;
    assert_eq!(
        PairingProofEndpoint::decode(&bad_magic),
        Err(PairingProofEndpointError::InvalidMagic)
    );

    let wrong_host = from_hex("43435042010100096c6f63616c686f7374020002962b030004000005dc");
    assert_eq!(
        PairingProofEndpoint::decode(&wrong_host),
        Err(PairingProofEndpointError::InvalidHost)
    );

    let out_of_order = from_hex("4343504201020002962b0100093132372e302e302e31030004000005dc");
    assert_eq!(
        PairingProofEndpoint::decode(&out_of_order),
        Err(PairingProofEndpointError::OutOfOrderTlv)
    );
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn from_hex(input: &str) -> Vec<u8> {
    input
        .as_bytes()
        .chunks(2)
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
        .collect()
}
