package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityCatalogTest {
    @Test
    fun catalogsDirectOpenCandidateAndPhysicalOnlyChildIdsSeparately() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("0", "1"),
            characteristics = mapOf(
                "0" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Back),
                    physicalCameraIds = setOf("0-wide", "0-tele"),
                    outputSizes = CapabilityState.Known(listOf(CameraOutputSize(width = 1920, height = 1080))),
                    fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(min = 30, max = 60))),
                    controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)),
                ),
                "1" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Front),
                    physicalCameraIds = emptySet(),
                ),
                "0-wide" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Known(listOf(CameraOutputSize(width = 1280, height = 720))),
                    fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(min = 15, max = 30))),
                    controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = false, zoomRatio = false)),
                ),
                "0-tele" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Unknown("physical camera stream map not exposed"),
                    fpsRanges = CapabilityState.Unknown("physical camera fps ranges not exposed"),
                    controls = CapabilityState.Unknown("physical camera controls not exposed"),
                ),
            ),
        )

        val catalog = CameraCapabilityCatalog(gateway).snapshot()

        assertEquals(listOf("0", "1", "0-tele", "0-wide"), catalog.entries.map { it.id })
        assertEquals(CameraIdRole.DirectOpenCandidate, catalog.entry("0").role)
        assertEquals(CameraIdRole.DirectOpenCandidate, catalog.entry("1").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "0"), catalog.entry("0-wide").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "0"), catalog.entry("0-tele").role)
        assertEquals(CapabilityState.Known(CameraFacing.Back), catalog.entry("0-wide").facing)
        assertEquals(CapabilityState.Known(listOf(CameraOutputSize(width = 1280, height = 720))), catalog.entry("0-wide").outputSizes)
        assertEquals(CapabilityState.Unknown("physical camera controls not exposed"), catalog.entry("0-tele").controls)
        assertFalse(catalog.entry("0-wide").role.canAttemptOpenDirectly)
    }

    @Test
    fun physicalIdAlsoReturnedByCameraIdListRemainsDirectOpenCandidate() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("logical", "wide"),
            characteristics = mapOf(
                "logical" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Back),
                    physicalCameraIds = setOf("wide", "tele"),
                ),
                "wide" to FakeCameraCharacteristics(facing = CapabilityState.Known(CameraFacing.Back)),
                "tele" to FakeCameraCharacteristics(facing = CapabilityState.Known(CameraFacing.Back)),
            ),
        )

        val catalog = CameraCapabilityCatalog(gateway).snapshot()

        assertEquals(CameraIdRole.DirectOpenCandidate, catalog.entry("wide").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "logical"), catalog.entry("tele").role)
        assertTrue(catalog.entry("wide").role.canAttemptOpenDirectly)
        assertFalse(catalog.entry("tele").role.canAttemptOpenDirectly)
    }


    @Test
    fun characteristicsFailureCreatesPartialUnknownEntryAndDoesNotAbortOtherCameras() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("broken", "healthy"),
            characteristics = mapOf(
                "healthy" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.Back),
                    outputSizes = CapabilityState.Known(listOf(CameraOutputSize(width = 640, height = 480))),
                ),
            ),
            failingIds = setOf("broken"),
        )

        val catalog = CameraCapabilityCatalog(gateway).snapshot()

        assertEquals(listOf("broken", "healthy"), catalog.entries.map { it.id })
        assertEquals(CameraIdRole.DirectOpenCandidate, catalog.entry("broken").role)
        assertEquals(CapabilityState.Unknown("characteristics unavailable for broken"), catalog.entry("broken").facing)
        assertEquals(CapabilityState.Known(CameraFacing.Back), catalog.entry("healthy").facing)
        assertEquals(CapabilityState.Known(listOf(CameraOutputSize(width = 640, height = 480))), catalog.entry("healthy").outputSizes)
    }

    @Test
    fun cameraIdsAndPhysicalChildrenAreDeterministicallySorted() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("2", "0", "1"),
            characteristics = mapOf(
                "2" to FakeCameraCharacteristics(physicalCameraIds = setOf("2-tele", "2-wide")),
                "0" to FakeCameraCharacteristics(physicalCameraIds = setOf("0-ultra", "0-wide")),
                "1" to FakeCameraCharacteristics(),
                "2-wide" to FakeCameraCharacteristics(),
                "2-tele" to FakeCameraCharacteristics(),
                "0-wide" to FakeCameraCharacteristics(),
                "0-ultra" to FakeCameraCharacteristics(),
            ),
        )

        val catalog = CameraCapabilityCatalog(gateway).snapshot()

        assertEquals(listOf("0", "1", "2", "0-ultra", "0-wide", "2-tele", "2-wide"), catalog.entries.map { it.id })
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "0"), catalog.entry("0-ultra").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "2"), catalog.entry("2-tele").role)
    }

    @Test
    fun missingCharacteristicsBecomeUnknownWithoutDroppingCameraId() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("0"),
            characteristics = emptyMap(),
        )

        val entry = CameraCapabilityCatalog(gateway).snapshot().entry("0")

        assertEquals(CameraIdRole.DirectOpenCandidate, entry.role)
        assertEquals(CapabilityState.Unknown("characteristics unavailable for 0"), entry.facing)
        assertEquals(CapabilityState.Unknown("characteristics unavailable for 0"), entry.outputSizes)
        assertEquals(CapabilityState.Unknown("characteristics unavailable for 0"), entry.fpsRanges)
        assertEquals(CapabilityState.Unknown("characteristics unavailable for 0"), entry.controls)
    }

    @Test
    fun unavailableCapabilitiesRemainDistinctFromUnknownValues() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("external"),
            characteristics = mapOf(
                "external" to FakeCameraCharacteristics(
                    facing = CapabilityState.Known(CameraFacing.External),
                    outputSizes = CapabilityState.Unavailable("no preview output advertised"),
                    fpsRanges = CapabilityState.Unknown("fps ranges omitted by gateway"),
                    controls = CapabilityState.Unavailable("manual controls not supported"),
                ),
            ),
        )

        val entry = CameraCapabilityCatalog(gateway).snapshot().entry("external")

        assertEquals(CapabilityState.Unavailable("no preview output advertised"), entry.outputSizes)
        assertEquals(CapabilityState.Unknown("fps ranges omitted by gateway"), entry.fpsRanges)
        assertEquals(CapabilityState.Unavailable("manual controls not supported"), entry.controls)
    }

    private fun CameraCatalogSnapshot.entry(id: String): CameraCatalogEntry =
        entries.single { it.id == id }
}

