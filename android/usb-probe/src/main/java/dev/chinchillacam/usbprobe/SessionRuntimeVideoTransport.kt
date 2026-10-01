package dev.chinchillacam.usbprobe

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges encoded-video egress onto a running [SessionRuntime] (`session-runtime.md` §4).
 * Implements [FragmentingSessionFrameTransport] (and thus [EncodedVideoSessionFrameTransport]) so an
 * [EncodedVideoSessionFrameSink] or [EncodedVideoFragmentingSessionFrameSink] can push video without
 * knowing about the session loop.
 *
 * Every [write]/[writeFragment] only enqueues the payload via [SessionRuntime.send]: the runtime's
 * single writer thread performs the TLS write, stamps the shared outbound sequence, and the
 * `sessionId` (contract §4.1, §4.5). No TLS I/O ever happens on the caller thread. [SendResult] maps
 * to the sink's fail-closed [EncodedVideoSessionFrameWriteResult]:
 *
 * - [SendResult.Accepted]    -> [EncodedVideoSessionFrameWriteResult.Written]
 * - [SendResult.Backpressure]-> [EncodedVideoSessionFrameWriteResult.BackpressureExceeded]
 * - [SendResult.Closed]      -> [EncodedVideoSessionFrameWriteResult.Closed]
 *
 * [close] stops only video egress through this transport (later writes report `Closed`); it does
 * **not** end the [SessionRuntime], which keeps serving keepalive, inbound commands, and liveness.
 * The runtime still owns its own fail-closed end on backpressure (contract §4.5), so a full outbound
 * queue both reports `BackpressureExceeded` here and ends the session there.
 *
 * [sessionId] is used only to size fragments (how large each fragment may be) so each encoded frame
 * stays within the transport budget; the actual on-the-wire `sessionId` is stamped by the runtime.
 */
class SessionRuntimeVideoTransport(
    private val runtime: SessionRuntime,
) : FragmentingSessionFrameTransport {
    /** The runtime's own session id, so fragment sizing always matches the id written on the wire. */
    override val sessionId: String = runtime.sessionId
    private val maxType8H264Bytes: Int = maxType8H264BytesFor(sessionId)

    private val closed = AtomicBoolean(false)

    init {
        require(sessionId.isNotEmpty()) { "session id must not be empty" }
        require(maxType8H264Bytes > 0) { "maxType8H264Bytes must be positive" }
    }

    override fun maxType8H264Bytes(): Int = maxType8H264Bytes

    override fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult = enqueue(payload)

    override fun writeFragment(payload: SessionPayload.VideoChunkFragmentV1): EncodedVideoSessionFrameWriteResult = enqueue(payload)

    /** Stops video egress through this transport only; the [SessionRuntime] keeps running (contract §4.5). */
    override fun close() {
        closed.set(true)
    }

    private fun enqueue(payload: SessionPayload): EncodedVideoSessionFrameWriteResult {
        if (closed.get()) return EncodedVideoSessionFrameWriteResult.Closed
        return when (runtime.send(payload)) {
            SendResult.Accepted -> EncodedVideoSessionFrameWriteResult.Written
            SendResult.Backpressure -> EncodedVideoSessionFrameWriteResult.BackpressureExceeded
            is SendResult.Closed -> EncodedVideoSessionFrameWriteResult.Closed
        }
    }

    companion object {
        // Mirrors EncodedVideoSustainedFakeTransportAdapter's type-8 budget formula (contract §4).
        private const val MAX_SESSION_PAYLOAD_BYTES = 65_528
        private const val SESSION_FRAME_BASE_BYTES = 16
        private const val VIDEO_CHUNK_V2_FIXED_PAYLOAD_BYTES = 15

        /** The largest type-8 h264 byte count for a given [sessionId] (same formula as the fake adapter). */
        fun maxType8H264BytesFor(sessionId: String): Int =
            MAX_SESSION_PAYLOAD_BYTES - SESSION_FRAME_BASE_BYTES -
                sessionId.toByteArray(Charsets.UTF_8).size - VIDEO_CHUNK_V2_FIXED_PAYLOAD_BYTES
    }
}
