package dev.chinchillacam.usbprobe

sealed class EncodedVideoChunkFragmentResult {
    data class Fragments(val fragments: List<SessionPayload.VideoChunkFragmentV1>) : EncodedVideoChunkFragmentResult()
    data class InvalidPayload(val reason: String) : EncodedVideoChunkFragmentResult()
    object Oversized : EncodedVideoChunkFragmentResult()
}

object EncodedVideoChunkFragmenter {
    private const val ACCESSORY_PACKET_BYTES = 65_536
    private const val ACCESSORY_HEADER_BYTES = 8
    private const val SESSION_FRAME_BASE_BYTES = 16
    private const val VIDEO_FRAGMENT_FIXED_PAYLOAD_BYTES = 27
    private const val MAX_TOTAL_H264_BYTES = 4 * 1024 * 1024
    private const val MAX_FRAGMENT_COUNT = 1024

    fun fragment(
        chunkIndex: Int,
        sessionId: String,
        chunk: EncodedVideoChunk,
    ): EncodedVideoChunkFragmentResult {
        if (sessionId.isEmpty()) return invalid("session id must not be empty")
        val sessionIdBytes = sessionId.toByteArray(Charsets.UTF_8)
        if (sessionIdBytes.size > UShort.MAX_VALUE.toInt()) return invalid("session id is too long")
        if (chunkIndex < 0) return invalid("chunk index must be non-negative")
        if (chunk.presentationTimeUs < 0L) return invalid("presentation time must be non-negative")
        if (chunk.bytes.isEmpty()) return invalid("h264 bytes must be non-empty")
        if (chunk.bytes.size > MAX_TOTAL_H264_BYTES) return EncodedVideoChunkFragmentResult.Oversized

        val maxFragmentBytes = ACCESSORY_PACKET_BYTES - ACCESSORY_HEADER_BYTES -
            SESSION_FRAME_BASE_BYTES - sessionIdBytes.size - VIDEO_FRAGMENT_FIXED_PAYLOAD_BYTES
        if (maxFragmentBytes <= 0) return invalid("session id leaves no fragment capacity")
        val fragmentCount = ceilDiv(chunk.bytes.size, maxFragmentBytes)
        if (fragmentCount > MAX_FRAGMENT_COUNT) return EncodedVideoChunkFragmentResult.Oversized

        val frameKind = SessionVideoFrameKind.fromCodecFlags(
            isKeyFrame = chunk.isKeyFrame,
            isCodecConfig = chunk.isCodecConfig,
        )
        val fragments = ArrayList<SessionPayload.VideoChunkFragmentV1>(fragmentCount)
        var offset = 0
        repeat(fragmentCount) { fragmentIndex ->
            val end = (offset + maxFragmentBytes).coerceAtMost(chunk.bytes.size)
            fragments += SessionPayload.VideoChunkFragmentV1(
                chunkIndex = chunkIndex,
                presentationTimeUs = chunk.presentationTimeUs,
                frameKind = frameKind,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                totalH264Bytes = chunk.bytes.size,
                fragmentBytes = chunk.bytes.copyOfRange(offset, end),
            )
            offset = end
        }
        return EncodedVideoChunkFragmentResult.Fragments(fragments)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = ((value.toLong() + divisor.toLong() - 1L) / divisor.toLong()).toInt()

    private fun invalid(reason: String): EncodedVideoChunkFragmentResult.InvalidPayload =
        EncodedVideoChunkFragmentResult.InvalidPayload(reason)
}
