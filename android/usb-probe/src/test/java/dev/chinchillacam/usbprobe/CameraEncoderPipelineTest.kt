package dev.chinchillacam.usbprobe

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEncoderPipelineTest {
    @Test
    fun startsEncoderAndRepeatingCaptureWhenCameraOpensSynchronously() {
        val cameraDevice = CloseTrackingPipelineCamera("camera-1")
        val openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(cameraDevice) }
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        val encoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec))
        val captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Submitted)
        val pipeline = pipeline(openGateway, encoderGateway, captureGateway)

        val result = pipeline.start(sampleSnapshot(), selectedCameraId = "camera-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig())

        assertTrue(result is CameraEncoderPipelineStartResult.ConfiguringCapture)
        assertEquals(listOf("camera-1"), openGateway.requestedCameraIds)
        assertEquals(listOf(sampleEncoderConfig()), encoderGateway.configs)
        assertEquals(listOf("camera-1:encoder-input"), captureGateway.requests)
    }

    @Test
    fun returnsOpeningWhenCameraOpenIsPendingAndCanContinueAfterCallback() {
        val openGateway = RecordingOpenGateway()
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        val captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Submitted)
        val result = pipeline(openGateway, RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec)), captureGateway)
            .start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig())
        val opening = result as CameraEncoderPipelineStartResult.Opening

        opening.openSession.callbacks.onOpened(CloseTrackingPipelineCamera("camera-1"))
        val continued = opening.continueAfterCameraOpened()

        assertTrue(continued is CameraEncoderPipelineStartResult.ConfiguringCapture)
        assertEquals(listOf("camera-1:encoder-input"), captureGateway.requests)
    }

    @Test
    fun rejectsMissingPermissionBeforeOpeningCamera() {
        val openGateway = RecordingOpenGateway()
        val result = pipeline(openGateway).start(sampleSnapshot(), "camera-1", cameraPermissionGranted = false, encoderConfig = sampleEncoderConfig())

        assertEquals(CameraEncoderPipelineStartResult.Failed("camera permission missing"), result)
        assertEquals(emptyList<String>(), openGateway.requestedCameraIds)
    }

    @Test
    fun rejectsStaleOrPhysicalOnlySelectionBeforeOpeningCamera() {
        val openGateway = RecordingOpenGateway()
        val result = pipeline(openGateway).start(sampleSnapshot(), "physical-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig())

        assertEquals(CameraEncoderPipelineStartResult.Failed("selected camera is not directly openable: physical-1"), result)
        assertEquals(emptyList<String>(), openGateway.requestedCameraIds)
    }

    @Test
    fun encoderFailureClosesOpenedCamera() {
        val cameraDevice = CloseTrackingPipelineCamera("camera-1")
        val openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(cameraDevice) }
        val result = pipeline(openGateway, RecordingEncoderGateway(H264EncoderStartOutcome.Failed("encoder unavailable")))
            .start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig())

        assertEquals(CameraEncoderPipelineStartResult.Failed("encoder unavailable"), result)
        assertEquals(1, cameraDevice.closeCount)
    }

    @Test
    fun captureConfigurationFailureStopsEncoderAndCamera() {
        val cameraDevice = CloseTrackingPipelineCamera("camera-1")
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        val result = pipeline(
            openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(cameraDevice) },
            encoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec)),
            captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Failed("capture rejected")),
        ).start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig())

        assertEquals(CameraEncoderPipelineStartResult.Failed("capture rejected"), result)
        assertEquals(1, codec.stopCount)
        assertEquals(1, codec.releaseCount)
        assertEquals(1, cameraDevice.closeCount)
    }

    @Test
    fun asynchronousCaptureConfigureFailureStopsEncoderAndCameraBeforeStarted() {
        val cameraDevice = CloseTrackingPipelineCamera("camera-1")
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        val captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Submitted)
        val configuring = pipeline(
            openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(cameraDevice) },
            encoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec)),
            captureGateway = captureGateway,
        ).start(sampleSnapshot(), "camera-1", true, sampleEncoderConfig()) as CameraEncoderPipelineStartResult.ConfiguringCapture

        captureGateway.callbacks.single().onConfigureFailed(CloseTrackingPipelineRepeatingSession("camera-1"))
        val result = configuring.continueAfterCaptureConfigured()

        assertEquals(CameraEncoderPipelineStartResult.Failed("capture configuration failed"), result)
        assertEquals(1, codec.stopCount)
        assertEquals(1, codec.releaseCount)
        assertEquals(1, cameraDevice.closeCount)
    }

    @Test
    fun drainReturnsEncodedChunksFromStartedPipeline() {
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        codec.outputs += H264CodecOutput.Buffer(7, ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3, 9)), H264BufferInfo(1, 3, 123L, H264BufferFlags.KEY_FRAME))
        val captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Submitted)
        val configuring = pipeline(
            openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(CloseTrackingPipelineCamera("camera-1")) },
            encoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec)),
            captureGateway = captureGateway,
        ).start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true, encoderConfig = sampleEncoderConfig()) as CameraEncoderPipelineStartResult.ConfiguringCapture
        captureGateway.callbacks.single().onConfigured(CloseTrackingPipelineRepeatingSession("camera-1"))
        val started = configuring.continueAfterCaptureConfigured() as CameraEncoderPipelineStartResult.Started

        val drained = started.session.drainEncoded(maxOutputs = 1) as H264DrainResult.Chunks

        assertArrayEquals(byteArrayOf(1, 2, 3), drained.chunks.single().bytes)
        assertEquals(123L, drained.chunks.single().presentationTimeUs)
        assertTrue(drained.chunks.single().isKeyFrame)
        assertEquals(listOf(7), codec.releasedOutputBuffers)
    }

    @Test
    fun stopClosesCaptureEncoderAndCamera() {
        val cameraDevice = CloseTrackingPipelineCamera("camera-1")
        val encoderSurface = PipelineEncoderSurface("encoder-input")
        val codec = PipelineCodecSession(encoderSurface)
        val captureGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Submitted)
        val configuring = pipeline(
            openGateway = RecordingOpenGateway { _, callbacks -> callbacks.onOpened(cameraDevice) },
            encoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Started(encoderSurface, codec)),
            captureGateway = captureGateway,
        ).start(sampleSnapshot(), "camera-1", true, sampleEncoderConfig()) as CameraEncoderPipelineStartResult.ConfiguringCapture
        val repeatingSession = CloseTrackingPipelineRepeatingSession("camera-1")
        captureGateway.callbacks.single().onConfigured(repeatingSession)
        val started = configuring.continueAfterCaptureConfigured() as CameraEncoderPipelineStartResult.Started

        val stopResult = started.session.stop()

        assertEquals(CameraEncoderPipelineStopResult.Stopped, stopResult)
        assertEquals(1, repeatingSession.stopRepeatingCount)
        assertEquals(1, repeatingSession.closeCount)
        assertEquals(1, codec.stopCount)
        assertEquals(1, codec.releaseCount)
        assertEquals(1, cameraDevice.closeCount)
    }

    @Test
    fun repeatingSessionStopAttemptsCloseWhenStopRepeatingThrows() {
        val repeatingSession = CloseTrackingPipelineRepeatingSession("camera-1", stopFailure = RuntimeException("stop failed"))
        val session = RepeatingCaptureSession("camera-1")
        session.callbacks.onConfigured(repeatingSession)

        val result = session.stop()

        assertEquals(RepeatingCaptureStopResult.Failed(listOf("stop repeating failed")), result)
        assertEquals(1, repeatingSession.stopRepeatingCount)
        assertEquals(1, repeatingSession.closeCount)
        assertTrue(session.isClosed)
    }

    private fun pipeline(
        openGateway: CameraDeviceOpenGateway = RecordingOpenGateway(),
        encoderGateway: H264EncoderGateway = RecordingEncoderGateway(H264EncoderStartOutcome.Failed("unused")),
        captureGateway: CameraCaptureSessionGateway = RecordingCaptureGateway(CaptureSessionRequestOutcome.Failed("unused")),
    ): CameraEncoderPipeline = CameraEncoderPipeline(
        cameraOpenBoundary = CameraDeviceOpenBoundary(openGateway),
        encoderBoundary = H264EncoderBoundary(encoderGateway, maxPendingChunks = 2),
        captureSessionBoundary = CameraCaptureSessionBoundary(captureGateway),
    )

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

    private fun sampleEncoderConfig(): H264EncoderConfig = H264EncoderConfig(1280, 720, 2_000_000, 30, 2)
}

