package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleCameraForegroundServiceActivityBindingTest {
    @Test
    fun recreatedActivityRendersServiceStartingAsStopAction() {
        val ui = VisibleCameraServiceActivityBindingPolicy.render(
            status = VisibleCameraServiceStatus(
                state = VisibleCameraServiceState.Starting,
                selectedCameraId = "camera-1",
                message = "Iniciando prueba local desde el servicio visible.",
            ),
        )

        assertEquals("Cámara local", ui.title)
        assertTrue(ui.detail.contains("servicio visible", ignoreCase = true))
        assertTrue(ui.detail.contains("Iniciando", ignoreCase = true))
        assertEquals("Detener cámara local", ui.primaryAction)
        assertTrue(ui.primaryActionEnabled)
    }

    @Test
    fun recreatedActivityRendersServiceRunningAsStopAction() {
        val ui = VisibleCameraServiceActivityBindingPolicy.render(
            VisibleCameraServiceStatus(
                state = VisibleCameraServiceState.Running,
                selectedCameraId = "camera-1",
                message = "Prueba local activa desde el servicio visible.",
            ),
        )

        assertTrue(ui.detail.contains("activa", ignoreCase = true))
        assertEquals("Detener cámara local", ui.primaryAction)
        assertTrue(ui.primaryActionEnabled)
    }

    @Test
    fun repeatedStartWhileServiceActiveDoesNotInvokeStarterAgain() {
        val starter = RecordingServiceStarter()
        val action = VisibleCameraServiceActivityBindingPolicy.actionForPrimaryClick(
            status = VisibleCameraServiceStatus(state = VisibleCameraServiceState.Running, selectedCameraId = "camera-1"),
        )

        action.apply(starter, selectedCameraId = "camera-1")

        assertEquals(0, starter.startCalls)
        assertEquals(1, starter.stopCalls)
    }

    @Test
    fun startWhenServiceAlreadyStartingDoesNotInvokeStarterAgain() {
        val starter = RecordingServiceStarter()
        val action = VisibleCameraServiceActivityBindingPolicy.actionForPrimaryClick(
            status = VisibleCameraServiceStatus(state = VisibleCameraServiceState.Starting, selectedCameraId = "camera-1"),
        )

        action.apply(starter, selectedCameraId = "camera-1")

        assertEquals(0, starter.startCalls)
        assertEquals(1, starter.stopCalls)
    }

    @Test
    fun stopFromRecreatedActivityClearsProcessLocalStatus() {
        VisibleCameraServiceStatusStore.publish(
            VisibleCameraServiceStatus(state = VisibleCameraServiceState.Running, selectedCameraId = "camera-1"),
        )
        val starter = RecordingServiceStarter()

        VisibleCameraServiceActivityBindingPolicy.actionForPrimaryClick(VisibleCameraServiceStatusStore.snapshot())
            .apply(starter, selectedCameraId = "camera-1")
        VisibleCameraServiceStatusStore.clearStopped()

        assertEquals(1, starter.stopCalls)
        assertEquals(VisibleCameraServiceState.Stopped, VisibleCameraServiceStatusStore.snapshot().state)
        assertNull(VisibleCameraServiceStatusStore.snapshot().selectedCameraId)
    }

    @Test
    fun processLocalStatusStoreDoesNotExposeActivityReference() {
        VisibleCameraServiceStatusStore.publish(
            VisibleCameraServiceStatus(state = VisibleCameraServiceState.Running, selectedCameraId = "camera-1"),
        )

        assertFalse(VisibleCameraServiceStatusStore.exposesActivityReference)
    }

    private class RecordingServiceStarter : VisibleCameraServiceActivityStarter {
        var startCalls: Int = 0
        var stopCalls: Int = 0

        override fun startVisibleCameraService(selectedCameraId: String) {
            startCalls += 1
        }

        override fun stopVisibleCameraService() {
            stopCalls += 1
        }
    }
}
