package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val SESSION_FRAME_MAGIC = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
private const val SUPPORTED_SESSION_FRAME_VERSION = 1
private const val DEFAULT_MAX_SESSION_FRAME_SIZE = 1024 * 1024
private const val HEADER_WITHOUT_SESSION_BYTES = 16

data class SessionFrame(
    val version: Int = SUPPORTED_SESSION_FRAME_VERSION,
    val sequence: Int,
    val sessionId: String,
    val payload: SessionPayload,
) {
    val type: SessionFrameType = payload.type
}

enum class SessionFrameType(val id: Int) {
    HANDSHAKE_HELLO(1),
    HANDSHAKE_ACCEPT(2),
    HANDSHAKE_REJECT(3),
    STREAM_METADATA(4),
    VIDEO_CHUNK(5),
    METRICS_SNAPSHOT(6),
    CAMERA_CONTROL_COMMAND(7),
    VIDEO_CHUNK_V2(8),
    VIDEO_CHUNK_FRAGMENT_V1(9);

    companion object {
        fun fromId(id: Int): SessionFrameType? = values().firstOrNull { it.id == id }
    }
}

enum class SessionVideoFrameKind(val id: Int) {
    DELTA(0),
    KEY(1),
    CODEC_CONFIG(2);

    companion object {
        fun fromId(id: Int): SessionVideoFrameKind? = values().firstOrNull { it.id == id }

        fun fromCodecFlags(isKeyFrame: Boolean, isCodecConfig: Boolean): SessionVideoFrameKind = when {
            isCodecConfig -> CODEC_CONFIG
            isKeyFrame -> KEY
            else -> DELTA
        }
    }
}

sealed class SessionPayload(val type: SessionFrameType) {
    data class HandshakeHello(
        val deviceId: String,
        val appName: String,
        val capabilities: List<String>,
    ) : SessionPayload(SessionFrameType.HANDSHAKE_HELLO)

    data class HandshakeAccept(
        val desktopId: String,
        val message: String,
    ) : SessionPayload(SessionFrameType.HANDSHAKE_ACCEPT)

    data class HandshakeReject(
        val reasonCode: String,
        val message: String,
    ) : SessionPayload(SessionFrameType.HANDSHAKE_REJECT)

    data class StreamMetadata(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val profile: String,
    ) : SessionPayload(SessionFrameType.STREAM_METADATA)

    data class VideoChunk(
        val chunkIndex: Int,
        val presentationTimeUs: Long,
        val h264Bytes: ByteArray,
    ) : SessionPayload(SessionFrameType.VIDEO_CHUNK) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoChunk) return false
            return chunkIndex == other.chunkIndex &&
                presentationTimeUs == other.presentationTimeUs &&
                h264Bytes.contentEquals(other.h264Bytes)
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + presentationTimeUs.hashCode()
            result = 31 * result + h264Bytes.contentHashCode()
            return result
        }
    }

    data class VideoChunkV2(
        val chunkIndex: Int,
        val presentationTimeUs: Long,
        val frameKind: SessionVideoFrameKind,
        val h264Bytes: ByteArray,
    ) : SessionPayload(SessionFrameType.VIDEO_CHUNK_V2) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoChunkV2) return false
            return chunkIndex == other.chunkIndex &&
                presentationTimeUs == other.presentationTimeUs &&
                frameKind == other.frameKind &&
                h264Bytes.contentEquals(other.h264Bytes)
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + presentationTimeUs.hashCode()
            result = 31 * result + frameKind.hashCode()
            result = 31 * result + h264Bytes.contentHashCode()
            return result
        }
    }

    data class VideoChunkFragmentV1(
        val chunkIndex: Int,
        val presentationTimeUs: Long,
        val frameKind: SessionVideoFrameKind,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val totalH264Bytes: Int,
        val fragmentBytes: ByteArray,
    ) : SessionPayload(SessionFrameType.VIDEO_CHUNK_FRAGMENT_V1) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoChunkFragmentV1) return false
            return chunkIndex == other.chunkIndex &&
                presentationTimeUs == other.presentationTimeUs &&
                frameKind == other.frameKind &&
                fragmentIndex == other.fragmentIndex &&
                fragmentCount == other.fragmentCount &&
                totalH264Bytes == other.totalH264Bytes &&
                fragmentBytes.contentEquals(other.fragmentBytes)
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + presentationTimeUs.hashCode()
            result = 31 * result + frameKind.hashCode()
            result = 31 * result + fragmentIndex
            result = 31 * result + fragmentCount
            result = 31 * result + totalH264Bytes
            result = 31 * result + fragmentBytes.contentHashCode()
            return result
        }
    }

    data class MetricsSnapshot(
        val capturedAtUs: Long,
        val droppedFrames: Int,
        val latencyMs: Int,
        val frameRate: Int,
    ) : SessionPayload(SessionFrameType.METRICS_SNAPSHOT)

    data class CameraControlCommand(
        val command: String,
        val arguments: Map<String, String>,
    ) : SessionPayload(SessionFrameType.CAMERA_CONTROL_COMMAND)
}

