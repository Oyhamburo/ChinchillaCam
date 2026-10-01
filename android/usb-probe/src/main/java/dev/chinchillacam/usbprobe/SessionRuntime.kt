package dev.chinchillacam.usbprobe

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Tunables for a [SessionRuntime] (contract `session-runtime.md` §4.3, §4.5, §4.7). */
data class SessionRuntimeConfig(
    /** How long outbound silence may last before the writer emits a `KEEPALIVE` (contract §4.3). */
    val keepaliveIntervalMillis: Long = 2_000,
    /** How long inbound silence may last before the peer is declared dead (contract §4.3). */
    val deadPeerThresholdMillis: Long = 6_000,
    /** Writer poll timeout: the cadence at which keepalive/dead-peer maintenance runs when the queue is idle. */
    val writerTickMillis: Long = 250,
    /** Bounded outbound queue capacity; a full queue fails closed with [SessionEnd.Backpressure] (contract §4.5). */
    val outboundQueueCapacity: Int = 64,
    /** Bound on how long [SessionRuntime.close] waits to join each worker thread. */
    val joinTimeoutMillis: Long = 2_000,
) {
    init {
        require(keepaliveIntervalMillis > 0) { "keepaliveIntervalMillis must be positive" }
        require(deadPeerThresholdMillis > keepaliveIntervalMillis) {
            "deadPeerThresholdMillis must exceed keepaliveIntervalMillis"
        }
        require(writerTickMillis > 0) { "writerTickMillis must be positive" }
        require(outboundQueueCapacity >= 1) { "outboundQueueCapacity must be at least 1" }
        require(joinTimeoutMillis > 0) { "joinTimeoutMillis must be positive" }
    }
}

/** Typed cause for which a [SessionRuntime] ended (contract §4.6); the first cause to fire wins. */
sealed class SessionEnd {
    /** The peer sent nothing for [SessionRuntimeConfig.deadPeerThresholdMillis] (tracker) or the reader timed out idle. */
    object PeerDead : SessionEnd()

    /** An inbound frame broke the contract: wrong `sessionId`, non-`+1` sequence, or an unexpected inbound type. */
    data class ProtocolViolation(val detail: String) : SessionEnd()

    /** The reader's adapter read failed for a reason other than an idle peer. */
    data class ReadFailed(val detail: String) : SessionEnd()

    /** The writer's adapter write failed, or the shared outbound sequence was exhausted. */
    data class WriteFailed(val detail: String) : SessionEnd()

    /** The bounded outbound queue was full on a [SessionRuntime.send] (contract §4.5, fail-closed). */
    object Backpressure : SessionEnd()

    /** [SessionRuntime.close] was called locally. */
    object LocalClose : SessionEnd()
}

/** Result of [SessionRuntime.send]: the caller never performs TLS I/O and never blocks. */
sealed class SendResult {
    /** The payload was enqueued for the writer thread. */
    object Accepted : SendResult()

    /** The bounded queue was full; the session is ending with [SessionEnd.Backpressure]. */
    object Backpressure : SendResult()

    /** The session had already ended; the frame was not enqueued. */
    data class Closed(val cause: SessionEnd) : SendResult()
}

/**
 * Keeps one authenticated phone-side session alive over an [SslEngineUsbTlsEstablishedChannel]
 * (contract `session-runtime.md` §4). Two daemon threads run until the session ends exactly once:
 *
 * - **Writer**: drains a bounded [ArrayBlockingQueue] of outbound [SessionPayload]s with a poll
 *   timeout of [SessionRuntimeConfig.writerTickMillis]. Each payload is stamped with the next value
 *   of a shared outbound sequence (starting at `nextOutboundSequence`; every frame type shares it,
 *   contract §4.1) and the session [sessionId], written through [frameAdapter], then recorded on the
 *   liveness tracker. On an idle tick it emits a `KEEPALIVE` when the tracker says outbound silence
 *   has lasted a full interval, and ends the session [SessionEnd.PeerDead] when the tracker says the
 *   peer is dead. Exhausting the sequence ends [SessionEnd.WriteFailed].
 * - **Reader**: loops [frameAdapter].read, validating the same `sessionId` and strict `+1` against
 *   `nextInboundSequence` (mismatch -> [SessionEnd.ProtocolViolation]); records inbound liveness; and
 *   dispatches: `KEEPALIVE` -> liveness only; [SessionPayload.CameraControlCommand] ->
 *   [onCameraControlCommand] inside a contained `runCatching` so a buggy callback can neither kill the
 *   reader silently nor end the session (log-and-continue; contract §4.4); any other inbound type
 *   (handshake, video, metrics, metadata) is unexpected on the phone -> [SessionEnd.ProtocolViolation].
 *   A read failure while the runtime is already ending is ignored; a [TlsSessionFrameIoFailureReason.PEER_IDLE]
 *   failure ends [SessionEnd.PeerDead]; any other read failure ends [SessionEnd.ReadFailed].
 *
 * The liveness tracker is not thread-safe, so every access is confined to [trackerLock]. The
 * [frameAdapter] serializes application writes inside the channel (task r1), so the writer thread is
 * the only producer of outbound frames and no caller thread ever performs TLS I/O (contract §4.5).
 *
 * Ending is idempotent and first-cause-wins: [end] closes the channel once, interrupts both threads,
 * and invokes [onEnd] exactly once (contained). [close] ends with [SessionEnd.LocalClose] and joins
 * both threads with a bounded timeout, skipping the current thread so a call from inside [onEnd] or
 * [onCameraControlCommand] cannot deadlock.
 */
