package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorNotificationSpecTest {
    @Test
    fun failure_uses_catalog_message_for_each_session_cause() {
        listOf(FailureCause.SessionPeerDead, FailureCause.SessionReadFailed,
            FailureCause.SessionWriteFailed, FailureCause.SessionProtocolViolation, FailureCause.VideoSaturated).forEach { cause ->
            assertEquals(ErrorNotificationSpec("ChinchillaCam se detuvo", UserFailureCatalog.messageFor(cause)),
                ErrorNotificationSpec.forFailure(VisibleCameraServiceStatus(
                    VisibleCameraServiceState.Error, message = "raw platform error", cause = cause)))
        }
    }

    @Test
    fun non_error_and_local_close_do_not_notify() {
        assertNull(ErrorNotificationSpec.forFailure(VisibleCameraServiceStatus(VisibleCameraServiceState.Stopped)))
        assertNull(ErrorNotificationSpec.forFailure(VisibleCameraServiceStatus(VisibleCameraServiceState.Stopping,
            message = "Stopping", cause = FailureCause.SessionPeerDead)))
        assertNull(ErrorNotificationSpec.forFailure(VisibleCameraServiceStatus(VisibleCameraServiceState.Error,
            message = "La sesión se cerró.", cause = FailureCause.SessionLocalClose)))
    }

    @Test
    fun untyped_failure_uses_visible_spanish_message() {
        assertEquals("No hay una sesión activa con la computadora.", ErrorNotificationSpec.forFailure(
            VisibleCameraServiceStatus(VisibleCameraServiceState.Error,
                message = "No hay una sesión activa con la computadora."))?.text)
    }
}
