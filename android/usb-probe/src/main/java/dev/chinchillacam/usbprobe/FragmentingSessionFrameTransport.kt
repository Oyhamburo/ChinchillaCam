package dev.chinchillacam.usbprobe

/**
 * The narrow transport surface an [EncodedVideoFragmentingSessionFrameSink] needs: the session
 * identity and type-8 size bound it uses to decide whole-chunk vs. fragmented egress, plus the two
 * payload writes and [close]. Extracted (`session-runtime.md` §4) so the sink no longer
 * depends on the concrete [EncodedVideoSustainedFakeTransportAdapter] and can instead run over the
 * runtime-backed [SessionRuntimeVideoTransport]. Inherits `write(VideoChunkV2)` and [close] from
 * [EncodedVideoSessionFrameTransport]; every implementation stays fail-closed (any non-`Written`
 * result closes the transport permanently).
 */
interface FragmentingSessionFrameTransport : EncodedVideoSessionFrameTransport {
    /** The session id used to size fragments so each encoded frame fits the transport's limit. */
    val sessionId: String

    /** The largest h264 byte count that still fits in a single type-8 [SessionPayload.VideoChunkV2]. */
    fun maxType8H264Bytes(): Int

    /** Writes one [SessionPayload.VideoChunkFragmentV1]; same fail-closed contract as [write]. */
    fun writeFragment(payload: SessionPayload.VideoChunkFragmentV1): EncodedVideoSessionFrameWriteResult
}
