package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionFrameSustainedFakeTransportTest {
    @Test
    fun writeEnqueuesEncodedAccessoryFramesFifoUntilCapacityThenBackpressure() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 2)
        val first = handshakeAccept(sequence = 1)
        val second = handshakeAccept(sequence = 2)
        val third = handshakeAccept(sequence = 3)

        assertEquals(UsbSessionFrameSustainedWriteResult.Sent, transport.write(first))
        assertEquals(UsbSessionFrameSustainedWriteResult.Sent, transport.write(second))
        assertEquals(
            UsbSessionFrameSustainedWriteResult.Backpressure(capacityFrames = 2, queuedFrames = 2),
            transport.write(third),
        )

        assertArrayEquals(encoded(first), transport.removeOutgoingEncodedAccessoryFrame())
        assertArrayEquals(encoded(second), transport.removeOutgoingEncodedAccessoryFrame())
        assertEquals(null, transport.removeOutgoingEncodedAccessoryFrame())
    }

    @Test
    fun writeAndReadReturnClosedAfterClose() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)

        transport.close()

        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, transport.write(handshakeAccept()))
        assertEquals(UsbSessionFrameSustainedReadResult.Closed, transport.read())
    }

    @Test
    fun readWithNoIncomingBytesReturnsOpenTimeoutAndConsumesNothing() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)

        assertEquals(UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = false), transport.read())
        assertEquals(UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = false), transport.read())
    }

    @Test
    fun readWithPartialHeaderReturnsClosedTimeoutAndPoisonsSession() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)
        transport.enqueueIncomingBytes(byteArrayOf(1, 2, 3))

        assertEquals(UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = true), transport.read())
        assertEquals(UsbSessionFrameSustainedReadResult.Closed, transport.read())
        assertEquals(UsbSessionFrameSustainedWriteResult.Closed, transport.write(handshakeAccept()))
    }

    @Test
    fun readWithPartialPayloadReturnsClosedTimeoutAndPoisonsSession() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)
        transport.enqueueIncomingBytes(littleEndianHeader(USB_SESSION_FRAME_STREAM_ID, payloadLength = 5) + byteArrayOf(9, 9))

        assertEquals(UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = true), transport.read())
        assertEquals(UsbSessionFrameSustainedReadResult.Closed, transport.read())
    }

    @Test
    fun oversizeDeclaredPayloadClosesBeforeConsumingPayload() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 4, outgoingCapacityFrames = 1)
        transport.enqueueIncomingBytes(littleEndianHeader(USB_SESSION_FRAME_STREAM_ID, payloadLength = 5) + byteArrayOf(9, 9, 9, 9, 9))

        assertEquals(
            UsbSessionFrameSustainedReadResult.Oversize(declaredBytes = 5, maxPayloadBytes = 4, sessionClosed = true),
            transport.read(),
        )
        assertEquals(5, transport.pendingIncomingPayloadBytesForTest())
        assertEquals(UsbSessionFrameSustainedReadResult.Closed, transport.read())
    }

    @Test
    fun completeIncomingFramesDecodeFifoToSessionFrames() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)
        val first = handshakeAccept(sequence = 1)
        val second = handshakeAccept(sequence = 2)
        transport.enqueueIncomingBytes(encoded(first) + encoded(second))

        val firstResult = transport.read()
        val secondResult = transport.read()

        assertTrue(firstResult is UsbSessionFrameSustainedReadResult.Received)
        assertTrue(secondResult is UsbSessionFrameSustainedReadResult.Received)
        assertEquals(first, (firstResult as UsbSessionFrameSustainedReadResult.Received).frame)
        assertEquals(second, (secondResult as UsbSessionFrameSustainedReadResult.Received).frame)
        assertEquals(UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = false), transport.read())
    }

    @Test
    fun sessionFrameBytesRemainOpaqueInsideAccessoryFrame() {
        val transport = UsbSessionFrameSustainedFakeTransport(maxPayloadBytes = 512, outgoingCapacityFrames = 1)
        val frame = SessionFrame(
            sequence = 0x01020304,
            sessionId = "s",
            payload = SessionPayload.VideoChunk(
                chunkIndex = 0x0A0B0C0D,
                presentationTimeUs = 0x0102030405060708L,
                h264Bytes = byteArrayOf(0x01, 0x23, 0x45, 0x67),
            ),
        )
        val sessionBytes = SessionFrameCodec.encode(frame)

        assertEquals(UsbSessionFrameSustainedWriteResult.Sent, transport.write(frame))

        val accessoryBytes = transport.removeOutgoingEncodedAccessoryFrame()!!
        val decodedAccessory = AccessoryFrameCodec.decode(accessoryBytes, maxPayloadBytes = 512).getOrThrow()
        assertEquals(USB_SESSION_FRAME_STREAM_ID, decodedAccessory.streamId)
        assertArrayEquals(sessionBytes, decodedAccessory.payload)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), decodedAccessory.payload.copyOfRange(6, 10))
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), accessoryBytes.copyOfRange(0, 4))
    }

    private fun handshakeAccept(sequence: Int = 1): SessionFrame = SessionFrame(
        sequence = sequence,
        sessionId = "session-a",
        payload = SessionPayload.HandshakeAccept("desktop-1", "ready"),
    )

    private fun encoded(frame: SessionFrame): ByteArray = AccessoryFrameCodec.encode(
        AccessoryFrame(USB_SESSION_FRAME_STREAM_ID, SessionFrameCodec.encode(frame)),
        maxPayloadBytes = 512,
    )

    private fun littleEndianHeader(streamId: Int, payloadLength: Int): ByteArray =
        streamId.toLittleEndianBytesForTest() + payloadLength.toLittleEndianBytesForTest()

    private fun Int.toLittleEndianBytesForTest(): ByteArray = byteArrayOf(
        (this and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 24) and 0xff).toByte(),
    )
}
