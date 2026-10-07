package dev.chinchillacam.usbprobe

import org.junit.Assert.*
import org.junit.Test

class QualityControlProtocolTest {
    private val plan = QualityControlsPlan(
        effectiveCameraId = "0",
        cameras = listOf(CameraChoice(null, "Automático", false), CameraChoice("0", "Trasera 1", true), CameraChoice("1", "Frontal 1", false)),
        resolutions = listOf(
            QualityChoice(Resolution(1920, 1080), "1920 × 1080", true, null),
            QualityChoice(Resolution(1280, 720), "1280 × 720", true, null),
            QualityChoice(Resolution(960, 540), "960 × 540", false, "La cámara no admite esta resolución."),
            QualityChoice(Resolution(640, 480), "640 × 480", true, null),
        ),
        frameRates = listOf(
            QualityChoice(30, "30 FPS", true, null), QualityChoice(24, "24 FPS", true, null),
            QualityChoice(15, "15 FPS", false, "La cámara no admite estos FPS."),
        ),
        appliedResolution = Resolution(1280, 720), appliedFps = 30,
        appliedPreference = QualityPreference.Manual(1280, 720, 30),
        summary = "Calidad: Manual (1280 × 720, 30 FPS)",
    )

    @Test fun quality_state_encodes_indexed_options() {
        assertEquals(mapOf(
            "v" to "1", "req" to "7", "mode" to "manual", "camera.selected" to "0",
            "camera.count" to "2", "camera.0.id" to "0", "camera.0.label" to "Trasera 1",
            "camera.1.id" to "1", "camera.1.label" to "Frontal 1",
            "res.count" to "4", "res.0" to "1920x1080", "res.0.enabled" to "1",
            "res.1" to "1280x720", "res.1.enabled" to "1",
            "res.2" to "960x540", "res.2.enabled" to "0", "res.2.reason" to "La cámara no admite esta resolución.",
            "res.3" to "640x480", "res.3.enabled" to "1",
            "fps.count" to "3", "fps.0" to "30", "fps.0.enabled" to "1",
            "fps.1" to "24", "fps.1.enabled" to "1",
            "fps.2" to "15", "fps.2.enabled" to "0", "fps.2.reason" to "La cámara no admite estos FPS.",
            "applied.res" to "1280x720", "applied.fps" to "30", "summary" to "Calidad: Manual (1280 × 720, 30 FPS)",
        ), encodeQualityState(plan, 7L, null))
    }

    @Test fun parser_accepts_valid_modes_and_ignores_unknown_keys() {
        assertEquals(SetQualityRequest.Valid(7, null, QualityPreference.Automatic),
            parseSetQuality(mapOf("v" to "1", "req" to "7", "mode" to "auto", "future" to "value")))
        assertEquals(SetQualityRequest.Valid(8, "auto", QualityPreference.Manual(1280, 720, 30)),
            parseSetQuality(mapOf("v" to "1", "req" to "8", "camera" to "auto", "mode" to "manual",
                "width" to "1280", "height" to "720", "fps" to "30")))
        assertTrue(isSubscribe(QUALITY_SUBSCRIBE, mapOf("v" to "1", "future" to "yes")))
        assertFalse(isSubscribe(QUALITY_SUBSCRIBE, mapOf("v" to "01")))
        assertFalse(isSubscribe(SET_QUALITY, mapOf("v" to "1")))
    }

    @Test fun parser_discards_invalid_numbers_missing_dimensions_and_oversized_entries() {
        val valid = mapOf("v" to "1", "req" to "7", "mode" to "manual", "width" to "1280", "height" to "720", "fps" to "30")
        for ((key, value) in listOf("req" to "01", "req" to "+7", "req" to "0", "req" to "9223372036854775808",
                "width" to "00", "height" to "-1", "fps" to "0", "fps" to "2147483648")) {
            assertTrue("$key=$value", parseSetQuality(valid + (key to value)) is SetQualityRequest.Invalid)
        }
        assertTrue(parseSetQuality(valid - "fps") is SetQualityRequest.Invalid)
        assertTrue(parseSetQuality(valid + ("v" to "2")) is SetQualityRequest.Invalid)
        assertTrue(parseSetQuality(valid + ("camera" to "")) is SetQualityRequest.Invalid)
        assertTrue(parseSetQuality(valid + ("unknown" to "é".repeat(129))) is SetQualityRequest.Invalid)
        assertTrue(parseSetQuality(valid + ("x".repeat(257) to "1")) is SetQualityRequest.Invalid)
        assertFalse(isSubscribe(QUALITY_SUBSCRIBE, mapOf("v" to "1", "other" to "x".repeat(257))))
    }

    @Test fun encoder_truncates_lists_and_utf8_text_at_code_point_boundaries() {
        val huge = "é".repeat(127) + "😀" + "z"
        val extended = plan.copy(
            cameras = listOf(CameraChoice(null, "Automático", true)) + (0..19).map { CameraChoice("$it", huge, false) },
            resolutions = List(20) { QualityChoice(Resolution(640, 480), "", false, huge) },
            frameRates = List(20) { QualityChoice(15, "", false, huge) }, summary = huge,
        )
        val state = encodeQualityState(extended, null, "invalid")
        assertEquals("auto", state["camera.selected"])
        assertEquals("16", state["camera.count"])
        assertEquals("15", state["camera.15.id"])
        assertFalse(state.containsKey("camera.16.id"))
        assertEquals("16", state["res.count"])
        assertEquals("16", state["fps.count"])
        assertEquals("é".repeat(127), state["summary"])
        assertEquals(state["summary"], state["camera.0.label"])
        assertEquals(state["summary"], state["res.0.reason"])
        assertEquals("invalid", state["error"])
        assertFalse(state.containsKey("req"))
        assertTrue(state.all { (k, v) -> k.toByteArray(Charsets.UTF_8).size <= 256 && v.toByteArray(Charsets.UTF_8).size <= 256 })
    }
}
