package dev.chinchillacam.usbprobe

/** A failure alert is separate from the foreground camera notification and survives its removal. */
data class ErrorNotificationSpec(val title: String, val text: String) {
    companion object {
        fun forFailure(status: VisibleCameraServiceStatus): ErrorNotificationSpec? {
            if (status.state != VisibleCameraServiceState.Error || status.cause == FailureCause.SessionLocalClose) return null
            val text = status.cause?.let(UserFailureCatalog::messageFor) ?: status.message
            if (text.isBlank()) return null
            return ErrorNotificationSpec("ChinchillaCam se detuvo", text)
        }
    }
}

/** Framework-free seam: stop/remove the foreground notification before posting a failure alert. */
class VisibleCameraFailureStop(
    private val stopForeground: () -> Unit,
    private val errorNotifier: (ErrorNotificationSpec) -> Unit,
) {
    fun stop(status: VisibleCameraServiceStatus) {
        stopForeground()
        ErrorNotificationSpec.forFailure(status)?.let(errorNotifier)
    }
}
