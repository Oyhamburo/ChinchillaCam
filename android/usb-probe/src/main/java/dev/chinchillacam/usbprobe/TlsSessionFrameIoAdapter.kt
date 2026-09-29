package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Minimum declared length (bytes) for a framed [SessionFrame] over TLS (contract §4.3): zero is always rejected. */
const val TLS_SESSION_FRAME_MIN_BYTES: Int = 1

/** Maximum declared length (bytes) for a framed [SessionFrame] over TLS (contract §4.3); matches [SessionFrameCodec]'s own default max frame size. */
const val TLS_SESSION_FRAME_MAX_BYTES: Int = 1_048_576

private const val TLS_SESSION_FRAME_LENGTH_PREFIX_BYTES: Int = 4
private const val DEFAULT_TLS_SESSION_FRAME_READ_TIMEOUT_MILLIS: Long = 5_000

/** Default idle budget for [TlsSessionFrameIoAdapter.read] (contract `session-liveness` §4.2): three keepalive intervals. */
private const val DEFAULT_TLS_SESSION_FRAME_IDLE_BUDGET_MILLIS: Long = 6_000

/** Distinguishes why [TlsSessionFrameIoAdapter.read] failed (task l2, `session-liveness` §4.1). */
enum class TlsSessionFrameIoFailureReason {
    /** No byte of the next frame arrived within the configured idle budget; the peer is presumed dead. */
    PEER_IDLE,

    /** Any other read/write/framing/decode failure, including a frame that started but did not complete within the frame deadline. */
    GENERAL,
}

/**
 * Thrown by [TlsSessionFrameIoAdapter] for any framing, length, or decode failure. The adapter
 * always closes the [SslEngineUsbTlsEstablishedChannel] before or while throwing -- see each
 * method's doc -- and the channel must never be reused afterward.
 */
class TlsSessionFrameIoException(
    message: String,
    cause: Throwable? = null,
    val reason: TlsSessionFrameIoFailureReason = TlsSessionFrameIoFailureReason.GENERAL,
) : IllegalStateException(message, cause)

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
 *
 * [read] is two-phase (task l2, `session-liveness` §4.1): it first waits for the first byte(s) of
 * the next frame's length prefix, bounded by [idleBudgetMillis] -- an idle session between frames
 * must not time out just because it is quiet -- then, once any byte has arrived, the rest of that
 * frame (remaining prefix and payload) must complete within a fresh [readTimeoutMillis] deadline.
 * Idle-budget expiry closes the channel and throws with [TlsSessionFrameIoFailureReason.PEER_IDLE];
 * every other failure (including a frame-deadline expiry once started) uses
 * [TlsSessionFrameIoFailureReason.GENERAL].
 */
class TlsSessionFrameIoAdapter(
    private val maxFrameBytes: Int = TLS_SESSION_FRAME_MAX_BYTES,
    private val readTimeoutMillis: Long = DEFAULT_TLS_SESSION_FRAME_READ_TIMEOUT_MILLIS,
    private val idleBudgetMillis: Long = DEFAULT_TLS_SESSION_FRAME_IDLE_BUDGET_MILLIS,
) {
    init {
        require(maxFrameBytes in TLS_SESSION_FRAME_MIN_BYTES..TLS_SESSION_FRAME_MAX_BYTES) {
            "maxFrameBytes must be between $TLS_SESSION_FRAME_MIN_BYTES and $TLS_SESSION_FRAME_MAX_BYTES"
        }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(idleBudgetMillis > 0) { "idleBudgetMillis must be positive" }
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
     * Reads exactly one length-prefixed [SessionFrame], two-phase per the class doc above: first
     * waits for the first byte(s) of the 4-byte declared length, bounded by [idleBudgetMillis]; once
     * any byte has arrived, the rest of the declared length (validated against
     * `[TLS_SESSION_FRAME_MIN_BYTES]..[maxFrameBytes]` *before* reading/allocating the payload) and
     * the payload itself are read (looping over
     * [SslEngineUsbTlsEstablishedChannel.readApplicationData]) bounded by a fresh absolute deadline
     * derived from [readTimeoutMillis], then decoded. Any failure at any step closes the channel and
     * throws [TlsSessionFrameIoException]; the channel must not be reused after.
     */
    fun read(channel: SslEngineUsbTlsEstablishedChannel): SessionFrame {
        val idleDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleBudgetMillis)
        val firstPrefixBytes = try {
            channel.readApplicationData(TLS_SESSION_FRAME_LENGTH_PREFIX_BYTES, idleDeadlineNanos)
        } catch (error: Exception) {
            // channel already closed by readApplicationData itself.
            throw TlsSessionFrameIoException(
                error.message ?: "no session frame arrived within the idle budget",
                error,
                TlsSessionFrameIoFailureReason.PEER_IDLE,
            )
        }

        val frameDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis)
        try {
            val length = readExactly(channel, TLS_SESSION_FRAME_LENGTH_PREFIX_BYTES, frameDeadlineNanos, firstPrefixBytes).toBigEndianInt()
            if (length < TLS_SESSION_FRAME_MIN_BYTES || length > maxFrameBytes) {
                throw IllegalArgumentException(
                    "declared session frame length $length outside $TLS_SESSION_FRAME_MIN_BYTES..$maxFrameBytes",
                )
            }
            val payload = readExactly(channel, length, frameDeadlineNanos)
            return SessionFrameCodec.decode(payload, maxFrameBytes).getOrThrow()
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw TlsSessionFrameIoException(error.message ?: "failed to read session frame over TLS", error)
        }
    }

    private fun readExactly(
        channel: SslEngineUsbTlsEstablishedChannel,
        bytes: Int,
        deadlineNanos: Long,
        alreadyRead: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream(bytes)
        out.write(alreadyRead)
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
