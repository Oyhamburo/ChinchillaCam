package dev.chinchillacam.usbprobe

class EncodedVideoSustainedFakeTransportAdapter(
    override val sessionId: String,
    private val transport: UsbSessionFrameSustainedFakeTransport,
    initialSequence: Int = 0,
) : FragmentingSessionFrameTransport {
    private var nextSequence: Int = initialSequence
    private var closed: Boolean = false

    init {
        require(sessionId.isNotEmpty()) { "session id must not be empty" }
        require(sessionId.toByteArray(Charsets.UTF_8).size <= UShort.MAX_VALUE.toInt()) { "session id is too long" }
        require(initialSequence >= 0) { "initial sequence must be non-negative" }
    }

    @Synchronized
    override fun write(payload: SessionPayload.VideoChunkV2): EncodedVideoSessionFrameWriteResult = writePayload(payload)

    @Synchronized
    override fun writeFragment(payload: SessionPayload.VideoChunkFragmentV1): EncodedVideoSessionFrameWriteResult = writePayload(payload)

    override fun maxType8H264Bytes(): Int = MAX_SESSION_PAYLOAD_BYTES - SESSION_FRAME_BASE_BYTES - sessionId.toByteArray(Charsets.UTF_8).size - VIDEO_CHUNK_V2_FIXED_PAYLOAD_BYTES

    private fun writePayload(payload: SessionPayload): EncodedVideoSessionFrameWriteResult {
        if (closed) return EncodedVideoSessionFrameWriteResult.Closed
        val frame = SessionFrame(sequence = nextSequence, sessionId = sessionId, payload = payload)
        val encodedSize = try {
            SessionFrameCodec.encode(frame).size
        } catch (error: RuntimeException) {
            closeDelegate()
            return EncodedVideoSessionFrameWriteResult.Failed("encode exception: ${error.message ?: error::class.java.simpleName}")
        }
        if (encodedSize > MAX_SESSION_PAYLOAD_BYTES) {
            closeDelegate()
            return EncodedVideoSessionFrameWriteResult.Oversized
        }
        val writeResult = try {
            transport.write(frame)
        } catch (error: RuntimeException) {
            closeDelegate()
            return EncodedVideoSessionFrameWriteResult.Failed("transport exception: ${error.message ?: error::class.java.simpleName}")
        }
        return when (writeResult) {
            UsbSessionFrameSustainedWriteResult.Sent -> {
                if (nextSequence == Int.MAX_VALUE) {
                    closeDelegate()
                } else {
                    nextSequence += 1
                }
                EncodedVideoSessionFrameWriteResult.Written
            }
            is UsbSessionFrameSustainedWriteResult.Backpressure -> {
                closeDelegate()
                EncodedVideoSessionFrameWriteResult.BackpressureExceeded
            }
            UsbSessionFrameSustainedWriteResult.Closed -> {
                closed = true
                EncodedVideoSessionFrameWriteResult.Closed
            }
            is UsbSessionFrameSustainedWriteResult.Oversize -> {
                closeDelegate()
                EncodedVideoSessionFrameWriteResult.Oversized
            }
        }
    }

    @Synchronized
    override fun close() {
        closeDelegate()
    }

    private fun closeDelegate() {
        if (!closed) {
            closed = true
            transport.close()
        }
    }

    private companion object {
        const val MAX_SESSION_PAYLOAD_BYTES = 65_528
        const val SESSION_FRAME_BASE_BYTES = 16
        const val VIDEO_CHUNK_V2_FIXED_PAYLOAD_BYTES = 15
    }
}
