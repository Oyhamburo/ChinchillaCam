use chinchillacam_app::view_model::{AppAction, AppState};
use usb_probe::{
    pairing_short_code_v1, DesktopConnectionFailure as Failure, DesktopEvent as Event,
    DesktopMetricsSnapshot, DesktopSessionEndReason as End, PairedPhoneCandidateConfirmError,
    PairingQrIssuerError, PhoneConnectionError, PhoneLinkError, SessionEnd, ShortCodeError,
    TrustedPhoneStoreError, TrustedPhoneSummary, UsbProbeError, UsbTlsPairingProofError,
};

#[test]
fn pairing_view_shows_qr_and_code() {
    let mut state = AppState::default();
    state.apply(Event::PairingQr {
        text: "QR-v1".into(),
        expires_at_epoch_seconds: 120,
    });
    let qr = state.view(100);
    assert_eq!(qr.qr_text.as_deref(), Some("QR-v1"));
    assert_eq!(qr.qr_seconds_left, Some(20));
    assert_eq!(qr.actions, vec![AppAction::CancelPairing]);
    assert_eq!(
        qr.status,
        "Escaneá este código con ChinchillaCam en el teléfono."
    );

    let desktop = (0..91u8).map(|i| 0x10 + i).collect::<Vec<_>>();
    let phone = (0..91u8).map(|i| 0x80 + i).collect::<Vec<_>>();
    let code = pairing_short_code_v1(&desktop, &phone, &[0xA5; 16], &[0x5A; 32]).unwrap();
    state.apply(Event::ConfirmCode {
        phone_id: "id".into(),
        code,
    });
    let confirm = state.view(101);
    assert_eq!(confirm.confirm_code.as_deref(), Some("841 406"));
    assert_eq!(
        confirm.actions,
        vec![AppAction::ConfirmPairing, AppAction::RejectPairing]
    );
    assert_eq!(confirm.status, "¿El código coincide con el del teléfono?");
    assert_eq!(state.confirm_label(), "Teléfono");
}

#[test]
fn waiting_view_offers_pairing() {
    let view = AppState::default().view(0);
    assert_eq!(view.status, "Esperando el teléfono por USB.");
    assert_eq!(view.actions, vec![AppAction::StartPairing]);
    assert_eq!(AppAction::StartPairing.label(), "Vincular un teléfono");
    assert_eq!(AppAction::CancelPairing.label(), "Cancelar");
    assert_eq!(AppAction::ConfirmPairing.label(), "Coincide");
    assert_eq!(AppAction::RejectPairing.label(), "No coincide");
    assert_eq!(AppAction::Disconnect.label(), "Desconectar");
}

#[test]
fn connected_view_shows_label_metrics_and_disconnect() {
    let mut state = AppState::default();
    state.apply(Event::Connected {
        phone_id: "id".into(),
        label: Some("Pixel".into()),
    });
    state.apply(Event::Metrics(DesktopMetricsSnapshot {
        arrival_fps: Some(29.5),
        decoded_fps: None,
        total_chunks: 30,
        total_bytes: 1234,
        dropped_chunks: 2,
        dropped_bytes: 10,
        phone_reported: None,
    }));
    let view = state.view(0);
    assert_eq!(view.status, "Conectado a «Pixel».");
    assert_eq!(view.actions, vec![AppAction::Disconnect]);
    let metrics = view.metrics.unwrap();
    assert_eq!(metrics.fps, "29.5");
    assert_eq!(metrics.decoded_fps, "—");
    assert_eq!(metrics.received, "30");
    assert_eq!(metrics.dropped, "2");
    state.apply(Event::Connected {
        phone_id: "id".into(),
        label: None,
    });
    assert_eq!(state.view(0).status, "Conectado a «el teléfono».");
}

#[test]
fn session_end_returns_to_waiting_with_notice() {
    let mut state = AppState::default();
    for (end, notice) in [
        (End::LocalClose, "Desconectado."),
        (End::PeerDead, "Se perdió la conexión con el teléfono."),
    ] {
        state.apply(Event::SessionEnded(end));
        let view = state.view(0);
        assert_eq!(view.status, "Esperando el teléfono por USB.");
        assert_eq!(view.notice.as_deref(), Some(notice));
        assert_eq!(view.actions, vec![AppAction::StartPairing]);
    }
}

