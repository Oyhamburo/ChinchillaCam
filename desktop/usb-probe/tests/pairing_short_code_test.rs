//! Task d1 (`odd/tasks/desktop-production-app.md`, contract section 4.1): the pairing
//! short authentication string (SAS v1). The fixed vector is shared with Android
//! `PairingShortCode` and was computed independently in Python.

use usb_probe::{pairing_short_code_v1, PairingShortCode, ShortCodeError};

fn vector_inputs() -> (Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>) {
    let desktop_spki = (0..91u8).map(|i| 0x10 + i).collect();
    let phone_spki = (0..91u8).map(|i| 0x80 + i).collect();
    (desktop_spki, phone_spki, vec![0xA5; 16], vec![0x5A; 32])
}

#[test]
fn short_code_matches_android_vector() {
    let (desktop_spki, phone_spki, qr_nonce, challenge_nonce) = vector_inputs();

    let code =
        pairing_short_code_v1(&desktop_spki, &phone_spki, &qr_nonce, &challenge_nonce).unwrap();

    assert_eq!(code.digits(), "841406");
    assert_eq!(code.display(), "841 406");
}

#[test]
fn swapping_spki_roles_changes_code() {
    let (desktop_spki, phone_spki, qr_nonce, challenge_nonce) = vector_inputs();

    let code =
        pairing_short_code_v1(&phone_spki, &desktop_spki, &qr_nonce, &challenge_nonce).unwrap();

    assert_eq!(code.digits(), "418534");
    assert_eq!(code.display(), "418 534");
}

#[test]
fn leading_zeros_are_padded() {
    let small = PairingShortCode::from_value(7);
    assert_eq!(small.digits(), "000007");
    assert_eq!(small.display(), "000 007");

    let medium = PairingShortCode::from_value(41_406);
    assert_eq!(medium.digits(), "041406");
    assert_eq!(medium.display(), "041 406");
}

#[test]
fn empty_input_is_rejected() {
    let (desktop_spki, phone_spki, qr_nonce, challenge_nonce) = vector_inputs();
    let names = ["desktop_spki", "phone_spki", "qr_nonce", "challenge_nonce"];

    for (empty_index, name) in names.into_iter().enumerate() {
        let mut inputs = [
            &desktop_spki[..],
            &phone_spki[..],
            &qr_nonce[..],
            &challenge_nonce[..],
        ];
        inputs[empty_index] = &[];
        assert_eq!(
            pairing_short_code_v1(inputs[0], inputs[1], inputs[2], inputs[3]),
            Err(ShortCodeError::EmptyInput(name))
        );
    }
}
