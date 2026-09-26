package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleCameraForegroundServiceOwnershipTest {
    @Test
    fun activityLifecycleDetachesOnlyWhenForegroundServiceOwnsPipeline() {
        assertEquals(
            VisibleCameraActivityLifecycleAction.DetachOnly,
            VisibleCameraActivityLifecyclePolicy.actionForStop(servicePipelineOwnershipRequested = true),
        )
        assertEquals(
            VisibleCameraActivityLifecycleAction.StopPipeline,
            VisibleCameraActivityLifecyclePolicy.actionForStop(servicePipelineOwnershipRequested = false),
        )
    }

    @Test
    fun serviceOwnerStartsPipelineAndStopsOnStopActionAndDestroy() {
        val pipeline = FakeServicePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop)

        assertEquals(
            VisibleCameraServiceCommandOutcome.Started,
            owner.handleStartCommand(
                VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
                cameraPermissionGranted = true,
                snapshot = sampleSnapshot(),
            ),
        )
        assertTrue(pipeline.started)

        assertEquals(VisibleCameraServiceCommandOutcome.Stopped, owner.handleStopCommand())
        assertTrue(pipeline.stopped)

        pipeline.stopped = false
        owner.handleStartCommand(
            VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
        )
        owner.handleDestroy()
        assertTrue(pipeline.stopped)
    }

    @Test
    fun serviceOwnerDoesNotHoldActivityReference() {
        val owner = VisibleCameraForegroundServicePipelineOwner(FakeServicePipeline(), VisibleCameraServiceDrainLoop.Noop)

        assertFalse(owner.requiresActivityReference)
    }

    private fun sampleSnapshot(): CameraCatalogSnapshot = CameraCatalogSnapshot(
        entries = listOf(
            CameraCatalogEntry(
                id = "camera-1",
                role = CameraIdRole.DirectOpenCandidate,
                facing = CapabilityState.Known(CameraFacing.Back),
                outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
                controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)),
            ),
        ),
    )
}

private class FakeServicePipeline : VisibleCameraServicePipeline {
    var started: Boolean = false
    var stopped: Boolean = false

    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus {
        started = true
        return VisibleCameraPipelineStatus.Running
    }

    override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Running

    override fun stop(): VisibleCameraPipelineStatus {
        stopped = true
        return VisibleCameraPipelineStatus.Stopped
    }
}