#[test]
fn failures_map_to_spanish_notices() {
    let mut state = AppState::default();
    for (failure, notice) in [
        (
            Failure::PairingConfirmTimedOut,
            "Se venció el tiempo para confirmar el código.",
        ),
        (
            Failure::Pairing(PhoneConnectionError::Pairing(
                UsbTlsPairingProofError::InvalidProof,
            )),
            "No coinciden los datos de vinculación.",
        ),
        (
            Failure::Link(PhoneLinkError::Usb(UsbProbeError::BulkReadTimeout)),
            "No se pudo abrir la conexión con el teléfono.",
        ),
        (
            Failure::Reconnect(PhoneConnectionError::Reconnect(
                UsbTlsPairingProofError::Timeout,
            )),
            "El teléfono tardó demasiado en responder.",
        ),
    ] {
        state.apply(Event::ConnectionFailed(failure));
        assert_eq!(state.view(0).notice.as_deref(), Some(notice));
    }
}

#[test]
fn link_failures_distinguish_missing_phone_from_device_error() {
    let mut state = AppState::default();
    state.apply(Event::ConnectionFailed(Failure::Link(PhoneLinkError::Usb(
        UsbProbeError::SelectedDeviceNotFound(usb_probe::DeviceIdentifier::VidPid {
            vendor_id: 0x04e8,
            product_id: 0x6860,
        }),
    ))));
    let missing = state.view(0);
    assert_eq!(
        missing.notice.as_deref(),
        Some("No encontramos el teléfono.")
    );
    assert_eq!(
        missing.hint.as_deref(),
        Some("Conectá el teléfono con el cable y abrí ChinchillaCam en el teléfono.")
    );
    state.apply(Event::ConnectionFailed(Failure::Link(PhoneLinkError::Usb(
        UsbProbeError::BulkInterfaceClaimFailed("busy".into()),
    ))));
    let busy = state.view(0);
    assert_eq!(
        busy.notice.as_deref(),
        Some("Otra app está usando el teléfono por USB.")
    );
    assert_ne!(missing.notice, busy.notice);
}

#[test]
fn cause_catalog_has_actionable_notices_without_diagnostics() {
    let cases = [
        (
            Failure::Link(PhoneLinkError::NoPhoneFound),
            "No encontramos el teléfono.",
        ),
        (
            Failure::Link(PhoneLinkError::Switch(
                UsbProbeError::AoaReenumerationTimedOut,
            )),
            "No se pudo preparar el teléfono para la conexión.",
        ),
        (
            Failure::Link(PhoneLinkError::Scan(
                UsbProbeError::UsbControlTransferFailed("denied".into()),
            )),
            "No se pudo buscar el teléfono.",
        ),
        (
            Failure::Link(PhoneLinkError::Open(
                UsbProbeError::BulkInterfaceClaimFailed("busy".into()),
            )),
            "Otra app está usando el teléfono por USB.",
        ),
        (
            Failure::Link(PhoneLinkError::Open(UsbProbeError::UsbBulkTransferFailed(
                "io".into(),
            ))),
            "No se pudo abrir la conexión con el teléfono.",
        ),
        (
            Failure::Reconnect(PhoneConnectionError::Reconnect(
                UsbTlsPairingProofError::Tls("unknown".into()),
            )),
            "No se pudo comprobar la identidad del teléfono.",
        ),
        (
            Failure::Reconnect(PhoneConnectionError::HelloDeviceIdMismatch),
            "No se pudo comprobar la identidad del teléfono.",
        ),
        (
            Failure::Reconnect(PhoneConnectionError::InvalidHello(
                usb_probe::TlsSessionFrameError::Timeout,
            )),
            "El teléfono tardó demasiado en responder.",
        ),
        (
            Failure::Pairing(PhoneConnectionError::PairingConfirm(
                PairedPhoneCandidateConfirmError::PhoneRevoked,
            )),
            "Este teléfono no está vinculado.",
        ),
        (
            Failure::PairingConfirmTimedOut,
            "Se venció el tiempo para confirmar el código.",
        ),
        (
            Failure::ShortCode(ShortCodeError::EmptyInput("code")),
            "No coinciden los códigos de vinculación.",
        ),
        (
            Failure::PairingQr(PairingQrIssuerError::RandomFailed),
            "No se pudo crear el código para vincular.",
        ),
        (
            Failure::Pipeline(usb_probe::DesktopSessionPipelineError::Metrics(
                usb_probe::DesktopMetricsError::InvalidWindow,
            )),
            "No se pudo mostrar el video.",
        ),
        (
            Failure::TrustedPhoneStore(TrustedPhoneStoreError::Io("disk".into())),
            "No se pudo guardar el teléfono vinculado.",
        ),
    ];
    let mut state = AppState::default();
    for (cause, message) in cases {
        state.apply(Event::ConnectionFailed(cause));
        let view = state.view(1);
        assert_eq!(view.notice.as_deref(), Some(message));
        let hint = view.hint.expect("actionable hint");
        for text in [message, hint.as_str()] {
            for diagnostic in ["Error:", "TLS", "USB claim", "failed"] {
                assert!(!text.contains(diagnostic), "{text}");
            }
        }
    }
}

