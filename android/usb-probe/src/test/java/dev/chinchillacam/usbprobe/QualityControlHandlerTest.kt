package dev.chinchillacam.usbprobe

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class QualityControlHandlerTest {
    @Test fun set_quality_applies_supported_choice_and_replies_with_state() {
        val fixture = Fixture()
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        fixture.sent.clear()
        fixture.handler.onCommand(SET_QUALITY, manual("7", "1"))
        assertEquals("1", fixture.cameraBacking.value)
        assertEquals(QualityPreference.Manual(1280, 720, 24), fixture.qualityStore.load())
        assertEquals(listOf("1"), fixture.applied)
        assertEquals(QUALITY_STATE, fixture.sent.single().first)
        assertEquals("7", fixture.sent.single().second["req"])
        assertEquals("1", fixture.sent.single().second["camera.selected"])
        assertEquals("manual", fixture.sent.single().second["mode"])
        assertEquals("1280x720", fixture.sent.single().second["applied.res"])
        assertEquals("24", fixture.sent.single().second["applied.fps"])
    }

    @Test fun rejects_unsupported_unavailable_and_invalid_without_mutating_preferences() {
        val fixture = Fixture()
        fixture.cameraBacking.put("0")
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        fixture.sent.clear()
        val unsupported = manual("8", "1") + ("width" to "640")
        val unavailable = manual("9", "missing")
        val invalid = manual("10", "1") + ("fps" to "00")
        listOf(unsupported, unavailable, invalid).forEach { fixture.handler.onCommand(SET_QUALITY, it) }
        assertEquals(listOf("unsupported", "unavailable", "invalid"), fixture.sent.map { it.second["error"] })
        assertEquals(listOf("8", "9", "10"), fixture.sent.map { it.second["req"] })
        assertEquals("0", fixture.cameraBacking.value)
        assertEquals(QualityPreference.Automatic, fixture.qualityStore.load())
        assertTrue(fixture.applied.isEmpty())
    }

    @Test fun subscription_and_local_changes_send_only_in_the_active_session() {
        val fixture = Fixture()
        fixture.handler.onLocalChange()
        fixture.handler.onCommand(SET_QUALITY, manual("7", "auto"))
        assertTrue(fixture.sent.isEmpty())
        assertEquals(listOf("0"), fixture.applied)
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        assertEquals(1, fixture.sent.size)
        assertFalse(fixture.sent.single().second.containsKey("req"))
        fixture.handler.onLocalChange()
        assertEquals(2, fixture.sent.size)
        fixture.handler.reset()
        fixture.handler.onLocalChange()
        fixture.handler.onCommand(SET_QUALITY, manual("8", "1"))
        assertEquals(2, fixture.sent.size)
        assertEquals("1", fixture.cameraBacking.value)
        fixture.handler.onCommand("future_command", emptyMap())
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "2"))
        assertEquals(2, fixture.sent.size)
    }

    @Test fun early_subscribe_retries_once_the_session_handle_is_ready() {
        val fixture = Fixture()
        fixture.canSend = false
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        assertTrue(fixture.sent.isEmpty())
        fixture.canSend = true
        fixture.handler.onSessionReady()
        assertEquals(1, fixture.sent.size)
        assertEquals(QUALITY_STATE, fixture.sent.single().first)
        fixture.handler.onSessionReady()
        assertEquals(1, fixture.sent.size)
    }

    @Test fun over_limit_frame_is_silently_dropped() {
        val fixture = Fixture()
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        fixture.sent.clear()
        fixture.handler.onCommand(SET_QUALITY, manual("13", "1") + ("future" to "x".repeat(257)))
        assertTrue(fixture.sent.isEmpty())
        assertTrue(fixture.applied.isEmpty())
        assertNull(fixture.cameraBacking.value)
    }

    @Test fun automatic_camera_clears_selection_and_absent_camera_preserves_it() {
        val fixture = Fixture()
        fixture.cameraBacking.put("1")
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        fixture.sent.clear()
        fixture.handler.onCommand(SET_QUALITY, mapOf("v" to "1", "req" to "11", "mode" to "auto"))
        assertEquals("1", fixture.cameraBacking.value)
        assertEquals("1", fixture.sent.single().second["camera.selected"])
        fixture.handler.onCommand(SET_QUALITY, manual("12", "auto"))
        assertNull(fixture.cameraBacking.value)
        assertEquals("auto", fixture.sent.last().second["camera.selected"])
        assertEquals(listOf("1", "0"), fixture.applied)
    }

    @Test fun reader_thread_only_enqueues_work() {
        val tasks = mutableListOf<Runnable>()
        val fixture = Fixture(Executor { tasks += it })
        fixture.handler.onCommand(QUALITY_SUBSCRIBE, mapOf("v" to "1"))
        fixture.handler.onCommand(SET_QUALITY, manual("7", "1"))
        assertEquals(2, tasks.size)
        assertTrue(fixture.sent.isEmpty())
        assertTrue(fixture.applied.isEmpty())
        assertNull(fixture.cameraBacking.value)
        tasks.forEach { it.run() }
        assertEquals(2, fixture.sent.size)
        assertEquals("1", fixture.cameraBacking.value)
    }

    private fun manual(req: String, camera: String) = mapOf("v" to "1", "req" to req, "camera" to camera,
        "mode" to "manual", "width" to "1280", "height" to "720", "fps" to "24")

    private class Backing(var value: String? = null) : StringPreferenceStore {
        override fun get() = value
        override fun put(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private class Fixture(executor: Executor = Executor { it.run() }) {
        val cameraBacking = Backing()
        val qualityStore = QualityPreferenceStore(Backing())
        val applied = mutableListOf<String?>()
        val sent = mutableListOf<Pair<String, Map<String, String>>>()
        var canSend = true
        val handler = QualityControlHandler(
            snapshotProvider = { CameraCatalogSnapshot(listOf(entry("0"), entry("1"))) },
            cameraSelection = cameraBacking, qualityStore = qualityStore,
            apply = { applied += it }, send = { command, args -> if (canSend) sent += command to args; canSend }, executor = executor,
        )
    }

    private companion object {
        fun entry(id: String) = CameraCatalogEntry(
            id, CameraIdRole.DirectOpenCandidate, CapabilityState.Known(CameraFacing.Back),
            CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
            CapabilityState.Known(listOf(CameraFpsRange(15, 30))), CapabilityState.Unknown("irrelevant"),
        )
    }
}
