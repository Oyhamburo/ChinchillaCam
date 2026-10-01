package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task r3 (`odd/tasks/session-runtime.md` §4): video egress flows through the phone-side
 * [SessionRuntime] via [SessionRuntimeVideoTransport], an [EncodedVideoSessionFrameTransport] /
 * [FragmentingSessionFrameTransport] that only enqueues (no TLS I/O on the caller thread). The
 * fragmenting sink splits over-sized chunks and the shared outbound sequence is +1 strict across
 * video frames and interleaved `KEEPALIVE`s, with the same `sessionId`, reassembling to the input.
 * The fake desktop is [RawStreamTlsTestSupport.RawStreamServerPeer] over raw TLS (no network).
 */
class SessionRuntimeVideoTransportTest {
    private val sessionId = "r3-video-session"
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

    @Test
    fun fragmentedVideoFlowsThroughRuntimeWithSharedSequence() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r3-flow-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r3-flow-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val frames = CopyOnWriteArrayList<SessionFrame>()
        val collected = CountDownLatch(1)

        // Three chunks, each larger than the type-8 limit so each splits into fragments.
        val maxType8 = SessionRuntimeVideoTransport.maxType8H264BytesFor(sessionId)
        val inputs = (0 until 3).map { index ->
            ByteArray(maxType8 + 5_000) { ((index * 7 + it) and 0xff).toByte() }
        }
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
                        when (val payload = it.payload) {
                            is SessionPayload.VideoChunkV2 -> payload.chunkIndex
                            is SessionPayload.VideoChunkFragmentV1 ->
                                if (payload.fragmentIndex == payload.fragmentCount - 1) payload.chunkIndex else null
                            else -> null
                        }
                    }.toSet()
                    if (videoChunkIndexes.size >= expectedChunks) {
                        collected.countDown()
                        break
                    }
                }
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val ends = CopyOnWriteArrayList<SessionEnd>()
        val runtime = SessionRuntime.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            config = SessionRuntimeConfig(
                keepaliveIntervalMillis = 80,
                deadPeerThresholdMillis = 5_000,
                writerTickMillis = 20,
                outboundQueueCapacity = 64,
                joinTimeoutMillis = 2_000,
            ),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        val transport = SessionRuntimeVideoTransport(runtime)
        assertEquals(runtime.sessionId, transport.sessionId)
        assertEquals(SessionRuntimeVideoTransport.maxType8H264BytesFor(runtime.sessionId), transport.maxType8H264Bytes())
        val egress: EncodedVideoEgressSink = FragmentingEncodedVideoEgressSink(
            EncodedVideoFragmentingSessionFrameSink(transport),
        )
        try {
            for (bytes in inputs) {
                assertEquals(EncodedVideoEgressSinkResult.Accepted, egress.write(chunk(bytes)))
                // Idle gap so the writer thread emits a KEEPALIVE sharing the same sequence.
                Thread.sleep(160)
            }
            assertTrue("fake desktop never collected the frames", collected.await(4, TimeUnit.SECONDS))

            val ordered = frames.toList()
            assertTrue("no frames were received", ordered.isNotEmpty())
            // Same sessionId on every frame and a strict +1 shared sequence across all frame types.
            var previous = ordered.first().sequence
            assertEquals(nextOutbound, previous)
            for (frame in ordered.drop(1)) {
                assertEquals("sequence must advance by exactly 1", previous + 1, frame.sequence)
                previous = frame.sequence
            }
            ordered.forEach { assertEquals(sessionId, it.sessionId) }
            assertTrue(
                "expected at least one interleaved KEEPALIVE in the shared sequence",
                ordered.any { it.payload is SessionPayload.Keepalive },
            )

            // Reassemble each chunk from its fragments (or whole frame) and compare to the input.
            inputs.forEachIndexed { index, expected ->
                val whole = ordered.mapNotNull { it.payload as? SessionPayload.VideoChunkV2 }
                    .firstOrNull { it.chunkIndex == index }
                val reassembled = if (whole != null) {
                    whole.h264Bytes
                } else {
                    val fragments = ordered.mapNotNull { it.payload as? SessionPayload.VideoChunkFragmentV1 }
                        .filter { it.chunkIndex == index }
                        .sortedBy { it.fragmentIndex }
                    assertTrue("chunk $index produced no frames", fragments.isNotEmpty())
                    fragments.fold(ByteArray(0)) { acc, fragment -> acc + fragment.fragmentBytes }
                }
                assertArrayEquals("chunk $index did not reassemble to the input", expected, reassembled)
            }
        } finally {
            egress.close()
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun videoTransportMapsBackpressureToFailClosed() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r3-bp-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r3-bp-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val release = CountDownLatch(1)
        val serverReady = CountDownLatch(1)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                // Never read after the handshake: the writer blocks on a full pipe, the capacity-1
                // queue fills, and the next send fails closed (contract §4.5).
                serverReady.countDown()
                release.await(5, TimeUnit.SECONDS)
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val ends = CopyOnWriteArrayList<SessionEnd>()
        val runtime = SessionRuntime.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            config = SessionRuntimeConfig(
                keepaliveIntervalMillis = 100,
                deadPeerThresholdMillis = 400,
                writerTickMillis = 20,
                outboundQueueCapacity = 1,
                joinTimeoutMillis = 2_000,
            ),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        val transport = SessionRuntimeVideoTransport(runtime)
        try {
            assertTrue("server handshake did not complete", serverReady.await(4, TimeUnit.SECONDS))
            val big = SessionPayload.VideoChunkV2(
                chunkIndex = 0,
                presentationTimeUs = 1L,
                frameKind = SessionVideoFrameKind.DELTA,
                h264Bytes = ByteArray(60_000) { 1 },
            )
            var last: EncodedVideoSessionFrameWriteResult = EncodedVideoSessionFrameWriteResult.Written
            for (i in 0 until 1_000) {
                last = transport.write(big)
                if (last == EncodedVideoSessionFrameWriteResult.BackpressureExceeded ||
                    last == EncodedVideoSessionFrameWriteResult.Closed
                ) {
                    break
                }
            }
            assertEquals(EncodedVideoSessionFrameWriteResult.BackpressureExceeded, last)
            // After the fail-closed end, further writes report Closed.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (ends.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(SessionEnd.Backpressure, ends.firstOrNull())
            assertEquals(EncodedVideoSessionFrameWriteResult.Closed, transport.write(big))
        } finally {
            release.countDown()
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    private fun chunk(bytes: ByteArray): EncodedVideoChunk =
        EncodedVideoChunk(bytes = bytes, presentationTimeUs = 1L, isCodecConfig = false, isKeyFrame = false)
}
