package dev.chinchillacam.usbprobe

/** Stable, user-facing failure identity; platform diagnostics must not become UI copy. */
enum class FailureCause {
    CameraPermissionDenied, CameraUnavailable, CameraOpenFailed, EncoderFailed, CaptureFailed,
    VideoSendFailed, VideoSaturated, CameraStopFailed, SessionPeerDead, SessionReadFailed,
    SessionWriteFailed, SessionProtocolViolation, SessionLocalClose,
}

enum class RecoveryAction { None, Retry, RetryCamera, OpenAppSettings, PairAgain, Disconnect }

object UserFailureCatalog {
    fun messageFor(cause: FailureCause): String = when (cause) {
        FailureCause.CameraPermissionDenied -> "Para usar la cámara, permití el acceso desde los ajustes de la app."
        FailureCause.CameraUnavailable -> "Elegí una cámara disponible e intentá de nuevo."
        FailureCause.CameraOpenFailed -> "No se pudo abrir la cámara. Cerrá otras apps que la estén usando y reintentá."
        FailureCause.EncoderFailed -> "No se pudo preparar el video. Intentá iniciar la cámara de nuevo."
        FailureCause.CaptureFailed -> "No se pudo iniciar el video de la cámara. Intentá de nuevo."
        FailureCause.VideoSendFailed -> "No se pudo enviar el video. Revisá la conexión e intentá de nuevo."
        FailureCause.VideoSaturated -> "El envío de video se saturó. Intentá de nuevo."
        FailureCause.CameraStopFailed -> "No se pudo detener la cámara correctamente. Intentá de nuevo."
        FailureCause.SessionPeerDead -> "Se perdió la conexión con la computadora. Revisá el cable e intentá de nuevo."
        FailureCause.SessionReadFailed -> "Se interrumpió la conexión con la computadora. Intentá de nuevo."
        FailureCause.SessionWriteFailed -> "No se pudo enviar datos a la computadora. Intentá de nuevo."
        FailureCause.SessionProtocolViolation -> "La computadora y el teléfono no se entendieron. Actualizá ChinchillaCam en los dos e intentá de nuevo."
        FailureCause.SessionLocalClose -> "La sesión se cerró."
    }

    fun actionFor(cause: FailureCause): RecoveryAction = when (cause) {
        FailureCause.CameraPermissionDenied -> RecoveryAction.OpenAppSettings
        FailureCause.CameraUnavailable, FailureCause.CameraOpenFailed, FailureCause.EncoderFailed,
        FailureCause.CaptureFailed, FailureCause.CameraStopFailed -> RecoveryAction.RetryCamera
        FailureCause.SessionLocalClose -> RecoveryAction.None
        FailureCause.VideoSendFailed, FailureCause.VideoSaturated, FailureCause.SessionPeerDead,
        FailureCause.SessionReadFailed, FailureCause.SessionWriteFailed, FailureCause.SessionProtocolViolation -> RecoveryAction.Retry
    }

    fun causeFor(end: SessionEnd): FailureCause = when (end) {
        SessionEnd.PeerDead -> FailureCause.SessionPeerDead
        SessionEnd.Backpressure -> FailureCause.VideoSaturated
        is SessionEnd.ReadFailed -> FailureCause.SessionReadFailed
        is SessionEnd.WriteFailed -> FailureCause.SessionWriteFailed
        is SessionEnd.ProtocolViolation -> FailureCause.SessionProtocolViolation
        SessionEnd.LocalClose -> FailureCause.SessionLocalClose
    }
}
