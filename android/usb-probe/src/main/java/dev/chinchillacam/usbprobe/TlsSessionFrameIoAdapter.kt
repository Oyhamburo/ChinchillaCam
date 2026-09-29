package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Minimum declared length (bytes) for a framed [SessionFrame] over TLS (contract §4.3): zero is always rejected. */
const val TLS_SESSION_FRAME_MIN_BYTES: Int = 1

/** Maximum declared length (bytes) for a framed [SessionFrame] over TLS (contract §4.3); matches [SessionFrameCodec]'s own default max frame size. */
const val TLS_SESSION_FRAME_MAX_BYTES: Int = 1_048_576

private const val TLS_SESSION_FRAME_LENGTH_PREFIX_BYTES: Int = 4
private const val DEFAULT_TLS_SESSION_FRAME_READ_TIMEOUT_MILLIS: Long = 5_000

/**
 * Thrown by [TlsSessionFrameIoAdapter] for any framing, length, or decode failure. The adapter
 * always closes the [SslEngineUsbTlsEstablishedChannel] before or while throwing -- see each
 * method's doc -- and the channel must never be reused afterward.
 */
class TlsSessionFrameIoException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Frames CCSF v1 [SessionFrame]s over an already-authenticated [SslEngineUsbTlsEstablishedChannel]
 * (contract `usb-authenticated-session` §4.3, task s2): each application-data unit is a 4-byte
 * big-endian length prefix (`[TLS_SESSION_FRAME_MIN_BYTES]..[maxFrameBytes]`) followed by exactly
 * one [SessionFrameCodec]-encoded frame.
 *
 * [read] validates the declared length against [maxFrameBytes] *before* allocating any buffer for
 * the payload, so a corrupted or hostile declared length can never force an oversized allocation.
 * Every failure -- a zero or oversized declared length, a truncated or closed channel, or an
 * invalid decoded frame -- closes the channel and throws [TlsSessionFrameIoException]. This
 * mirrors the bounded-read pattern already used by [UsbTlsPairingProofVerifier]'s private CCP1
 * frame reader, generalized here to every [SessionFrameType] instead of just the pairing-proof
 * protocol.
 */
class TlsSessionFrameIoAdapter(
    private val maxFrameBytes: Int = TLS_SESSION_FRAME_MAX_BYTES,
    private val readTimeoutMillis: Long = DEFAULT_TLS_SESSION_FRAME_READ_TIMEOUT_MILLIS,
) {
    init {
        require(maxFrameBytes in TLS_SESSION_FRAME_MIN_BYTES..TLS_SESSION_FRAME_MAX_BYTES) {
            "maxFrameBytes must be between $TLS_SESSION_FRAME_MIN_BYTES and $TLS_SESSION_FRAME_MAX_BYTES"
        }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
    }

    /**
     * Encodes [frame] and writes it as one length-prefixed application-data unit. Delegates the
     * actual TLS write to [SslEngineUsbTlsEstablishedChannel.writeApplicationData], which already
     * closes the channel and rethrows on any I/O failure; this method additionally validates the
     * encoded size against [maxFrameBytes] first and wraps every failure as
     * [TlsSessionFrameIoException].
     */
    fun write(channel: SslEngineUsbTlsEstablishedChannel, frame: SessionFrame) {
        try {
            val encoded = SessionFrameCodec.encode(frame)
            require(encoded.size in TLS_SESSION_FRAME_MIN_BYTES..maxFrameBytes) {
                "encoded session frame size ${encoded.size} outside $TLS_SESSION_FRAME_MIN_BYTES..$maxFrameBytes"
            }
            channel.writeApplicationData(encoded.size.toBigEndianBytes() + encoded)
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw TlsSessionFrameIoException(error.message ?: "failed to write session frame over TLS", error)
        }
    }

    /**
     * Reads exactly one length-prefixed [SessionFrame]: 4 bytes of declared length, validated
     * against `[TLS_SESSION_FRAME_MIN_BYTES]..[maxFrameBytes]` *before* reading/allocating the
     * payload, then exactly that many payload bytes (looping over
     * [SslEngineUsbTlsEstablishedChannel.readApplicationData], bounded by an absolute deadline
     * derived from [readTimeoutMillis]), then decodes them. Any failure at any step -- oversized or
     * zero length, a truncated/closed channel, a read timeout, or an invalid decoded frame -- closes
     * the channel and throws [TlsSessionFrameIoException]; the channel must not be reused after.
     */
    fun read(channel: SslEngineUsbTlsEstablishedChannel): SessionFrame {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis)
        try {
            val length = readExactly(channel, TLS_SESSION_FRAME_LENGTH_PREFIX_BYTES, deadlineNanos).toBigEndianInt()
            if (length < TLS_SESSION_FRAME_MIN_BYTES || length > maxFrameBytes) {
                throw IllegalArgumentException(
                    "declared session frame length $length outside $TLS_SESSION_FRAME_MIN_BYTES..$maxFrameBytes",
                )
            }
            val payload = readExactly(channel, length, deadlineNanos)
            return SessionFrameCodec.decode(payload, maxFrameBytes).getOrThrow()
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw TlsSessionFrameIoException(error.message ?: "failed to read session frame over TLS", error)
        }
    }

    private fun readExactly(channel: SslEngineUsbTlsEstablishedChannel, bytes: Int, deadlineNanos: Long): ByteArray {
        val out = ByteArrayOutputStream(bytes)
        while (out.size() < bytes) {
            out.write(channel.readApplicationData(bytes - out.size(), deadlineNanos))
        }
        return out.toByteArray()
    }
}

private fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
    ((this ushr 24) and 0xff).toByte(),
    ((this ushr 16) and 0xff).toByte(),
    ((this ushr 8) and 0xff).toByte(),
    (this and 0xff).toByte(),
)

private fun ByteArray.toBigEndianInt(): Int =
    ((this[0].toInt() and 0xff) shl 24) or
        ((this[1].toInt() and 0xff) shl 16) or
        ((this[2].toInt() and 0xff) shl 8) or
        (this[3].toInt() and 0xff)