private class RecordingOpenGateway(
    private val onRequest: ((String, CameraOpenCallbacks) -> Unit)? = null,
) : CameraDeviceOpenGateway {
    val requestedCameraIds = mutableListOf<String>()
    override fun requestOpen(cameraId: String, callbacks: CameraOpenCallbacks): CameraDeviceOpenRequestOutcome {
        requestedCameraIds += cameraId
        onRequest?.invoke(cameraId, callbacks)
        return CameraDeviceOpenRequestOutcome.Submitted
    }
}

private class RecordingEncoderGateway(
    private val outcome: H264EncoderStartOutcome,
) : H264EncoderGateway {
    val configs = mutableListOf<H264EncoderConfig>()
    override fun start(config: H264EncoderConfig): H264EncoderStartOutcome {
        configs += config
        return outcome
    }
}

private class RecordingCaptureGateway(
    private val outcome: CaptureSessionRequestOutcome,
) : CameraCaptureSessionGateway {
    val requests = mutableListOf<String>()
    val callbacks = mutableListOf<CaptureSessionCallbacks>()
    override fun configureRepeating(cameraId: String, targetSurface: CaptureTargetSurface, callbacks: CaptureSessionCallbacks): CaptureSessionRequestOutcome {
        requests += "$cameraId:${targetSurface.label}"
        this.callbacks += callbacks
        return outcome
    }
}

private data class PipelineEncoderSurface(
    override val label: String,
) : EncoderInputSurface

private class PipelineCodecSession(
    override val inputSurface: EncoderInputSurface,
) : CloseableH264CodecSession {
    val outputs = ArrayDeque<H264CodecOutput>()
    val releasedOutputBuffers = mutableListOf<Int>()
    var stopCount = 0
    var releaseCount = 0
    override fun dequeueOutput(): H264CodecOutput = outputs.removeFirstOrNull() ?: H264CodecOutput.TryAgainLater
    override fun releaseOutputBuffer(index: Int) { releasedOutputBuffers += index }
    override fun stop(): H264CodecCloseOutcome { stopCount += 1; return H264CodecCloseOutcome.Closed }
    override fun release(): H264CodecCloseOutcome { releaseCount += 1; return H264CodecCloseOutcome.Closed }
}

private class CloseTrackingPipelineCamera(
    override val cameraId: String,
) : CloseableCameraDevice {
    var closeCount = 0
    override fun close() { closeCount += 1 }
}

private class CloseTrackingPipelineRepeatingSession(
    override val cameraId: String,
    private val stopFailure: RuntimeException? = null,
) : CloseableRepeatingCaptureSession {
    var stopRepeatingCount = 0
    var closeCount = 0
    override fun stopRepeating() {
        stopRepeatingCount += 1
        stopFailure?.let { throw it }
    }
    override fun close() { closeCount += 1 }
}
