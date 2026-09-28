package dev.chinchillacam.usbprobe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbTlsCiphertextIoAdapterTest {
    @Test
    fun writesNonemptyCiphertextOnReservedTlsStream() {
        val output = ByteArrayOutputStream()
        val session = sessionFor(inputBytes = ByteArray(0), output = output)
        val ciphertext = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x20)

        assertEquals(UsbTlsCiphertextWriteResult.Sent, UsbTlsCiphertextIoAdapter().write(session, ciphertext))

        assertArrayEquals(
            AccessoryFrameCodec.encode(AccessoryFrame(USB_TLS_RECORD_STREAM_ID, ciphertext), maxPayloadBytes = USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES),
            output.toByteArray(),
        )
    }

    @Test
    fun splitsOutboundCiphertextIntoBoundedAccessoryFrames() {
        val output = ByteArrayOutputStream()
        val session = sessionFor(inputBytes = ByteArray(0), output = output)
        val ciphertext = ByteArray(USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES + 1) { it.toByte() }

        assertEquals(UsbTlsCiphertextWriteResult.Sent, UsbTlsCiphertextIoAdapter().write(session, ciphertext))

        assertArrayEquals(
            AccessoryFrameCodec.encode(
                AccessoryFrame(USB_TLS_RECORD_STREAM_ID, ciphertext.copyOfRange(0, USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES)),
                maxPayloadBytes = USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES,
            ) + AccessoryFrameCodec.encode(
                AccessoryFrame(USB_TLS_RECORD_STREAM_ID, ciphertext.copyOfRange(USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES, ciphertext.size)),
                maxPayloadBytes = USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES,
            ),
            output.toByteArray(),
        )
    }

    @Test
    fun rejectsEmptyAndOverTotalWritesWithoutCleartextTlsClaims() {
        val emptyCloseable = RecordingCloseable()
        val emptyOutput = ByteArrayOutputStream()
        val emptySession = sessionFor(ByteArray(0), emptyOutput, emptyCloseable)
        val oversizeCloseable = RecordingCloseable()
        val oversizeOutput = ByteArrayOutputStream()
        val oversizeSession = sessionFor(ByteArray(0), oversizeOutput, oversizeCloseable)

        assertEquals(UsbTlsCiphertextWriteResult.Empty, UsbTlsCiphertextIoAdapter().write(emptySession, ByteArray(0)))
        assertEquals(
            UsbTlsCiphertextWriteResult.Oversize(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES + 1, USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES),
            UsbTlsCiphertextIoAdapter().write(oversizeSession, ByteArray(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES + 1)),
        )

        assertEquals(true, emptyCloseable.closed)
        assertEquals(true, oversizeCloseable.closed)
        assertArrayEquals(ByteArray(0), emptyOutput.toByteArray())
        assertArrayEquals(ByteArray(0), oversizeOutput.toByteArray())
    }

    @Test
    fun closesSessionWhenWriteFails() {
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), FailingOutputStream(), closeable)

        val failure = runCatching { UsbTlsCiphertextIoAdapter().write(session, byteArrayOf(1)) }

        assertEquals(true, failure.isFailure)
        assertEquals(true, closeable.closed)
    }

    @Test
    fun readsSplitTlsCiphertextFramesWithoutRecordBoundaryAssumptions() {
        val first = byteArrayOf(0x16, 0x03, 0x03)
        val second = byteArrayOf(0x00, 0x20)
        val input = encodedFrame(USB_TLS_RECORD_STREAM_ID, first) + encodedFrame(USB_TLS_RECORD_STREAM_ID, second)
        val adapter = UsbTlsCiphertextIoAdapter()
        val session = sessionFor(input)

        assertEquals(UsbTlsCiphertextReadResult.Received(first), adapter.read(session))
        assertEquals(UsbTlsCiphertextReadResult.Received(second), adapter.read(session))
    }

    @Test
    fun closesAndFailsOnWrongStreamEmptyOversizeAndTruncatedFrames() {
        val wrong = sessionFor(encodedFrame(USB_SESSION_FRAME_STREAM_ID, byteArrayOf(1)), closeable = RecordingCloseable())
        val empty = sessionFor(littleEndianHeader(USB_TLS_RECORD_STREAM_ID, 0), closeable = RecordingCloseable())
        val oversize = sessionFor(littleEndianHeader(USB_TLS_RECORD_STREAM_ID, USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES + 1), closeable = RecordingCloseable())
        val truncated = sessionFor(littleEndianHeader(USB_TLS_RECORD_STREAM_ID, 3) + byteArrayOf(1), closeable = RecordingCloseable())

        assertEquals(UsbTlsCiphertextReadResult.WrongStreamId(USB_SESSION_FRAME_STREAM_ID, USB_TLS_RECORD_STREAM_ID), UsbTlsCiphertextIoAdapter().read(wrong))
        assertEquals(UsbTlsCiphertextReadResult.Empty, UsbTlsCiphertextIoAdapter().read(empty))
        assertEquals(
            UsbTlsCiphertextReadResult.Oversize(USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES + 1, USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES),
            UsbTlsCiphertextIoAdapter().read(oversize),
        )
        assertEquals(UsbTlsCiphertextReadResult.Truncated(expectedBytes = 3, actualBytes = 1), UsbTlsCiphertextIoAdapter().read(truncated))
        assertEquals(true, wrong.closeable.closed)
        assertEquals(true, empty.closeable.closed)
        assertEquals(true, oversize.closeable.closed)
        assertEquals(true, truncated.closeable.closed)
    }

    @Test
    fun closesAndFailsOnMalformedShortHeader() {
        val session = sessionFor(byteArrayOf(1, 2, 3), closeable = RecordingCloseable())

        assertEquals(UsbTlsCiphertextReadResult.Truncated(expectedBytes = 8, actualBytes = 3), UsbTlsCiphertextIoAdapter().read(session))
        assertEquals(true, session.closeable.closed)
    }

    @Test
    fun closesSessionWhenReadThrows() {
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(FailingInputStream(), ByteArrayOutputStream(), closeable)

        val failure = runCatching { UsbTlsCiphertextIoAdapter().read(session) }

        assertEquals(true, failure.isFailure)
        assertEquals(true, closeable.closed)
    }

    private fun encodedFrame(streamId: Int, payload: ByteArray): ByteArray =
        AccessoryFrameCodec.encode(AccessoryFrame(streamId, payload), maxPayloadBytes = USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES)

    private fun sessionFor(
        inputBytes: ByteArray,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
        closeable: RecordingCloseable = RecordingCloseable(),
    ): TestSession = TestSession(closeable, AccessoryIoSession(ByteArrayInputStream(inputBytes), output, closeable))

    private fun littleEndianHeader(streamId: Int, payloadLength: Int): ByteArray =
        streamId.toLittleEndianBytesForTest() + payloadLength.toLittleEndianBytesForTest()

    private fun Int.toLittleEndianBytesForTest(): ByteArray = byteArrayOf(
        (this and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 24) and 0xff).toByte(),
    )

    private data class TestSession(
        val closeable: RecordingCloseable,
        val io: AccessoryIoSession,
    )

    private fun UsbTlsCiphertextIoAdapter.write(session: TestSession, ciphertext: ByteArray): UsbTlsCiphertextWriteResult = write(session.io, ciphertext)
    private fun UsbTlsCiphertextIoAdapter.read(session: TestSession): UsbTlsCiphertextReadResult = read(session.io)

    private class RecordingCloseable : java.io.Closeable {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw java.io.IOException("write failed")
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int {
            throw java.io.IOException("read failed")
        }
    }
}
