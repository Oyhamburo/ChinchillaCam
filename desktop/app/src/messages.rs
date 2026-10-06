use usb_probe::{DesktopConnectionFailure, DesktopSessionEndReason, PhoneConnectionError};

pub fn session_end_notice(reason: &DesktopSessionEndReason) -> &'static str {
    match reason {
        DesktopSessionEndReason::LocalClose => "Desconectado.",
        DesktopSessionEndReason::PeerDead => "Se perdió la conexión con el teléfono.",
        DesktopSessionEndReason::Failed(_) => "La sesión terminó por un error.",
    }
}

pub fn connection_failure_notice(failure: &DesktopConnectionFailure) -> &'static str {
    match failure {
        DesktopConnectionFailure::Pairing(_) | DesktopConnectionFailure::ShortCode(_) => {
            "No se pudo vincular el teléfono."
        }
        DesktopConnectionFailure::PairingConfirmTimedOut => {
            "Se venció el tiempo para confirmar el código."
        }
        DesktopConnectionFailure::Link(_) => "No se pudo usar el USB. Revisá el cable.",
        DesktopConnectionFailure::Reconnect(error) => reconnect_notice(error),
        DesktopConnectionFailure::Pipeline(_)
        | DesktopConnectionFailure::PairingQr(_)
        | DesktopConnectionFailure::TrustedPhoneStore(_) => "Ocurrió un error. Intentá de nuevo.",
    }
}

fn reconnect_notice(error: &PhoneConnectionError) -> &'static str {
    match error {
        // TLS errors do not distinguish an unknown phone from a revoked one.
        PhoneConnectionError::Reconnect(_) => {
            "No se pudo reconectar el teléfono. Si no está vinculado, vinculalo primero."
        }
        PhoneConnectionError::Pairing(_)
        | PhoneConnectionError::UnexpectedFirstFrame
        | PhoneConnectionError::InvalidHello(_)
        | PhoneConnectionError::AcceptWriteFailed(_)
        | PhoneConnectionError::HelloDeviceIdMismatch
        | PhoneConnectionError::SessionSequenceOverflow(_)
        | PhoneConnectionError::PairingConfirm(_) => "No se pudo reconectar el teléfono.",
    }
}
