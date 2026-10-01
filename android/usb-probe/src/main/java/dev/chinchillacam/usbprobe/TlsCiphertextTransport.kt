package dev.chinchillacam.usbprobe

/**
 * Minimal transport seam beneath the phone-side SSLEngine (contract `wifi-loopback-transport` §4.3).
 *
 * Carries opaque TLS ciphertext chunks over some byte transport without exposing how they are framed
 * or delivered: the USB implementation ([UsbAccessoryTlsCiphertextTransport]) wraps each chunk in an
 * AccessoryFrame, while a later raw-stream implementation will write TLS records directly. TLS
 * already delimits its own records, so callers treat each chunk as opaque bytes and never see
 * AccessoryFrame or USB types through this interface.
 */
interface TlsCiphertextTransport : AutoCloseable {
    /** Maximum ciphertext payload, in bytes, a single [writeCiphertext] call may carry. */
    val maxWriteBytes: Int

    /**
     * Writes one ciphertext chunk. Fails closed: any result other than [TlsCiphertextWriteResult.Sent]
     * means the transport rejected the chunk and closed itself.
     */
    fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult

    /** Reads the next available ciphertext chunk, end-of-stream, or a fail-closed error. */
    fun readCiphertext(): TlsCiphertextReadResult

    override fun close()
}

/** Outcome of [TlsCiphertextTransport.writeCiphertext]. */
sealed class TlsCiphertextWriteResult {
    /** The whole chunk was handed to the underlying transport. */
    object Sent : TlsCiphertextWriteResult() {
        override fun toString(): String = "Sent"
    }

    /** The transport rejected the chunk and closed itself; [detail] describes why. */
    data class Failed(val detail: String) : TlsCiphertextWriteResult()
}

/** Outcome of [TlsCiphertextTransport.readCiphertext]. */
sealed class TlsCiphertextReadResult {
    /** A ciphertext chunk was received. */
    data class Received(val ciphertext: ByteArray) : TlsCiphertextReadResult() {
        override fun equals(other: Any?): Boolean = other is Received && ciphertext.contentEquals(other.ciphertext)
        override fun hashCode(): Int = ciphertext.contentHashCode()
    }

    /** The peer closed the stream cleanly before sending another chunk; [detail] describes it. */
    data class Eof(val detail: String) : TlsCiphertextReadResult()

    /** The transport failed the read and closed itself; [detail] describes why. */
    data class Failed(val detail: String) : TlsCiphertextReadResult()
}
