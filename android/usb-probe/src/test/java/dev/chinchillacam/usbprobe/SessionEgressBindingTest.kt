package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task p2 (`odd/tasks/session-pipeline-wiring.md` §4.1, decision 1): a phone-side
 * [SessionEgressBinding] starts a [SessionRuntime] from a reconnection, exposes a trivial video sink
 * factory, and on any [SessionEnd] other than [SessionEnd.LocalClose] hands a typed Spanish message
 * off the runtime thread so the camera pipeline can be stopped with a visible error. The fake desktop
 * is [RawStreamTlsTestSupport.RawStreamServerPeer] over raw TLS (no network).
 */
class SessionEgressBindingTest {
    private val sessionId = "p2-session"
    private val nextOutbound = 1
    private val nextInbound = 2

    private fun phoneAdapter() = TlsSessionFrameIoAdapter(idleBudgetMillis = 3_000, readTimeoutMillis = 3_000)

    /** Accumulating, split/coalesce-safe reader of length-prefixed frames off the raw peer. */
    private class FramedPeerReader(private val peer: RawStreamTlsTestSupport.RawStreamServerPeer) {
        private var buffer = ByteArray(0)

        fun next(): SessionFrame {
            while (true) {
                parseBuffered()?.let { return it }
                buffer += peer.readApplicationFrame()
            }
        }

        private fun parseBuffered(): SessionFrame? {
            if (buffer.size < 4) return null
            val length = ((buffer[0].toInt() and 0xff) shl 24) or
                ((buffer[1].toInt() and 0xff) shl 16) or
                ((buffer[2].toInt() and 0xff) shl 8) or
                (buffer[3].toInt() and 0xff)
            if (buffer.size < 4 + length) return null
            val frame = SessionFrameCodec.decode(buffer.copyOfRange(4, 4 + length)).getOrThrow()
            buffer = buffer.copyOfRange(4 + length, buffer.size)
            return frame
        }
    }

    private fun establishPhoneChannel(
        desktop: RawStreamTlsTestSupport.DesktopFixture,
        phone: PhoneTlsIdentity,
        endpoints: RawStreamTlsTestSupport.RawStreamEndpoints,
    ): SslEngineUsbTlsEstablishedChannel {
        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        val result = SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(transport, desktop.spki)
        result as SslEngineUsbTlsHandshakeResult.Authenticated
        return result.channel
    }

    private fun flowConfig() = SessionRuntimeConfig(
        keepaliveIntervalMillis = 100,
        deadPeerThresholdMillis = 5_000,
        writerTickMillis = 20,
        outboundQueueCapacity = 64,
        joinTimeoutMillis = 2_000,
    )