sealed class SessionFrameDecodeError(message: String) : Exception(message) {
    data class UnsupportedVersion(val actualVersion: Int) : SessionFrameDecodeError("unsupported session frame version: $actualVersion")
    data class UnknownType(val actualType: Int) : SessionFrameDecodeError("unknown session frame type: $actualType")
    data class FrameTooLarge(val actualSize: Int, val maxSize: Int) : SessionFrameDecodeError("session frame size $actualSize exceeds max $maxSize")
    data class InvalidPayload(val reason: String) : SessionFrameDecodeError("invalid session frame payload: $reason")
    data class InvalidSequence(val sequence: Int) : SessionFrameDecodeError("invalid session frame sequence: $sequence")
    object InvalidSessionId : SessionFrameDecodeError("invalid session id")
    data class TruncatedFrame(val field: String) : SessionFrameDecodeError("truncated session frame: $field")
}

object SessionFrameCodec {
    fun encode(frame: SessionFrame): ByteArray {
        require(frame.version == SUPPORTED_SESSION_FRAME_VERSION) { "unsupported session frame version: ${frame.version}" }
        require(frame.sequence >= 0) { "sequence must be non-negative" }
        require(frame.sessionId.isNotEmpty()) { "session id must not be empty" }

        val sessionIdBytes = frame.sessionId.encodeUtf8Field("sessionId")
        require(sessionIdBytes.size <= UShort.MAX_VALUE.toInt()) { "session id is too long" }
        val framePrefixSize = HEADER_WITHOUT_SESSION_BYTES + sessionIdBytes.size
        val payloadSize = PayloadSizer(maxPayloadBytes = DEFAULT_MAX_SESSION_FRAME_SIZE - framePrefixSize).measurePayload(frame.payload)
        require(framePrefixSize + payloadSize <= DEFAULT_MAX_SESSION_FRAME_SIZE) { "session frame exceeds max frame size" }
        val payloadBytes = PayloadWriter().apply { writePayload(frame.payload) }.toByteArray()

        val encoded = ByteArrayOutputStream().apply {
            write(SESSION_FRAME_MAGIC)
            write(frame.version)
            write(frame.type.id)
            writeInt(frame.sequence)
            writeShort(sessionIdBytes.size)
            write(sessionIdBytes)
            writeInt(payloadBytes.size)
            write(payloadBytes)
        }.toByteArray()
        require(encoded.size <= DEFAULT_MAX_SESSION_FRAME_SIZE) { "session frame exceeds max frame size" }
        return encoded
    }

