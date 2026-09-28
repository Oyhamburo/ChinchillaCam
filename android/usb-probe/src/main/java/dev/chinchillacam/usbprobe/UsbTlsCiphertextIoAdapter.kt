package dev.chinchillacam.usbprobe

const val USB_TLS_RECORD_STREAM_ID: Int = 0x01020305
const val USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES: Int = 32 * 1024
const val USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES: Int = 64 * 1024

sealed class UsbTlsCiphertextWriteResult {
    object Sent : UsbTlsCiphertextWriteResult() {
        override fun toString(): String = "Sent"
    }

    object Empty : UsbTlsCiphertextWriteResult() {
        override fun toString(): String = "Empty"
    }

    data class Oversize(
        val payloadBytes: Int,
        val maxBytes: Int,
    ) : UsbTlsCiphertextWriteResult()
}

sealed class UsbTlsCiphertextReadResult {
    data class Received(val ciphertext: ByteArray) : UsbTlsCiphertextReadResult() {
        override fun equals(other: Any?): Boolean = other is Received && ciphertext.contentEquals(other.ciphertext)
        override fun hashCode(): Int = ciphertext.contentHashCode()
    }

    object EofEmpty : UsbTlsCiphertextReadResult() {
        override fun toString(): String = "EofEmpty"
    }

    data class Truncated(
        val expectedBytes: Int,
        val actualBytes: Int,
    ) : UsbTlsCiphertextReadResult()

    data class WrongStreamId(
        val actualStreamId: Int,
        val expectedStreamId: Int,
    ) : UsbTlsCiphertextReadResult()

    object Empty : UsbTlsCiphertextReadResult() {
        override fun toString(): String = "Empty"
    }

    data class Oversize(
        val declaredBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbTlsCiphertextReadResult()
}

class UsbTlsCiphertextIoAdapter(
    private val maxCiphertextChunkBytes: Int = USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES,
    private val streamId: Int = USB_TLS_RECORD_STREAM_ID,
) {
    init {
        require(maxCiphertextChunkBytes in 1..USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES) {
            "maxCiphertextChunkBytes must be between 1 and $USB_TLS_CIPHERTEXT_CHUNK_MAX_BYTES"
        }
    }

    fun write(session: AccessoryIoSession, ciphertext: ByteArray): UsbTlsCiphertextWriteResult {
        if (ciphertext.isEmpty()) {
            session.close()
            return UsbTlsCiphertextWriteResult.Empty
        }
        if (ciphertext.size > USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES) {
            session.close()
            return UsbTlsCiphertextWriteResult.Oversize(ciphertext.size, USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        }
        try {
            var offset = 0
            while (offset < ciphertext.size) {
                val end = minOf(offset + maxCiphertextChunkBytes, ciphertext.size)
                session.write(
                    AccessoryFrameCodec.encode(
                        AccessoryFrame(streamId = streamId, payload = ciphertext.copyOfRange(offset, end)),
                        maxPayloadBytes = maxCiphertextChunkBytes,
                    ),
                )
                offset = end
            }
        } catch (error: Exception) {
            session.close()
            throw error
        }
        return UsbTlsCiphertextWriteResult.Sent
    }

    fun read(session: AccessoryIoSession): UsbTlsCiphertextReadResult = try {
        readFailClosed(session)
    } catch (error: Exception) {
        session.close()
        throw error
    }

    private fun readFailClosed(session: AccessoryIoSession): UsbTlsCiphertextReadResult {
        val header = when (val headerResult = session.readExactly(ACCESSORY_FRAME_HEADER_BYTES)) {
            is AccessoryReadResult.Complete -> headerResult.bytes
            AccessoryReadResult.Eof -> return UsbTlsCiphertextReadResult.EofEmpty
            is AccessoryReadResult.ShortRead -> {
                session.close()
                return UsbTlsCiphertextReadResult.Truncated(
                    expectedBytes = headerResult.expectedBytes,
                    actualBytes = headerResult.actualBytes,
                )
            }
        }

        val actualStreamId = header.readLittleEndianInt(offset = 0)
        val declaredPayloadBytes = header.readLittleEndianInt(offset = 4)
        if (actualStreamId != streamId) {
            session.close()
            return UsbTlsCiphertextReadResult.WrongStreamId(actualStreamId, streamId)
        }
        if (declaredPayloadBytes == 0) {
            session.close()
            return UsbTlsCiphertextReadResult.Empty
        }
        if (declaredPayloadBytes < 0 || declaredPayloadBytes > maxCiphertextChunkBytes) {
            session.close()
            return UsbTlsCiphertextReadResult.Oversize(declaredPayloadBytes, maxCiphertextChunkBytes)
        }

        val payload = when (val payloadResult = session.readExactly(declaredPayloadBytes)) {
            is AccessoryReadResult.Complete -> payloadResult.bytes
            AccessoryReadResult.Eof -> {
                session.close()
                return UsbTlsCiphertextReadResult.Truncated(expectedBytes = declaredPayloadBytes, actualBytes = 0)
            }
            is AccessoryReadResult.ShortRead -> {
                session.close()
                return UsbTlsCiphertextReadResult.Truncated(
                    expectedBytes = payloadResult.expectedBytes,
                    actualBytes = payloadResult.actualBytes,
                )
            }
        }
        return UsbTlsCiphertextReadResult.Received(payload)
    }

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        const val ACCESSORY_FRAME_HEADER_BYTES = 8
    }
}
