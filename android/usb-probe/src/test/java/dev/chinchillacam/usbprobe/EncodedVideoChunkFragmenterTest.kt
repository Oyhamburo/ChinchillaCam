package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoChunkFragmenterTest {
    @Test
    fun sessionSAllows65484BytesInOneAccessoryPacket() {
        val result = EncodedVideoChunkFragmenter.fragment(0, "s", chunk(ByteArray(65_484) { 7 }))

        val fragments = (result as EncodedVideoChunkFragmentResult.Fragments).fragments
        assertEquals(1, fragments.size)
        assertEquals(0, fragments.single().fragmentIndex)
        assertEquals(1, fragments.single().fragmentCount)
        assertEquals(65_484, fragments.single().totalH264Bytes)
        assertEquals(65_484, fragments.single().fragmentBytes.size)
        assertAccessoryPacketFits("s", fragments.single(), expectedTotalPacketBytes = 65_536)
    }

    @Test
    fun sessionSSplits65485BytesIntoTwoFragmentsWithoutTruncation() {
        val bytes = ByteArray(65_485) { (it and 0xff).toByte() }
        val result = EncodedVideoChunkFragmenter.fragment(3, "s", chunk(bytes, presentationTimeUs = 9L, isKeyFrame = true))

        val fragments = (result as EncodedVideoChunkFragmentResult.Fragments).fragments
        assertEquals(2, fragments.size)
        assertEquals(listOf(0, 1), fragments.map { it.fragmentIndex })
        assertEquals(listOf(2, 2), fragments.map { it.fragmentCount })
        assertEquals(listOf(65_484, 1), fragments.map { it.fragmentBytes.size })
        assertEquals(listOf(SessionVideoFrameKind.KEY, SessionVideoFrameKind.KEY), fragments.map { it.frameKind })
        assertArrayEquals(bytes, fragments.flatMap { it.fragmentBytes.asIterable() }.toByteArray())
        fragments.forEach { assertTrue(8 + SessionFrameCodec.encode(SessionFrame(1, 0, "s", it)).size <= 65_536) }
    }

    @Test
    fun utf8SessionIdReducesFragmentCapacityByEncodedByteLength() {
        val sessionId = "ññ"
        val capacity = 65_485 - sessionId.toByteArray(Charsets.UTF_8).size
        val result = EncodedVideoChunkFragmenter.fragment(0, sessionId, chunk(ByteArray(capacity + 1) { 1 }))

        val fragments = (result as EncodedVideoChunkFragmentResult.Fragments).fragments
        assertEquals(listOf(capacity, 1), fragments.map { it.fragmentBytes.size })
        fragments.forEach { assertTrue(8 + SessionFrameCodec.encode(SessionFrame(1, 0, sessionId, it)).size <= 65_536) }
    }

    @Test
    fun rejectsInvalidInputsWithTypedResults() {
        assertEquals(EncodedVideoChunkFragmentResult.InvalidPayload("session id must not be empty"), EncodedVideoChunkFragmenter.fragment(0, "", chunk(byteArrayOf(1))))
        assertEquals(EncodedVideoChunkFragmentResult.InvalidPayload("session id is too long"), EncodedVideoChunkFragmenter.fragment(0, "x".repeat(65_536), chunk(byteArrayOf(1))))
        assertEquals(EncodedVideoChunkFragmentResult.InvalidPayload("chunk index must be non-negative"), EncodedVideoChunkFragmenter.fragment(-1, "s", chunk(byteArrayOf(1))))
        assertEquals(EncodedVideoChunkFragmentResult.InvalidPayload("presentation time must be non-negative"), EncodedVideoChunkFragmenter.fragment(0, "s", chunk(byteArrayOf(1), presentationTimeUs = -1L)))
        assertEquals(EncodedVideoChunkFragmentResult.InvalidPayload("h264 bytes must be non-empty"), EncodedVideoChunkFragmenter.fragment(0, "s", chunk(byteArrayOf())))
    }

    @Test
    fun rejectsOversizedAndTooManyFragmentsWithoutTruncation() {
        val oversized = EncodedVideoChunkFragmenter.fragment(0, "s", chunk(ByteArray(4 * 1024 * 1024 + 1) { 1 }))
        assertEquals(EncodedVideoChunkFragmentResult.Oversized, oversized)

        val longSessionId = "x".repeat(65_420)
        val tooMany = EncodedVideoChunkFragmenter.fragment(0, longSessionId, chunk(ByteArray(66 * 1024 + 1) { 1 }))
        assertEquals(EncodedVideoChunkFragmentResult.Oversized, tooMany)
    }

    @Test
    fun codecConfigFrameKindHasPriorityOverKey() {
        val result = EncodedVideoChunkFragmenter.fragment(0, "s", chunk(byteArrayOf(1), isCodecConfig = true, isKeyFrame = true))

        val fragment = (result as EncodedVideoChunkFragmentResult.Fragments).fragments.single()
        assertEquals(SessionVideoFrameKind.CODEC_CONFIG, fragment.frameKind)
    }

    private fun chunk(
        bytes: ByteArray,
        presentationTimeUs: Long = 1L,
        isCodecConfig: Boolean = false,
        isKeyFrame: Boolean = false,
    ): EncodedVideoChunk = EncodedVideoChunk(bytes, presentationTimeUs, isCodecConfig, isKeyFrame)

    private fun assertAccessoryPacketFits(sessionId: String, payload: SessionPayload.VideoChunkFragmentV1, expectedTotalPacketBytes: Int) {
        val encoded = SessionFrameCodec.encode(SessionFrame(1, 0, sessionId, payload))
        assertEquals(expectedTotalPacketBytes, 8 + encoded.size)
    }
}
