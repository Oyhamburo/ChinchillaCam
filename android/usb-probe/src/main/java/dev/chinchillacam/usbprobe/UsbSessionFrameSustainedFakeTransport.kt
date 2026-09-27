package dev.chinchillacam.usbprobe

sealed class UsbSessionFrameSustainedWriteResult {
    object Sent : UsbSessionFrameSustainedWriteResult() {
        override fun toString(): String = "Sent"
    }

    data class Backpressure(
        val capacityFrames: Int,
        val queuedFrames: Int,
    ) : UsbSessionFrameSustainedWriteResult()

    object Closed : UsbSessionFrameSustainedWriteResult() {
        override fun toString(): String = "Closed"
    }

    data class Oversize(
        val payloadBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbSessionFrameSustainedWriteResult()
}

sealed class UsbSessionFrameSustainedReadResult {
    data class Received(val frame: SessionFrame) : UsbSessionFrameSustainedReadResult()
    data class Timeout(val sessionClosed: Boolean) : UsbSessionFrameSustainedReadResult()
    object Closed : UsbSessionFrameSustainedReadResult() {
        override fun toString(): String = "Closed"
    }

    data class Oversize(
        val declaredBytes: Int,
        val maxPayloadBytes: Int,
        val sessionClosed: Boolean,
    ) : UsbSessionFrameSustainedReadResult()

    data class WrongStreamId(
        val actualStreamId: Int,
        val expectedStreamId: Int,
    ) : UsbSessionFrameSustainedReadResult()

    data class InvalidSessionFrame(
        val error: SessionFrameDecodeError,
    ) : UsbSessionFrameSustainedReadResult()
}

class UsbSessionFrameSustainedFakeTransport(
    private val maxPayloadBytes: Int,
    private val outgoingCapacityFrames: Int,
    private val streamId: Int = USB_SESSION_FRAME_STREAM_ID,
) : AutoCloseable {
    private val outgoingEncodedAccessoryFrames = ArrayDeque<ByteArray>()
    private val incomingBytes = ArrayDeque<Byte>()
    private var closed = false

    init {
        require(maxPayloadBytes >= 0) { "maxPayloadBytes must be non-negative" }
        require(outgoingCapacityFrames >= 0) { "outgoingCapacityFrames must be non-negative" }
    }

    fun write(frame: SessionFrame): UsbSessionFrameSustainedWriteResult {
        if (closed) return UsbSessionFrameSustainedWriteResult.Closed
        if (outgoingEncodedAccessoryFrames.size >= outgoingCapacityFrames) {
            return UsbSessionFrameSustainedWriteResult.Backpressure(
                capacityFrames = outgoingCapacityFrames,
                queuedFrames = outgoingEncodedAccessoryFrames.size,
            )
        }

        val sessionBytes = SessionFrameCodec.encode(frame)
        if (sessionBytes.size > maxPayloadBytes) {
            return UsbSessionFrameSustainedWriteResult.Oversize(
                payloadBytes = sessionBytes.size,
                maxPayloadBytes = maxPayloadBytes,
            )
        }

        outgoingEncodedAccessoryFrames.addLast(
            AccessoryFrameCodec.encode(
                AccessoryFrame(streamId = streamId, payload = sessionBytes),
                maxPayloadBytes = maxPayloadBytes,
            ),
        )
        return UsbSessionFrameSustainedWriteResult.Sent
    }

    fun read(): UsbSessionFrameSustainedReadResult {
        if (closed) return UsbSessionFrameSustainedReadResult.Closed
        if (incomingBytes.isEmpty()) return UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = false)
        if (incomingBytes.size < ACCESSORY_FRAME_HEADER_BYTES) return poisonWithTimeout()

        val header = incomingBytes.takeBytes(ACCESSORY_FRAME_HEADER_BYTES)
        val declaredPayloadBytes = header.readLittleEndianInt(offset = 4)
        if (declaredPayloadBytes < 0 || declaredPayloadBytes > maxPayloadBytes) {
            repeat(ACCESSORY_FRAME_HEADER_BYTES) { incomingBytes.removeFirst() }
            closed = true
            return UsbSessionFrameSustainedReadResult.Oversize(
                declaredBytes = declaredPayloadBytes,
                maxPayloadBytes = maxPayloadBytes,
                sessionClosed = true,
            )
        }
        if (incomingBytes.size < ACCESSORY_FRAME_HEADER_BYTES + declaredPayloadBytes) return poisonWithTimeout()

        repeat(ACCESSORY_FRAME_HEADER_BYTES) { incomingBytes.removeFirst() }
        val payload = incomingBytes.removeFirstBytes(declaredPayloadBytes)
        return when (val decodedAccessory = AccessoryFrameCodec.decode(header + payload, maxPayloadBytes)) {
            is FrameDecodeResult.Complete -> decodeSessionFrame(decodedAccessory.frame)
            is FrameDecodeResult.OversizePayload -> {
                closed = true
                UsbSessionFrameSustainedReadResult.Oversize(
                    declaredBytes = decodedAccessory.declaredBytes,
                    maxPayloadBytes = decodedAccessory.maxPayloadBytes,
                    sessionClosed = true,
                )
            }
            is FrameDecodeResult.ShortHeader,
            is FrameDecodeResult.ShortPayload -> poisonWithTimeout()
        }
    }

    fun enqueueIncomingBytes(bytes: ByteArray) {
        bytes.forEach { incomingBytes.addLast(it) }
    }

    fun removeOutgoingEncodedAccessoryFrame(): ByteArray? = outgoingEncodedAccessoryFrames.removeFirstOrNull()?.copyOf()

    fun pendingIncomingPayloadBytesForTest(): Int = incomingBytes.size

    override fun close() {
        closed = true
    }

    private fun poisonWithTimeout(): UsbSessionFrameSustainedReadResult.Timeout {
        closed = true
        return UsbSessionFrameSustainedReadResult.Timeout(sessionClosed = true)
    }

    private fun decodeSessionFrame(frame: AccessoryFrame): UsbSessionFrameSustainedReadResult {
        if (frame.streamId != streamId) {
            return UsbSessionFrameSustainedReadResult.WrongStreamId(
                actualStreamId = frame.streamId,
                expectedStreamId = streamId,
            )
        }

        return SessionFrameCodec.decode(frame.payload).fold(
            onSuccess = { UsbSessionFrameSustainedReadResult.Received(it) },
            onFailure = { error ->
                UsbSessionFrameSustainedReadResult.InvalidSessionFrame(
                    error as? SessionFrameDecodeError
                        ?: SessionFrameDecodeError.InvalidPayload(error.message ?: "invalid session frame"),
                )
            },
        )
    }

    private fun ArrayDeque<Byte>.takeBytes(count: Int): ByteArray = ByteArray(count) { index -> elementAt(index) }

    private fun ArrayDeque<Byte>.removeFirstBytes(count: Int): ByteArray = ByteArray(count) { removeFirst() }

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        const val ACCESSORY_FRAME_HEADER_BYTES = 8
    }
}
