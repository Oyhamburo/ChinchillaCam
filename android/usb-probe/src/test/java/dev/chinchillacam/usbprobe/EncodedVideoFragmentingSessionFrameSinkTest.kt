package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedVideoFragmentingSessionFrameSinkTest {
    @Test
    fun choosesType8AtSessionS65496BoundaryAndType9AboveIt() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 8)
        val sink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))

        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Accepted(chunkIndex = 0, framesWritten = 1), sink.write(chunk(ByteArray(65_496) { 1 })))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Accepted(chunkIndex = 1, framesWritten = 2), sink.write(chunk(ByteArray(65_497) { 2 })))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkStats(accepted = 2, dropped = 0), sink.stats())

        val first = decodeOutgoing(fake)
        val second = decodeOutgoing(fake)
        val third = decodeOutgoing(fake)
        assertEquals(SessionFrameType.VIDEO_CHUNK_V2, first.type)
        assertEquals(0, (first.payload as SessionPayload.VideoChunkV2).chunkIndex)
        assertEquals(0, first.sequence)
        assertEquals(SessionFrameType.VIDEO_CHUNK_FRAGMENT_V1, second.type)
        val secondPayload = second.payload as SessionPayload.VideoChunkFragmentV1
        assertEquals(1, secondPayload.chunkIndex)
        assertEquals(0, secondPayload.fragmentIndex)
        assertEquals(1, second.sequence)
        assertEquals(1, (third.payload as SessionPayload.VideoChunkFragmentV1).fragmentIndex)
        assertEquals(2, third.sequence)
    }

    @Test
    fun sends65536AsFragmentsAndPreservesKindPriorityAndCopiesBytes() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 4)
        val sink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))
        val bytes = ByteArray(65_536) { (it and 0xff).toByte() }

        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Accepted(chunkIndex = 0, framesWritten = 2), sink.write(chunk(bytes, isCodecConfig = true, isKeyFrame = true)))
        bytes.fill(0)

        val first = decodeOutgoing(fake).payload as SessionPayload.VideoChunkFragmentV1
        val second = decodeOutgoing(fake).payload as SessionPayload.VideoChunkFragmentV1
        assertEquals(SessionVideoFrameKind.CODEC_CONFIG, first.frameKind)
        assertEquals(65_536, first.totalH264Bytes)
        assertArrayEquals(ByteArray(256) { it.toByte() }, first.fragmentBytes.copyOfRange(0, 256))
        assertEquals(2, second.fragmentCount)
    }

    @Test
    fun partialFragmentBackpressureClosesWithoutFalseAcceptOrRetry() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val sink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))

        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.BackpressureExceeded, sink.write(chunk(ByteArray(65_497) { 1 })))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkStats(accepted = 0, dropped = 1), sink.stats())
        assertEquals(0, (decodeOutgoing(fake).payload as SessionPayload.VideoChunkFragmentV1).fragmentIndex)
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Closed, sink.write(chunk(ByteArray(1) { 1 })))
    }

    @Test
    fun rejectsInvalidAndOversizedBeforeSending() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val emptySink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.InvalidPayload("h264 bytes must be non-empty"), emptySink.write(chunk(byteArrayOf())))
        assertEquals(null, fake.removeOutgoingEncodedAccessoryFrame())

        val negativeSink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", UsbSessionFrameSustainedFakeTransport(65_528, 1)))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.InvalidPayload("presentation time must be non-negative"), negativeSink.write(chunk(byteArrayOf(1), presentationTimeUs = -1L)))

        val oversizedFake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val oversizedSink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", oversizedFake))
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Oversized, oversizedSink.write(chunk(ByteArray(4 * 1024 * 1024 + 1) { 1 })))
        assertEquals(null, oversizedFake.removeOutgoingEncodedAccessoryFrame())
    }

    @Test
    fun maxChunkIndexIsLastAcceptedWithoutWrap() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 2)
        val sink = EncodedVideoFragmentingSessionFrameSink(EncodedVideoSustainedFakeTransportAdapter("s", fake), initialChunkIndex = Int.MAX_VALUE)

        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Accepted(chunkIndex = Int.MAX_VALUE, framesWritten = 1), sink.write(chunk(ByteArray(1) { 1 })))
        assertEquals(Int.MAX_VALUE, (decodeOutgoing(fake).payload as SessionPayload.VideoChunkV2).chunkIndex)
        assertEquals(EncodedVideoFragmentingSessionFrameSinkResult.Closed, sink.write(chunk(ByteArray(1) { 1 })))
    }

    private fun chunk(
        bytes: ByteArray,
        presentationTimeUs: Long = 1L,
        isCodecConfig: Boolean = false,
        isKeyFrame: Boolean = false,
    ): EncodedVideoChunk = EncodedVideoChunk(bytes, presentationTimeUs, isCodecConfig, isKeyFrame)

    private fun decodeOutgoing(fake: UsbSessionFrameSustainedFakeTransport): SessionFrame {
        val accessoryBytes = fake.removeOutgoingEncodedAccessoryFrame() ?: error("expected queued frame")
        val accessory = AccessoryFrameCodec.decode(accessoryBytes, maxPayloadBytes = 65_536).getOrThrow()
        return SessionFrameCodec.decode(accessory.payload).getOrThrow()
    }
}