#[test]
fn session_end_causes_have_distinct_actionable_notices() {
    let cases = [
        (End::PeerDead, "Se perdió la conexión con el teléfono."),
        (
            End::Failed(SessionEnd::Backpressure),
            "La conexión está saturada.",
        ),
        (
            End::Failed(SessionEnd::ReadFailed("x".into())),
            "Se interrumpió la conexión con el teléfono.",
        ),
        (
            End::Failed(SessionEnd::WriteFailed("x".into())),
            "Se interrumpió la conexión con el teléfono.",
        ),
        (
            End::Failed(SessionEnd::ProtocolViolation("x".into())),
            "Las versiones no coinciden.",
        ),
        (
            End::Failed(SessionEnd::DecoderFailed("x".into())),
            "No se pudo mostrar el video.",
        ),
    ];
    let mut state = AppState::default();
    for (cause, expected) in cases {
        state.apply(Event::SessionEnded(cause));
        let view = state.view(0);
        assert_eq!(view.notice.as_deref(), Some(expected));
        assert!(view.hint.is_some());
    }
}

#[test]
fn repeated_link_failure_does_not_replace_notice_and_success_clears_it() {
    let mut state = AppState::default();
    let missing = || Event::ConnectionFailed(Failure::Link(PhoneLinkError::NoPhoneFound));
    state.apply(missing());
    let first = state.view(0);
    state.apply(missing());
    assert_eq!(state.view(10), first);
    state.apply(Event::ConnectionFailed(Failure::Link(
        PhoneLinkError::Switch(UsbProbeError::AoaReenumerationTimedOut),
    )));
    assert_ne!(state.view(10).notice, first.notice);
    state.apply(Event::Connected {
        phone_id: "a".into(),
        label: None,
    });
    assert!(state.view(10).notice.is_none());
    assert!(state.view(10).hint.is_none());
}

#[test]
fn trusted_phones_rows_and_empty_text() {
    let mut state = AppState::default();
    assert_eq!(
        state.view(0).empty_phones_text,
        "Todavía no hay teléfonos vinculados."
    );
    state.apply(Event::TrustedPhones(vec![TrustedPhoneSummary {
        phone_id: "abc".into(),
        label: "Mi Pixel".into(),
        revoked: true,
    }]));
    let view = state.view(0);
    assert_eq!(view.phones[0].phone_id, "abc");
    assert_eq!(view.phones[0].label, "Mi Pixel");
    assert!(view.phones[0].revoked);
    assert!(view.phones[0].can_forget);
}

#[test]
fn qr_seconds_left_counts_down() {
    let mut state = AppState::default();
    state.apply(Event::PairingQr {
        text: "QR".into(),
        expires_at_epoch_seconds: 10,
    });
    assert_eq!(state.view(9).qr_seconds_left, Some(1));
    assert_eq!(state.view(10).qr_seconds_left, Some(0));
    assert_eq!(state.view(11).qr_seconds_left, Some(0));
    state.apply(Event::PairingCancelled);
    assert_eq!(state.view(11).qr_text, None);
}

#[test]
fn failures_keep_the_pairing_qr_but_end_pending_flows() {
    let mut state = AppState::default();
    state.apply(Event::PairingQr {
        text: "CHINCHILLACAM-PAIR:v1:x".into(),
        expires_at_epoch_seconds: 160,
    });
    state.apply(Event::ConnectionFailed(Failure::Link(PhoneLinkError::Usb(
        UsbProbeError::BulkReadTimeout,
    ))));
    let view = state.view(100);
    assert_eq!(view.qr_text.as_deref(), Some("CHINCHILLACAM-PAIR:v1:x"));
    assert_eq!(view.actions, vec![AppAction::CancelPairing]);
    assert_eq!(
        view.notice.as_deref(),
        Some("No se pudo abrir la conexión con el teléfono.")
    );

    state.apply(Event::Connecting);
    state.apply(Event::ConnectionFailed(Failure::PairingConfirmTimedOut));
    let view = state.view(100);
    assert_eq!(view.qr_text, None);
    assert_eq!(view.actions, vec![AppAction::StartPairing]);
}