    fun decode(bytes: ByteArray, maxFrameSize: Int = DEFAULT_MAX_SESSION_FRAME_SIZE): Result<SessionFrame> {
        if (bytes.size > maxFrameSize) {
            return Result.failure(SessionFrameDecodeError.FrameTooLarge(bytes.size, maxFrameSize))
        }
        if (bytes.size < HEADER_WITHOUT_SESSION_BYTES) {
            return Result.failure(SessionFrameDecodeError.TruncatedFrame("header"))
        }

        val reader = FrameReader(bytes)
        return try {
            if (!SESSION_FRAME_MAGIC.contentEquals(reader.readBytes(SESSION_FRAME_MAGIC.size, "magic"))) {
                return Result.failure(SessionFrameDecodeError.InvalidPayload("magic"))
            }
            val version = reader.readUnsignedByte("version")
            if (version != SUPPORTED_SESSION_FRAME_VERSION) {
                return Result.failure(SessionFrameDecodeError.UnsupportedVersion(version))
            }
            val typeId = reader.readUnsignedByte("type")
            val type = SessionFrameType.fromId(typeId)
                ?: return Result.failure(SessionFrameDecodeError.UnknownType(typeId))
            val sequence = reader.readInt("sequence")
            if (sequence < 0) {
                return Result.failure(SessionFrameDecodeError.InvalidSequence(sequence))
            }
            val sessionIdLength = reader.readUnsignedShort("sessionIdLength")
            if (sessionIdLength == 0) {
                return Result.failure(SessionFrameDecodeError.InvalidSessionId)
            }
            val sessionId = reader.readUtf8(sessionIdLength, "sessionId")
            val payloadLength = reader.readInt("payloadLength")
            if (payloadLength < 0) {
                return Result.failure(SessionFrameDecodeError.InvalidPayload("payload length must be non-negative"))
            }
            if (payloadLength > maxFrameSize - reader.position) {
                return Result.failure(SessionFrameDecodeError.FrameTooLarge(payloadLength + reader.position, maxFrameSize))
            }
            if (reader.remaining < payloadLength) {
                return Result.failure(SessionFrameDecodeError.TruncatedFrame("payload"))
            }
            val payload = PayloadReader(reader.readBytes(payloadLength, "payload")).readPayload(type)
            if (reader.remaining != 0) {
                return Result.failure(SessionFrameDecodeError.InvalidPayload("trailing bytes"))
            }
            Result.success(SessionFrame(version, sequence, sessionId, payload))
        } catch (error: SessionFrameDecodeError) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(SessionFrameDecodeError.InvalidPayload(error.message ?: "invalid value"))
        }
    }
}

private const val MAX_VIDEO_FRAGMENT_COUNT = 1024
private const val MAX_FRAGMENT_TOTAL_H264_BYTES = 4 * 1024 * 1024

private fun validateVideoChunkFragmentV1(payload: SessionPayload.VideoChunkFragmentV1) {
    require(payload.chunkIndex >= 0) { "chunk index must be non-negative" }
    require(payload.presentationTimeUs >= 0) { "presentation timestamp must be non-negative" }
    require(payload.fragmentIndex >= 0) { "fragment index must be non-negative" }
    require(payload.fragmentCount in 1..MAX_VIDEO_FRAGMENT_COUNT) { "fragment count must be in 1..1024" }
    require(payload.fragmentIndex < payload.fragmentCount) { "fragment index must be less than fragment count" }
    require(payload.totalH264Bytes in 1..MAX_FRAGMENT_TOTAL_H264_BYTES) { "total h264 bytes must be in 1..4194304" }
    require(payload.fragmentBytes.isNotEmpty()) { "fragment bytes must not be empty" }
    require(payload.fragmentBytes.size <= payload.totalH264Bytes) { "fragment bytes must not exceed total h264 bytes" }
}

private fun validateVideoChunkFragmentV1Decoded(
    chunkIndex: Int,
    presentationTimeUs: Long,
    fragmentIndex: Int,
    fragmentCount: Int,
    totalH264Bytes: Int,
    fragmentBytes: ByteArray,
) {
    if (chunkIndex < 0) throw SessionFrameDecodeError.InvalidPayload("chunk index must be non-negative")
    if (presentationTimeUs < 0) throw SessionFrameDecodeError.InvalidPayload("presentation timestamp must be non-negative")
    if (fragmentIndex < 0) throw SessionFrameDecodeError.InvalidPayload("fragment index must be non-negative")
    if (fragmentCount !in 1..MAX_VIDEO_FRAGMENT_COUNT) throw SessionFrameDecodeError.InvalidPayload("fragment count must be in 1..1024")
    if (fragmentIndex >= fragmentCount) throw SessionFrameDecodeError.InvalidPayload("fragment index must be less than fragment count")
    if (totalH264Bytes !in 1..MAX_FRAGMENT_TOTAL_H264_BYTES) throw SessionFrameDecodeError.InvalidPayload("total h264 bytes must be in 1..4194304")
    if (fragmentBytes.isEmpty()) throw SessionFrameDecodeError.InvalidPayload("fragment bytes must not be empty")
    if (fragmentBytes.size > totalH264Bytes) throw SessionFrameDecodeError.InvalidPayload("fragment bytes must not exceed total h264 bytes")
}

