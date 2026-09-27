package dev.chinchillacam.usbprobe

sealed class UsbSessionFrameIoWriteResult {
    object Sent : UsbSessionFrameIoWriteResult() {
        override fun toString(): String = "Sent"
    }

    data class Oversize(
        val payloadBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbSessionFrameIoWriteResult()
}

sealed class UsbSessionFrameIoReadResult {
    data class Received(val frame: SessionFrame) : UsbSessionFrameIoReadResult()
    object EofEmpty : UsbSessionFrameIoReadResult() {
        override fun toString(): String = "EofEmpty"
    }

    data class ShortHeader(
        val expectedBytes: Int,
        val actualBytes: Int,
    ) : UsbSessionFrameIoReadResult()

    data class ShortPayload(
        val expectedBytes: Int,
        val actualBytes: Int,
    ) : UsbSessionFrameIoReadResult()

    data class Oversize(
        val declaredBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbSessionFrameIoReadResult()

    data class WrongStreamId(
        val actualStreamId: Int,
        val expectedStreamId: Int,
    ) : UsbSessionFrameIoReadResult()

    data class InvalidSessionFrame(
        val error: SessionFrameDecodeError,
    ) : UsbSessionFrameIoReadResult()
}

class UsbSessionFrameIoAdapter(
    private val maxPayloadBytes: Int,
    private val streamId: Int = USB_SESSION_FRAME_STREAM_ID,
) {
    init {
        require(maxPayloadBytes >= 0) { "maxPayloadBytes must be non-negative" }
    }

    fun write(session: AccessoryIoSession, frame: SessionFrame): UsbSessionFrameIoWriteResult {
        val sessionBytes = SessionFrameCodec.encode(frame)
        if (sessionBytes.size > maxPayloadBytes) {
            return UsbSessionFrameIoWriteResult.Oversize(sessionBytes.size, maxPayloadBytes)
        }

        session.write(
            AccessoryFrameCodec.encode(
                AccessoryFrame(streamId = streamId, payload = sessionBytes),
                maxPayloadBytes = maxPayloadBytes,
            ),
        )
        return UsbSessionFrameIoWriteResult.Sent
    }

    fun read(session: AccessoryIoSession): UsbSessionFrameIoReadResult {
        val header = when (val headerResult = session.readExactly(ACCESSORY_FRAME_HEADER_BYTES)) {
            is AccessoryReadResult.Complete -> headerResult.bytes
            AccessoryReadResult.Eof -> return UsbSessionFrameIoReadResult.EofEmpty
            is AccessoryReadResult.ShortRead -> return UsbSessionFrameIoReadResult.ShortHeader(
                expectedBytes = headerResult.expectedBytes,
                actualBytes = headerResult.actualBytes,
            )
        }

        val declaredPayloadBytes = header.readLittleEndianInt(offset = 4)
        if (declaredPayloadBytes < 0 || declaredPayloadBytes > maxPayloadBytes) {
            return UsbSessionFrameIoReadResult.Oversize(
                declaredBytes = declaredPayloadBytes,
                maxPayloadBytes = maxPayloadBytes,
            )
        }

        val payload = if (declaredPayloadBytes == 0) {
            ByteArray(0)
        } else {
            when (val payloadResult = session.readExactly(declaredPayloadBytes)) {
                is AccessoryReadResult.Complete -> payloadResult.bytes
                AccessoryReadResult.Eof -> return UsbSessionFrameIoReadResult.ShortPayload(
                    expectedBytes = declaredPayloadBytes,
                    actualBytes = 0,
                )
                is AccessoryReadResult.ShortRead -> return UsbSessionFrameIoReadResult.ShortPayload(
                    expectedBytes = payloadResult.expectedBytes,
                    actualBytes = payloadResult.actualBytes,
                )
            }
        }

        return when (val decodedAccessory = AccessoryFrameCodec.decode(header + payload, maxPayloadBytes)) {
            is FrameDecodeResult.Complete -> decodeSessionFrame(decodedAccessory.frame)
            is FrameDecodeResult.OversizePayload -> UsbSessionFrameIoReadResult.Oversize(
                declaredBytes = decodedAccessory.declaredBytes,
                maxPayloadBytes = decodedAccessory.maxPayloadBytes,
            )
            is FrameDecodeResult.ShortHeader -> UsbSessionFrameIoReadResult.ShortHeader(
                expectedBytes = ACCESSORY_FRAME_HEADER_BYTES,
                actualBytes = decodedAccessory.actualBytes,
            )
            is FrameDecodeResult.ShortPayload -> UsbSessionFrameIoReadResult.ShortPayload(
                expectedBytes = decodedAccessory.expectedBytes,
                actualBytes = decodedAccessory.actualBytes,
            )
        }
    }

    private fun decodeSessionFrame(frame: AccessoryFrame): UsbSessionFrameIoReadResult {
        if (frame.streamId != streamId) {
            return UsbSessionFrameIoReadResult.WrongStreamId(
                actualStreamId = frame.streamId,
                expectedStreamId = streamId,
            )
        }

        return SessionFrameCodec.decode(frame.payload).fold(
            onSuccess = { UsbSessionFrameIoReadResult.Received(it) },
            onFailure = { error ->
                UsbSessionFrameIoReadResult.InvalidSessionFrame(
                    error as? SessionFrameDecodeError
                        ?: SessionFrameDecodeError.InvalidPayload(error.message ?: "invalid session frame"),
                )
            },
        )
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
