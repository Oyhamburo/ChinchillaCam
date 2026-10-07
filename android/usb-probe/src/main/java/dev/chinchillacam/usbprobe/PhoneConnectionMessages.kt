package dev.chinchillacam.usbprobe

/** Stable connection failure identity; the screen never interprets Spanish copy. */
enum class ConnectionFailureKind {
    QR_INVALID, QR_EXPIRED, USB_PERMISSION_DENIED, USB_OPEN_FAILED, USB_DETACHED,
    PAIRING_FAILED, SECOND_ACTIVE_DESKTOP, DESKTOP_REJECTED, DESKTOP_TIMED_OUT,
    DESKTOP_NOT_TRUSTED, RECONNECT_TLS_REJECTED, CONNECTION_FAILED, SESSION_START_FAILED, FORGET_FAILED,
}

/** User-facing Spanish texts emitted by [PhoneConnectionController] (task c3). */
object PhoneConnectionMessages {
    const val QR_INVALID = "Código QR no válido."
    const val QR_EXPIRED = "El código QR venció. Generá uno nuevo en la computadora."
    const val CONNECT_CABLE = "Conectá el cable USB a la computadora."
    const val USB_PERMISSION_DENIED = "Sin permiso para usar el USB. Volvé a conectar el cable y aceptá el permiso."
    const val USB_OPEN_FAILED = "No se pudo abrir la conexión USB."
    const val USB_DETACHED = "Se desconectó el cable USB."
    const val PAIRING_FAILED = "No se pudo vincular con la computadora. Probá de nuevo."
    const val SECOND_ACTIVE_DESKTOP = "Ya hay otra computadora conectada."
    const val DESKTOP_REJECTED = "La computadora rechazó la conexión."
    const val DESKTOP_TIMED_OUT = "La computadora no respondió a tiempo."
    const val DESKTOP_NOT_TRUSTED = "Esta computadora ya no es confiable. Vinculala de nuevo."
    const val CONNECTION_FAILED = "No se pudo conectar con la computadora."
    const val SESSION_START_FAILED = "No se pudo iniciar la transmisión."
    const val FORGET_FAILED = "No se pudo olvidar la computadora."
    const val DISCONNECTED = "Desconectado."

    fun messageFor(kind: ConnectionFailureKind): String = when (kind) {
        ConnectionFailureKind.QR_INVALID -> QR_INVALID
        ConnectionFailureKind.QR_EXPIRED -> QR_EXPIRED
        ConnectionFailureKind.USB_PERMISSION_DENIED -> USB_PERMISSION_DENIED
        ConnectionFailureKind.USB_OPEN_FAILED -> USB_OPEN_FAILED
        ConnectionFailureKind.USB_DETACHED -> USB_DETACHED
        ConnectionFailureKind.PAIRING_FAILED -> PAIRING_FAILED
        ConnectionFailureKind.SECOND_ACTIVE_DESKTOP -> SECOND_ACTIVE_DESKTOP
        ConnectionFailureKind.DESKTOP_REJECTED -> DESKTOP_REJECTED
        ConnectionFailureKind.DESKTOP_TIMED_OUT -> DESKTOP_TIMED_OUT
        ConnectionFailureKind.DESKTOP_NOT_TRUSTED -> DESKTOP_NOT_TRUSTED
        ConnectionFailureKind.RECONNECT_TLS_REJECTED -> CONNECTION_FAILED
        ConnectionFailureKind.CONNECTION_FAILED -> CONNECTION_FAILED
        ConnectionFailureKind.SESSION_START_FAILED -> SESSION_START_FAILED
        ConnectionFailureKind.FORGET_FAILED -> FORGET_FAILED
    }

    /** Keep the original Spanish message API for existing callers. */
    fun forRejection(rejection: UsbTrustedReconnectResult.Rejected): String = messageFor(kindForRejection(rejection))

    fun kindForRejection(rejection: UsbTrustedReconnectResult.Rejected): ConnectionFailureKind = when (rejection) {
        is UsbTrustedReconnectResult.Rejected.NotTrusted -> ConnectionFailureKind.DESKTOP_NOT_TRUSTED
        is UsbTrustedReconnectResult.Rejected.DesktopRejected -> ConnectionFailureKind.DESKTOP_REJECTED
        is UsbTrustedReconnectResult.Rejected.TimedOut -> ConnectionFailureKind.DESKTOP_TIMED_OUT
        is UsbTrustedReconnectResult.Rejected.ActivationRejected ->
            if (rejection.activation is ActiveDesktopAuthority.ActivationResult.Rejected.SecondActiveDesktop)
                ConnectionFailureKind.SECOND_ACTIVE_DESKTOP else ConnectionFailureKind.DESKTOP_NOT_TRUSTED
        // TLS pin rejection includes fingerprint changes; no stable typed TLS subreason is available here.
        is UsbTrustedReconnectResult.Rejected.TlsRejected -> ConnectionFailureKind.RECONNECT_TLS_REJECTED
        is UsbTrustedReconnectResult.Rejected.UnexpectedFrame,
        is UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept,
        is UsbTrustedReconnectResult.Rejected.ActivationFailed,
        -> ConnectionFailureKind.CONNECTION_FAILED
    }
}
