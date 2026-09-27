package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionFrameTransportTest {
    @Test
    fun roundtripsHandshakeHelloThroughDedicatedUsbStream() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
        val frame = SessionFrame(
            sequence = 1,
            sessionId = "session-a",
            payload = SessionPayload.HandshakeHello("android-phone", "ChinchillaCam", listOf("h264", "metrics")),
        )

        assertEquals(UsbSessionFrameSendResult.Sent, transport.send(frame))

        val received = transport.receive()
        assertTrue(received is UsbSessionFrameReceiveResult.Received)
        assertEquals(frame, (received as UsbSessionFrameReceiveResult.Received).frame)
    }

    @Test
    fun roundtripsStreamMetadata() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
        val frame = SessionFrame(
            sequence = 2,
            sessionId = "session-a",
            payload = SessionPayload.StreamMetadata("video/h264", 1920, 1080, 30, "baseline"),
        )

        assertEquals(UsbSessionFrameSendResult.Sent, transport.send(frame))

        val received = transport.receive()
        assertTrue(received is UsbSessionFrameReceiveResult.Received)
        assertEquals(frame, (received as UsbSessionFrameReceiveResult.Received).frame)
    }

    @Test
    fun roundtripsVideoChunkBytes() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
        val frame = SessionFrame(
            sequence = 3,
            sessionId = "session-a",
            payload = SessionPayload.VideoChunk(7, 123456789L, byteArrayOf(0x00, 0x00, 0x01, 0x65.toByte(), 0x88.toByte())),
        )

        assertEquals(UsbSessionFrameSendResult.Sent, transport.send(frame))

        val received = transport.receive()
        assertTrue(received is UsbSessionFrameReceiveResult.Received)
        val payload = (received as UsbSessionFrameReceiveResult.Received).frame.payload as SessionPayload.VideoChunk
        assertEquals(7, payload.chunkIndex)
        assertEquals(123456789L, payload.presentationTimeUs)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01, 0x65.toByte(), 0x88.toByte()), payload.h264Bytes)
    }

    @Test
    fun rejectsWrongUsbStreamId() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
        val sessionBytes = SessionFrameCodec.encode(handshakeAccept())
        val wrongStreamBytes = AccessoryFrameCodec.encode(AccessoryFrame(USB_SESSION_FRAME_STREAM_ID + 1, sessionBytes), maxPayloadBytes = 512)

        transport.enqueueEncodedAccessoryFrame(wrongStreamBytes)

        assertEquals(
            UsbSessionFrameReceiveResult.WrongStreamId(USB_SESSION_FRAME_STREAM_ID + 1, USB_SESSION_FRAME_STREAM_ID),
            transport.receive(),
        )
    }

    @Test
    fun rejectsUsbPayloadOversizeOnSendAndReceive() {
        val tinyTransport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 4)
        val frame = handshakeAccept()

        assertEquals(
            UsbSessionFrameSendResult.UsbPayloadOversize(SessionFrameCodec.encode(frame).size, 4),
            tinyTransport.send(frame),
        )

        val receivingTransport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 4)
        receivingTransport.enqueueEncodedAccessoryFrame(littleEndianHeader(streamId = USB_SESSION_FRAME_STREAM_ID, payloadLength = 5))

        assertEquals(
            UsbSessionFrameReceiveResult.UsbPayloadOversize(declaredBytes = 5, maxPayloadBytes = 4),
            receivingTransport.receive(),
        )
    }

    @Test
    fun rejectsInvalidSessionFrameBytes() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
        val invalidSessionBytes = byteArrayOf('n'.code.toByte(), 'o'.code.toByte())
        transport.enqueueEncodedAccessoryFrame(
            AccessoryFrameCodec.encode(AccessoryFrame(USB_SESSION_FRAME_STREAM_ID, invalidSessionBytes), maxPayloadBytes = 512),
        )

        assertEquals(
            UsbSessionFrameReceiveResult.InvalidSessionFrame(SessionFrameDecodeError.TruncatedFrame("header")),
            transport.receive(),
        )
    }

    @Test
    fun returnsEmptyQueueWhenNoEncodedFrameIsAvailable() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)

        assertEquals(UsbSessionFrameReceiveResult.EmptyQueue, transport.receive())
    }

    @Test
    fun preservesSessionPayloadBytesOpaquelyInsideLittleEndianAccessoryFrame() {
        val transport = InMemoryUsbSessionFrameTransport(maxPayloadBytes = 512)
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

        assertEquals(UsbSessionFrameSendResult.Sent, transport.send(frame))

        val accessoryBytes = transport.sentEncodedAccessoryFrames.single()
        val decodedAccessory = AccessoryFrameCodec.decode(accessoryBytes, maxPayloadBytes = 512).getOrThrow()
        assertEquals(USB_SESSION_FRAME_STREAM_ID, decodedAccessory.streamId)
        assertArrayEquals(sessionBytes, decodedAccessory.payload)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), decodedAccessory.payload.copyOfRange(6, 10))
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), accessoryBytes.copyOfRange(0, 4))
    }

    private fun handshakeAccept(): SessionFrame = SessionFrame(
        sequence = 1,
        sessionId = "session-a",
        payload = SessionPayload.HandshakeAccept("desktop-1", "ready"),
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