private data class FakeCameraCharacteristics(
    val facing: CapabilityState<CameraFacing> = CapabilityState.Unknown("facing not exposed"),
    val physicalCameraIds: Set<String> = emptySet(),
    val outputSizes: CapabilityState<List<CameraOutputSize>> = CapabilityState.Unknown("output sizes not exposed"),
    val fpsRanges: CapabilityState<List<CameraFpsRange>> = CapabilityState.Unknown("fps ranges not exposed"),
    val controls: CapabilityState<CameraControlAvailability> = CapabilityState.Unknown("controls not exposed"),
)

private class FakeCameraCapabilityGateway(
    private val openableIds: List<String>,
    private val characteristics: Map<String, FakeCameraCharacteristics>,
    private val failingIds: Set<String> = emptySet(),
) : CameraCapabilityGateway {
    override fun getOpenableCameraIds(): List<String> = openableIds

    override fun getCharacteristics(cameraId: String): CameraCapabilityCharacteristics? {
        if (cameraId in failingIds) error("camera characteristics unavailable")
        return characteristics[cameraId]?.let {
            CameraCapabilityCharacteristics(
                facing = it.facing,
                physicalCameraIds = it.physicalCameraIds,
                outputSizes = it.outputSizes,
                fpsRanges = it.fpsRanges,
                controls = it.controls,
            )
        }
    }
}
