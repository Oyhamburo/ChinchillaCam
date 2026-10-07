package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleCameraForegroundServiceOwnershipTest {
    @Test
    fun session_failure_posts_error_notification() {
        val events = mutableListOf<String>()
        val stop = VisibleCameraFailureStop(
            stopForeground = { events += "foreground removed" },
            errorNotifier = { spec ->
                assertEquals("ChinchillaCam se detuvo", spec.title)
                assertEquals(UserFailureCatalog.messageFor(FailureCause.SessionPeerDead), spec.text)
                events += "error posted"
            },
        )

        stop.stop(VisibleCameraServiceStatus(VisibleCameraServiceState.Error,
            message = UserFailureCatalog.messageFor(FailureCause.SessionPeerDead), cause = FailureCause.SessionPeerDead))

        assertEquals(listOf("foreground removed", "error posted"), events)
    }

    @Test
    fun user_stop_does_not_post_error_notification() {
        val posted = mutableListOf<ErrorNotificationSpec>()
        val stop = VisibleCameraFailureStop(stopForeground = {}, errorNotifier = posted::add)

        stop.stop(VisibleCameraServiceStatus(VisibleCameraServiceState.Stopped, message = "Servicio visible detenido."))
        stop.stop(VisibleCameraServiceStatus(VisibleCameraServiceState.Error,
            message = UserFailureCatalog.messageFor(FailureCause.SessionLocalClose), cause = FailureCause.SessionLocalClose))

        assertTrue(posted.isEmpty())
    }

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
    fun egressFailurePublishesErrorAndStopsService() {
        val pipeline = FailingDetailServicePipeline("El envío de video se detuvo: el canal se cerró; cámara local detenida.")
        val drainLoop = CallbackCapturingDrainLoop()
        var failureStopRequested = false
        val owner = VisibleCameraForegroundServicePipelineOwner(
            pipeline,
            drainLoop,
            onPipelineFailureStop = { failureStopRequested = true },
        )
        owner.handleStartCommand(
            VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
        )

        drainLoop.invokeOnPipelineStopped()

        val status = VisibleCameraServiceStatusStore.snapshot()
        assertEquals(VisibleCameraServiceState.Error, status.state)
        assertEquals("El envío de video se detuvo: el canal se cerró; cámara local detenida.", status.message)
        assertTrue("pipeline failure must request the service stop", failureStopRequested)
        assertTrue("pipeline resources must be released on failure", pipeline.stopped)
    }

    @Test
    fun userStopDoesNotPublishError() {
        val pipeline = FakeServicePipeline()
        val drainLoop = CallbackCapturingDrainLoop()
        val owner = VisibleCameraForegroundServicePipelineOwner(
            pipeline,
            drainLoop,
            onPipelineFailureStop = { throw AssertionError("a user stop must not use the failure stop path") },
        )
        owner.handleStartCommand(
            VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
        )

        owner.handleStopCommand()

        val status = VisibleCameraServiceStatusStore.snapshot()
        assertEquals(VisibleCameraServiceState.Stopped, status.state)
        assertNotEquals(VisibleCameraServiceState.Error, status.state)
    }

    @Test
    fun failedLauncherCauseReachesVisibleServiceStatus() {
        val launcher = object : VisibleCameraPipelineLauncher {
            override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig) =
                VisibleCameraPipelineLaunchResult.Failed("platform-secret", FailureCause.EncoderFailed)
        }
        val pipeline = ControllerVisibleCameraServicePipeline(VisibleCameraPipelineController(
            launcher, H264EncoderConfig(1280, 720, 2_000_000, 30, 2),
        ))
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop)

        assertEquals(VisibleCameraServiceCommandOutcome.Blocked,
            owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, sampleSnapshot()))
        val status = VisibleCameraServiceStatusStore.snapshot()
        assertEquals(FailureCause.EncoderFailed, status.cause)
        assertEquals(UserFailureCatalog.messageFor(FailureCause.EncoderFailed), status.message)
    }

    @Test
    fun serviceOwnerUsesPlannedEncoderAndFpsAtLauncher() {
        val expected = H264EncoderConfig(1920, 1080, 4_000_000, 24, 2)
        val range = CameraFpsRange(24, 30)
        val launched = mutableListOf<CameraStreamConfig>()
        val launcher = object : VisibleCameraPipelineLauncher {
            override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig) =
                VisibleCameraPipelineLaunchResult.Failed("not opened in unit test")

            override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, streamConfig: CameraStreamConfig): VisibleCameraPipelineLaunchResult {
                launched += streamConfig
                return VisibleCameraPipelineLaunchResult.Failed("not opened in unit test")
            }
        }
        val pipeline = ControllerVisibleCameraServicePipeline(VisibleCameraPipelineController(launcher, CameraQualityPlanner.plan(null, QualityPreference.Automatic).encoderConfig))
        val owner = VisibleCameraForegroundServicePipelineOwner(
            pipeline,
            VisibleCameraServiceDrainLoop.Noop,
            qualityPlanResolver = { id ->
                assertEquals("camera-1", id)
                CameraQualityPlanner.plan(null, QualityPreference.Automatic).copy(encoderConfig = expected, fpsRange = range)
            },
        )

        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, sampleSnapshot())

        assertEquals(listOf(CameraStreamConfig(expected, range)), launched)
    }

    @Test
    fun serviceOwnerFallsBackWhenQualityResolverFails() {
        val pipeline = PlanRecordingServicePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(
            pipeline,
            VisibleCameraServiceDrainLoop.Noop,
            qualityPlanResolver = { throw IllegalStateException("catalog snapshot unavailable") },
        )

        val outcome = owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, sampleSnapshot())

        assertEquals(VisibleCameraServiceCommandOutcome.Started, outcome)
        assertEquals(H264EncoderConfig(1280, 720, 2_000_000, 30, 2), pipeline.plan?.encoderConfig)
        assertEquals(null, pipeline.plan?.fpsRange)
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

private class PlanRecordingServicePipeline : VisibleCameraServicePipeline {
    var plan: QualityPlan? = null
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean) = VisibleCameraPipelineStatus.Running
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean, plan: QualityPlan): VisibleCameraPipelineStatus {
        this.plan = plan
        return VisibleCameraPipelineStatus.Running
    }
    override fun drainOnce(maxOutputs: Int) = VisibleCameraPipelineStatus.Running
    override fun stop() = VisibleCameraPipelineStatus.Stopped
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

private class FailingDetailServicePipeline(private val detail: String) : VisibleCameraServicePipeline {
    var stopped: Boolean = false

    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
        VisibleCameraPipelineStatus.Running

    override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Error

    override fun stop(): VisibleCameraPipelineStatus {
        stopped = true
        return VisibleCameraPipelineStatus.Error
    }

    override fun currentDetail(): String = detail
}

private class CallbackCapturingDrainLoop : VisibleCameraServiceDrainLoop {
    private var onPipelineStopped: (() -> Unit)? = null
    var stopCount: Int = 0

    override fun start(pipeline: VisibleCameraServicePipeline, onPipelineStopped: () -> Unit) {
        this.onPipelineStopped = onPipelineStopped
    }

    override fun stop() {
        stopCount += 1
    }

    fun invokeOnPipelineStopped() {
        onPipelineStopped?.invoke()
    }
}
