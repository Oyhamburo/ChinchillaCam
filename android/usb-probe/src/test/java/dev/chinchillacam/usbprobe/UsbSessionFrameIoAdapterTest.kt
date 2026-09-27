package dev.chinchillacam.usbprobe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSessionFrameIoAdapterTest {
    @Test
    fun writeEncodesSessionFrameAsExactAccessoryIoBytes() {
        val output = ByteArrayOutputStream()
        val session = sessionFor(inputBytes = ByteArray(0), output = output)
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)
        val frame = handshakeAccept()

        assertEquals(UsbSessionFrameIoWriteResult.Sent, adapter.write(session, frame))

        assertArrayEquals(
            AccessoryFrameCodec.encode(
                AccessoryFrame(USB_SESSION_FRAME_STREAM_ID, SessionFrameCodec.encode(frame)),
                maxPayloadBytes = 512,
            ),
            output.toByteArray(),
        )
    }

    @Test
    fun writeRejectsOversizeSessionPayloadBeforeWritingBytes() {
        val output = ByteArrayOutputStream()
        val session = sessionFor(inputBytes = ByteArray(0), output = output)
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 4, streamId = USB_SESSION_FRAME_STREAM_ID)
        val frame = handshakeAccept()

        assertEquals(
            UsbSessionFrameIoWriteResult.Oversize(SessionFrameCodec.encode(frame).size, 4),
            adapter.write(session, frame),
        )
        assertArrayEquals(ByteArray(0), output.toByteArray())
    }

    @Test
    fun readCompleteAccessoryFrameFromIoSession() {
        val frame = handshakeAccept()
        val encoded = encodedAccessoryFrame(USB_SESSION_FRAME_STREAM_ID, SessionFrameCodec.encode(frame))
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        val result = adapter.read(sessionFor(inputBytes = encoded))

        assertTrue(result is UsbSessionFrameIoReadResult.Received)
        assertEquals(frame, (result as UsbSessionFrameIoReadResult.Received).frame)
    }

    @Test
    fun readReturnsShortHeaderWhenEofHappensAfterPartialHeader() {
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.ShortHeader(expectedBytes = 8, actualBytes = 3),
            adapter.read(sessionFor(inputBytes = byteArrayOf(1, 2, 3))),
        )
    }

    @Test
    fun readReturnsShortPayloadWhenEofHappensAfterDeclaredPayloadStarts() {
        val input = littleEndianHeader(USB_SESSION_FRAME_STREAM_ID, payloadLength = 5) + byteArrayOf(1, 2)
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.ShortPayload(expectedBytes = 5, actualBytes = 2),
            adapter.read(sessionFor(inputBytes = input)),
        )
    }

    @Test
    fun readRejectsOversizeDeclaredPayloadBeforeReadingPayloadBytes() {
        val input = littleEndianHeader(USB_SESSION_FRAME_STREAM_ID, payloadLength = 5) + byteArrayOf(9, 9, 9, 9, 9)
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 4, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.Oversize(declaredBytes = 5, maxPayloadBytes = 4),
            adapter.read(sessionFor(inputBytes = input)),
        )
    }

    @Test
    fun readReturnsEofEmptyWhenNoHeaderBytesAreAvailable() {
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.EofEmpty,
            adapter.read(sessionFor(inputBytes = ByteArray(0))),
        )
    }

    @Test
    fun readRejectsWrongStreamIdAfterFullAccessoryFrame() {
        val wrongStreamId = USB_SESSION_FRAME_STREAM_ID + 1
        val encoded = encodedAccessoryFrame(wrongStreamId, SessionFrameCodec.encode(handshakeAccept()))
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.WrongStreamId(wrongStreamId, USB_SESSION_FRAME_STREAM_ID),
            adapter.read(sessionFor(inputBytes = encoded)),
        )
    }

    @Test
    fun readRejectsInvalidSessionFrameBytesAfterFullAccessoryFrame() {
        val encoded = encodedAccessoryFrame(USB_SESSION_FRAME_STREAM_ID, byteArrayOf('n'.code.toByte(), 'o'.code.toByte()))
        val adapter = UsbSessionFrameIoAdapter(maxPayloadBytes = 512, streamId = USB_SESSION_FRAME_STREAM_ID)

        assertEquals(
            UsbSessionFrameIoReadResult.InvalidSessionFrame(SessionFrameDecodeError.TruncatedFrame("header")),
            adapter.read(sessionFor(inputBytes = encoded)),
        )
    }

    private fun handshakeAccept(): SessionFrame = SessionFrame(
        sequence = 1,
        sessionId = "session-a",
        payload = SessionPayload.HandshakeAccept("desktop-1", "ready"),
    )

    private fun encodedAccessoryFrame(streamId: Int, payload: ByteArray): ByteArray =
        AccessoryFrameCodec.encode(AccessoryFrame(streamId, payload), maxPayloadBytes = 512)

    private fun sessionFor(
        inputBytes: ByteArray,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): AccessoryIoSession = AccessoryIoSession(
        input = ByteArrayInputStream(inputBytes),
        output = output,
        closeable = AutoCloseableCloseable(),
    )

    private fun littleEndianHeader(streamId: Int, payloadLength: Int): ByteArray =
        streamId.toLittleEndianBytesForTest() + payloadLength.toLittleEndianBytesForTest()

    private fun Int.toLittleEndianBytesForTest(): ByteArray = byteArrayOf(
        (this and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 24) and 0xff).toByte(),
    )

    private class AutoCloseableCloseable : java.io.Closeable {
        override fun close() = Unit
    }
}
