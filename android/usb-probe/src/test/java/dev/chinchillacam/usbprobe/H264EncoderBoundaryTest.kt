package dev.chinchillacam.usbprobe

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264EncoderBoundaryTest {
    private val H264_CSD_BYTES = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
        0x00, 0x00, 0x00, 0x01, 0x68, 0xce.toByte(), 0x06, 0xe2.toByte(),
    )

    @Test
    fun startsSurfaceInputEncoderWithExplicitConfiguration() {
        val inputSurface = FakeEncoderSurface("codec-input")
        val codec = FakeH264CodecSession(inputSurface)
        val gateway = RecordingH264EncoderGateway(H264EncoderStartOutcome.Started(inputSurface, codec))
        val config = sampleConfig()

        val result = H264EncoderBoundary(gateway).start(config)

        assertTrue(result is H264EncoderStartResult.Started)
        val started = result as H264EncoderStartResult.Started
        assertEquals(inputSurface, started.inputSurface)
        assertEquals(config, gateway.configs.single())
    }

    @Test
    fun mapsConfigureFailureToTypedResult() {
        val gateway = RecordingH264EncoderGateway(H264EncoderStartOutcome.Failed("codec unavailable"))

        val result = H264EncoderBoundary(gateway).start(sampleConfig())

        assertEquals(H264EncoderStartResult.Failed("codec unavailable"), result)
    }

    @Test
    fun drainsOwnedChunkCopyFromBufferInfoOffsetAndSize() {
        val source = ByteBuffer.wrap(byteArrayOf(9, 8, 1, 2, 3, 7))
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.Buffer(
            index = 3,
            buffer = source,
            info = H264BufferInfo(offset = 2, size = 3, presentationTimeUs = 42L, flags = 0),
        )
        val session = startedSession(codec)

        val result = session.drain(maxOutputs = 1)
        source.put(2, 99)

        val chunks = (result as H264DrainResult.Chunks).chunks
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), chunks.single().bytes)
        assertEquals(42L, chunks.single().presentationTimeUs)
        assertFalse(chunks.single().isCodecConfig)
        assertFalse(chunks.single().isKeyFrame)
        assertEquals(listOf(3), codec.releasedOutputBuffers)
    }

    @Test
    fun emitsCodecConfigAndKeyFrameFlagsWithoutDroppingSpsPps() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.FormatChanged("avc-format")
        codec.outputs += H264CodecOutput.Buffer(
            index = 4,
            buffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 103)),
            info = H264BufferInfo(offset = 0, size = 5, presentationTimeUs = 100L, flags = H264BufferFlags.CODEC_CONFIG),
        )
        codec.outputs += H264CodecOutput.Buffer(
            index = 5,
            buffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 101)),
            info = H264BufferInfo(offset = 0, size = 5, presentationTimeUs = 200L, flags = H264BufferFlags.KEY_FRAME),
        )
        val session = startedSession(codec)

        val result = session.drain(maxOutputs = 3) as H264DrainResult.Chunks

        assertEquals(listOf("avc-format"), session.outputFormats)
        assertEquals(2, result.chunks.size)
        assertTrue(result.chunks[0].isCodecConfig)
        assertFalse(result.chunks[0].isKeyFrame)
        assertFalse(result.chunks[1].isCodecConfig)
        assertTrue(result.chunks[1].isKeyFrame)
        assertEquals(listOf(4, 5), codec.releasedOutputBuffers)
    }


    @Test
    fun emitsCodecConfigFromFormatChangedBeforeKeyFrame() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        val mutableCsd = H264_CSD_BYTES.copyOf()
        codec.outputs += H264CodecOutput.FormatChanged("avc-format", codecConfigBytes = mutableCsd)
        mutableCsd[4] = 0x66
        codec.outputs += H264CodecOutput.Buffer(
            index = 5,
            buffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 101)),
            info = H264BufferInfo(offset = 0, size = 5, presentationTimeUs = 200L, flags = H264BufferFlags.KEY_FRAME),
        )
        val session = startedSession(codec)

        val result = session.drain(maxOutputs = 2) as H264DrainResult.Chunks

        assertEquals(listOf("avc-format"), session.outputFormats)
        assertEquals(2, result.chunks.size)
        assertArrayEquals(H264_CSD_BYTES, result.chunks[0].bytes)
        assertEquals(0L, result.chunks[0].presentationTimeUs)
        assertTrue(result.chunks[0].isCodecConfig)
        assertFalse(result.chunks[0].isKeyFrame)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 101), result.chunks[1].bytes)
        assertEquals(200L, result.chunks[1].presentationTimeUs)
        assertFalse(result.chunks[1].isCodecConfig)
        assertTrue(result.chunks[1].isKeyFrame)
        assertEquals(listOf(5), codec.releasedOutputBuffers)
    }

    @Test
    fun formatChangedCodecConfigCountsAgainstMaxOutputsAndPendingBackpressure() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.FormatChanged("avc-format", codecConfigBytes = H264_CSD_BYTES)
        codec.outputs += H264CodecOutput.Buffer(5, ByteBuffer.wrap(byteArrayOf(101)), H264BufferInfo(0, 1, 200L, H264BufferFlags.KEY_FRAME))
        val session = startedSession(codec, maxPendingChunks = 1)

        val first = session.drain(maxOutputs = 1) as H264DrainResult.Chunks
        val blocked = session.drain(maxOutputs = 1)
        session.consumePending(1)
        val second = session.drain(maxOutputs = 1) as H264DrainResult.Chunks

        assertArrayEquals(H264_CSD_BYTES, first.chunks.single().bytes)
        assertEquals(H264DrainResult.BackpressureExceeded(maxPendingChunks = 1), blocked)
        assertArrayEquals(byteArrayOf(101), second.chunks.single().bytes)
        assertEquals(listOf(5), codec.releasedOutputBuffers)
    }

    @Test
    fun formatChangedWithoutValidCodecConfigDoesNotInventConfigChunk() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.FormatChanged("missing-csd")
        codec.outputs += H264CodecOutput.FormatChanged("empty-csd", codecConfigBytes = byteArrayOf())
        codec.outputs += H264CodecOutput.Buffer(5, ByteBuffer.wrap(byteArrayOf(101)), H264BufferInfo(0, 1, 200L, H264BufferFlags.KEY_FRAME))
        val session = startedSession(codec)

        val result = session.drain(maxOutputs = 3) as H264DrainResult.Chunks

        assertEquals(listOf("missing-csd", "empty-csd"), session.outputFormats)
        assertEquals(1, result.chunks.size)
        assertArrayEquals(byteArrayOf(101), result.chunks.single().bytes)
        assertTrue(result.chunks.single().isKeyFrame)
        assertFalse(result.chunks.single().isCodecConfig)
        assertEquals(listOf(5), codec.releasedOutputBuffers)
    }

    @Test
    fun releasesOutputBufferWhenCopyFails() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.Buffer(
            index = 9,
            buffer = ByteBuffer.wrap(byteArrayOf(1, 2)),
            info = H264BufferInfo(offset = 1, size = 5, presentationTimeUs = 1L, flags = 0),
        )
        val session = startedSession(codec)

        val result = session.drain(maxOutputs = 1)

        assertEquals(H264DrainResult.Failed("output buffer bounds invalid"), result)
        assertEquals(listOf(9), codec.releasedOutputBuffers)
    }

    @Test
    fun boundsPendingOutputBackpressure() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.Buffer(1, ByteBuffer.wrap(byteArrayOf(1)), H264BufferInfo(0, 1, 1L, 0))
        codec.outputs += H264CodecOutput.Buffer(2, ByteBuffer.wrap(byteArrayOf(2)), H264BufferInfo(0, 1, 2L, 0))
        val session = startedSession(codec, maxPendingChunks = 1)

        assertTrue(session.drain(maxOutputs = 1) is H264DrainResult.Chunks)
        val blocked = session.drain(maxOutputs = 1)

        assertEquals(H264DrainResult.BackpressureExceeded(maxPendingChunks = 1), blocked)
        assertEquals(listOf(1), codec.releasedOutputBuffers)
    }

    @Test
    fun consumePendingAllowsFurtherDrain() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.Buffer(1, ByteBuffer.wrap(byteArrayOf(1)), H264BufferInfo(0, 1, 1L, 0))
        codec.outputs += H264CodecOutput.Buffer(2, ByteBuffer.wrap(byteArrayOf(2)), H264BufferInfo(0, 1, 2L, 0))
        val session = startedSession(codec, maxPendingChunks = 1)

        session.drain(maxOutputs = 1)
        session.consumePending(1)
        val result = session.drain(maxOutputs = 1) as H264DrainResult.Chunks

        assertArrayEquals(byteArrayOf(2), result.chunks.single().bytes)
        assertEquals(listOf(1, 2), codec.releasedOutputBuffers)
    }

    @Test
    fun stopIsIdempotentAndReportsCloseErrors() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"), stopFailure = "stop failed", releaseFailure = "release failed")
        val session = startedSession(codec)

        val first = session.stop()
        val second = session.stop()

        assertEquals(H264EncoderStopResult.Failed(listOf("stop failed", "release failed")), first)
        assertEquals(H264EncoderStopResult.AlreadyStopped, second)
        assertEquals(1, codec.stopCount)
        assertEquals(1, codec.releaseCount)
    }

    @Test
    fun cancelReleasesCodecAndPreventsDrain() {
        val codec = FakeH264CodecSession(FakeEncoderSurface("codec-input"))
        codec.outputs += H264CodecOutput.Buffer(1, ByteBuffer.wrap(byteArrayOf(1)), H264BufferInfo(0, 1, 1L, 0))
        val session = startedSession(codec)

        val cancelResult = session.cancel()
        val drainResult = session.drain(maxOutputs = 1)

        assertEquals(H264EncoderStopResult.Stopped, cancelResult)
        assertEquals(H264DrainResult.Stopped, drainResult)
        assertEquals(1, codec.releaseCount)
    }

    private fun startedSession(
        codec: FakeH264CodecSession,
        maxPendingChunks: Int = 4,
    ): H264EncoderSession = H264EncoderSession(codec.inputSurface, codec, maxPendingChunks)

    private fun sampleConfig(): H264EncoderConfig = H264EncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 2_000_000,
        frameRate = 30,
        iFrameIntervalSeconds = 2,
    )
}

