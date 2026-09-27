package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoSustainedFakeTransportAdapterTest {
    @Test
    fun writesVideoChunkV2SessionFramesWithOwnedMonotonicSequence() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 4)
        val adapter = EncodedVideoSustainedFakeTransportAdapter(sessionId = "session-a", transport = fake, initialSequence = 7)

        assertEquals(EncodedVideoSessionFrameWriteResult.Written, adapter.write(videoPayload(chunkIndex = 42)))
        assertEquals(EncodedVideoSessionFrameWriteResult.Written, adapter.write(videoPayload(chunkIndex = 42)))

        val first = decodeOutgoing(fake)
        val second = decodeOutgoing(fake)
        assertEquals("session-a", first.sessionId)
        assertEquals(7, first.sequence)
        assertEquals(8, second.sequence)
        assertEquals(42, (first.payload as SessionPayload.VideoChunkV2).chunkIndex)
        assertEquals(SessionFrameType.VIDEO_CHUNK_V2, first.type)
    }

    @Test
    fun preflightsSessionFramePayloadAt65528EvenWhenFakeTransportIsPermissive() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_536, outgoingCapacityFrames = 1)
        val adapter = EncodedVideoSustainedFakeTransportAdapter(sessionId = "s", transport = fake)
        val payload = videoPayload(h264Bytes = ByteArray(65_497) { 1 })

        assertEquals(EncodedVideoSessionFrameWriteResult.Oversized, adapter.write(payload))
        assertEquals(null, fake.removeOutgoingEncodedAccessoryFrame())
    }

    @Test
    fun mapsBackpressureClosedAndOversizeWithoutAdvancingSequence() {
        val backpressureFake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 0)
        val backpressureAdapter = EncodedVideoSustainedFakeTransportAdapter("s", backpressureFake, initialSequence = 5)
        assertEquals(EncodedVideoSessionFrameWriteResult.BackpressureExceeded, backpressureAdapter.write(videoPayload()))
        assertEquals(EncodedVideoSessionFrameWriteResult.Closed, backpressureAdapter.write(videoPayload()))
        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, backpressureFake.write(SessionFrame(1, 5, "s", videoPayload())))

        val closedFake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val closedAdapter = EncodedVideoSustainedFakeTransportAdapter("s", closedFake)
        closedFake.close()
        assertEquals(EncodedVideoSessionFrameWriteResult.Closed, closedAdapter.write(videoPayload()))

        val tinyFake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 1, outgoingCapacityFrames = 1)
        val tinyAdapter = EncodedVideoSustainedFakeTransportAdapter("s", tinyFake)
        assertEquals(EncodedVideoSessionFrameWriteResult.Oversized, tinyAdapter.write(videoPayload()))
    }

    @Test
    fun validatesSessionIdAndSequenceAndFailsClosedBeforeSequenceWrap() {
        assertTrue(runCatching { EncodedVideoSustainedFakeTransportAdapter("", UsbSessionFrameSustainedFakeTransport(65_528, 1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { EncodedVideoSustainedFakeTransportAdapter("x".repeat(65_536), UsbSessionFrameSustainedFakeTransport(65_528, 1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { EncodedVideoSustainedFakeTransportAdapter("s", UsbSessionFrameSustainedFakeTransport(65_528, 1), initialSequence = -1) }.exceptionOrNull() is IllegalArgumentException)

        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 2)
        val adapter = EncodedVideoSustainedFakeTransportAdapter("s", fake, initialSequence = Int.MAX_VALUE)
        assertEquals(EncodedVideoSessionFrameWriteResult.Written, adapter.write(videoPayload()))
        assertEquals(Int.MAX_VALUE, decodeOutgoing(fake).sequence)
        assertEquals(EncodedVideoSessionFrameWriteResult.Closed, adapter.write(videoPayload()))
        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, fake.write(SessionFrame(1, 0, "s", videoPayload())))
    }


    @Test
    fun malformedPayloadEncodeExceptionFailsClosedWithoutLeaking() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val adapter = EncodedVideoSustainedFakeTransportAdapter("s", fake)

        assertEquals(
            EncodedVideoSessionFrameWriteResult.Failed("encode exception: h264 bytes must not be empty"),
            adapter.write(videoPayload(h264Bytes = byteArrayOf())),
        )
        assertEquals(EncodedVideoSessionFrameWriteResult.Closed, adapter.write(videoPayload()))
        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, fake.write(SessionFrame(1, 0, "s", videoPayload())))
    }

    @Test
    fun closeDelegatesAndLaterWritesReturnClosed() {
        val fake = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 65_528, outgoingCapacityFrames = 1)
        val adapter = EncodedVideoSustainedFakeTransportAdapter("s", fake)

        adapter.close()

        assertEquals(EncodedVideoSessionFrameWriteResult.Closed, adapter.write(videoPayload()))
        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, fake.write(SessionFrame(1, 0, "s", videoPayload())))
    }

    private fun videoPayload(
        chunkIndex: Int = 0,
        h264Bytes: ByteArray = byteArrayOf(0x65),
    ): SessionPayload.VideoChunkV2 = SessionPayload.VideoChunkV2(
        chunkIndex = chunkIndex,
        presentationTimeUs = 1L,
        frameKind = SessionVideoFrameKind.KEY,
        h264Bytes = h264Bytes,
    )

    private fun decodeOutgoing(fake: UsbSessionFrameSustainedFakeTransport): SessionFrame {
        val accessoryBytes = fake.removeOutgoingEncodedAccessoryFrame() ?: error("expected queued frame")
        val accessory = AccessoryFrameCodec.decode(accessoryBytes, maxPayloadBytes = 65_536).getOrThrow()
        return SessionFrameCodec.decode(accessory.payload).getOrThrow()
    }
}
