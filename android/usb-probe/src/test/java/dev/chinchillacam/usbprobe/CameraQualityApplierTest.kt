package dev.chinchillacam.usbprobe

import org.junit.Assert.*
import org.junit.Test

class CameraQualityApplierTest {
    @Test
    fun reconfigures_only_while_connected() {
        var state: PhoneConnectionState = PhoneConnectionState.Idle()
        val service = FakeCameraService()
        val applier = CameraQualityApplier({ state }, service)
        assertFalse(applier.apply("0"))
        assertTrue(service.ids.isEmpty())
        state = PhoneConnectionState.Connected("pc", "Studio")
        assertTrue(applier.apply(null))
        assertTrue(applier.apply("1"))
        assertEquals(listOf(null, "1"), service.ids)
        state = PhoneConnectionState.Idle()
        assertFalse(applier.apply("0"))
        assertEquals(2, service.ids.size)
    }

    private class FakeCameraService : CameraServiceControl {
        val ids = mutableListOf<String?>()
        override fun start(cameraId: String) = Unit
        override fun stop() = Unit
        override fun reconfigure(cameraId: String?) { ids += cameraId }
    }
}
