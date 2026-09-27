package dev.chinchillacam.usbprobe

interface EncodedVideoSessionFrameTransport {
    fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult
    fun close()
}

sealed class EncodedVideoSessionFrameWriteResult {
    object Written : EncodedVideoSessionFrameWriteResult()
    object BackpressureExceeded : EncodedVideoSessionFrameWriteResult()
    object Closed : EncodedVideoSessionFrameWriteResult()
    data class Failed(val reason: String) : EncodedVideoSessionFrameWriteResult()
}

sealed class EncodedVideoSessionFrameSinkResult {
    data class Accepted(val payload: SessionPayload.VideoChunkV2) : EncodedVideoSessionFrameSinkResult()
    object BackpressureExceeded : EncodedVideoSessionFrameSinkResult()
    object Closed : EncodedVideoSessionFrameSinkResult()
    object Oversized : EncodedVideoSessionFrameSinkResult()
    data class InvalidPayload(val reason: String) : EncodedVideoSessionFrameSinkResult()
    data class Failed(val reason: String) : EncodedVideoSessionFrameSinkResult()
}

data class EncodedVideoSessionFrameSinkStats(
    val accepted: Int,
    val dropped: Int,
)

class EncodedVideoSessionFrameSink(
    private val transport: EncodedVideoSessionFrameTransport,
) {
    private var nextChunkIndex: Int = 0
    private var closed: Boolean = false
    private var accepted: Int = 0
    private var dropped: Int = 0

    @Synchronized
    fun write(chunk: EncodedVideoChunk): EncodedVideoSessionFrameSinkResult {
        if (closed) return dropClosed()
        if (chunk.bytes.isEmpty()) return dropInvalid("h264 bytes must be non-empty")
        if (chunk.presentationTimeUs < 0L) return dropInvalid("presentation time must be non-negative")
        if (chunk.bytes.size > MAX_H264_BYTES) {
            dropped += 1
            closeLocked()
            return EncodedVideoSessionFrameSinkResult.Oversized
        }

        val payload = SessionPayload.VideoChunkV2(
            chunkIndex = nextChunkIndex,
            presentationTimeUs = chunk.presentationTimeUs,
            frameKind = SessionVideoFrameKind.fromCodecFlags(
                isKeyFrame = chunk.isKeyFrame,
                isCodecConfig = chunk.isCodecConfig,
            ),
            h264Bytes = chunk.bytes.copyOf(),
        )
        val writeResult = try {
            transport.write(payload)
        } catch (error: RuntimeException) {
            dropped += 1
            closeLocked()
            return EncodedVideoSessionFrameSinkResult.Failed("transport exception: ${error.message ?: error::class.java.simpleName}")
        }
        return when (writeResult) {
            EncodedVideoSessionFrameWriteResult.Written -> {
                nextChunkIndex += 1
                accepted += 1
                EncodedVideoSessionFrameSinkResult.Accepted(payload)
            }
            EncodedVideoSessionFrameWriteResult.BackpressureExceeded -> {
                dropped += 1
                closeLocked()
                EncodedVideoSessionFrameSinkResult.BackpressureExceeded
            }
            EncodedVideoSessionFrameWriteResult.Closed -> {
                dropped += 1
                closeLocked()
                EncodedVideoSessionFrameSinkResult.Closed
            }
            is EncodedVideoSessionFrameWriteResult.Failed -> {
                dropped += 1
                closeLocked()
                EncodedVideoSessionFrameSinkResult.Failed(writeResult.reason)
            }
        }
    }

    @Synchronized
    fun close() {
        closeLocked()
    }

    @Synchronized
    fun stats(): EncodedVideoSessionFrameSinkStats = EncodedVideoSessionFrameSinkStats(
        accepted = accepted,
        dropped = dropped,
    )

    private fun dropClosed(): EncodedVideoSessionFrameSinkResult {
        dropped += 1
        return EncodedVideoSessionFrameSinkResult.Closed
    }

    private fun dropInvalid(reason: String): EncodedVideoSessionFrameSinkResult {
        dropped += 1
        closeLocked()
        return EncodedVideoSessionFrameSinkResult.InvalidPayload(reason)
    }

    private fun closeLocked() {
        if (!closed) {
            closed = true
            transport.close()
        }
    }

    private companion object {
        const val MAX_H264_BYTES = 65_535
    }
}
