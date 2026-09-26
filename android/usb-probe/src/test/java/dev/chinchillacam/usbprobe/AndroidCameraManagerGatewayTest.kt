package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCameraManagerGatewayTest {
    @Test
    fun mapsCameraManagerFacadeIntoCatalogWithoutClaimingOpenGuaranteed() {
        val facade = FakeAndroidCameraManagerFacade(
            sdkInt = 33,
            listedIds = listOf("logical"),
            records = mapOf(
                "logical" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Back,
                    physicalCameraIds = setOf("wide"),
                    outputSizes = listOf(CameraOutputSize(width = 1920, height = 1080)),
                    fpsRanges = listOf(CameraFpsRange(min = 30, max = 60)),
                    controls = CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true),
                ),
                "wide" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Back,
                    outputSizes = listOf(CameraOutputSize(width = 1280, height = 720)),
                    fpsRanges = listOf(CameraFpsRange(min = 15, max = 30)),
                    controls = CameraControlAvailability(autoFocus = true, exposureCompensation = false, zoomRatio = false),
                ),
            ),
        )

        val catalog = CameraCapabilityCatalog(AndroidCameraManagerGateway(facade)).snapshot()

        assertEquals(CameraIdRole.DirectOpenCandidate, catalog.entry("logical").role)
        assertTrue(catalog.entry("logical").role.canAttemptOpenDirectly)
        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "logical"), catalog.entry("wide").role)
        assertFalse(catalog.entry("wide").role.canAttemptOpenDirectly)
        assertEquals(CapabilityState.Known(listOf(CameraOutputSize(width = 1280, height = 720))), catalog.entry("wide").outputSizes)
        assertEquals(CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)), catalog.entry("logical").controls)
    }

    @Test
    fun physicalOnlyCameraCharacteristicsRequireApi29OrBecomeUnknown() {
        val facade = FakeAndroidCameraManagerFacade(
            sdkInt = 28,
            listedIds = listOf("logical"),
            records = mapOf(
                "logical" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Back,
                    physicalCameraIds = setOf("wide"),
                ),
                "wide" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Back,
                    outputSizes = listOf(CameraOutputSize(width = 1280, height = 720)),
                ),
            ),
        )

        val wide = CameraCapabilityCatalog(AndroidCameraManagerGateway(facade)).snapshot().entry("wide")

        assertEquals(CameraIdRole.PhysicalOnlyChild(parentId = "logical"), wide.role)
        assertEquals(CapabilityState.Unknown("physical camera characteristics require API 29 for wide"), wide.facing)
        assertEquals(CapabilityState.Unknown("physical camera characteristics require API 29 for wide"), wide.outputSizes)
    }

    @Test
    fun restrictedOrMissingCameraCharacteristicsBecomeUnknownOrUnavailableWithoutCrash() {
        val facade = FakeAndroidCameraManagerFacade(
            sdkInt = 33,
            listedIds = listOf("0", "1"),
            records = mapOf(
                "0" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Front,
                    outputSizes = null,
                    fpsRanges = null,
                    controls = null,
                ),
                "1" to FakeAndroidCameraRecord(
                    facing = CameraFacing.Back,
                    restrictedFields = setOf(AndroidCameraCharacteristicField.FpsRanges, AndroidCameraCharacteristicField.Controls),
                    outputSizes = listOf(CameraOutputSize(width = 640, height = 480)),
                ),
            ),
        )

        val catalog = CameraCapabilityCatalog(AndroidCameraManagerGateway(facade)).snapshot()

        assertEquals(CapabilityState.Known(CameraFacing.Front), catalog.entry("0").facing)
        assertEquals(CapabilityState.Unavailable("output sizes not advertised for 0"), catalog.entry("0").outputSizes)
        assertEquals(CapabilityState.Unknown("fps ranges unavailable for 0"), catalog.entry("0").fpsRanges)
        assertEquals(CapabilityState.Unknown("controls unavailable for 0"), catalog.entry("0").controls)
        assertEquals(CapabilityState.Known(listOf(CameraOutputSize(width = 640, height = 480))), catalog.entry("1").outputSizes)
        assertEquals(CapabilityState.Unknown("fps ranges unavailable for 1"), catalog.entry("1").fpsRanges)
        assertEquals(CapabilityState.Unknown("controls unavailable for 1"), catalog.entry("1").controls)
    }

    @Test
    fun listedCameraIdsFailureReturnsEmptyCatalogWithoutThrowing() {
        val facade = FakeAndroidCameraManagerFacade(
            sdkInt = 33,
            listedIds = emptyList(),
            records = emptyMap(),
            failList = true,
        )

        val catalog = CameraCapabilityCatalog(AndroidCameraManagerGateway(facade)).snapshot()

        assertEquals(emptyList<CameraCatalogEntry>(), catalog.entries)
    }

    private fun CameraCatalogSnapshot.entry(id: String): CameraCatalogEntry =
        entries.single { it.id == id }
}

private data class FakeAndroidCameraRecord(
    val facing: CameraFacing? = null,
    val physicalCameraIds: Set<String> = emptySet(),
    val outputSizes: List<CameraOutputSize>? = null,
    val fpsRanges: List<CameraFpsRange>? = null,
    val controls: CameraControlAvailability? = null,
    val restrictedFields: Set<AndroidCameraCharacteristicField> = emptySet(),
)

private class FakeAndroidCameraManagerFacade(
    override val sdkInt: Int,
    private val listedIds: List<String>,
    private val records: Map<String, FakeAndroidCameraRecord>,
    private val failList: Boolean = false,
) : AndroidCameraManagerFacade {
    override fun getCameraIdList(): List<String> {
        if (failList) error("camera list unavailable")
        return listedIds
    }

    override fun getFacing(cameraId: String): CameraFacing? = record(cameraId, AndroidCameraCharacteristicField.Facing).facing

    override fun getPhysicalCameraIds(cameraId: String): Set<String> = record(cameraId, AndroidCameraCharacteristicField.PhysicalCameraIds).physicalCameraIds

    override fun getOutputSizes(cameraId: String): List<CameraOutputSize>? = record(cameraId, AndroidCameraCharacteristicField.OutputSizes).outputSizes

    override fun getFpsRanges(cameraId: String): List<CameraFpsRange>? = record(cameraId, AndroidCameraCharacteristicField.FpsRanges).fpsRanges

    override fun getControls(cameraId: String): CameraControlAvailability? = record(cameraId, AndroidCameraCharacteristicField.Controls).controls

    private fun record(cameraId: String, field: AndroidCameraCharacteristicField): FakeAndroidCameraRecord {
        val record = records[cameraId] ?: error("missing $cameraId")
        if (field in record.restrictedFields) throw SecurityException("restricted $field")
        return record
    }
}
