package dev.chinchillacam.usbprobe

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext

sealed class SslEngineUsbTlsHandshakeResult {
    data class Authenticated(
        val channel: SslEngineUsbTlsEstablishedChannel,
    ) : SslEngineUsbTlsHandshakeResult() {
        val protocol: String = channel.protocol
        val cipherSuite: String = channel.cipherSuite
        fun close() = channel.close()
    }

    data class Rejected(val reason: String) : SslEngineUsbTlsHandshakeResult()
}

class SslEngineUsbTlsEstablishedChannel internal constructor(
    internal val engine: SSLEngine,
    internal val session: AccessoryIoSession,
    internal val pendingCiphertext: ByteBuffer,
    internal val adapter: UsbTlsCiphertextIoAdapter,
    private val readExecutor: ExecutorService,
) : AutoCloseable {
    val protocol: String = engine.session.protocol
    val cipherSuite: String = engine.session.cipherSuite

    override fun close() {
        try {
            runCatching { engine.closeOutbound() }
            session.close()
        } finally {
            readExecutor.shutdownNow()
        }
    }
}

class SslEngineUsbTlsChannel(
    private val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxHandshakeSteps: Int = DEFAULT_MAX_HANDSHAKE_STEPS,
    private val adapter: UsbTlsCiphertextIoAdapter = UsbTlsCiphertextIoAdapter(),
) {
    init {
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(maxHandshakeSteps > 0) { "maxHandshakeSteps must be positive" }
    }

    fun handshake(
        session: AccessoryIoSession,
        pinnedDesktopSubjectPublicKeyInfoDer: ByteArray,
    ): SslEngineUsbTlsHandshakeResult {
        val readExecutor = Executors.newSingleThreadExecutor(DaemonThreadFactory)
        val engine = runCatching { newEngine(pinnedDesktopSubjectPublicKeyInfoDer) }
            .getOrElse { return reject(session, null, readExecutor, it.reason()) }
        val pending = ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        val empty = ByteBuffer.allocate(0)
        var app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis)

        return try {
            engine.beginHandshake()
            repeat(maxHandshakeSteps) {
                deadlineExceeded(deadlineNanos)?.let { return reject(session, engine, readExecutor, it) }
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) {
                            deadlineExceeded(deadlineNanos)?.let { return reject(session, engine, readExecutor, it) }
                            task.run()
                            task = engine.delegatedTask
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(engine, session, empty, deadlineNanos)?.let {
                        return reject(session, engine, readExecutor, it)
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> when (val unwrapped = unwrap(engine, session, readExecutor, pending, app, deadlineNanos)) {
                        is UnwrapResult.Ok -> app = unwrapped.app
                        is UnwrapResult.Reject -> return reject(session, engine, readExecutor, unwrapped.reason)
                    }
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return SslEngineUsbTlsHandshakeResult.Authenticated(
                        SslEngineUsbTlsEstablishedChannel(engine, session, pending, adapter, readExecutor),
                    )
                    null -> return reject(session, engine, readExecutor, "TLS engine returned null handshake status")
                }
            }
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                SslEngineUsbTlsHandshakeResult.Authenticated(
                    SslEngineUsbTlsEstablishedChannel(engine, session, pending, adapter, readExecutor),
                )
            } else {
                reject(session, engine, readExecutor, "TLS handshake exceeded $maxHandshakeSteps steps")
            }
        } catch (error: Exception) {
            reject(session, engine, readExecutor, error.reason())
        }
    }

    private fun newEngine(pinnedSpki: ByteArray): SSLEngine {
        val identity = DesktopTlsIdentityMaterial.validate(pinnedSpki).getOrThrow()
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(PinnedDesktopTlsTrustManager(identity)), null)
        }.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = supportedProtocols.filter { it == TLS_1_3 || it == TLS_1_2 }.toTypedArray()
        }
    }

    private fun wrap(engine: SSLEngine, session: AccessoryIoSession, empty: ByteBuffer, deadlineNanos: Long): String? {
        var out = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (true) {
            deadlineExceeded(deadlineNanos)?.let { return it }
            when (engine.wrap(empty, out).status) {
                SSLEngineResult.Status.OK -> {
                    out.flip()
                    if (out.hasRemaining()) {
                        val bytes = ByteArray(out.remaining()).also { out.get(it) }
                        val write = adapter.write(session, bytes)
                        if (write != UsbTlsCiphertextWriteResult.Sent) return "USB TLS ciphertext write failed: $write"
                    }
                    return null
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    if (out.capacity() >= USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES) return "TLS wrap overflowed ${out.capacity()} bytes"
                    out = out.growForWrite() ?: return "TLS wrap overflowed ${out.capacity()} bytes"
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> return "TLS wrap requested underflow"
                SSLEngineResult.Status.CLOSED -> return "TLS engine closed during wrap"
                null -> return "TLS wrap returned null status"
            }
        }
    }

    private fun unwrap(
        engine: SSLEngine,
        session: AccessoryIoSession,
        readExecutor: ExecutorService,
        pending: ByteBuffer,
        initialApp: ByteBuffer,
        deadlineNanos: Long,
    ): UnwrapResult {
        var app = initialApp
        if (pending.position() == 0) read(session, readExecutor, pending, deadlineNanos)?.let { return UnwrapResult.Reject(it) }
        while (true) {
            deadlineExceeded(deadlineNanos)?.let { return UnwrapResult.Reject(it) }
            pending.flip()
            val result = engine.unwrap(pending, app)
            pending.compact()
            when (result.status) {
                SSLEngineResult.Status.OK -> return UnwrapResult.Ok(app)
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> read(session, readExecutor, pending, deadlineNanos)?.let { return UnwrapResult.Reject(it) }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> app = app.growForWrite(MAX_HANDSHAKE_APPLICATION_BUFFER_BYTES)
                    ?: return UnwrapResult.Reject("TLS unwrap application buffer overflow")
                SSLEngineResult.Status.CLOSED -> return UnwrapResult.Reject("TLS engine closed during unwrap")
                null -> return UnwrapResult.Reject("TLS unwrap returned null status")
            }
        }
    }

    private fun read(
        session: AccessoryIoSession,
        readExecutor: ExecutorService,
        pending: ByteBuffer,
        deadlineNanos: Long,
    ): String? {
        deadlineExceeded(deadlineNanos)?.let { return it }
        val remainingMillis = (deadlineNanos - System.nanoTime()).let { if (it <= 0) 0 else TimeUnit.NANOSECONDS.toMillis(it).coerceAtLeast(1) }
        if (remainingMillis <= 0) return timeoutReason()
        val read = try {
            readExecutor.submit<UsbTlsCiphertextReadResult> { adapter.read(session) }.get(remainingMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return timeoutReason()
        }
        val bytes = when (read) {
            is UsbTlsCiphertextReadResult.Received -> read.ciphertext
            else -> return "USB TLS ciphertext read failed: $read"
        }
        if (bytes.size > pending.remaining()) return "USB TLS ciphertext overflow: pending=${pending.position()} incoming=${bytes.size} max=$USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES"
        pending.put(bytes)
        return null
    }

    private fun deadlineExceeded(deadlineNanos: Long): String? =
        if (System.nanoTime() >= deadlineNanos) timeoutReason() else null

    private fun timeoutReason(): String = "TLS handshake timed out after ${readTimeoutMillis}ms"

    private fun reject(
        session: AccessoryIoSession,
        engine: SSLEngine?,
        readExecutor: ExecutorService,
        reason: String,
    ): SslEngineUsbTlsHandshakeResult.Rejected {
        runCatching { engine?.closeOutbound() }
        session.close()
        readExecutor.shutdownNow()
        return SslEngineUsbTlsHandshakeResult.Rejected(reason)
    }

    private fun ByteBuffer.growForWrite(limit: Int = USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES): ByteBuffer? {
        if (capacity() >= limit) return null
        return ByteBuffer.allocate(minOf(capacity() * 2, limit)).also {
            flip()
            it.put(this)
        }
    }

    private fun Throwable.reason(): String = message ?: javaClass.simpleName

    private sealed class UnwrapResult {
        data class Ok(val app: ByteBuffer) : UnwrapResult()
        data class Reject(val reason: String) : UnwrapResult()
    }

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "usb-tls-ciphertext-read").apply { isDaemon = true }
    }

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 5_000
        const val DEFAULT_MAX_HANDSHAKE_STEPS: Int = 256
        const val MAX_HANDSHAKE_APPLICATION_BUFFER_BYTES: Int = 64 * 1024
        const val TLS_1_2: String = "TLSv1.2"
        const val TLS_1_3: String = "TLSv1.3"
    }
}
