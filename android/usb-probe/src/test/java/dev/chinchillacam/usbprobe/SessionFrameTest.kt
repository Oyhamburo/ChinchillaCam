package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFrameTest {
    @Test
    fun roundtripsEveryPayloadType() {
        val frames = listOf(
            SessionFrame(1, 0, "session-a", SessionPayload.HandshakeHello("android-phone", "ChinchillaCam", listOf("h264", "metrics"))),
            SessionFrame(1, 1, "session-a", SessionPayload.HandshakeAccept("desktop-1", "ready")),
            SessionFrame(1, 2, "session-a", SessionPayload.HandshakeReject("busy", "Ya hay una computadora activa")),
            SessionFrame(1, 3, "session-a", SessionPayload.StreamMetadata("video/h264", 1920, 1080, 30, "baseline")),
            SessionFrame(1, 4, "session-a", SessionPayload.VideoChunk(7, 123456789L, byteArrayOf(0x00, 0x00, 0x01, 0x65))),
            SessionFrame(1, 5, "session-a", SessionPayload.MetricsSnapshot(123456790L, 42, 18, 30)),
            SessionFrame(1, 6, "session-a", SessionPayload.CameraControlCommand("setZoom", mapOf("level" to "2.0", "mode" to "smooth"))),
        )

        for (frame in frames) {
            val decoded = SessionFrameCodec.decode(SessionFrameCodec.encode(frame)).getOrThrow()
            assertFrameEquals(frame, decoded)
        }
    }

    @Test
    fun encodingIsDeterministicAndBigEndian() {
        val frame = SessionFrame(1, 9, "abc", SessionPayload.HandshakeAccept("pc", "ok"))

        val first = SessionFrameCodec.encode(frame)
        val second = SessionFrameCodec.encode(frame)

        assertArrayEquals(first, second)
        assertEquals('C'.code.toByte(), first[0])
        assertEquals('C'.code.toByte(), first[1])
        assertEquals('S'.code.toByte(), first[2])
        assertEquals('F'.code.toByte(), first[3])
        assertEquals(1, first[4].toInt())
        assertEquals(SessionFrameType.HANDSHAKE_ACCEPT.id, first[5].toInt())
        assertEquals(0, first[6].toInt())
        assertEquals(0, first[7].toInt())
        assertEquals(0, first[8].toInt())
        assertEquals(9, first[9].toInt())
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.HandshakeAccept("pc", "ok"))).clone()
        bytes[4] = 2

        assertEquals(SessionFrameDecodeError.UnsupportedVersion(2), SessionFrameCodec.decode(bytes).exceptionOrNull())
    }

    @Test
    fun rejectsUnknownType() {
        val bytes = SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.HandshakeAccept("pc", "ok"))).clone()
        bytes[5] = 99

        assertEquals(SessionFrameDecodeError.UnknownType(99), SessionFrameCodec.decode(bytes).exceptionOrNull())
    }

    @Test
    fun rejectsFrameOverConfiguredLimitBeforePayloadAllocation() {
        val bytes = SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.VideoChunk(1, 1L, ByteArray(8))))

        assertEquals(SessionFrameDecodeError.FrameTooLarge(bytes.size, 12), SessionFrameCodec.decode(bytes, maxFrameSize = 12).exceptionOrNull())
    }

    @Test
    fun rejectsTruncatedHeaderAndPayload() {
        assertEquals(SessionFrameDecodeError.TruncatedFrame("header"), SessionFrameCodec.decode(byteArrayOf(0x43, 0x43)).exceptionOrNull())

        val bytes = SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.HandshakeAccept("pc", "ok")))
        assertEquals(SessionFrameDecodeError.TruncatedFrame("payload"), SessionFrameCodec.decode(bytes.copyOf(bytes.size - 1)).exceptionOrNull())
    }

    @Test
    fun rejectsInvalidPayload() {
        val frame = SessionFrame(1, 1, "s", SessionPayload.StreamMetadata("video/h264", 1920, 1080, 30, "baseline"))
        val bytes = SessionFrameCodec.encode(frame).clone()
        val widthOffset = 4 + 1 + 1 + 4 + 2 + 1 + 4 + 2 + "video/h264".length
        bytes[widthOffset] = 0
        bytes[widthOffset + 1] = 0
        bytes[widthOffset + 2] = 0
        bytes[widthOffset + 3] = 0

        assertEquals(SessionFrameDecodeError.InvalidPayload("stream width must be positive"), SessionFrameCodec.decode(bytes).exceptionOrNull())
    }

    @Test
    fun rejectsInvalidSequenceAndSessionId() {
        val negativeSequence = byteArrayOf(
            'C'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(),
            1, SessionFrameType.HANDSHAKE_ACCEPT.id.toByte(),
            0x80.toByte(), 0, 0, 0,
            0, 1, 's'.code.toByte(),
            0, 0, 0, 0
        )
        assertEquals(SessionFrameDecodeError.InvalidSequence(-2147483648), SessionFrameCodec.decode(negativeSequence).exceptionOrNull())

        val emptySessionId = byteArrayOf(
            'C'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(),
            1, SessionFrameType.HANDSHAKE_ACCEPT.id.toByte(),
            0, 0, 0, 1,
            0, 0,
            0, 0, 0, 0
        )
        assertEquals(SessionFrameDecodeError.InvalidSessionId, SessionFrameCodec.decode(emptySessionId).exceptionOrNull())
    }


    @Test
    fun rejectsMalformedUtf8WithoutReplacement() {
        val bytes = byteArrayOf(
            'C'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(),
            1, SessionFrameType.HANDSHAKE_ACCEPT.id.toByte(),
            0, 0, 0, 1,
            0, 1, 0xC3.toByte(),
            0, 0, 0, 0,
        )

        assertEquals(SessionFrameDecodeError.InvalidPayload("invalid utf-8 in sessionId"), SessionFrameCodec.decode(bytes).exceptionOrNull())
    }

    @Test
    fun rejectsDuplicateCameraControlArguments() {
        val payload = ByteArrayBuilder()
            .writeString("setZoom")
            .writeShort(2)
            .writeString("level")
            .writeString("2.0")
            .writeString("level")
            .writeString("3.0")
            .toByteArray()
        val frame = rawFrame(SessionFrameType.CAMERA_CONTROL_COMMAND, payload)

        assertEquals(SessionFrameDecodeError.InvalidPayload("duplicate argument key: level"), SessionFrameCodec.decode(frame).exceptionOrNull())
    }

    @Test
    fun rejectsCollectionCountsThatDoNotFitWireShort() {
        val tooManyCapabilities = List(UShort.MAX_VALUE.toInt() + 1) { "cap" }
        val capabilitiesError = runCatching {
            SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.HandshakeHello("phone", "ChinchillaCam", tooManyCapabilities)))
        }.exceptionOrNull()
        assertTrue(capabilitiesError is IllegalArgumentException)
        assertEquals("capability count is too large", capabilitiesError?.message)

        val tooManyArguments = (0..UShort.MAX_VALUE.toInt()).associate { index -> "k$index" to "v" }
        val argumentsError = runCatching {
            SessionFrameCodec.encode(SessionFrame(1, 1, "s", SessionPayload.CameraControlCommand("set", tooManyArguments)))
        }.exceptionOrNull()
        assertTrue(argumentsError is IllegalArgumentException)
        assertEquals("argument count is too large", argumentsError?.message)
    }

    private fun assertFrameEquals(expected: SessionFrame, actual: SessionFrame) {
        assertEquals(expected.version, actual.version)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.sessionId, actual.sessionId)
        when (val payload = expected.payload) {
            is SessionPayload.VideoChunk -> {
                val actualPayload = actual.payload as SessionPayload.VideoChunk
                assertEquals(payload.chunkIndex, actualPayload.chunkIndex)
                assertEquals(payload.presentationTimeUs, actualPayload.presentationTimeUs)
                assertArrayEquals(payload.h264Bytes, actualPayload.h264Bytes)
            }
            else -> assertEquals(payload, actual.payload)
        }
        assertTrue(actual.sequence >= 0)
    }

    private fun rawFrame(type: SessionFrameType, payload: ByteArray): ByteArray = ByteArrayBuilder()
        .writeBytes(byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        .writeByte(1)
        .writeByte(type.id)
        .writeInt(1)
        .writeString("s")
        .writeInt(payload.size)
        .writeBytes(payload)
        .toByteArray()

    private class ByteArrayBuilder {
        private val bytes = mutableListOf<Byte>()

        fun writeByte(value: Int) = apply { bytes.add(value.toByte()) }
        fun writeBytes(value: ByteArray) = apply { bytes.addAll(value.toList()) }
        fun writeShort(value: Int) = apply {
            bytes.add(((value ushr 8) and 0xff).toByte())
            bytes.add((value and 0xff).toByte())
        }
        fun writeInt(value: Int) = apply {
            bytes.add(((value ushr 24) and 0xff).toByte())
            bytes.add(((value ushr 16) and 0xff).toByte())
            bytes.add(((value ushr 8) and 0xff).toByte())
            bytes.add((value and 0xff).toByte())
        }
        fun writeString(value: String) = apply {
            val encoded = value.toByteArray(Charsets.UTF_8)
            writeShort(encoded.size)
            writeBytes(encoded)
        }
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

}
