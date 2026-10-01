package dev.chinchillacam.usbprobe

sealed class EncodedVideoFragmentingSessionFrameSinkResult {
    data class Accepted(val chunkIndex: Int, val framesWritten: Int) : EncodedVideoFragmentingSessionFrameSinkResult()
    object BackpressureExceeded : EncodedVideoFragmentingSessionFrameSinkResult()
    object Closed : EncodedVideoFragmentingSessionFrameSinkResult()
    object Oversized : EncodedVideoFragmentingSessionFrameSinkResult()
    data class InvalidPayload(val reason: String) : EncodedVideoFragmentingSessionFrameSinkResult()
    data class Failed(val reason: String) : EncodedVideoFragmentingSessionFrameSinkResult()
}

data class EncodedVideoFragmentingSessionFrameSinkStats(
    val accepted: Int,
    val dropped: Int,
)

class EncodedVideoFragmentingSessionFrameSink(
    private val transport: FragmentingSessionFrameTransport,
    initialChunkIndex: Int = 0,
) {
    private var nextChunkIndex: Int = initialChunkIndex
    private var closed: Boolean = false
    private var accepted: Int = 0
    private var dropped: Int = 0

    init {
        require(initialChunkIndex >= 0) { "initial chunk index must be non-negative" }
    }

    @Synchronized
    fun write(chunk: EncodedVideoChunk): EncodedVideoFragmentingSessionFrameSinkResult {
        if (closed) return EncodedVideoFragmentingSessionFrameSinkResult.Closed
        if (chunk.bytes.isEmpty()) return dropInvalid("h264 bytes must be non-empty")
        if (chunk.presentationTimeUs < 0L) return dropInvalid("presentation time must be non-negative")

        val chunkIndex = nextChunkIndex
        val payloads = when {
            chunk.bytes.size <= transport.maxType8H264Bytes() -> listOf(type8Payload(chunkIndex, chunk))
            else -> when (val fragmentResult = EncodedVideoChunkFragmenter.fragment(chunkIndex, transport.sessionId, chunk)) {
                is EncodedVideoChunkFragmentResult.Fragments -> fragmentResult.fragments
                is EncodedVideoChunkFragmentResult.InvalidPayload -> return dropInvalid(fragmentResult.reason)
                EncodedVideoChunkFragmentResult.Oversized -> return dropOversized()
            }
        }

        var framesWritten = 0
        for (payload in payloads) {
            val writeResult = when (payload) {
                is SessionPayload.VideoChunkV2 -> transport.write(payload)
                is SessionPayload.VideoChunkFragmentV1 -> transport.writeFragment(payload)
                else -> error("unsupported payload type")
            }
            when (writeResult) {
                EncodedVideoSessionFrameWriteResult.Written -> framesWritten += 1
                EncodedVideoSessionFrameWriteResult.BackpressureExceeded -> return dropAfterPartial(EncodedVideoFragmentingSessionFrameSinkResult.BackpressureExceeded)
                EncodedVideoSessionFrameWriteResult.Closed -> return dropAfterPartial(EncodedVideoFragmentingSessionFrameSinkResult.Closed)
                EncodedVideoSessionFrameWriteResult.Oversized -> return dropAfterPartial(EncodedVideoFragmentingSessionFrameSinkResult.Oversized)
                is EncodedVideoSessionFrameWriteResult.Failed -> return dropAfterPartial(EncodedVideoFragmentingSessionFrameSinkResult.Failed(writeResult.reason))
            }
        }

        accepted += 1
        if (nextChunkIndex == Int.MAX_VALUE) {
            closeLocked()
        } else {
            nextChunkIndex += 1
        }
        return EncodedVideoFragmentingSessionFrameSinkResult.Accepted(chunkIndex = chunkIndex, framesWritten = framesWritten)
    }

    @Synchronized
    fun close() {
        closeLocked()
    }

    @Synchronized
    fun stats(): EncodedVideoFragmentingSessionFrameSinkStats = EncodedVideoFragmentingSessionFrameSinkStats(
        accepted = accepted,
        dropped = dropped,
    )

    private fun type8Payload(chunkIndex: Int, chunk: EncodedVideoChunk): SessionPayload.VideoChunkV2 = SessionPayload.VideoChunkV2(
        chunkIndex = chunkIndex,
        presentationTimeUs = chunk.presentationTimeUs,
        frameKind = SessionVideoFrameKind.fromCodecFlags(
            isKeyFrame = chunk.isKeyFrame,
            isCodecConfig = chunk.isCodecConfig,
        ),
        h264Bytes = chunk.bytes.copyOf(),
    )

    private fun dropInvalid(reason: String): EncodedVideoFragmentingSessionFrameSinkResult.InvalidPayload {
        dropped += 1
        closeLocked()
        return EncodedVideoFragmentingSessionFrameSinkResult.InvalidPayload(reason)
    }

    private fun dropOversized(): EncodedVideoFragmentingSessionFrameSinkResult.Oversized {
        dropped += 1
        closeLocked()
        return EncodedVideoFragmentingSessionFrameSinkResult.Oversized
    }

    private fun <T : EncodedVideoFragmentingSessionFrameSinkResult> dropAfterPartial(result: T): T {
        dropped += 1
        closeLocked()
        return result
    }

    private fun closeLocked() {
        if (!closed) {
            closed = true
            transport.close()
        }
    }
}
