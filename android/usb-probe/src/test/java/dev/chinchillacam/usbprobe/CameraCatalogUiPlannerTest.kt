package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCatalogUiPlannerTest {
    @Test
    fun spanishCatalogUiSelectsOnlyDirectOpenCandidatesAndLabelsPhysicalChildrenAsNotOpenable() {
        val snapshot = CameraCatalogSnapshot(
            entries = listOf(
                CameraCatalogEntry(
                    id = "0",
                    role = CameraIdRole.DirectOpenCandidate,
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Known(listOf(CameraOutputSize(width = 1920, height = 1080))),
                    fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(min = 30, max = 60))),
                    controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = false, zoomRatio = true)),
                ),
                CameraCatalogEntry(
                    id = "0-wide",
                    role = CameraIdRole.PhysicalOnlyChild(parentId = "0"),
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Known(listOf(CameraOutputSize(width = 1280, height = 720))),
                    fpsRanges = CapabilityState.Unknown("fps ranges unavailable for 0-wide"),
                    controls = CapabilityState.Unknown("controls unavailable for 0-wide"),
                ),
            ),
        )

        val ui = CameraCatalogUiPlanner.plan(snapshot = snapshot, requestedSelectionId = "0-wide")

        assertEquals("Cámaras del teléfono", ui.title)
        assertEquals("1 cámara seleccionable; 1 físico no abrible directamente.", ui.summary)
        assertEquals("0", ui.selectedCameraId)
        assertEquals("Cámara 0 · trasera", ui.rows[0].title)
        assertEquals("Seleccionada · direccionable por Android; la apertura real se validará después.", ui.rows[0].status)
        assertTrue(ui.rows[0].selectable)
        assertTrue(ui.rows[0].selected)
        assertEquals("Físico 0-wide de 0 · trasera", ui.rows[1].title)
        assertEquals("No abrible directamente; se muestra solo como información del grupo lógico.", ui.rows[1].status)
        assertFalse(ui.rows[1].selectable)
        assertFalse(ui.rows[1].selected)
    }


    @Test
    fun requestedDirectCandidateCanBeSelectedAmongMultipleDirectCameras() {
        val snapshot = CameraCatalogSnapshot(
            entries = listOf(
                CameraCatalogEntry(
                    id = "0",
                    role = CameraIdRole.DirectOpenCandidate,
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Unknown("not needed"),
                    fpsRanges = CapabilityState.Unknown("not needed"),
                    controls = CapabilityState.Unknown("not needed"),
                ),
                CameraCatalogEntry(
                    id = "1",
                    role = CameraIdRole.DirectOpenCandidate,
                    facing = CapabilityState.Known(CameraFacing.Front),
                    outputSizes = CapabilityState.Unknown("not needed"),
                    fpsRanges = CapabilityState.Unknown("not needed"),
                    controls = CapabilityState.Unknown("not needed"),
                ),
            ),
        )

        val ui = CameraCatalogUiPlanner.plan(snapshot = snapshot, requestedSelectionId = "1")

        assertEquals("1", ui.selectedCameraId)
        assertFalse(ui.rows[0].selected)
        assertTrue(ui.rows[1].selected)
    }

    @Test
    fun spanishCatalogUiExplainsWhenThereAreNoDirectCandidatesWithoutHardwareClaim() {
        val snapshot = CameraCatalogSnapshot(entries = emptyList())

        val ui = CameraCatalogUiPlanner.plan(snapshot = snapshot, requestedSelectionId = null)

        assertEquals("No hay cámaras direccionables para listar todavía.", ui.summary)
        assertEquals(null, ui.selectedCameraId)
        assertEquals(emptyList<CameraCatalogUiRow>(), ui.rows)
    }
}
