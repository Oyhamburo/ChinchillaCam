package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedVideoSessionFrameSinkTest {
    @Test
    fun writesVideoChunkV2WithDeterministicIndexesAndFrameKindPrecedence() {
        val transport = RecordingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)
        val codecConfigAndKey = byteArrayOf(1, 2, 3)

        val first = sink.write(EncodedVideoChunk(codecConfigAndKey, 10L, isCodecConfig = true, isKeyFrame = true))
        codecConfigAndKey[0] = 99
        val second = sink.write(EncodedVideoChunk(byteArrayOf(4), 20L, isCodecConfig = false, isKeyFrame = true))
        val third = sink.write(EncodedVideoChunk(byteArrayOf(5), 30L, isCodecConfig = false, isKeyFrame = false))

        assertEquals(EncodedVideoSessionFrameSinkResult.Accepted(transport.payloads[0]), first)
        assertEquals(EncodedVideoSessionFrameSinkResult.Accepted(transport.payloads[1]), second)
        assertEquals(EncodedVideoSessionFrameSinkResult.Accepted(transport.payloads[2]), third)
        assertEquals(listOf(0, 1, 2), transport.payloads.map { it.chunkIndex })
        assertEquals(listOf(SessionVideoFrameKind.CODEC_CONFIG, SessionVideoFrameKind.KEY, SessionVideoFrameKind.DELTA), transport.payloads.map { it.frameKind })
        assertEquals(listOf(10L, 20L, 30L), transport.payloads.map { it.presentationTimeUs })
        assertArrayEquals(byteArrayOf(1, 2, 3), transport.payloads[0].h264Bytes)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 3, dropped = 0), sink.stats())
    }

    @Test
    fun backpressureClosesSinkAndPreventsLaterWrites() {
        val transport = RecordingEncodedVideoTransport(EncodedVideoSessionFrameWriteResult.BackpressureExceeded)
        val sink = EncodedVideoSessionFrameSink(transport)

        val first = sink.write(EncodedVideoChunk(byteArrayOf(1), 1L, isCodecConfig = false, isKeyFrame = false))
        val second = sink.write(EncodedVideoChunk(byteArrayOf(2), 2L, isCodecConfig = false, isKeyFrame = false))

        assertEquals(EncodedVideoSessionFrameSinkResult.BackpressureExceeded, first)
        assertEquals(EncodedVideoSessionFrameSinkResult.Closed, second)
        assertEquals(1, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 0, dropped = 2), sink.stats())
    }

    @Test
    fun oversizedChunkClosesSinkWithoutWriting() {
        val transport = RecordingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)

        val result = sink.write(EncodedVideoChunk(ByteArray(65_536) { 7 }, 1L, isCodecConfig = false, isKeyFrame = true))
        val afterClose = sink.write(EncodedVideoChunk(byteArrayOf(1), 2L, isCodecConfig = false, isKeyFrame = true))

        assertEquals(EncodedVideoSessionFrameSinkResult.Oversized, result)
        assertEquals(EncodedVideoSessionFrameSinkResult.Closed, afterClose)
        assertEquals(0, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 0, dropped = 2), sink.stats())
    }

    @Test
    fun emptyChunkFailsClosedBeforeTransportWrite() {
        val transport = RecordingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)

        val result = sink.write(EncodedVideoChunk(byteArrayOf(), 1L, isCodecConfig = false, isKeyFrame = false))
        val afterClose = sink.write(EncodedVideoChunk(byteArrayOf(1), 2L, isCodecConfig = false, isKeyFrame = false))

        assertEquals(EncodedVideoSessionFrameSinkResult.InvalidPayload("h264 bytes must be non-empty"), result)
        assertEquals(EncodedVideoSessionFrameSinkResult.Closed, afterClose)
        assertEquals(0, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 0, dropped = 2), sink.stats())
    }

    @Test
    fun negativePresentationTimeFailsClosedBeforeTransportWrite() {
        val transport = RecordingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)

        val result = sink.write(EncodedVideoChunk(byteArrayOf(1), -1L, isCodecConfig = false, isKeyFrame = false))

        assertEquals(EncodedVideoSessionFrameSinkResult.InvalidPayload("presentation time must be non-negative"), result)
        assertEquals(0, transport.payloads.size)
        assertEquals(1, transport.closeCount)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 0, dropped = 1), sink.stats())
    }

    @Test
    fun transportRuntimeExceptionFailsClosedInsteadOfLeaking() {
        val transport = ThrowingEncodedVideoTransport()
        val sink = EncodedVideoSessionFrameSink(transport)

        val result = sink.write(EncodedVideoChunk(byteArrayOf(1), 1L, isCodecConfig = false, isKeyFrame = true))
        val afterClose = sink.write(EncodedVideoChunk(byteArrayOf(2), 2L, isCodecConfig = false, isKeyFrame = true))

        assertEquals(EncodedVideoSessionFrameSinkResult.Failed("transport exception: boom"), result)
        assertEquals(EncodedVideoSessionFrameSinkResult.Closed, afterClose)
        assertEquals(1, transport.closeCount)
        assertEquals(EncodedVideoSessionFrameSinkStats(accepted = 0, dropped = 2), sink.stats())
    }
}

private class RecordingEncodedVideoTransport(
    private val result: EncodedVideoSessionFrameWriteResult = EncodedVideoSessionFrameWriteResult.Written,
) : EncodedVideoSessionFrameTransport {
    val payloads = mutableListOf<SessionPayload.VideoChunkV2>()
    var closeCount = 0

    override fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult {
        payloads += payload
        return result
    }

    override fun close() {
        closeCount += 1
    }
}

private class ThrowingEncodedVideoTransport : EncodedVideoSessionFrameTransport {
    var closeCount = 0

    override fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult {
        throw IllegalStateException("boom")
    }

    override fun close() {
        closeCount += 1
    }
}
