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
import javax.net.ssl.X509TrustManager

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
    private var pendingPlaintext = ByteArray(0)

    fun writeApplicationData(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val source = ByteBuffer.wrap(bytes)
        var out = ByteBuffer.allocate(engine.session.packetBufferSize)
        try {
            var emptyWrapSteps = 0
            while (source.hasRemaining()) {
                val result = engine.wrap(source, out)
                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        flushCiphertext(out)
                        if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                            emptyWrapSteps += 1
                            if (emptyWrapSteps >= MAX_EMPTY_WRAP_STEPS) throw IllegalStateException("TLS application write made no progress")
                        } else {
                            emptyWrapSteps = 0
                        }
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        flushCiphertext(out)
                        out = out.growForWrite() ?: throw IllegalStateException("TLS application write buffer overflow")
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IllegalStateException("TLS wrap requested underflow")
                    SSLEngineResult.Status.CLOSED -> throw IllegalStateException("TLS engine closed during application write")
                    null -> throw IllegalStateException("TLS wrap returned null status")
                }
            }
            flushCiphertext(out)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun readApplicationData(maxBytes: Int, deadlineNanos: Long): ByteArray {
        require(maxBytes in 1..MAX_APPLICATION_BUFFER_BYTES) {
            "maxBytes must be between 1 and $MAX_APPLICATION_BUFFER_BYTES"
        }
        var app = ByteBuffer.allocate(minOf(MAX_APPLICATION_BUFFER_BYTES, maxOf(maxBytes, engine.session.applicationBufferSize)))
        try {
            ensureReadDeadline(deadlineNanos)
            takePending(maxBytes)?.let { return it }
            while (true) {
                ensureReadDeadline(deadlineNanos)
                pendingCiphertext.flip()
                val result = engine.unwrap(pendingCiphertext, app)
                pendingCiphertext.compact()
                ensureReadDeadline(deadlineNanos)
                if (app.position() > 0) return app.take(maxBytes)
                when (result.status) {
                    SSLEngineResult.Status.OK -> when (result.handshakeStatus) {
                        SSLEngineResult.HandshakeStatus.NEED_TASK -> runDelegatedTasks()
                        SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapEmptyHandshakeData(deadlineNanos)
                        else -> Unit
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        readCiphertext(deadlineNanos)
                        ensureReadDeadline(deadlineNanos)
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> app = app.growForWrite(MAX_APPLICATION_BUFFER_BYTES)
                        ?: throw IllegalStateException("TLS application buffer overflow")
                    SSLEngineResult.Status.CLOSED -> throw IllegalStateException("TLS engine closed during application read")
                    null -> throw IllegalStateException("TLS unwrap returned null status")
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun wrapEmptyHandshakeData(deadlineNanos: Long) {
        val empty = ByteBuffer.allocate(0)
        var out = ByteBuffer.allocate(engine.session.packetBufferSize)
        var steps = 0
        while (true) {
            ensureReadDeadline(deadlineNanos)
            val result = engine.wrap(empty, out)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    flushCiphertext(out)
                    if (result.bytesProduced() > 0 || result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) return
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    flushCiphertext(out)
                    out = out.growForWrite() ?: throw IllegalStateException("TLS empty wrap buffer overflow")
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IllegalStateException("TLS empty wrap requested underflow")
                SSLEngineResult.Status.CLOSED -> throw IllegalStateException("TLS engine closed during empty wrap")
                null -> throw IllegalStateException("TLS empty wrap returned null status")
            }
            steps += 1
            if (steps >= MAX_EMPTY_WRAP_STEPS) throw IllegalStateException("TLS empty wrap made no progress")
        }
    }

    private fun ensureReadDeadline(deadlineNanos: Long) {
        if (System.nanoTime() >= deadlineNanos) throw IllegalStateException("TLS application read timed out")
    }

    private fun flushCiphertext(out: ByteBuffer) {
        out.flip()
        if (out.hasRemaining()) {
            val bytes = ByteArray(out.remaining()).also { out.get(it) }
            val write = adapter.write(session, bytes)
            if (write != UsbTlsCiphertextWriteResult.Sent) throw IllegalStateException("USB TLS ciphertext write failed: $write")
        }
        out.clear()
    }

    private fun readCiphertext(deadlineNanos: Long) {
        val remainingMillis = (deadlineNanos - System.nanoTime()).let { if (it <= 0) 0 else TimeUnit.NANOSECONDS.toMillis(it).coerceAtLeast(1) }
        if (remainingMillis <= 0) throw IllegalStateException("TLS application read timed out")
        val read = try {
            readExecutor.submit<UsbTlsCiphertextReadResult> { adapter.read(session) }.get(remainingMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("TLS application read timed out")
        }
        val bytes = when (read) {
            is UsbTlsCiphertextReadResult.Received -> read.ciphertext
            else -> throw IllegalStateException("USB TLS ciphertext read failed: $read")
        }
        if (bytes.size > pendingCiphertext.remaining()) throw IllegalStateException("USB TLS ciphertext overflow")
        pendingCiphertext.put(bytes)
    }

    private fun runDelegatedTasks() {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    private fun takePending(maxBytes: Int): ByteArray? {
        if (pendingPlaintext.isEmpty()) return null
        val count = minOf(maxBytes, pendingPlaintext.size)
        val result = pendingPlaintext.copyOfRange(0, count)
        pendingPlaintext = pendingPlaintext.copyOfRange(count, pendingPlaintext.size)
        return result
    }

    private fun ByteBuffer.take(maxBytes: Int): ByteArray {
        flip()
        val count = minOf(maxBytes, remaining())
        val result = ByteArray(count).also { get(it) }
        if (hasRemaining()) pendingPlaintext += ByteArray(remaining()).also { get(it) }
        clear()
        return result
    }

    private fun ByteBuffer.growForWrite(limit: Int = USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES): ByteBuffer? {
        if (capacity() >= limit) return null
        return ByteBuffer.allocate(minOf(capacity() * 2, limit)).also {
            flip()
            it.put(this)
        }
    }

    override fun close() {
        try {
            runCatching { engine.closeOutbound() }
            session.close()
        } finally {
            readExecutor.shutdownNow()
        }
    }

    private companion object {
        const val MAX_APPLICATION_BUFFER_BYTES: Int = 64 * 1024
        const val MAX_EMPTY_WRAP_STEPS: Int = 4
    }
}

class SslEngineUsbTlsChannel(
    private val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxHandshakeSteps: Int = DEFAULT_MAX_HANDSHAKE_STEPS,
    private val adapter: UsbTlsCiphertextIoAdapter = UsbTlsCiphertextIoAdapter(),
    private val phoneTlsIdentity: PhoneTlsIdentity? = null,
) {
    init {
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(maxHandshakeSteps > 0) { "maxHandshakeSteps must be positive" }
    }

    fun handshake(
        session: AccessoryIoSession,
        pinnedDesktopSubjectPublicKeyInfoDer: ByteArray,
    ): SslEngineUsbTlsHandshakeResult = handshakeCore(session) { newEngine(pinnedDesktopSubjectPublicKeyInfoDer) }

    /**
     * Reconnection counterpart of [handshake] (task s3, `usb-authenticated-session` §4.4):
     * [TrustedDesktopStore] only ever persists a [PairingTrustFingerprint] from pairing time, never
     * the raw SubjectPublicKeyInfo bytes [handshake] pins against -- a fingerprint cannot be
     * reversed back into the original SPKI. This overload instead pins the server's certificate by
     * the SHA-256 fingerprint of its presented SubjectPublicKeyInfo (see
     * [PinnedDesktopFingerprintTrustManager]), so reconnection can authenticate a desktop using only
     * what the store has. Shares every other handshake rule with [handshake] via [handshakeCore].
     */
    fun handshakeWithPinnedFingerprint(
        session: AccessoryIoSession,
        pinnedDesktopTrustMaterialFingerprint: ByteArray,
    ): SslEngineUsbTlsHandshakeResult = handshakeCore(session) { newFingerprintPinnedEngine(pinnedDesktopTrustMaterialFingerprint) }

    private fun handshakeCore(
        session: AccessoryIoSession,
        engineFactory: () -> SSLEngine,
    ): SslEngineUsbTlsHandshakeResult {
        val readExecutor = Executors.newSingleThreadExecutor(DaemonThreadFactory)
        val engine = runCatching { engineFactory() }
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
        return buildClientEngine(PinnedDesktopTlsTrustManager(identity))
    }

    private fun newFingerprintPinnedEngine(pinnedFingerprint: ByteArray): SSLEngine =
        buildClientEngine(PinnedDesktopFingerprintTrustManager(pinnedFingerprint))

    private fun buildClientEngine(trustManager: X509TrustManager): SSLEngine =
        SSLContext.getInstance("TLS").apply {
            init(phoneTlsIdentity?.keyManagers(), arrayOf(trustManager), null)
        }.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = supportedProtocols.filter { it == TLS_1_3 || it == TLS_1_2 }.toTypedArray()
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
        try {
            runCatching { engine?.closeOutbound() }
            session.close()
        } finally {
            readExecutor.shutdownNow()
        }
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
        const val MAX_APPLICATION_BUFFER_BYTES: Int = 64 * 1024
        const val TLS_1_2: String = "TLSv1.2"
        const val TLS_1_3: String = "TLSv1.3"
    }
}
