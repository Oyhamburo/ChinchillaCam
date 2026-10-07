package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VisibleCameraPipelineControllerTest {
    private val h264CsdBytes = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
        0x00, 0x00, 0x00, 0x01, 0x68, 0xce.toByte(), 0x06, 0xe2.toByte(),
    )

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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(sink) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(listOf(2), handle.consumed)
        assertEquals(listOf(0, 1), transport.payloads.map { it.chunkIndex })
        assertEquals(listOf(SessionVideoFrameKind.CODEC_CONFIG, SessionVideoFrameKind.DELTA), transport.payloads.map { it.frameKind })
        assertEquals("Cámara local activa. 2 fragmentos de video entregados al canal de salida; 0 descartados.", state.detail)
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertTrue(state.metricsText.contains("FPS: 2.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 2"))
        assertTrue(state.metricsText.contains("Chunks descartados: 0"))
        assertFalse(state.metricsText.contains("Bytes descartados"))
    }


    @Test
    fun controllerDeliversCsdBeforeVideoFramesAndFpsExcludesConfig() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(h264CsdBytes, presentationTimeUs = 0L, isCodecConfig = true, isKeyFrame = false),
            EncodedVideoChunk(byteArrayOf(0, 0, 0, 1, 0x65), presentationTimeUs = 100L, isCodecConfig = false, isKeyFrame = true),
            EncodedVideoChunk(byteArrayOf(0, 0, 0, 1, 0x41), presentationTimeUs = 200L, isCodecConfig = false, isKeyFrame = false),
        )))
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 4)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = {
                FragmentingEncodedVideoEgressSink(
                    EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake)),
                )
            },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 3)

        val payloads = listOf(
            decodeOutgoing(fake).payload as SessionPayload.VideoChunkV2,
            decodeOutgoing(fake).payload as SessionPayload.VideoChunkV2,
            decodeOutgoing(fake).payload as SessionPayload.VideoChunkV2,
        )
        assertEquals(listOf(0, 1, 2), payloads.map { it.chunkIndex })
        assertEquals(listOf(0L, 100L, 200L), payloads.map { it.presentationTimeUs })
        assertEquals(listOf(SessionVideoFrameKind.CODEC_CONFIG, SessionVideoFrameKind.KEY, SessionVideoFrameKind.DELTA), payloads.map { it.frameKind })
        assertArrayEquals(h264CsdBytes, payloads[0].h264Bytes)
        assertEquals(listOf(3), handle.consumed)
        assertTrue(state.metricsText.contains("FPS: 2.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 3"))
        assertTrue(state.metricsText.contains("Chunks descartados: 0"))
        assertEquals(null, fake.removeOutgoingEncodedAccessoryFrame())
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
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
        assertEquals("No se pudo iniciar el envío de video: sink init boom", state.detail)
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)
        val afterFailure = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(1, transport.closeCount)
        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals("El envío de video se detuvo por saturación; cámara local detenida.", state.detail)
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)
        controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(1, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals("El envío de video se detuvo porque el canal se cerró; cámara local detenida.", state.detail)
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, handle.stopCount)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertEquals(0, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals("El envío de video se detuvo: un fragmento de video superó el tamaño permitido; cámara local detenida.", state.detail)
    }

    @Test
    fun explicitStopClosesInjectedFakeEgressSink() {
        val handle = RecordingVisibleHandle()
        val transport = ControllerRecordingEncodedVideoTransport()
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transport)) },
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transports[nextTransport++])) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val stopped = controller.stopFromUser()
        controller.start(sampleSnapshot(), "camera-1", true)
        val restarted = controller.drainOnce(maxOutputs = 4)

        assertEquals("La cámara se detuvo con errores: falló el cierre del canal de salida: close boom", stopped.detail)
        assertEquals(1, secondTransport.payloads.size)
        assertEquals(VisibleCameraPipelineStatus.Running, restarted.status)
    }

    @Test
    fun reconfigureStopClosesOldHandleAndSinkAndCanStartWithNewConfig() {
        val first = RecordingVisibleHandle()
        val second = RecordingVisibleHandle()
        val transports = mutableListOf<ControllerRecordingEncodedVideoTransport>()
        val launched = mutableListOf<CameraStreamConfig>()
        val launcher = object : VisibleCameraPipelineLauncher {
            private val handles = listOf(first, second).iterator()
            override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig) =
                VisibleCameraPipelineLaunchResult.Failed("use stream config")
            override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, streamConfig: CameraStreamConfig): VisibleCameraPipelineLaunchResult {
                launched += streamConfig
                return VisibleCameraPipelineLaunchResult.Running(handles.next())
            }
        }
        val controller = VisibleCameraPipelineController(launcher, sampleConfig(), encodedVideoSinkFactory = {
            ControllerRecordingEncodedVideoTransport().also { transports += it }.let { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(it)) }
        })
        controller.start(sampleSnapshot(), "camera-1", true)

        assertEquals(VisibleCameraPipelineStatus.Stopped, controller.stopForReconfigure().status)
        val config = CameraStreamConfig(H264EncoderConfig(1920, 1080, 4_000_000, 24, 2), CameraFpsRange(24, 30))
        assertEquals(VisibleCameraPipelineStatus.Running, controller.start(sampleSnapshot(), "camera-1", true, config).status)

        assertEquals(listOf(CameraStreamConfig(sampleConfig()), config), launched)
        assertEquals(1, first.stopCount)
        assertEquals(2, transports.size)
        assertEquals(1, transports[0].closeCount)
        assertEquals(0, transports[1].closeCount)
        controller.stopFromUser()
        assertEquals(1, second.stopCount)
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
                ControllerRecordingEncodedVideoTransport().also { transports += it }.let { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(it)) }
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
            encodedVideoSinkFactory = { LegacyEncodedVideoEgressSink(EncodedVideoSessionFrameSink(transports[nextTransport++])) },
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
    fun fragmentingFakeEgressReportsLogicalAcceptedFpsAndCounters() {
        val chunks = listOf(
            EncodedVideoChunk(ByteArray(65_496) { 1 }, presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
            EncodedVideoChunk(ByteArray(65_497) { 2 }, presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = false),
        )
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(chunks))
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 8)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { FragmentingEncodedVideoEgressSink(EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(VisibleCameraPipelineStatus.Running, state.status)
        assertEquals(listOf(2), handle.consumed)
        assertTrue(state.metricsText.contains("FPS: 2.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 2"))
        assertTrue(state.metricsText.contains("Chunks descartados: 0"))
        assertEquals(SessionFrameType.VIDEO_CHUNK_V2, decodeOutgoing(fake).type)
        assertEquals(SessionFrameType.VIDEO_CHUNK_FRAGMENT_V1, decodeOutgoing(fake).type)
        assertEquals(SessionFrameType.VIDEO_CHUNK_FRAGMENT_V1, decodeOutgoing(fake).type)
    }

    @Test
    fun fragmentingPartialBackpressureDoesNotRecordFalseAcceptedFpsOrConsume() {
        val handle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(ByteArray(65_497) { 1 }, presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
        )))
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = { FragmentingEncodedVideoEgressSink(EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))) },
        )
        controller.start(sampleSnapshot(), "camera-1", true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertEquals(VisibleCameraPipelineStatus.Error, state.status)
        assertEquals(emptyList<Int>(), handle.consumed)
        assertTrue(state.metricsText.contains("FPS: 0.0"))
        assertTrue(state.metricsText.contains("Chunks aceptados: 0"))
        assertTrue(state.metricsText.contains("Chunks descartados: 1"))
        val first = decodeOutgoing(fake)
        assertEquals(0, first.sequence)
        assertEquals(0, (first.payload as SessionPayload.VideoChunkFragmentV1).fragmentIndex)
    }

    @Test
    fun restartAfterFragmentingBackpressureCreatesFreshSessionSequence() {
        val firstHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(ByteArray(65_497) { 1 }, presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true),
        )))
        val secondHandle = RecordingVisibleHandle(drainResult = H264DrainResult.Chunks(listOf(
            EncodedVideoChunk(ByteArray(65_496) { 2 }, presentationTimeUs = 2L, isCodecConfig = false, isKeyFrame = true),
        )))
        val fakes = listOf(
            UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1),
            UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 2),
        )
        var nextFake = 0
        val controller = VisibleCameraPipelineController(
            QueueVisibleLauncher(
                VisibleCameraPipelineLaunchResult.Running(firstHandle),
                VisibleCameraPipelineLaunchResult.Running(secondHandle),
            ),
            sampleConfig(),
            encodedVideoSinkFactory = {
                val fake = fakes[nextFake++]
                FragmentingEncodedVideoEgressSink(EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake)))
            },
        )

        controller.start(sampleSnapshot(), "camera-1", true)
        controller.drainOnce(maxOutputs = 4)
        controller.start(sampleSnapshot(), "camera-1", true)
        val restarted = controller.drainOnce(maxOutputs = 4)

        assertEquals(1, firstHandle.stopCount)
        assertEquals(listOf(1), secondHandle.consumed)
        val restartedFrame = decodeOutgoing(fakes[1])
        assertEquals(0, restartedFrame.sequence)
        assertEquals(SessionFrameType.VIDEO_CHUNK_V2, restartedFrame.type)
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


private fun decodeOutgoing(fake: UsbSessionFrameSustainedFakeTransport): SessionFrame {
    val accessoryBytes = fake.removeOutgoingEncodedAccessoryFrame() ?: error("expected queued frame")
    val accessory = AccessoryFrameCodec.decode(accessoryBytes, maxPayloadBytes = 65_536).getOrThrow()
    return SessionFrameCodec.decode(accessory.payload).getOrThrow()
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