    private fun deadConfig() = SessionRuntimeConfig(
        keepaliveIntervalMillis = 100,
        deadPeerThresholdMillis = 400,
        writerTickMillis = 20,
        outboundQueueCapacity = 64,
        joinTimeoutMillis = 2_000,
    )

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
        ),
    )

    @Test
    fun videoFlowsFromControllerThroughBinding() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("p2-flow-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("p2-flow-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val frames = CopyOnWriteArrayList<SessionFrame>()
        val collected = CountDownLatch(1)
        val release = CountDownLatch(1)

        val inputs = listOf(
            ByteArray(512) { (it and 0xff).toByte() },
            ByteArray(777) { ((it * 3 + 1) and 0xff).toByte() },
        )
        val expectedChunks = inputs.size

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val reader = FramedPeerReader(peer)
                while (true) {
                    val frame = reader.next()
                    frames.add(frame)
                    val videoChunkIndexes = frames.mapNotNull {
                        (it.payload as? SessionPayload.VideoChunkV2)?.chunkIndex
                    }.toSet()
                    if (videoChunkIndexes.size >= expectedChunks) {
                        collected.countDown()
                        break
                    }
                }
                // Stay alive during the assertions so closing the peer never races a ReadFailed end.
                release.await(5, TimeUnit.SECONDS)
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val endExecutor = Executors.newSingleThreadExecutor { Thread(it, "p2-end-executor") }
        val errorEnds = CopyOnWriteArrayList<Pair<SessionEnd, String>>()
        val binding = SessionEgressBinding.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            onCameraControlCommand = {},
            onSessionEndedWithError = { end, message -> errorEnds.add(end to message) },
            endExecutor = endExecutor,
            config = flowConfig(),
        )

        val handle = RecordingVisibleHandle(
            drainResult = H264DrainResult.Chunks(
                inputs.mapIndexed { index, bytes ->
                    EncodedVideoChunk(bytes, presentationTimeUs = index.toLong(), isCodecConfig = false, isKeyFrame = index == 0)
                },
            ),
        )
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = binding.videoSinkFactory,
        )

        try {
            controller.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true)
            val state = controller.drainOnce(maxOutputs = 4)
            assertEquals(VisibleCameraPipelineStatus.Running, state.status)
            assertTrue("fake desktop never collected the frames", collected.await(4, TimeUnit.SECONDS))

            val ordered = frames.toList()
            assertTrue("no frames were received", ordered.isNotEmpty())
            var previous = ordered.first().sequence
            assertEquals(nextOutbound, previous)
            for (frame in ordered.drop(1)) {
                assertEquals("sequence must advance by exactly 1", previous + 1, frame.sequence)
                previous = frame.sequence
            }
            ordered.forEach { assertEquals(sessionId, it.sessionId) }

            inputs.forEachIndexed { index, expected ->
                val chunk = ordered.mapNotNull { it.payload as? SessionPayload.VideoChunkV2 }
                    .firstOrNull { it.chunkIndex == index }
                assertNotNull("chunk $index was not received", chunk)
                assertArrayEquals("chunk $index did not match the input", expected, chunk!!.h264Bytes)
            }
            assertTrue("no error end expected on a clean flow", errorEnds.isEmpty())
        } finally {
            release.countDown()
            binding.close()
            endExecutor.shutdownNow()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun peerDeadStopsPipelineWithVisibleError() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("p2-dead-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("p2-dead-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                // Never respond: the tracker declares the peer dead after the threshold.
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val endExecutor = Executors.newSingleThreadExecutor { Thread(it, "p2-end-executor") }
        val callbackThread = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val stopCalls = AtomicInteger(0)
        val compositionRef = AtomicReference<SessionEgressServicePipelineComposition>()

        VisibleCameraServiceStatusStore.clearStopped("reset for test")
        val binding = SessionEgressBinding.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            onCameraControlCommand = {},
            onSessionEndedWithError = { end, message ->
                callbackThread.set(Thread.currentThread().name)
                compositionRef.get().onSessionEndedWithError(end, message)
                latch.countDown()
            },
            endExecutor = endExecutor,
            config = deadConfig(),
        )
        val composition = SessionEgressServicePipelineComposition(binding) { stopCalls.incrementAndGet() }
        compositionRef.set(composition)

        try {
            assertTrue("peer-dead error was not reported", latch.await(4, TimeUnit.SECONDS))
            val thread = callbackThread.get()
            assertNotNull("callback thread was not recorded", thread)
            assertEquals("the error must be handed off the runtime thread", "p2-end-executor", thread)
            assertFalse("must not run on the runtime reader thread", thread!!.contains("session-runtime-reader"))
            assertFalse("must not run on the runtime writer thread", thread.contains("session-runtime-writer"))

            val status = VisibleCameraServiceStatusStore.snapshot()
            assertEquals(VisibleCameraServiceState.Error, status.state)
            assertEquals("Se perdió la conexión con la computadora.", status.message)
            assertEquals(1, stopCalls.get())
        } finally {
            binding.close()
            endExecutor.shutdownNow()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun localCloseDoesNotReportError() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("p2-local-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("p2-local-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverReady = CountDownLatch(1)
        val release = CountDownLatch(1)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                serverReady.countDown()
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
                release.await(5, TimeUnit.SECONDS)
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val endExecutor = Executors.newSingleThreadExecutor { Thread(it, "p2-end-executor") }
        val errorEnds = CopyOnWriteArrayList<Pair<SessionEnd, String>>()
        val binding = SessionEgressBinding.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            onCameraControlCommand = {},
            onSessionEndedWithError = { end, message -> errorEnds.add(end to message) },
            endExecutor = endExecutor,
            config = flowConfig(),
        )

        try {
            assertTrue("server handshake did not complete", serverReady.await(4, TimeUnit.SECONDS))
            binding.close()
            // Idempotent: a second close from another thread must also be safe.
            binding.close()
            // Give any erroneous handoff a chance to land before asserting it did not.
            Thread.sleep(300)
            assertTrue("LocalClose must not report a user-visible error", errorEnds.isEmpty())
        } finally {
            release.countDown()
            endExecutor.shutdownNow()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun endDuringConcurrentDrainDoesNotDeadlock() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("p2-drain-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("p2-drain-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverReady = CountDownLatch(1)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                serverReady.countDown()
                // Silent after the handshake -> PeerDead ends the session mid-drain.
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val endExecutor = Executors.newSingleThreadExecutor { Thread(it, "p2-end-executor") }
        val closeReturned = CountDownLatch(1)
        val errorReported = CountDownLatch(1)
        val bindingRef = AtomicReference<SessionEgressBinding>()
        val binding = SessionEgressBinding.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            onCameraControlCommand = {},
            onSessionEndedWithError = { _, _ ->
                errorReported.countDown()
                // Closing from inside the end-executor handoff must not deadlock with the drain.
                bindingRef.get().close()
                closeReturned.countDown()
            },
            endExecutor = endExecutor,
            config = deadConfig(),
        )
        bindingRef.set(binding)

        val handle = RecordingVisibleHandle(
            drainResult = H264DrainResult.Chunks(
                listOf(EncodedVideoChunk(ByteArray(256) { 7 }, presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = true)),
            ),
        )
        val controller = VisibleCameraPipelineController(
            RecordingVisibleLauncher(VisibleCameraPipelineLaunchResult.Running(handle)),
            sampleConfig(),
            encodedVideoSinkFactory = binding.videoSinkFactory,
        )
        controller.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true)

        val draining = AtomicInteger(0)
        val drainThread = thread(name = "p2-drain-loop") {
            while (draining.get() >= 0) {
                val status = controller.drainOnce(maxOutputs = 4)
                if (status.status != VisibleCameraPipelineStatus.Running) break
                Thread.sleep(5)
            }
        }

        try {
            assertTrue("server handshake did not complete", serverReady.await(4, TimeUnit.SECONDS))
            assertTrue("session end was not reported under concurrent drain", errorReported.await(5, TimeUnit.SECONDS))
            assertTrue("binding.close deadlocked under concurrent drain", closeReturned.await(5, TimeUnit.SECONDS))
        } finally {
            draining.set(-1)
            drainThread.join(4_000)
            binding.close()
            endExecutor.shutdownNow()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    private class RecordingVisibleLauncher(
        private val result: VisibleCameraPipelineLaunchResult,
    ) : VisibleCameraPipelineLauncher {
        override fun start(
            snapshot: CameraCatalogSnapshot,
            selectedCameraId: String?,
            cameraPermissionGranted: Boolean,
            encoderConfig: H264EncoderConfig,
        ): VisibleCameraPipelineLaunchResult = result
    }

    private class RecordingVisibleHandle(
        private val drainResult: H264DrainResult = H264DrainResult.TryAgainLater,
        private val stopResult: CameraEncoderPipelineStopResult = CameraEncoderPipelineStopResult.Stopped,
    ) : VisibleCameraPipelineHandle {
        override fun drainEncoded(maxOutputs: Int): H264DrainResult = drainResult
        override fun consumeEncoded(count: Int) = Unit
        override fun stop(): CameraEncoderPipelineStopResult = stopResult
    }
}