private data class FakeEncoderSurface(
    override val label: String,
) : EncoderInputSurface

private class RecordingH264EncoderGateway(
    private val outcome: H264EncoderStartOutcome,
) : H264EncoderGateway {
    val configs = mutableListOf<H264EncoderConfig>()
    override fun start(config: H264EncoderConfig): H264EncoderStartOutcome {
        configs += config
        return outcome
    }
}

private class FakeH264CodecSession(
    override val inputSurface: EncoderInputSurface,
    private val stopFailure: String? = null,
    private val releaseFailure: String? = null,
) : CloseableH264CodecSession {
    val outputs = ArrayDeque<H264CodecOutput>()
    val releasedOutputBuffers = mutableListOf<Int>()
    var stopCount = 0
    var releaseCount = 0

    override fun dequeueOutput(): H264CodecOutput = outputs.removeFirstOrNull() ?: H264CodecOutput.TryAgainLater

    override fun releaseOutputBuffer(index: Int) {
        releasedOutputBuffers += index
    }

    override fun stop(): H264CodecCloseOutcome {
        stopCount += 1
        return stopFailure?.let { H264CodecCloseOutcome.Failed(it) } ?: H264CodecCloseOutcome.Closed
    }

    override fun release(): H264CodecCloseOutcome {
        releaseCount += 1
        return releaseFailure?.let { H264CodecCloseOutcome.Failed(it) } ?: H264CodecCloseOutcome.Closed
    }
}
