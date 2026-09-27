package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    fun drainTickWritesChunksToInjectedFakeEgressSinkAndConsumesAcceptedMemory() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = true, isKeyFrame = true),
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val transport = ControllerRecordingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { sink },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(listOf(2), handle.consumed)
        assertEquals(listOf(0, 1), transport.payloads.map { it.chunkIndex })
        assertEquals(listOf(SessionVideoFrameKind.CODEC_CONFIG, SessionVideoFrameKind.DELTA), transport.payloads.map { it.frameKind })
        assertEquals("Cámara local activa. 2 chunks codificados enviados al egreso fake; 0 descartados.", state.detail)
        assertTrue(state.metricsText.contains("Chunks aceptados: 2"))
        assertTrue(state.metricsText.contains("Chunks descartados: 0"))
    }

    @Test
    fun acceptedFakeEgressChunksReportFpsAndCountersWithoutFalseDiscardMetrics() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val transport = ControllerRecordingEncodedVideoTransport()
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertTrue(state.metricsText.contains("FPS: 2.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 2"))
        assertTrue(state.metricsText.contains("Chunks descartados: 0"))
        assertFalse(state.metricsText.contains("Bytes descartados"))
    }

    @Test
    fun partialFakeEgressSuccessRecordsOnlyAcceptedChunksBeforeFailingClosed() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = false),
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val transport = ControllerRecordingEncodedVideoTransport(
            EncodedVideoSessionFrameWriteResult.Written,
            EncodedVideoSessionFrameWriteResult.BackpressureExceeded,
        )
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertTrue(state.metricsText.contains("FPS: 1.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 1"))
        assertTrue(state.metricsText.contains("Chunks descartados: 1"))
        assertEquals(2, transport.payloads.size)
    }

    @Test
    fun throwingFakeEgressFactoryStopsNewHandleAndReportsTypedStartError() {
        val handle = RecordingVisibleHandle()
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { throw IllegalStateException("sink init boom") },
        )

        val state = controller.start(sampleSnapshot(), "camera-1", true)
        val afterFailure = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("Egreso fake no pudo iniciar: sink init boom", state.detail)
        assertEquals(state, afterFailure)
    }

    @Test
    fun fakeEgressBackpressureStopsActiveHandleAndDoesNotConsumePendingMemory() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
        )))
        val transport = ControllerRecordingEncodedVideoTransport(EncodedVideoSessionFrameWriteResult.BackpressureExceeded)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)
        val afterFailure = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(1, transport.closeCount)
        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("Egreso fake detenido por backpressure; cámara local detenida.", state.detail)
        assertEquals(state, afterFailure)
    }

    @Test
    fun fakeEgressClosedStopsActiveHandleAndPreventsLaterWrites() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = false),
        )))
        val transport = ControllerRecordingEncodedVideoTransport(EncodedVideoSessionFrameWriteResult.Closed)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)
        controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(1, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals("Egreso fake cerrado; cámara local detenida.", state.detail)
    }

    @Test
    fun fakeEgressOversizedChunkStopsActiveHandleWithoutWriting() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(ByteArray(65_536), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
        )))
        val transport = ControllerRecordingEncodedVideoTransport()
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(0, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals("Egreso fake rechazó chunk H.264 oversized; cámara local detenida.", state.detail)
    }

    @Test
    fun explicitStopClosesInjectedFakeEgressSink() {
        val handle = RecordingVisibleHandle()
        val transport = ControllerRecordingEncodedVideoTransport()
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transport) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        controller.stopFromUser()

        assertEquals(1, handle.stopCount)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun throwingFakeEgressCloseDoesNotRetainClosedSinkAcrossRestart() {
        val firstHandle = RecordingVisibleHandle()
        val secondHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val firstTransport = ControllerRecordingEncodedVideoTransport(closeFailure = IllegalStateException("close boom"))
        val secondTransport = ControllerRecordingEncodedVideoTransport()
        val transports = listOf(firstTransport, secondTransport)
        var nextTransport = 0
        val controller = VisibleCameraPipelineController(
            QueueVisibleLauncher(
                VisibleCameraPipelineLaunchResult.Running(firstHandle),
                VisibleCameraPipelineLaunchResult.Running(secondHandle),
            ),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transports[nextTransport++]) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val stopped = controller.stopFromUser()
        controller.start(sampleSnapshot(), "camera-1", true)
        val restarted = controller.drainOnce(maxOutputs = 4)

        assertEquals("La cámara se detuvo con errores: falló el cierre del egreso simulado: close boom", stopped.detail)
        assertEquals(1, secondTransport.payloads.size)
        assertEquals(VisibleCameraPipelineStatus.Running, restarted.status)
    }

    @Test
    fun restartAfterExplicitStopCreatesFreshFakeEgressSink() {
        val firstHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = false),
        )))
        val secondHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = true),
        )))
        val transports = mutableListOf<ControllerRecordingEncodedVideoTransport>()
        val controller = VisibleCameraPipelineController(
            QueueVisibleLauncher(
                VisibleCameraPipelineLaunchResult.Running(firstHandle),
                VisibleCameraPipelineLaunchResult.Running(secondHandle),
            ),
            sampleConfig(),
            encodedVideoSinkFactory = {
                ControllerRecordingEncodedVideoTransport().also { transports += it }.let(::EncodedVideoSessionFrameSink)
            },
        )

        controller.start(sampleSnapshot(), "camera-1", true)
        controller.drainOnce(maxOutputs = 4)
        controller.stopFromUser()
        controller.start(sampleSnapshot(), "camera-1", true)
        val restarted = controller.drainOnce(maxOutputs = 4)

        assertEquals(2, transports.size)
        assertEquals(1, transports[0].closeCount)
        assertEquals(1, transports[0].payloads.size)
        assertEquals(1, transports[1].payloads.size)
        assertEquals(SessionVideoFrameKind.KEY, transports[1].payloads.single().frameKind)
        assertEquals(VisibleCameraPipelineStatus.Running, restarted.status)
    }

    @Test
    fun restartAfterFakeEgressBackpressureCreatesFreshSinkInsteadOfReopeningClosedTransport() {
        val firstHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(1), presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = false),
        )))
        val secondHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(byteArrayOf(2), presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )))
        val transports = mutableListOf(
            ControllerRecordingEncodedVideoTransport(EncodedVideoSessionFrameWriteResult.BackpressureExceeded),
            ControllerRecordingEncodedVideoTransport(),
        )
        var nextTransport = 0
        val controller = VisibleCameraPipelineController(
            QueueVisibleLauncher(
                VisibleCameraPipelineLaunchResult.Running(firstHandle),
                VisibleCameraPipelineLaunchResult.Running(secondHandle),
            ),
            sampleConfig(),
            encodedVideoSinkFactory = { EncodedVideoSessionFrameSink(transports[nextTransport++]) },
        )

        controller.start(sampleSnapshot(), "camera-1", true)
        controller.drainOnce(maxOutputs = 4)
        controller.start(sampleSnapshot(), "camera-1", true)
        val restarted = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, firstHandle.stopCount)
        assertEquals(listOf(1), secondHandle.consumed)
        assertEquals(1, transports[0].closeCount)
        assertEquals(1, transports[1].payloads.size)
        assertEquals(VisibleCameraPipelineStatus.Running, restarted.status)
    }

    @Test
    fun repeatedStartWhileRunningDoesNotLaunchOrReplaceHandle() {
        val firstHandle = RecordingVisibleHandle()
        val launcher = QueueVisibleLauncher(
            VisibleCameraPipelineLaunchResult.Running(firstHandle),
            VisibleCameraPipelineLaunchResult.Running(RecordingVisibleHandle()),
        )
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.start(sampleSnapshot(), "camera-1", true)

        assertEquals(1, launcher.starts)
        assertEquals(VisibleCameraPipelineStatus.Running, state.status)
        controller.stopFromUser()
        assertEquals(1, firstHandle.stopCount)
    }

    @Test
    fun drainFailureStopsActiveHandleBeforeShowingError() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Failed("codec died"))
        val controller = VisibleCameraPipelineController(RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)), sampleConfig())
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("Error al drenar encoder: codec died", state.detail)
    }


    @Test
    fun lifecycleStopDuringPendingStartReturnsPromptlyAndClosesLateHandle() {
        val lateHandle = RecordingVisibleHandle()
        val launcher = BlockingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(lateHandle))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())
        val token = controller.prepareStart() ?: error("start token expected")
        val worker = thread {
            controller.completeStart(token, sampleSnapshot(), "camera-1", true)
        }
        assertTrue(launcher.entered.await(1, TimeUnit.SECONDS))

        val beforeStop = System.nanoTime()
        val stopped = controller.stopForLifecycle()
        val stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeStop)
        launcher.release.countDown()
        worker.join(1_000)

        assertTrue("stop should not wait for pending launcher", stopMillis < 200)
        assertEquals(VisibleCameraPipelineStatus.Stopped, stopped.status)
        assertEquals(1, lateHandle.stopCount)
        assertEquals(VisibleCameraPipelineStatus.Stopped, controller.currentState().status)
    }

    @Test
    fun lateFailureAfterLifecycleStopDoesNotOverwriteStoppedState() {
        val launcher = BlockingVisibleLauncher(VisibleCameraPipelineLaunchResult.Failed("late failure"))
        val controller = VisibleCameraPipelineController(launcher, sampleConfig())
        val token = controller.prepareStart() ?: error("start token expected")
        val worker = thread {
            controller.completeStart(token, sampleSnapshot(), "camera-1", true)
        }
        assertTrue(launcher.entered.await(1, TimeUnit.SECONDS))
        controller.stopForLifecycle()

        launcher.release.countDown()
        worker.join(1_000)

        assertEquals(VisibleCameraPipelineStatus.Stopped, controller.currentState().status)
        assertEquals("Cámara local detenida al ocultar la app.", controller.currentState().detail)
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


private class QueueVisibleLauncher(
    private vararg val results: VisibleCameraPipelineLaunchResult,
) : VisibleCameraPipelineLauncher {
    var starts = 0
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig): VisibleCameraPipelineLaunchResult {
        val index = starts.coerceAtMost(results.lastIndex)
        starts += 1
        return results[index]
    }
}


private class BlockingVisibleLauncher(
    private val result: VisibleCameraPipelineLaunchResult,
) : VisibleCameraPipelineLauncher {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig): VisibleCameraPipelineLaunchResult {
        entered.countDown()
        release.await(1, TimeUnit.SECONDS)
        return result
    }
}

private class ControllerRecordingEncodedVideoTransport(
    private vararg val results: EncodedVideoSessionFrameWriteResult = arrayOf(EncodedVideoSessionFrameWriteResult.Written),
    private val closeFailure: RuntimeException? = null,
) : EncodedVideoSessionFrameTransport {
    val payloads = mutableListOf<SessionPayload.VideoChunkV2>()
    var closeCount = 0

    override fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult {
        val result = results[payloads.size.coerceAtMost(results.lastIndex)]
        payloads += payload
        return result
    }

    override fun close() {
        closeCount += 1
        closeFailure?.let { throw it }
    }
}