private class PayloadSizer(private val maxPayloadBytes: Int) {
    private var size = 0

    fun measurePayload(payload: SessionPayload): Int {
        when (payload) {
            is SessionPayload.HandshakeHello -> {
                addString(payload.deviceId)
                addString(payload.appName)
                require(payload.capabilities.size <= UShort.MAX_VALUE.toInt()) { "capability count is too large" }
                addBytes(2)
                payload.capabilities.forEach(::addString)
            }
            is SessionPayload.HandshakeAccept -> {
                addString(payload.desktopId)
                addString(payload.message)
            }
            is SessionPayload.HandshakeReject -> {
                addString(payload.reasonCode)
                addString(payload.message)
            }
            is SessionPayload.StreamMetadata -> {
                require(payload.width > 0) { "stream width must be positive" }
                require(payload.height > 0) { "stream height must be positive" }
                require(payload.frameRate > 0) { "stream frame rate must be positive" }
                addString(payload.mimeType)
                addBytes(4)
                addBytes(4)
                addBytes(4)
                addString(payload.profile)
            }
            is SessionPayload.VideoChunk -> {
                require(payload.chunkIndex >= 0) { "chunk index must be non-negative" }
                addBytes(4)
                addBytes(8)
                addBytesWithLength(payload.h264Bytes.size)
            }
            is SessionPayload.VideoChunkV2 -> {
                require(payload.chunkIndex >= 0) { "chunk index must be non-negative" }
                require(payload.presentationTimeUs >= 0) { "presentation timestamp must be non-negative" }
                require(payload.h264Bytes.isNotEmpty()) { "h264 bytes must not be empty" }
                addBytes(4)
                addBytes(8)
                addBytes(1)
                addBytesWithLength(payload.h264Bytes.size)
            }
            is SessionPayload.VideoChunkFragmentV1 -> {
                validateVideoChunkFragmentV1(payload)
                addBytes(4)
                addBytes(8)
                addBytes(1)
                addBytes(4)
                addBytes(4)
                addBytes(4)
                addBytesWithLength(payload.fragmentBytes.size)
            }
            is SessionPayload.MetricsSnapshot -> {
                require(payload.capturedAtUs >= 0) { "metrics timestamp must be non-negative" }
                require(payload.droppedFrames >= 0) { "dropped frames must be non-negative" }
                require(payload.latencyMs >= 0) { "latency must be non-negative" }
                require(payload.frameRate >= 0) { "frame rate must be non-negative" }
                addBytes(8)
                addBytes(4)
                addBytes(4)
                addBytes(4)
            }
            is SessionPayload.CameraControlCommand -> {
                addString(payload.command)
                val sortedArguments = payload.arguments.toSortedMap()
                require(sortedArguments.size <= UShort.MAX_VALUE.toInt()) { "argument count is too large" }
                addBytes(2)
                sortedArguments.forEach { (key, value) ->
                    addString(key)
                    addString(value)
                }
            }
        }
        return size
    }

    private fun addString(value: String) = addBytesWithLength(value.encodeUtf8Field("string").size)

    private fun addBytesWithLength(byteCount: Int) {
        require(byteCount <= UShort.MAX_VALUE.toInt()) { "field is too large" }
        addBytes(2)
        addBytes(byteCount)
    }

