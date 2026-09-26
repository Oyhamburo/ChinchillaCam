package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleCameraPipelineControllerTest {
    @Test
    fun startRequiresFreshCameraPermissionBeforeLaunching() {
        val launcher = RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Failed("unused"))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())

        val state = controller.start(sampleSnapshot(), selectedCameraId = "camera-1", cameraPermissionGranted = false)

        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("Permiso de cámara requerido antes de iniciar.", state.detail)
        assertEquals(0, launcher.starts)
    }

    @Test
    fun startUsesCurrentSelectedDirectCameraAndReportsRunningSpanishState() {
        val handle = RecordingVisibleHandle()
        val launcher = RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())

        val state = controller.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true)

        assertEquals(VisibleCameraPipelineStatus.Running, state.status)
        assertEquals("Cámara local activa. Video codificado se descarta en memoria; no se transmite ni se graba.", state.detail)
        assertEquals(listOf("camera-1"), launcher.selectedIds)
    }

    @Test
    fun rejectsPhysicalOnlySelectionBeforeLaunch() {
        val launcher = RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(RecordingVisibleHandle()))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())

        val state = controller.start(sampleSnapshot(), "physical-1", cameraPermissionGranted = true)

        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("Selecciona una cámara directa antes de iniciar.", state.detail)
        assertEquals(0, launcher.starts)
    }

    @Test
    fun explicitStopClosesRunningHandleAndReportsCleanupErrors() {
        val handle = RecordingVisibleHandle(stopResult = CameraEncoderPipelineStopResult.Failed(listOf("encoder release failed")))
        val controller = VisibleCameraPipelineController(RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)), sampleConfig())
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.stopFromUser()

        assertEquals(1, handle.stopCount)
        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("La cámara se detuvo con errores: encoder release failed", state.detail)
    }

    @Test
    fun lifecycleStopClosesRunningHandleAndDoesNotAutoRestart() {
        val handle = RecordingVisibleHandle()
        val launcher = RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())
        controller.start(sampleSnapshot(), "camera-1", true)

        val stopped = controller.stopForLifecycle()
        val resumed = controller.currentState()

        assertEquals(1, handle.stopCount)
        assertEquals(VisibleCameraPipelineStatus.Stopped, stopped.status)
        assertEquals(VisibleCameraPipelineStatus.Stopped, resumed.status)
        assertEquals(1, launcher.starts)
    }

    @Test
    fun drainTickDiscardsChunksAndConsumesPendingMemory() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val controller = VisibleCameraPipelineController(RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)), sampleConfig())
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.drainCount)
        assertEquals(listOf(2), handle.consumed)
        assertEquals("Cámara local activa. 2 chunks codificados descartados en memoria.", state.detail)
    }

    @Test
    fun terminalOpenCallbackBecomesFailureInsteadOfPendingForever() {
        val openGateway = VisibleTestOpenGateway { _, callbacks -> callbacks.onDisconnected(VisibleTestCamera("camera-1")) }
        val pipeline = CameraEncoderPipeline(
            cameraOpenBoundary = CameraDeviceOpenBoundary(openGateway),
            encoderBoundary = H264EncoderBoundary(VisibleTestEncoderGateway(H264EncoderStartOutcome.Failed("should not start"))),
            captureSessionBoundary = CameraCaptureSessionBoundary(VisibleTestCaptureGateway(CaptureSessionRequestOutcome.Submitted)),
        )

        val result = pipeline.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true, encoderConfig = sampleConfig())

        assertEquals(CameraEncoderPipelineStartResult.Failed("camera open did not complete"), result)
    }

    private fun sampleConfig(): H264EncoderConfig = H264EncoderConfig(1280, 720, 2_000_000, 30, 2)

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
            CameraCatalogEntry(
                id = "physical-1",
                role = CameraIdRole.PhysicalOnlyChild("camera-1"),
                facing = CapabilityState.Known(CameraFacing.Back),
                outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
                controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)),
            ),
        ),
    )
}

private class RecordingVisibleLauncher(
    private val result: VisibleCameraPipelineLaunchResult,
) : VisibleCameraPipelineLauncher {
    var starts = 0
    val selectedIds = mutableListOf<String?>()
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig): VisibleCameraPipelineLaunchResult {
        starts += 1
        selectedIds += selectedCameraId
        return result
    }
}

private class RecordingVisibleHandle(
    private val drainResult: H264DrainResult = H264DrainResult.TryAgainLater,
    private val stopResult: CameraEncoderPipelineStopResult = CameraEncoderPipelineStopResult.Stopped,
) : VisibleCameraPipelineHandle {
    var drainCount = 0
    var stopCount = 0
    val consumed = mutableListOf<Int>()
    override fun drainEncoded(maxOutputs: Int): H264DrainResult {
        drainCount += 1
        return drainResult
    }
    override fun consumeEncoded(count: Int) {
        consumed += count
    }
    override fun stop(): CameraEncoderPipelineStopResult {
        stopCount += 1
        return stopResult
    }
}


private class VisibleTestOpenGateway(
    private val onRequest: ((String, CameraOpenCallbacks) -> Unit)? = null,
) : CameraDeviceOpenGateway {
    override fun requestOpen(cameraId: String, callbacks: CameraOpenCallbacks): CameraDeviceOpenRequestOutcome {
        onRequest?.invoke(cameraId, callbacks)
        return CameraDeviceOpenRequestOutcome.Submitted
    }
}

private class VisibleTestEncoderGateway(
    private val outcome: H264EncoderStartOutcome,
) : H264EncoderGateway {
    override fun start(config: H264EncoderConfig): H264EncoderStartOutcome = outcome
}

private class VisibleTestCaptureGateway(
    private val outcome: CaptureSessionRequestOutcome,
) : CameraCaptureSessionGateway {
    override fun configureRepeating(cameraId: String, targetSurface: CaptureTargetSurface, callbacks: CaptureSessionCallbacks): CaptureSessionRequestOutcome = outcome
}

private class VisibleTestCamera(
    override val cameraId: String,
) : CloseableCameraDevice {
    override fun close() = Unit
}
