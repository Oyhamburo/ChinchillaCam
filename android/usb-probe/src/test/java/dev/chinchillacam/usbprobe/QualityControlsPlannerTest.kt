package dev.chinchillacam.usbprobe

import org.junit.Assert.*
import org.junit.Test

class QualityControlsPlannerTest {
    @Test
    fun connected_screen_offers_supported_quality_options() {
        val quality = QualityControlsPlanner.plan(snapshot(), "0", QualityPreference.Automatic)
        val screen = ConnectionScreenPlanner.plan(ConnectionScreenInput(
            state = PhoneConnectionState.Connected("pc", "Studio"), quality = quality,
        ))
        assertEquals(quality, screen.quality)
        assertEquals("Calidad: Automático (1280 × 720, 30 FPS)", quality.summary)
        assertEquals(listOf(true, true, false, false), quality.resolutions.map { it.enabled })
        assertEquals(listOf(true, true, true), quality.frameRates.map { it.enabled })
        assertEquals("La cámara no admite esta resolución.", quality.resolutions.last().disabledReason)
        assertEquals("1920 × 1080", quality.resolutions.first().label)
        assertEquals("30 FPS", quality.frameRates.first().label)
    }

    @Test
    fun incomplete_capabilities_disable_every_manual_choice_even_when_one_dimension_is_known() {
        val camera = entry("0", CameraFacing.Back).copy(fpsRanges = CapabilityState.Unknown("unreadable"))
        val plan = QualityControlsPlanner.plan(CameraCatalogSnapshot(listOf(camera)), "0", QualityPreference.Manual(1280, 720, 30))
        assertTrue(plan.resolutions.all { !it.enabled && it.disabledReason == "No se pudieron confirmar las capacidades de calidad de la cámara." })
        assertTrue(plan.frameRates.all { !it.enabled && it.disabledReason == "No se pudieron confirmar las capacidades de calidad de la cámara." })
        assertEquals(QualityPreference.Automatic, plan.appliedPreference)
        assertTrue(plan.summary.contains("No se pudieron confirmar"))
        assertNull(QualityControlsPlanner.selectResolution(plan, Resolution(1280, 720)))
        assertNull(QualityControlsPlanner.selectFps(plan, 30))
        val absent = QualityControlsPlanner.plan(CameraCatalogSnapshot(emptyList()), null, QualityPreference.Automatic)
        assertTrue(absent.resolutions.all { !it.enabled })
        assertTrue(absent.frameRates.all { !it.enabled })
    }

    @Test
    fun camera_choices_exclude_physical_children_and_mark_selection() {
        val direct = entry("1", CameraFacing.Back)
        val child = direct.copy(id = "physical", role = CameraIdRole.PhysicalOnlyChild("1"))
        val plan = QualityControlsPlanner.plan(CameraCatalogSnapshot(listOf(entry("0", CameraFacing.Back), direct, child)), "1", QualityPreference.Automatic)
        assertEquals(listOf("Automático", "Trasera 1", "Trasera 2"), plan.cameras.map { it.label })
        assertEquals(listOf(false, false, true), plan.cameras.map { it.selected })
        assertEquals(listOf(null, "0", "1"), plan.cameras.map { it.id })
    }

    @Test
    fun manual_selection_preserves_supported_counterpart_or_uses_highest_supported() {
        val plan = QualityControlsPlanner.plan(snapshot(), "0", QualityPreference.Manual(1920, 1080, 24))
        assertEquals(QualityPreference.Manual(1280, 720, 24), QualityControlsPlanner.selectResolution(plan, Resolution(1280, 720)))
        assertEquals(QualityPreference.Manual(1920, 1080, 15), QualityControlsPlanner.selectFps(plan, 15))
        assertNull(QualityControlsPlanner.selectResolution(plan, Resolution(640, 480)))
        assertNull(QualityControlsPlanner.selectFps(plan, 60))
        val automatic = QualityControlsPlanner.plan(snapshot(), null, QualityPreference.Manual(640, 480, 60))
        assertEquals(QualityPreference.Manual(1920, 1080, 30), QualityControlsPlanner.selectResolution(automatic, Resolution(1920, 1080)))
        assertEquals(QualityPreference.Manual(1280, 720, 24), QualityControlsPlanner.selectFps(automatic, 24))
        val unavailableCurrent = plan.copy(appliedResolution = Resolution(640, 480), appliedFps = 60)
        assertEquals(QualityPreference.Manual(1280, 720, 30), QualityControlsPlanner.selectResolution(unavailableCurrent, Resolution(1280, 720)))
        assertEquals(QualityPreference.Manual(1920, 1080, 15), QualityControlsPlanner.selectFps(unavailableCurrent, 15))
    }

    @Test
    fun idle_includes_quality_but_pairing_omits_it() {
        val quality = QualityControlsPlanner.plan(snapshot(), null, QualityPreference.Automatic)
        assertEquals(quality, ConnectionScreenPlanner.plan(ConnectionScreenInput(PhoneConnectionState.Idle(), quality = quality)).quality)
        assertNull(ConnectionScreenPlanner.plan(ConnectionScreenInput(PhoneConnectionState.Connecting("pc"), quality = quality)).quality)
    }

    private fun snapshot(): CameraCatalogSnapshot = CameraCatalogSnapshot(listOf(entry("0", CameraFacing.Back)))

    private fun entry(id: String, facing: CameraFacing): CameraCatalogEntry = CameraCatalogEntry(
        id, CameraIdRole.DirectOpenCandidate, CapabilityState.Known(facing),
        CapabilityState.Known(listOf(CameraOutputSize(1920, 1080), CameraOutputSize(1280, 720))),
        CapabilityState.Known(listOf(CameraFpsRange(15, 30))), CapabilityState.Unknown("irrelevant"),
    )
}
