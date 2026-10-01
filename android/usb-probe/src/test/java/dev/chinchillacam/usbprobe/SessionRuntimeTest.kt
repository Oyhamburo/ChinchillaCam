package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task r2 (`odd/tasks/session-runtime.md` §4): the phone-side [SessionRuntime] keeps an
 * authenticated session alive over a real TLS channel -- a writer thread that stamps a shared
 * sequence and emits `KEEPALIVE` when idle, a reader thread that validates strict +1 sequence and
 * `sessionId` and dispatches, bounded-queue fail-closed backpressure, and typed end causes. The
 * fake desktop is [RawStreamTlsTestSupport.RawStreamServerPeer] speaking framed [SessionFrame]s over
 * raw TLS; timing config is small with generous assertion bounds.
 */
class SessionRuntimeTest {
    private val sessionId = "r2-session"
    private val nextOutbound = 1
    private val nextInbound = 2

    private fun timingConfig(outboundQueueCapacity: Int = 64) = SessionRuntimeConfig(
        keepaliveIntervalMillis = 100,
        deadPeerThresholdMillis = 400,
        writerTickMillis = 20,
        outboundQueueCapacity = outboundQueueCapacity,
        joinTimeoutMillis = 2_000,
    )

    private fun phoneAdapter() = TlsSessionFrameIoAdapter(idleBudgetMillis = 2_000, readTimeoutMillis = 2_000)

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

    private fun awaitEnd(ends: List<SessionEnd>, timeoutMillis: Long): SessionEnd? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            ends.firstOrNull()?.let { return it }
            Thread.sleep(10)
        }
        return ends.firstOrNull()
    }

    @Test
    fun sendsKeepaliveWhenIdle() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r2-keepalive-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r2-keepalive-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val firstFrame = AtomicReference<SessionFrame?>(null)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val reader = FramedPeerReader(peer)
                firstFrame.set(reader.next())
                runCatching { while (true) reader.next() }
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
            config = timingConfig(),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (firstFrame.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
            val frame = firstFrame.get()
            assertTrue("expected a keepalive, got $frame", frame?.payload is SessionPayload.Keepalive)
            assertEquals(sessionId, frame!!.sessionId)
            assertEquals(nextOutbound, frame.sequence)
        } finally {
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun deliversCameraControlCommand() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r2-command-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r2-command-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val command = SessionPayload.CameraControlCommand("focus", mapOf("x" to "1"))

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val frame = SessionFrame(sequence = nextInbound, sessionId = sessionId, payload = command)
                peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(frame))
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val channel = establishPhoneChannel(desktop, phone, endpoints)
        val delivered = AtomicReference<SessionPayload.CameraControlCommand?>(null)
        val latch = CountDownLatch(1)
        val ends = CopyOnWriteArrayList<SessionEnd>()
        val runtime = SessionRuntime.start(
            channel = channel,
            frameAdapter = phoneAdapter(),
            sessionId = sessionId,
            nextOutboundSequence = nextOutbound,
            nextInboundSequence = nextInbound,
            config = timingConfig(),
            onCameraControlCommand = { delivered.set(it); latch.countDown() },
            onEnd = { ends.add(it) },
        )
        try {
            assertTrue("command was not delivered", latch.await(3, TimeUnit.SECONDS))
            assertEquals(command, delivered.get())
        } finally {
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun endsWithPeerDeadWhenPeerSilent() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r2-dead-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r2-dead-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
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
            config = timingConfig(),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        try {
            assertEquals(SessionEnd.PeerDead, awaitEnd(ends, 3_000))
        } finally {
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun rejectsOutOfOrderSequence() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r2-order-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r2-order-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val outOfOrder = SessionFrame(
                    sequence = nextInbound + 3,
                    sessionId = sessionId,
                    payload = SessionPayload.CameraControlCommand("focus", emptyMap()),
                )
                peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(outOfOrder))
                val reader = FramedPeerReader(peer)
                runCatching { while (true) reader.next() }
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
            config = timingConfig(),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        try {
            val end = awaitEnd(ends, 3_000)
            assertTrue("expected ProtocolViolation, got $end", end is SessionEnd.ProtocolViolation)
        } finally {
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }

    @Test
    fun backpressureEndsSession() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("r2-backpressure-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r2-backpressure-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val release = CountDownLatch(1)
        val serverReady = CountDownLatch(1)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                // Signal that the TLS handshake (including any post-handshake write) is fully done
                // before the phone floods, so the abrupt fail-closed channel close never tears down
                // the pipe mid-handshake. After this the peer never reads: the phone's writer blocks
                // on a full pipe, the bounded queue fills, and the next offer fails closed (§4.5).
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
            config = timingConfig(outboundQueueCapacity = 1),
            onCameraControlCommand = {},
            onEnd = { ends.add(it) },
        )
        try {
            assertTrue("server handshake did not complete", serverReady.await(4, TimeUnit.SECONDS))
            // Each frame is ~60 KB of valid payload (codec caps strings at 65535 bytes); the
            // unread 256 KB pipe saturates after a few writes so the writer blocks and the
            // capacity-1 queue fills, forcing a fail-closed offer rejection (contract §4.5).
            val big = SessionPayload.CameraControlCommand("blob", mapOf("data" to "x".repeat(60_000)))
            var last: SendResult = SendResult.Accepted
            for (i in 0 until 1_000) {
                last = runtime.send(big)
                if (last is SendResult.Backpressure || last is SendResult.Closed) break
            }
            assertTrue("expected backpressure, got $last", last is SendResult.Backpressure)
            assertEquals(SessionEnd.Backpressure, awaitEnd(ends, 2_000))
            assertTrue("send after end must be Closed", runtime.send(big) is SendResult.Closed)
        } finally {
            release.countDown()
            runtime.close()
            server.join(4_000)
            serverError.get()?.let { throw it }
        }
    }
}
