package dev.chinchillacam.usbprobe

const val USB_SESSION_FRAME_STREAM_ID: Int = 0x01020304

interface UsbSessionFrameTransport {
    fun send(frame: SessionFrame): UsbSessionFrameSendResult
    fun receive(): UsbSessionFrameReceiveResult
}

sealed class UsbSessionFrameSendResult {
    object Sent : UsbSessionFrameSendResult() {
        override fun toString(): String = "Sent"
    }

    data class UsbPayloadOversize(
        val payloadBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbSessionFrameSendResult()
}

sealed class UsbSessionFrameReceiveResult {
    data class Received(val frame: SessionFrame) : UsbSessionFrameReceiveResult()
    object EmptyQueue : UsbSessionFrameReceiveResult() {
        override fun toString(): String = "EmptyQueue"
    }

    data class WrongStreamId(
        val actualStreamId: Int,
        val expectedStreamId: Int,
    ) : UsbSessionFrameReceiveResult()

    data class UsbPayloadOversize(
        val declaredBytes: Int,
        val maxPayloadBytes: Int,
    ) : UsbSessionFrameReceiveResult()

    data class InvalidSessionFrame(
        val error: SessionFrameDecodeError,
    ) : UsbSessionFrameReceiveResult()
}

class InMemoryUsbSessionFrameTransport(
    private val maxPayloadBytes: Int,
    private val streamId: Int = USB_SESSION_FRAME_STREAM_ID,
) : UsbSessionFrameTransport {
    private val queuedEncodedAccessoryFrames = ArrayDeque<ByteArray>()
    private val recordedSentEncodedAccessoryFrames = mutableListOf<ByteArray>()

    val sentEncodedAccessoryFrames: List<ByteArray>
        get() = recordedSentEncodedAccessoryFrames.map { it.copyOf() }

    fun enqueueEncodedAccessoryFrame(bytes: ByteArray) {
        queuedEncodedAccessoryFrames.addLast(bytes.copyOf())
    }

    override fun send(frame: SessionFrame): UsbSessionFrameSendResult {
        val sessionBytes = SessionFrameCodec.encode(frame)
        if (sessionBytes.size > maxPayloadBytes) {
            return UsbSessionFrameSendResult.UsbPayloadOversize(sessionBytes.size, maxPayloadBytes)
        }

        val encodedAccessoryFrame = AccessoryFrameCodec.encode(
            AccessoryFrame(streamId, sessionBytes),
            maxPayloadBytes,
        )
        recordedSentEncodedAccessoryFrames.add(encodedAccessoryFrame.copyOf())
        queuedEncodedAccessoryFrames.addLast(encodedAccessoryFrame)
        return UsbSessionFrameSendResult.Sent
    }

    override fun receive(): UsbSessionFrameReceiveResult {
        val encodedAccessoryFrame = queuedEncodedAccessoryFrames.removeFirstOrNull()
            ?: return UsbSessionFrameReceiveResult.EmptyQueue

        return when (val decodedAccessory = AccessoryFrameCodec.decode(encodedAccessoryFrame, maxPayloadBytes)) {
            is FrameDecodeResult.Complete -> decodeSessionFrame(decodedAccessory.frame)
            is FrameDecodeResult.OversizePayload -> UsbSessionFrameReceiveResult.UsbPayloadOversize(
                declaredBytes = decodedAccessory.declaredBytes,
                maxPayloadBytes = decodedAccessory.maxPayloadBytes,
            )
            is FrameDecodeResult.ShortHeader -> UsbSessionFrameReceiveResult.InvalidSessionFrame(
                SessionFrameDecodeError.TruncatedFrame("usb accessory header"),
            )
            is FrameDecodeResult.ShortPayload -> UsbSessionFrameReceiveResult.InvalidSessionFrame(
                SessionFrameDecodeError.TruncatedFrame("usb accessory payload"),
            )
        }
    }

    private fun decodeSessionFrame(frame: AccessoryFrame): UsbSessionFrameReceiveResult {
        if (frame.streamId != streamId) {
            return UsbSessionFrameReceiveResult.WrongStreamId(
                actualStreamId = frame.streamId,
                expectedStreamId = streamId,
            )
        }

        return SessionFrameCodec.decode(frame.payload).fold(
            onSuccess = { UsbSessionFrameReceiveResult.Received(it) },
            onFailure = { error ->
                UsbSessionFrameReceiveResult.InvalidSessionFrame(
                    error as? SessionFrameDecodeError
                        ?: SessionFrameDecodeError.InvalidPayload(error.message ?: "invalid session frame"),
                )
            },
        )
    }
}
