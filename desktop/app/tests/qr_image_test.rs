use chinchillacam_app::qr_image::{qr_modules, qr_rgba};

#[test]
fn qr_image_has_quiet_zone_and_finder_patterns() {
    let modules = qr_modules("CHINCHILLACAM-PAIR:test").unwrap();
    let (width, height, pixels) = qr_rgba("CHINCHILLACAM-PAIR:test", 1, 4).unwrap();
    assert_eq!((width, height), (modules.size + 8, modules.size + 8));
    let pixel = |x: usize, y: usize| &pixels[(y * width + x) * 4..][..4];
    for (x, y) in [
        (0, 0),
        (width - 1, 0),
        (0, height - 1),
        (width - 1, height - 1),
        (3, 4),
    ] {
        assert_eq!(pixel(x, y), &[255, 255, 255, 255]);
    }
    for (ox, oy) in [(4, 4), (4 + modules.size - 7, 4), (4, 4 + modules.size - 7)] {
        for y in 0..7 {
            for x in 0..7 {
                let dark = x == 0
                    || x == 6
                    || y == 0
                    || y == 6
                    || (2..=4).contains(&x) && (2..=4).contains(&y);
                assert_eq!(
                    pixel(ox + x, oy + y),
                    if dark {
                        &[0, 0, 0, 255]
                    } else {
                        &[255, 255, 255, 255]
                    }
                );
            }
        }
    }
}

#[test]
fn qr_image_scales_modules() {
    let text = "CHINCHILLACAM-PAIR:test";
    let (width, height, pixels) = qr_rgba(text, 3, 4).unwrap();
    let (base_width, base_height, base) = qr_rgba(text, 1, 4).unwrap();
    assert_eq!((width, height), (base_width * 3, base_height * 3));
    for y in 0..base_height {
        for x in 0..base_width {
            for dy in 0..3 {
                for dx in 0..3 {
                    assert_eq!(
                        &pixels[((y * 3 + dy) * width + x * 3 + dx) * 4..][..4],
                        &base[(y * base_width + x) * 4..][..4]
                    );
                }
            }
        }
    }
}

#[test]
fn qr_image_rejects_invalid_dimensions() {
    assert!(qr_rgba("test", 0, 4).is_err());
    assert!(qr_rgba("test", 6, usize::MAX).is_err());
}

#[test]
fn pairing_text_fits_in_a_qr() {
    let identity = usb_probe::DesktopTlsIdentity::generate_ephemeral("Mi computadora").unwrap();
    let payload = usb_probe::PairingQrPayload::new(
        "a".repeat(64),
        "Mi computadora",
        1_800_000_000,
        vec![42; 16],
        identity.qr_trust_material().to_vec(),
    )
    .unwrap();
    let text =
        String::from_utf8(usb_probe::PairingQrProducer::encode_v1(&payload).unwrap()).unwrap();
    assert!(text.len() > 250);
    let modules = qr_modules(&text).unwrap();
    assert_eq!(modules.dark.len(), modules.size * modules.size);
    assert!(modules.size > 21);
}
