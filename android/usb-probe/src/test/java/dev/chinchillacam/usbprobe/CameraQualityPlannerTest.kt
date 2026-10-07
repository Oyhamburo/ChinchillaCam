package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraQualityPlannerTest {
    @Test
    fun automatic_prefers_720p30_when_supported() {
        val plan = CameraQualityPlanner.plan(
            entry(
                sizes = CapabilityState.Known(listOf(CameraOutputSize(1920, 1080), CameraOutputSize(1280, 720))),
                ranges = CapabilityState.Known(listOf(CameraFpsRange(15, 30))),
            ),
            QualityPreference.Automatic,
        )

        assertEquals(H264EncoderConfig(1280, 720, 2_000_000, 30, 2), plan.encoderConfig)
        assertEquals(CameraFpsRange(15, 30), plan.fpsRange)
        assertEquals(QualityPreference.Automatic, plan.appliedPreference)
        assertNull(plan.fallbackReason)
        assertEquals(listOf(true, true, false, false), plan.resolutionOptions.map { it.enabled })
        assertEquals(listOf(true, true, true), plan.fpsOptions.map { it.enabled })
    }

    @Test
    fun automatic_chooses_largest_supported_at_or_below_720p_then_smallest_if_none() {
        val ranges = CapabilityState.Known(listOf(CameraFpsRange(15, 24)))
        val withVga = CameraQualityPlanner.plan(
            entry(CapabilityState.Known(listOf(CameraOutputSize(1920, 1080), CameraOutputSize(640, 480))), ranges),
            QualityPreference.Automatic,
        )
        assertEquals(H264EncoderConfig(640, 480, 533_333, 24, 2), withVga.encoderConfig)
        assertTrue(withVga.resolutionOptions.first().enabled)
        assertEquals(Resolution(640, 480), withVga.resolutionOptions.last().value)

        val onlyFullHd = CameraQualityPlanner.plan(
            entry(CapabilityState.Known(listOf(CameraOutputSize(1920, 1080))), ranges),
            QualityPreference.Automatic,
        )
        assertEquals(Resolution(1920, 1080), onlyFullHd.encoderConfig.let { Resolution(it.width, it.height) })
        assertEquals(24, onlyFullHd.encoderConfig.frameRate)
    }

    @Test
    fun manual_supported_and_fixed_range_preferred_over_wide_range() {
        val manual = QualityPreference.Manual(960, 540, 30)
        val plan = CameraQualityPlanner.plan(
            entry(
                CapabilityState.Known(listOf(CameraOutputSize(960, 540))),
                CapabilityState.Known(listOf(CameraFpsRange(15, 30), CameraFpsRange(30, 30))),
            ),
            manual,
        )
        assertEquals(manual, plan.appliedPreference)
        assertEquals(CameraFpsRange(30, 30), plan.fpsRange)
        assertEquals(960, plan.encoderConfig.width)
        assertEquals(30, plan.encoderConfig.frameRate)
        assertNull(plan.fallbackReason)
    }

    @Test
    fun unsupported_manual_reverts_to_automatic() {
        val plan = CameraQualityPlanner.plan(
            entry(
                CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                CapabilityState.Known(listOf(CameraFpsRange(24, 30))),
            ),
            QualityPreference.Manual(1920, 1080, 15),
        )
        assertEquals(QualityPreference.Automatic, plan.appliedPreference)
        assertEquals(H264EncoderConfig(1280, 720, 2_000_000, 30, 2), plan.encoderConfig)
        assertNotNull(plan.fallbackReason)
    }

    @Test
    fun unknown_sizes_disable_resolutions_and_fall_back_without_range() {
        val plan = CameraQualityPlanner.plan(
            entry(CapabilityState.Unknown("diagnostic"), CapabilityState.Known(listOf(CameraFpsRange(30, 30)))),
            QualityPreference.Automatic,
        )
        assertFallback(plan)
        assertTrue(plan.resolutionOptions.all { !it.enabled && it.disabledReason == "No se pudo leer qué resoluciones admite la cámara." })
        assertTrue(plan.fpsOptions.first().enabled)
    }

    @Test
    fun unavailable_sizes_have_specific_reason() {
        val plan = CameraQualityPlanner.plan(
            entry(CapabilityState.Unavailable("diagnostic"), CapabilityState.Known(listOf(CameraFpsRange(30, 30)))),
            QualityPreference.Automatic,
        )
        assertFallback(plan)
        assertTrue(plan.resolutionOptions.all { !it.enabled && it.disabledReason == "La cámara no informó las resoluciones disponibles." })
    }

    @Test
    fun unknown_fps_disable_fps_options_and_fall_back() {
        val plan = CameraQualityPlanner.plan(
            entry(CapabilityState.Known(listOf(CameraOutputSize(640, 480))), CapabilityState.Unknown("diagnostic")),
            QualityPreference.Automatic,
        )
        assertFallback(plan)
        assertTrue(plan.fpsOptions.all { !it.enabled && it.disabledReason == "No se pudo leer qué FPS admite la cámara." })
        assertTrue(plan.resolutionOptions.last().enabled)
    }

    @Test
    fun unavailable_fps_have_specific_reason() {
        val plan = CameraQualityPlanner.plan(
            entry(CapabilityState.Known(listOf(CameraOutputSize(1280, 720))), CapabilityState.Unavailable("diagnostic")),
            QualityPreference.Automatic,
        )
        assertFallback(plan)
        assertTrue(plan.fpsOptions.all { !it.enabled && it.disabledReason == "La cámara no informó los FPS disponibles." })
    }

    @Test
    fun known_but_empty_options_fall_back() {
        val plan = CameraQualityPlanner.plan(
            entry(CapabilityState.Known(emptyList()), CapabilityState.Known(listOf(CameraFpsRange(15, 30)))),
            QualityPreference.Automatic,
        )
        assertFallback(plan)
        assertTrue(plan.resolutionOptions.all { !it.enabled && it.disabledReason == "La cámara no admite esta resolución." })
    }

    @Test
    fun null_entry_disables_all_options_and_falls_back() {
        val plan = CameraQualityPlanner.plan(null, QualityPreference.Manual(640, 480, 15))
        assertFallback(plan)
        assertEquals(QualityPreference.Automatic, plan.appliedPreference)
        assertTrue(plan.resolutionOptions.all { !it.enabled })
        assertTrue(plan.fpsOptions.all { !it.enabled })
    }

    @Test
    fun bitrate_scales_and_clamps_at_lower_bound() {
        val entry = entry(
            CapabilityState.Known(listOf(CameraOutputSize(1920, 1080), CameraOutputSize(640, 480))),
            CapabilityState.Known(listOf(CameraFpsRange(15, 30))),
        )
        val hd = CameraQualityPlanner.plan(entry, QualityPreference.Manual(1920, 1080, 30))
        assertEquals(4_500_000, hd.encoderConfig.bitrate)
        val vga = CameraQualityPlanner.plan(entry, QualityPreference.Manual(640, 480, 15))
        assertEquals(500_000, vga.encoderConfig.bitrate)
        assertNull(vga.resolutionOptions.last().disabledReason)
    }

    private fun assertFallback(plan: QualityPlan) {
        assertEquals(H264EncoderConfig(1280, 720, 2_000_000, 30, 2), plan.encoderConfig)
        assertNull(plan.fpsRange)
        assertNotNull(plan.fallbackReason)
    }

    private fun entry(
        sizes: CapabilityState<List<CameraOutputSize>>,
        ranges: CapabilityState<List<CameraFpsRange>>,
    ) = CameraCatalogEntry(
        id = "0",
        role = CameraIdRole.DirectOpenCandidate,
        facing = CapabilityState.Unknown("not relevant"),
        outputSizes = sizes,
        fpsRanges = ranges,
        controls = CapabilityState.Unknown("not relevant"),
    )
}