    private fun addBytes(byteCount: Int) {
        size += byteCount
        require(size <= maxPayloadBytes) { "session frame exceeds max frame size" }
    }
}

private class PayloadWriter {
    private val output = ByteArrayOutputStream()

    fun writePayload(payload: SessionPayload) {
        when (payload) {
            is SessionPayload.HandshakeHello -> {
                writeString(payload.deviceId)
                writeString(payload.appName)
                require(payload.capabilities.size <= UShort.MAX_VALUE.toInt()) { "capability count is too large" }
                writeShort(payload.capabilities.size)
                payload.capabilities.forEach(::writeString)
            }
            is SessionPayload.HandshakeAccept -> {
                writeString(payload.desktopId)
                writeString(payload.message)
            }
            is SessionPayload.HandshakeReject -> {
                writeString(payload.reasonCode)
                writeString(payload.message)
            }
            is SessionPayload.StreamMetadata -> {
                require(payload.width > 0) { "stream width must be positive" }
                require(payload.height > 0) { "stream height must be positive" }
                require(payload.frameRate > 0) { "stream frame rate must be positive" }
                writeString(payload.mimeType)
                writeInt(payload.width)
                writeInt(payload.height)
                writeInt(payload.frameRate)
                writeString(payload.profile)
            }
            is SessionPayload.VideoChunk -> {
                require(payload.chunkIndex >= 0) { "chunk index must be non-negative" }
                writeInt(payload.chunkIndex)
                writeLong(payload.presentationTimeUs)
                writeBytesWithLength(payload.h264Bytes)
            }
            is SessionPayload.VideoChunkV2 -> {
                require(payload.chunkIndex >= 0) { "chunk index must be non-negative" }
                require(payload.presentationTimeUs >= 0) { "presentation timestamp must be non-negative" }
                require(payload.h264Bytes.isNotEmpty()) { "h264 bytes must not be empty" }
                writeInt(payload.chunkIndex)
                writeLong(payload.presentationTimeUs)
                writeByte(payload.frameKind.id)
                writeBytesWithLength(payload.h264Bytes)
            }
            is SessionPayload.VideoChunkFragmentV1 -> {
                validateVideoChunkFragmentV1(payload)
                writeInt(payload.chunkIndex)
                writeLong(payload.presentationTimeUs)
                writeByte(payload.frameKind.id)
                writeInt(payload.fragmentIndex)
                writeInt(payload.fragmentCount)
                writeInt(payload.totalH264Bytes)
                writeBytesWithLength(payload.fragmentBytes)
            }
            is SessionPayload.MetricsSnapshot -> {
                require(payload.capturedAtUs >= 0) { "metrics timestamp must be non-negative" }
                require(payload.droppedFrames >= 0) { "dropped frames must be non-negative" }
                require(payload.latencyMs >= 0) { "latency must be non-negative" }
                require(payload.frameRate >= 0) { "frame rate must be non-negative" }
                writeLong(payload.capturedAtUs)
                writeInt(payload.droppedFrames)
                writeInt(payload.latencyMs)
                writeInt(payload.frameRate)
            }
            is SessionPayload.CameraControlCommand -> {
                writeString(payload.command)
                val sortedArguments = payload.arguments.toSortedMap()
                require(sortedArguments.size <= UShort.MAX_VALUE.toInt()) { "argument count is too large" }
                writeShort(sortedArguments.size)
                sortedArguments.forEach { (key, value) ->
                    writeString(key)
                    writeString(value)
                }
            }
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeString(value: String) = writeBytesWithLength(value.encodeUtf8Field("string"))

    private fun writeBytesWithLength(bytes: ByteArray) {
        require(bytes.size <= UShort.MAX_VALUE.toInt()) { "field is too large" }
        writeShort(bytes.size)
        output.write(bytes)
    }

    private fun writeByte(value: Int) = output.write(value)
    private fun writeShort(value: Int) = output.writeShort(value)
    private fun writeInt(value: Int) = output.writeInt(value)
    private fun writeLong(value: Long) = output.writeLong(value)
}

private class PayloadReader(private val bytes: ByteArray) {
    private val reader = FrameReader(bytes)

    fun readPayload(type: SessionFrameType): SessionPayload {
        val payload = when (type) {
            SessionFrameType.HANDSHAKE_HELLO -> SessionPayload.HandshakeHello(
                deviceId = readString("deviceId"),
                appName = readString("appName"),
                capabilities = List(reader.readUnsignedShort("capabilityCount")) { readString("capability") },
            )
            SessionFrameType.HANDSHAKE_ACCEPT -> SessionPayload.HandshakeAccept(
                desktopId = readString("desktopId"),
                message = readString("message"),
            )
            SessionFrameType.HANDSHAKE_REJECT -> SessionPayload.HandshakeReject(
                reasonCode = readString("reasonCode"),
                message = readString("message"),
            )
            SessionFrameType.STREAM_METADATA -> {
                val mimeType = readString("mimeType")
                val width = reader.readInt("width")
                val height = reader.readInt("height")
                val frameRate = reader.readInt("frameRate")
                val profile = readString("profile")
                if (width <= 0) throw SessionFrameDecodeError.InvalidPayload("stream width must be positive")
                if (height <= 0) throw SessionFrameDecodeError.InvalidPayload("stream height must be positive")
                if (frameRate <= 0) throw SessionFrameDecodeError.InvalidPayload("stream frame rate must be positive")
                SessionPayload.StreamMetadata(mimeType, width, height, frameRate, profile)
            }
            SessionFrameType.VIDEO_CHUNK -> {
                val chunkIndex = reader.readInt("chunkIndex")
                if (chunkIndex < 0) throw SessionFrameDecodeError.InvalidPayload("chunk index must be non-negative")
                SessionPayload.VideoChunk(
                    chunkIndex = chunkIndex,
                    presentationTimeUs = reader.readLong("presentationTimeUs"),
                    h264Bytes = readBytesWithLength("h264Bytes"),
                )
            }
            SessionFrameType.VIDEO_CHUNK_V2 -> {
                val chunkIndex = reader.readInt("chunkIndex")
                val presentationTimeUs = reader.readLong("presentationTimeUs")
                val frameKindId = reader.readUnsignedByte("frameKind")
                val h264Bytes = readBytesWithLength("h264Bytes")
                val frameKind = SessionVideoFrameKind.fromId(frameKindId)
                    ?: throw SessionFrameDecodeError.InvalidPayload("video frame kind is unknown: $frameKindId")
                if (chunkIndex < 0) throw SessionFrameDecodeError.InvalidPayload("chunk index must be non-negative")
                if (presentationTimeUs < 0) throw SessionFrameDecodeError.InvalidPayload("presentation timestamp must be non-negative")
                if (h264Bytes.isEmpty()) throw SessionFrameDecodeError.InvalidPayload("h264 bytes must not be empty")
                SessionPayload.VideoChunkV2(chunkIndex, presentationTimeUs, frameKind, h264Bytes)
            }
            SessionFrameType.VIDEO_CHUNK_FRAGMENT_V1 -> {
                val chunkIndex = reader.readInt("chunkIndex")
                val presentationTimeUs = reader.readLong("presentationTimeUs")
                val frameKindId = reader.readUnsignedByte("frameKind")
                val fragmentIndex = reader.readInt("fragmentIndex")
                val fragmentCount = reader.readInt("fragmentCount")
                val totalH264Bytes = reader.readInt("totalH264Bytes")
                val fragmentBytes = readBytesWithLength("fragmentBytes")
                val frameKind = SessionVideoFrameKind.fromId(frameKindId)
                    ?: throw SessionFrameDecodeError.InvalidPayload("video frame kind is unknown: $frameKindId")
                validateVideoChunkFragmentV1Decoded(chunkIndex, presentationTimeUs, fragmentIndex, fragmentCount, totalH264Bytes, fragmentBytes)
                SessionPayload.VideoChunkFragmentV1(
                    chunkIndex,
                    presentationTimeUs,
                    frameKind,
                    fragmentIndex,
                    fragmentCount,
                    totalH264Bytes,
                    fragmentBytes,
                )
            }
            SessionFrameType.METRICS_SNAPSHOT -> {
                val capturedAtUs = reader.readLong("capturedAtUs")
                val droppedFrames = reader.readInt("droppedFrames")
                val latencyMs = reader.readInt("latencyMs")
                val frameRate = reader.readInt("frameRate")
                if (capturedAtUs < 0) throw SessionFrameDecodeError.InvalidPayload("metrics timestamp must be non-negative")
                if (droppedFrames < 0) throw SessionFrameDecodeError.InvalidPayload("dropped frames must be non-negative")
                if (latencyMs < 0) throw SessionFrameDecodeError.InvalidPayload("latency must be non-negative")
                if (frameRate < 0) throw SessionFrameDecodeError.InvalidPayload("frame rate must be non-negative")
                SessionPayload.MetricsSnapshot(capturedAtUs, droppedFrames, latencyMs, frameRate)
            }
            SessionFrameType.CAMERA_CONTROL_COMMAND -> {
                val command = readString("command")
                val argumentCount = reader.readUnsignedShort("argumentCount")
                val arguments = linkedMapOf<String, String>()
                repeat(argumentCount) {
                    val key = readString("argumentKey")
                    val value = readString("argumentValue")
                    if (arguments.put(key, value) != null) {
                        throw SessionFrameDecodeError.InvalidPayload("duplicate argument key: $key")
                    }
                }
                SessionPayload.CameraControlCommand(command, arguments)
            }
        }
        if (reader.remaining != 0) {
            throw SessionFrameDecodeError.InvalidPayload("trailing payload bytes")
        }
        return payload
    }

    private fun readString(field: String): String = reader.readUtf8(reader.readUnsignedShort("${field}Length"), field)
    private fun readBytesWithLength(field: String): ByteArray = reader.readBytes(reader.readUnsignedShort("${field}Length"), field)
}

private class FrameReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    val remaining: Int
        get() = bytes.size - position

    fun readUnsignedByte(field: String): Int {
        ensureAvailable(1, field)
        return bytes[position++].toInt() and 0xff
    }

    fun readUnsignedShort(field: String): Int {
        ensureAvailable(2, field)
        val value = ByteBuffer.wrap(bytes, position, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        position += 2
        return value
    }

    fun readInt(field: String): Int {
        ensureAvailable(4, field)
        val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.BIG_ENDIAN).int
        position += 4
        return value
    }

    fun readLong(field: String): Long {
        ensureAvailable(8, field)
        val value = ByteBuffer.wrap(bytes, position, 8).order(ByteOrder.BIG_ENDIAN).long
        position += 8
        return value
    }

    fun readBytes(length: Int, field: String): ByteArray {
        if (length < 0) throw SessionFrameDecodeError.InvalidPayload("$field length must be non-negative")
        ensureAvailable(length, field)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readUtf8(length: Int, field: String): String {
        val fieldBytes = readBytes(length, field)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(fieldBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw SessionFrameDecodeError.InvalidPayload("invalid utf-8 in $field")
        }
    }

    private fun ensureAvailable(length: Int, field: String) {
        if (remaining < length) {
            throw SessionFrameDecodeError.TruncatedFrame(field)
        }
    }
}

private fun ByteArrayOutputStream.writeShort(value: Int) {
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun ByteArrayOutputStream.writeInt(value: Int) {
    write((value ushr 24) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun ByteArrayOutputStream.writeLong(value: Long) {
    write(((value ushr 56) and 0xff).toInt())
    write(((value ushr 48) and 0xff).toInt())
    write(((value ushr 40) and 0xff).toInt())
    write(((value ushr 32) and 0xff).toInt())
    write(((value ushr 24) and 0xff).toInt())
    write(((value ushr 16) and 0xff).toInt())
    write(((value ushr 8) and 0xff).toInt())
    write((value and 0xff).toInt())
}

private fun String.encodeUtf8Field(field: String): ByteArray = toByteArray(Charsets.UTF_8).also {
    require(it.size <= UShort.MAX_VALUE.toInt()) { "$field is too long" }
}
