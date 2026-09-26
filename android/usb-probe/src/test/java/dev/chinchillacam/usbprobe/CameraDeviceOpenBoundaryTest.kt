package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDeviceOpenBoundaryTest {
    @Test
    fun opensOnlySelectedDirectCandidateWhenPermissionGranted() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(
            snapshot = snapshotWithDirectAndPhysical(),
            selectedCameraId = "1",
            cameraPermissionGranted = true,
        )

        assertTrue(result is CameraDeviceOpenResult.OpenRequestSubmitted)
        assertEquals("1", (result as CameraDeviceOpenResult.OpenRequestSubmitted).cameraId)
        assertEquals(listOf("1"), gateway.requestedIds)
    }

    @Test
    fun rejectsMissingSelectionWithoutCallingGateway() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(
            snapshot = snapshotWithDirectAndPhysical(),
            selectedCameraId = null,
            cameraPermissionGranted = true,
        )

        assertEquals(CameraDeviceOpenResult.MissingSelection, result)
        assertEquals(emptyList<String>(), gateway.requestedIds)
    }

    @Test
    fun rejectsPhysicalOnlyAndStaleSelectionsWithoutCallingGatewayOrFallingBack() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val boundary = CameraDeviceOpenBoundary(gateway)

        val physical = boundary.requestOpenSelected(snapshotWithDirectAndPhysical(), "0-wide", true)
        val stale = boundary.requestOpenSelected(snapshotWithDirectAndPhysical(), "removed", true)

        assertEquals(CameraDeviceOpenResult.SelectionNotDirectOpenCandidate("0-wide"), physical)
        assertEquals(CameraDeviceOpenResult.SelectionNotDirectOpenCandidate("removed"), stale)
        assertEquals(emptyList<String>(), gateway.requestedIds)
    }

    @Test
    fun rechecksPermissionImmediatelyAndRefusesRevokedPermission() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(
            snapshot = snapshotWithDirectAndPhysical(),
            selectedCameraId = "1",
            cameraPermissionGranted = false,
        )

        assertEquals(CameraDeviceOpenResult.CameraPermissionMissing, result)
        assertEquals(emptyList<String>(), gateway.requestedIds)
    }

    @Test
    fun mapsGatewayFailureToTypedResult() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Failed("camera in use"))
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(
            snapshot = snapshotWithDirectAndPhysical(),
            selectedCameraId = "1",
            cameraPermissionGranted = true,
        )

        assertEquals(CameraDeviceOpenResult.OpenRequestFailed(cameraId = "1", reason = "camera in use"), result)
    }

    @Test
    fun activatesOpenedCallbackAndClosesOnDisconnectOrError() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(snapshotWithDirectAndPhysical(), "1", true)
        val submitted = result as CameraDeviceOpenResult.OpenRequestSubmitted
        val device = CloseTrackingCameraDevice("1")

        assertEquals(CameraOpenCallbackResult.Activated("1"), gateway.callbacks.single().onOpened(device))
        assertTrue(submitted.session.isActive)
        assertEquals(CameraOpenCallbackResult.Closed("1"), gateway.callbacks.single().onDisconnected())
        assertFalse(submitted.session.isActive)
        assertEquals(1, device.closeCount)
    }

    @Test
    fun cancelBeforeOpenedPreventsStaleCallbackActivationAndClosesLateDevice() {
        val gateway = RecordingCameraOpenGateway(CameraDeviceOpenRequestOutcome.Submitted)
        val result = CameraDeviceOpenBoundary(gateway).requestOpenSelected(snapshotWithDirectAndPhysical(), "1", true)
        val submitted = result as CameraDeviceOpenResult.OpenRequestSubmitted
        val device = CloseTrackingCameraDevice("1")

        submitted.session.cancel()
        val callbackResult = gateway.callbacks.single().onOpened(device)

        assertEquals(CameraOpenCallbackResult.StaleIgnored("1"), callbackResult)
        assertFalse(submitted.session.isActive)
        assertEquals(1, device.closeCount)
    }

    private fun snapshotWithDirectAndPhysical(): CameraCatalogSnapshot = CameraCatalogSnapshot(
        entries = listOf(
            entry("0", CameraIdRole.DirectOpenCandidate),
            entry("1", CameraIdRole.DirectOpenCandidate),
            entry("0-wide", CameraIdRole.PhysicalOnlyChild(parentId = "0")),
        ),
    )

    private fun entry(id: String, role: CameraIdRole): CameraCatalogEntry = CameraCatalogEntry(
        id = id,
        role = role,
        facing = CapabilityState.Unknown("not relevant"),
        outputSizes = CapabilityState.Unknown("not relevant"),
        fpsRanges = CapabilityState.Unknown("not relevant"),
        controls = CapabilityState.Unknown("not relevant"),
    )
}

private class RecordingCameraOpenGateway(
    private val outcome: CameraDeviceOpenRequestOutcome,
) : CameraDeviceOpenGateway {
    val requestedIds = mutableListOf<String>()
    val callbacks = mutableListOf<CameraOpenCallbacks>()
    override fun requestOpen(cameraId: String, callbacks: CameraOpenCallbacks): CameraDeviceOpenRequestOutcome {
        requestedIds += cameraId
        this.callbacks += callbacks
        return outcome
    }
}

private class CloseTrackingCameraDevice(
    override val cameraId: String,
) : CloseableCameraDevice {
    var closeCount = 0
    override fun close() {
        closeCount += 1
    }
}
