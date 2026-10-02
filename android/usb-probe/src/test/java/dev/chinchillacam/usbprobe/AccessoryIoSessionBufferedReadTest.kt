package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryIoSessionBufferedReadTest {
    @Test
    fun readsFrameDeliveredInSingleTransfer() {
        val frame = AccessoryFrame(streamId = 0x01020304, payload = patternBytes(size = 300, seed = 1))
        val input = TransferExactInputStream(listOf(encode(frame)))

        val decoded = sessionOf(input).readFrame()

        assertEquals(frame, decoded)
    }

    @Test
    fun readsMultipleFramesFromOneTransfer() {
        val first = AccessoryFrame(streamId = 7, payload = patternBytes(size = 40, seed = 2))
        val second = AccessoryFrame(streamId = 8, payload = patternBytes(size = 1, seed = 3))
        val third = AccessoryFrame(streamId = 9, payload = patternBytes(size = 900, seed = 4))
        val input = TransferExactInputStream(listOf(encode(first) + encode(second) + encode(third)))
        val session = sessionOf(input)

        assertEquals(first, session.readFrame())
        assertEquals(second, session.readFrame())
        assertEquals(third, session.readFrame())
        assertEquals(AccessoryReadResult.Eof, session.readExactly(ACCESSORY_HEADER_BYTES))
    }

    @Test
    fun readsFrameSplitAcrossTransfers() {
        val frame = AccessoryFrame(streamId = 11, payload = patternBytes(size = 2_000, seed = 5))
        val encoded = encode(frame)
        val input = TransferExactInputStream(
            listOf(
                encoded.copyOfRange(0, 5),
                encoded.copyOfRange(5, 700),
                encoded.copyOfRange(700, encoded.size),
            ),
        )

        val decoded = sessionOf(input).readFrame()

        assertEquals(frame, decoded)
    }

    @Test
    fun neverReadsWithBufferSmallerThanTransfer() {
        val frames = listOf(
            AccessoryFrame(streamId = 21, payload = patternBytes(size = 16_376, seed = 6)),
            AccessoryFrame(streamId = 22, payload = patternBytes(size = 3, seed = 7)),
        )
        val input = TransferExactInputStream(frames.map(::encode))
        val session = sessionOf(input)

        frames.forEach { expected -> assertEquals(expected, session.readFrame()) }

        assertTrue("expected at least one transfer read", input.requestedLengths.isNotEmpty())
        input.requestedLengths.forEach { length ->
            assertEquals(DEFAULT_ACCESSORY_READ_TRANSFER_BYTES, length)
        }
    }

    @Test
    fun eofAndShortReadSemanticsUnchanged() {
        assertEquals(
            AccessoryReadResult.Eof,
            sessionOf(TransferExactInputStream(emptyList())).readExactly(ACCESSORY_HEADER_BYTES),
        )

        val partialHeader = sessionOf(TransferExactInputStream(listOf(byteArrayOf(1, 2, 3))))
        assertEquals(
            AccessoryReadResult.ShortRead(expectedBytes = ACCESSORY_HEADER_BYTES, actualBytes = 3),
            partialHeader.readExactly(ACCESSORY_HEADER_BYTES),
        )
        assertEquals(AccessoryReadResult.Eof, partialHeader.readExactly(1))

        val leftover = sessionOf(TransferExactInputStream(listOf(patternBytes(size = 10, seed = 8))))
        assertArrayEquals(
            patternBytes(size = 10, seed = 8).copyOfRange(0, 8),
            leftover.readExactly(8).getOrThrow(),
        )
        assertEquals(
            AccessoryReadResult.ShortRead(expectedBytes = 4, actualBytes = 2),
            leftover.readExactly(4),
        )
        assertEquals(AccessoryReadResult.Eof, leftover.readExactly(1))
    }

    @Test
    fun rejectsTooSmallReadTransferBytes() {
        val tooSmall = runCatching {
            AccessoryIoSession(
                input = TransferExactInputStream(emptyList()),
                output = ByteArrayOutputStream(),
                closeable = Closeable {},
                readTransferBytes = 511,
            )
        }

        assertTrue(tooSmall.exceptionOrNull() is IllegalArgumentException)
        AccessoryIoSession(
            input = TransferExactInputStream(emptyList()),
            output = ByteArrayOutputStream(),
            closeable = Closeable {},
            readTransferBytes = 512,
        )
    }

    private fun sessionOf(input: InputStream): AccessoryIoSession =
        AccessoryIoSession(input = input, output = ByteArrayOutputStream(), closeable = Closeable {})

    private fun AccessoryIoSession.readFrame(): AccessoryFrame {
        val header = readExactly(ACCESSORY_HEADER_BYTES).getOrThrow()
        val payloadLength = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val payload = readExactly(payloadLength).getOrThrow()
        return AccessoryFrameCodec.decode(header + payload, maxPayloadBytes = MAX_TEST_PAYLOAD_BYTES).getOrThrow()
    }

    private fun encode(frame: AccessoryFrame): ByteArray =
        AccessoryFrameCodec.encode(frame, maxPayloadBytes = MAX_TEST_PAYLOAD_BYTES)

    private fun patternBytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xff).toByte() }

    private companion object {
        const val ACCESSORY_HEADER_BYTES = 8
        const val MAX_TEST_PAYLOAD_BYTES = 64 * 1024
    }
}

/**
 * Models the accessory bulk endpoint: each queued transfer must be consumed by one read whose
 * buffer can hold the whole transfer. A smaller buffer fails like the accessory driver does.
 */
private class TransferExactInputStream(transfers: List<ByteArray>) : InputStream() {
    private val pending = ArrayDeque(transfers)
    val requestedLengths = mutableListOf<Int>()

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        requestedLengths += length
        val next = pending.removeFirstOrNull() ?: return -1
        if (length < next.size) {
            throw IOException("overflow: read buffer $length bytes, transfer ${next.size} bytes")
        }
        next.copyInto(buffer, destinationOffset = offset)
        return next.size
    }
}
