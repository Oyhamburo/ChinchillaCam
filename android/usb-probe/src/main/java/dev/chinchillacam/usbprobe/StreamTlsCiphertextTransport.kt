package dev.chinchillacam.usbprobe

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TlsCiphertextTransport] that carries TLS records directly over a byte stream, with NO
 * AccessoryFrame (contract `wifi-loopback-transport` §4.1): TLS already delimits its own records, so
 * each ciphertext chunk is written straight to [output] and read straight from [input]. This is the
 * Wi-Fi-wire counterpart of [UsbAccessoryTlsCiphertextTransport]; the SSLEngine stack above the seam
 * is identical over either transport.
 *
 * Fails closed, like the USB transport: any [IOException] on read or write closes the whole transport
 * ([input], [output], and [closeable]) before reporting a [TlsCiphertextReadResult.Failed] /
 * [TlsCiphertextWriteResult.Failed]. A clean end-of-stream (`-1`) also closes the transport and
 * reports [TlsCiphertextReadResult.Eof]. [close] is idempotent.
 *
 * Uses only in-memory byte streams -- no network package, no socket, no listener (contract §4.2).
 */
class StreamTlsCiphertextTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
) : TlsCiphertextTransport {
    override val maxWriteBytes: Int = STREAM_TLS_CIPHERTEXT_MAX_BYTES

    // Atomic so a concurrent reader and writer failing closed at once still close the underlying
    // streams exactly once.
    private val closed = AtomicBoolean(false)

    override fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult = try {
        output.write(ciphertext)
        output.flush()
        TlsCiphertextWriteResult.Sent
    } catch (error: IOException) {
        close()
        TlsCiphertextWriteResult.Failed(error.message ?: "stream ciphertext write failed")
    }

    /**
     * Blocks until at least one byte is available, then returns the bytes currently available, up to
     * [maxWriteBytes]. `-1` (clean end-of-stream) closes the transport and reports
     * [TlsCiphertextReadResult.Eof]; any [IOException] closes the transport and reports
     * [TlsCiphertextReadResult.Failed].
     */
    override fun readCiphertext(): TlsCiphertextReadResult = try {
        val buffer = ByteArray(maxWriteBytes)
        val read = input.read(buffer, 0, buffer.size)
        if (read == -1) {
            close()
            TlsCiphertextReadResult.Eof("stream reached end of input")
        } else {
            TlsCiphertextReadResult.Received(buffer.copyOfRange(0, read))
        }
    } catch (error: IOException) {
        close()
        TlsCiphertextReadResult.Failed(error.message ?: "stream ciphertext read failed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { closeable.close() }
    }

    private companion object {
        /** Maximum ciphertext payload per read/write; matches the USB transport's 64 KiB cap. */
        const val STREAM_TLS_CIPHERTEXT_MAX_BYTES: Int = 64 * 1024
    }
}