class SessionRuntime private constructor(
    private val channel: SslEngineUsbTlsEstablishedChannel,
    private val frameAdapter: TlsSessionFrameIoAdapter,
    /** The session identity stamped on every outbound frame. */
    val sessionId: String,
    nextOutboundSequence: Int,
    nextInboundSequence: Int,
    private val config: SessionRuntimeConfig,
    private val clock: () -> Long,
    private val onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
    private val onEnd: (SessionEnd) -> Unit,
) {
    private val tracker = SessionLivenessTracker(config.keepaliveIntervalMillis, config.deadPeerThresholdMillis)
    private val trackerLock = Any()
    private val outboundQueue = ArrayBlockingQueue<SessionPayload>(config.outboundQueueCapacity)
    private val endCause = AtomicReference<SessionEnd?>(null)

    private var outboundCounter: Long = nextOutboundSequence.toLong()
    private var expectedInbound: Long = nextInboundSequence.toLong()

    private lateinit var writerThread: Thread
    private lateinit var readerThread: Thread

    private val ended: Boolean get() = endCause.get() != null

    private fun launch() {
        // The tracker starts at runtime launch so the just-completed handshake counts as the last
        // inbound traffic (contract §4.3): isPeerDead stays false until the real dead threshold elapses.
        synchronized(trackerLock) { tracker.recordReceived(clock()) }
        writerThread = thread(start = false, isDaemon = true, name = "session-runtime-writer") { writerLoop() }
        readerThread = thread(start = false, isDaemon = true, name = "session-runtime-reader") { readerLoop() }
        writerThread.start()
        readerThread.start()
    }

    /** Enqueues [payload] for the writer thread without blocking or performing TLS I/O (contract §4.5). */
    fun send(payload: SessionPayload): SendResult {
        endCause.get()?.let { return SendResult.Closed(it) }
        if (!outboundQueue.offer(payload)) {
            end(SessionEnd.Backpressure)
            return SendResult.Backpressure
        }
        endCause.get()?.let { return SendResult.Closed(it) }
        return SendResult.Accepted
    }

    /** Ends the session with [SessionEnd.LocalClose] (if not already ended) and joins the worker threads. */
    fun close() {
        end(SessionEnd.LocalClose)
        joinThreads()
    }

    private fun writerLoop() {
        while (!ended) {
            val payload = try {
                outboundQueue.poll(config.writerTickMillis, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                break
            }
            if (ended) break
            val now = clock()
            if (payload != null) {
                if (!writeFrame(payload, now)) break
            } else {
                if (!maintain(now)) break
            }
        }
    }

    /** Idle-tick maintenance: emit a keepalive when due, then end if the tracker declares the peer dead. */
    private fun maintain(now: Long): Boolean {
        val shouldSendKeepalive = synchronized(trackerLock) { tracker.shouldSendKeepalive(now) }
        if (shouldSendKeepalive && !writeFrame(SessionPayload.Keepalive, now)) return false
        val peerDead = synchronized(trackerLock) { tracker.isPeerDead(now) }
        if (peerDead) {
            end(SessionEnd.PeerDead)
            return false
        }
        return true
    }

    private fun writeFrame(payload: SessionPayload, now: Long): Boolean {
        val sequence = nextOutboundSequence() ?: return false
        val frame = SessionFrame(sequence = sequence, sessionId = sessionId, payload = payload)
        try {
            frameAdapter.write(channel, frame)
        } catch (error: Throwable) {
            if (!ended) end(SessionEnd.WriteFailed(error.message ?: "session frame write failed"))
            return false
        }
        synchronized(trackerLock) { tracker.recordSent(now) }
        return true
    }

    /** Returns the next outbound sequence, or ends [SessionEnd.WriteFailed] and returns null on exhaustion (contract §4.1). */
    private fun nextOutboundSequence(): Int? {
        if (outboundCounter > Int.MAX_VALUE) {
            end(SessionEnd.WriteFailed("outbound sequence exhausted"))
            return null
        }
        val sequence = outboundCounter.toInt()
        outboundCounter += 1
        return sequence
    }

    private fun readerLoop() {
        while (!ended) {
            val frame = try {
                frameAdapter.read(channel)
            } catch (error: TlsSessionFrameIoException) {
                if (!ended) {
                    if (error.reason == TlsSessionFrameIoFailureReason.PEER_IDLE) {
                        end(SessionEnd.PeerDead)
                    } else {
                        end(SessionEnd.ReadFailed(error.message ?: "session frame read failed"))
                    }
                }
                break
            } catch (error: Throwable) {
                if (!ended) end(SessionEnd.ReadFailed(error.message ?: "session frame read failed"))
                break
            }
            if (ended) break
            if (!dispatchInbound(frame)) break
        }
    }

    /** Validates identity/sequence and dispatches one inbound frame; returns false once the session ends. */
    private fun dispatchInbound(frame: SessionFrame): Boolean {
        if (frame.sessionId != sessionId) {
            end(SessionEnd.ProtocolViolation("unexpected sessionId ${frame.sessionId}"))
            return false
        }
        if (frame.sequence.toLong() != expectedInbound) {
            end(SessionEnd.ProtocolViolation("expected inbound sequence $expectedInbound but got ${frame.sequence}"))
            return false
        }
        expectedInbound += 1
        synchronized(trackerLock) { tracker.recordReceived(clock()) }
        when (val payload = frame.payload) {
            is SessionPayload.Keepalive -> Unit
            is SessionPayload.CameraControlCommand ->
                // Contained so a buggy consumer cannot silently kill the reader or end the session
                // (contract §4.4, log-and-continue): any callback failure is swallowed and the loop proceeds.
                runCatching { onCameraControlCommand(payload) }
            else -> {
                end(SessionEnd.ProtocolViolation("unexpected inbound frame type ${frame.type}"))
                return false
            }
        }
        return true
    }

    private fun end(cause: SessionEnd) {
        if (!endCause.compareAndSet(null, cause)) return
        runCatching { channel.close() }
        if (this::writerThread.isInitialized) writerThread.interrupt()
        if (this::readerThread.isInitialized) readerThread.interrupt()
        runCatching { onEnd(cause) }
    }

    private fun joinThreads() {
        val current = Thread.currentThread()
        for (worker in listOf(writerThread, readerThread)) {
            if (worker === current) continue
            worker.interrupt()
            runCatching { worker.join(config.joinTimeoutMillis) }
        }
    }

    companion object {
        /** Monotonic wall clock in milliseconds; injectable so tests fully control time. */
        private val DEFAULT_CLOCK: () -> Long = { System.nanoTime() / 1_000_000 }

        /** Starts a [SessionRuntime] from the explicit parts a [UsbTrustedReconnectResult.Reconnected] carries. */
        fun start(
            channel: SslEngineUsbTlsEstablishedChannel,
            frameAdapter: TlsSessionFrameIoAdapter,
            sessionId: String,
            nextOutboundSequence: Int,
            nextInboundSequence: Int,
            config: SessionRuntimeConfig = SessionRuntimeConfig(),
            clock: () -> Long = DEFAULT_CLOCK,
            onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
            onEnd: (SessionEnd) -> Unit,
        ): SessionRuntime = SessionRuntime(
            channel = channel,
            frameAdapter = frameAdapter,
            sessionId = sessionId,
            nextOutboundSequence = nextOutboundSequence,
            nextInboundSequence = nextInboundSequence,
            config = config,
            clock = clock,
            onCameraControlCommand = onCameraControlCommand,
            onEnd = onEnd,
        ).also { it.launch() }

        /** Starts a [SessionRuntime] directly from a [UsbTrustedReconnectResult.Reconnected]. */
        fun start(
            reconnected: UsbTrustedReconnectResult.Reconnected,
            config: SessionRuntimeConfig = SessionRuntimeConfig(),
            clock: () -> Long = DEFAULT_CLOCK,
            onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
            onEnd: (SessionEnd) -> Unit,
        ): SessionRuntime = start(
            channel = reconnected.channel,
            frameAdapter = reconnected.frameAdapter,
            sessionId = reconnected.sessionId,
            nextOutboundSequence = reconnected.nextOutboundSequence,
            nextInboundSequence = reconnected.nextInboundSequence,
            config = config,
            clock = clock,
            onCameraControlCommand = onCameraControlCommand,
            onEnd = onEnd,
        )
    }
}
