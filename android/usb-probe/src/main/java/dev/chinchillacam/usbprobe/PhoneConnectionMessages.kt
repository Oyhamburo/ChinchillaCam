package dev.chinchillacam.usbprobe

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

    /** Maps a typed session-start/reconnect rejection to its Spanish message. */
    fun forRejection(rejection: UsbTrustedReconnectResult.Rejected): String = when (rejection) {
        is UsbTrustedReconnectResult.Rejected.NotTrusted -> DESKTOP_NOT_TRUSTED
        is UsbTrustedReconnectResult.Rejected.DesktopRejected -> DESKTOP_REJECTED
        is UsbTrustedReconnectResult.Rejected.TimedOut -> DESKTOP_TIMED_OUT
        is UsbTrustedReconnectResult.Rejected.ActivationRejected ->
            if (rejection.activation is ActiveDesktopAuthority.ActivationResult.Rejected.SecondActiveDesktop) SECOND_ACTIVE_DESKTOP else DESKTOP_NOT_TRUSTED
        is UsbTrustedReconnectResult.Rejected.TlsRejected,
        is UsbTrustedReconnectResult.Rejected.UnexpectedFrame,
        is UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept,
        is UsbTrustedReconnectResult.Rejected.ActivationFailed,
        -> CONNECTION_FAILED
    }
}
