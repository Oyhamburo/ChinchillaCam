package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityCatalogTest {
    @Test
    fun catalogsStandaloneOpenableAndPhysicalOnlyChildIdsSeparately() {
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

        assertEquals(listOf("0", "1", "0-wide", "0-tele"), catalog.entries.map { it.id })
        assertEquals(CameraIdRole.StandaloneOpenable, catalog.entry("0").role)
        assertEquals(CameraIdRole.StandaloneOpenable, catalog.entry("1").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "0"), catalog.entry("0-wide").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "0"), catalog.entry("0-tele").role)
        assertEquals(CapabilityState.Known(CameraFacing.Back), catalog.entry("0-wide").facing)
        assertEquals(CapabilityState.Known(listOf(CameraOutputSize(width = 1280, height = 720))), catalog.entry("0-wide").outputSizes)
        assertEquals(CapabilityState.Unknown("physical camera controls not exposed"), catalog.entry("0-tele").controls)
        assertFalse(catalog.entry("0-wide").role.isOpenable)
    }

    @Test
    fun physicalIdAlsoReturnedByCameraIdListRemainsStandaloneOpenable() {
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

        assertEquals(CameraIdRole.StandaloneOpenable, catalog.entry("wide").role)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "logical"), catalog.entry("tele").role)
        assertTrue(catalog.entry("wide").role.isOpenable)
        assertFalse(catalog.entry("tele").role.isOpenable)
    }

    @Test
    fun missingCharacteristicsBecomeUnknownWithoutDroppingCameraId() {
        val gateway = FakeCameraCapabilityGateway(
            openableIds = listOf("0"),
            characteristics = emptyMap(),
        )

        val entry = CameraCapabilityCatalog(gateway).snapshot().entry("0")

        assertEquals(CameraIdRole.StandaloneOpenable, entry.role)
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
) : CameraCapabilityGateway {
    override fun getOpenableCameraIds(): List<String> = openableIds

    override fun getCharacteristics(cameraId: String): CameraCapabilityCharacteristics? =
        characteristics[cameraId]?.let {
            CameraCapabilityCharacteristics(
                facing = it.facing,
                physicalCameraIds = it.physicalCameraIds,
                outputSizes = it.outputSizes,
                fpsRanges = it.fpsRanges,
                controls = it.controls,
            )
        }
}
