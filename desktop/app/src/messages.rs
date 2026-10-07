use usb_probe::{
    DesktopConnectionFailure, DesktopSessionEndReason, PairedPhoneCandidateConfirmError,
    PhoneConnectionError, PhoneLinkError, SessionEnd, UsbProbeError, UsbTlsPairingProofError,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UserNotice {
    pub message: &'static str,
    pub hint: Option<&'static str>,
}

const fn notice(message: &'static str, hint: &'static str) -> UserNotice {
    UserNotice {
        message,
        hint: Some(hint),
    }
}

pub fn session_end_notice(reason: &DesktopSessionEndReason) -> UserNotice {
    match reason {
        DesktopSessionEndReason::LocalClose
        | DesktopSessionEndReason::Failed(SessionEnd::LocalClose) => UserNotice {
            message: "Desconectado.",
            hint: None,
        },
        DesktopSessionEndReason::PeerDead
        | DesktopSessionEndReason::Failed(SessionEnd::PeerDead) => notice(
            "Se perdió la conexión con el teléfono.",
            "Revisá el cable y desbloqueá el teléfono.",
        ),
        DesktopSessionEndReason::Failed(end) => match end {
            SessionEnd::Backpressure => notice(
                "La conexión está saturada.",
                "Cerrá otras apps y volvé a conectar el teléfono.",
            ),
            SessionEnd::ReadFailed(_) | SessionEnd::WriteFailed(_) => notice(
                "Se interrumpió la conexión con el teléfono.",
                "Revisá el cable y volvé a conectarlo.",
            ),
            SessionEnd::ProtocolViolation(_) => notice(
                "Las versiones no coinciden.",
                "Actualizá ChinchillaCam en los dos dispositivos.",
            ),
            SessionEnd::DecoderFailed(_) => video_notice(),
            SessionEnd::PeerDead | SessionEnd::LocalClose => unreachable!("handled above"),
        },
    }
}

fn video_notice() -> UserNotice {
    notice(
        "No se pudo mostrar el video.",
        "Volvé a conectar el teléfono e intentá de nuevo.",
    )
}

pub fn connection_failure_notice(failure: &DesktopConnectionFailure) -> UserNotice {
    match failure {
        DesktopConnectionFailure::Link(error) => link_notice(error),
        DesktopConnectionFailure::Reconnect(error) => reconnect_notice(error),
        DesktopConnectionFailure::Pairing(error) => pairing_notice(error),
        DesktopConnectionFailure::PairingConfirmTimedOut => notice(
            "Se venció el tiempo para confirmar el código.",
            "Volvé a vincular el teléfono y confirmá el código a tiempo.",
        ),
        DesktopConnectionFailure::ShortCode(_) => notice(
            "No coinciden los códigos de vinculación.",
            "Volvé a escanear el código y compará los números.",
        ),
        DesktopConnectionFailure::Pipeline(_) => video_notice(),
        DesktopConnectionFailure::PairingQr(_) => notice(
            "No se pudo crear el código para vincular.",
            "Intentá vincular el teléfono de nuevo.",
        ),
        DesktopConnectionFailure::TrustedPhoneStore(_) => notice(
            "No se pudo guardar el teléfono vinculado.",
            "Revisá el espacio disponible e intentá de nuevo.",
        ),
    }
}

fn link_notice(error: &PhoneLinkError) -> UserNotice {
    match error {
        PhoneLinkError::NoPhoneFound
        | PhoneLinkError::Usb(UsbProbeError::SelectedDeviceNotFound(_)) => notice(
            "No encontramos el teléfono.",
            "Conectá el teléfono con el cable y abrí ChinchillaCam en el teléfono.",
        ),
        PhoneLinkError::Switch(_) => notice(
            "No se pudo preparar el teléfono para la conexión.",
            "Desbloqueá el teléfono y aceptá el aviso de USB.",
        ),
        PhoneLinkError::Open(error) | PhoneLinkError::Usb(error) if is_claim_error(error) => {
            notice(
                "Otra app está usando el teléfono por USB.",
                "Cerrá la otra app o permití el acceso al teléfono e intentá de nuevo.",
            )
        }
        PhoneLinkError::Scan(_) => notice(
            "No se pudo buscar el teléfono.",
            "Revisá el cable y el puerto e intentá de nuevo.",
        ),
        PhoneLinkError::Open(_) | PhoneLinkError::Usb(_) => notice(
            "No se pudo abrir la conexión con el teléfono.",
            "Probá con otro cable o puerto e intentá de nuevo.",
        ),
    }
}

fn is_claim_error(error: &UsbProbeError) -> bool {
    matches!(
        error,
        UsbProbeError::BulkInterfaceClaimFailed(_)
            | UsbProbeError::BulkInterfaceNotClaimed
            | UsbProbeError::BulkInterfaceNotFound
            | UsbProbeError::BulkInterfaceAmbiguous
            | UsbProbeError::BulkAlternateSettingFailed(_)
    )
}

fn reconnect_notice(error: &PhoneConnectionError) -> UserNotice {
    match error {
        PhoneConnectionError::Reconnect(UsbTlsPairingProofError::Timeout)
        | PhoneConnectionError::InvalidHello(usb_probe::TlsSessionFrameError::Timeout) => notice(
            "El teléfono tardó demasiado en responder.",
            "Desbloqueá el teléfono y volvé a conectarlo.",
        ),
        // An unknown or revoked phone is rejected inside the handshake. Its current error is
        // only a TLS string; neither case can be identified reliably from that string.
        PhoneConnectionError::Reconnect(UsbTlsPairingProofError::Tls(_))
        | PhoneConnectionError::HelloDeviceIdMismatch => notice(
            "No se pudo comprobar la identidad del teléfono.",
            "Si no está vinculado, vinculalo con el QR.",
        ),
        PhoneConnectionError::Reconnect(UsbTlsPairingProofError::Io(_))
        | PhoneConnectionError::AcceptWriteFailed(_) => notice(
            "Se interrumpió la conexión con el teléfono.",
            "Revisá el cable y volvé a conectarlo.",
        ),
        PhoneConnectionError::Reconnect(
            UsbTlsPairingProofError::InvalidProof | UsbTlsPairingProofError::Issuer(_),
        )
        | PhoneConnectionError::InvalidHello(_)
        | PhoneConnectionError::UnexpectedFirstFrame
        | PhoneConnectionError::SessionSequenceOverflow(_) => notice(
            "No se pudo reconocer al teléfono.",
            "Volvé a vincularlo con el QR.",
        ),
        PhoneConnectionError::Pairing(_) | PhoneConnectionError::PairingConfirm(_) => {
            pairing_notice(error)
        }
    }
}

fn pairing_notice(error: &PhoneConnectionError) -> UserNotice {
    match error {
        PhoneConnectionError::PairingConfirm(PairedPhoneCandidateConfirmError::PhoneRevoked) => {
            notice("Este teléfono no está vinculado.", "Vinculalo con el QR.")
        }
        PhoneConnectionError::PairingConfirm(PairedPhoneCandidateConfirmError::Store(_)) => notice(
            "No se pudo guardar el teléfono vinculado.",
            "Revisá el espacio disponible e intentá de nuevo.",
        ),
        PhoneConnectionError::Pairing(UsbTlsPairingProofError::Timeout) => notice(
            "Se venció el tiempo para vincular el teléfono.",
            "Volvé a escanear el código.",
        ),
        PhoneConnectionError::Pairing(UsbTlsPairingProofError::InvalidProof) => notice(
            "No coinciden los datos de vinculación.",
            "Volvé a escanear el código y confirmá el código en ambos dispositivos.",
        ),
        PhoneConnectionError::Pairing(UsbTlsPairingProofError::Issuer(_)) => notice(
            "No se pudo crear el código para vincular.",
            "Intentá vincular el teléfono de nuevo.",
        ),
        PhoneConnectionError::Pairing(
            UsbTlsPairingProofError::Tls(_) | UsbTlsPairingProofError::Io(_),
        )
        | PhoneConnectionError::UnexpectedFirstFrame
        | PhoneConnectionError::InvalidHello(_)
        | PhoneConnectionError::AcceptWriteFailed(_)
        | PhoneConnectionError::HelloDeviceIdMismatch
        | PhoneConnectionError::SessionSequenceOverflow(_)
        | PhoneConnectionError::Reconnect(_) => notice(
            "No se pudo vincular el teléfono.",
            "Revisá el cable y volvé a escanear el código.",
        ),
    }
}
