package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SslEngineUsbTlsChannelTest {
    @Test
    fun completesPinnedClientHandshakeOverUsbCiphertextFrames() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        val server = thread { serverHandshake(fixture.context, pair.server) }

        val result = SslEngineUsbTlsChannel().handshake(pair.client, fixture.certificate.publicKey.encoded)

        assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
        result as SslEngineUsbTlsHandshakeResult.Authenticated
        assertTrue(result.protocol == "TLSv1.2" || result.protocol == "TLSv1.3")
        result.close()
        server.join(2000)
        assertEquals(false, server.isAlive)
    }

    @Test
    fun rejectsPinnedSpkiMismatchAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val mismatch = TlsFixture.create("mismatch")
        val pair = sessionPair()
        val server = thread { runCatching { serverHandshake(fixture.context, pair.server) } }

        val result = SslEngineUsbTlsChannel().handshake(pair.client, mismatch.certificate.publicKey.encoded)

        assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
        assertEquals(true, pair.clientCloseable.closed)
        server.join(2000)
    }

    @Test
    fun blockedReadDeadlineRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        try {
            val result = SslEngineUsbTlsChannel(readTimeoutMillis = 50)
                .handshake(pair.client, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun usbWriteErrorRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ThrowingOutputStream(), closeable)
        try {
            val result = SslEngineUsbTlsChannel(readTimeoutMillis = 100)
                .handshake(session, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, closeable.closed)
        } finally {
            session.close()
        }
    }

    @Test
    fun maxHandshakeStepLimitRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        try {
            val result = SslEngineUsbTlsChannel(maxHandshakeSteps = 1)
                .handshake(pair.client, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
        } finally {
            pair.close()
        }
    }


    @Test
    fun readApplicationDataRejectsOversizeMaxBytesBeforeEngineUse() {
        val engine = FakeEngine(listOf())
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), RecordingCloseable())
        val channel = establishedChannel(engine, session, executor)
        try {
            try {
                channel.readApplicationData(64 * 1024 + 1, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
                fail("expected oversize maxBytes to be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("maxBytes"))
            }
            assertEquals(0, engine.unwrapCalls)
        } finally {
            channel.close()
        }
    }

    @Test
    fun blockedApplicationReadDeadlineClosesEstablishedChannel() {
        val engine = FakeEngine(listOf(FakeUnwrap(SSLEngineResult.Status.BUFFER_UNDERFLOW)))
        val executor = Executors.newSingleThreadExecutor()
        val input = PipedInputStream()
        val heldWriter = PipedOutputStream(input)
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(input, ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor)

        try {
            try {
                channel.readApplicationData(16, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))
                fail("expected blocked app-data read to time out")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("timed out"))
            }
            assertEquals(true, closeable.closed)
            assertEquals(true, executor.isShutdown)
        } finally {
            runCatching { heldWriter.close() }
            runCatching { channel.close() }
        }
    }

    @Test
    fun expiredApplicationReadDeadlineClosesBeforeReturningPendingPlaintext() {
        val engine = FakeEngine(listOf())
        val executor = Executors.newSingleThreadExecutor()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor, pendingPlaintext = byteArrayOf(1))

        try {
            channel.readApplicationData(1, System.nanoTime() - TimeUnit.SECONDS.toNanos(1))
            fail("expected expired app-data read to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertEquals(true, closeable.closed)
        }
    }

    @Test
    fun applicationWriteRejectsEngineNoProgress() {
        val engine = FakeEngine(listOf(), noProgressWrapsBeforeThrow = 4)
        val executor = Executors.newSingleThreadExecutor()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor)

        try {
            channel.writeApplicationData(byteArrayOf(9))
            fail("expected no-progress app-data write to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("made no progress"))
            assertEquals(true, closeable.closed)
        }
    }

    @Test
    fun needWrapDuringApplicationReadPerformsActualEmptyWrap() {
        val output = ByteArrayOutputStream()
        val engine = FakeEngine(
            unwraps = listOf(
                FakeUnwrap(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP),
                FakeUnwrap(SSLEngineResult.Status.CLOSED),
            ),
            wrapBytes = byteArrayOf(1, 2, 3),
        )
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), output, RecordingCloseable())
        val channel = establishedChannel(engine, session, executor)

        try {
            try {
                channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
                fail("expected read to fail after empty wrap")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("closed"))
            }
            assertEquals(1, engine.wrapCalls)
            assertTrue(output.toByteArray().isNotEmpty())
        } finally {
            runCatching { channel.close() }
        }
    }

    @Test
    fun applicationReadFailureShutsDownExecutorWhenSessionCloseThrows() {
        val engine = FakeEngine(listOf(FakeUnwrap(SSLEngineResult.Status.CLOSED)))
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ThrowingCloseable())
        val channel = establishedChannel(engine, session, executor)

        try {
            channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
            fail("expected closed engine read to fail")
        } catch (_: Exception) {
            assertEquals(true, executor.isShutdown)
        }
    }

    private fun serverHandshake(context: SSLContext, session: AccessoryIoSession) {
        val engine = context.createSSLEngine().apply { useClientMode = false; beginHandshake() }
        val adapter = UsbTlsCiphertextIoAdapter()
        val empty = java.nio.ByteBuffer.allocate(0)
        val peer = java.nio.ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
                    engine.wrap(empty, out)
                    out.flip()
                    if (out.hasRemaining()) adapter.write(session, ByteArray(out.remaining()).also { out.get(it) })
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (peer.position() == 0) {
                        val read = adapter.read(session) as UsbTlsCiphertextReadResult.Received
                        require(read.ciphertext.size <= peer.remaining())
                        peer.put(read.ciphertext)
                    }
                    peer.flip()
                    val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(peer, app)
                    peer.compact()
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        val read = adapter.read(session) as UsbTlsCiphertextReadResult.Received
                        require(read.ciphertext.size <= peer.remaining())
                        peer.put(read.ciphertext)
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach { it.run() }
                else -> Unit
            }
        }
    }

    private fun sessionPair(): SessionPair {
        val clientIn = PipedInputStream(64 * 1024)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(64 * 1024)
        val clientOut = PipedOutputStream(serverIn)
        val clientClose = RecordingCloseable()
        val serverClose = RecordingCloseable()
        return SessionPair(
            client = AccessoryIoSession(clientIn, clientOut, clientClose),
            server = AccessoryIoSession(serverIn, serverOut, serverClose),
            clientCloseable = clientClose,
        )
    }


    private fun establishedChannel(
        engine: SSLEngine,
        session: AccessoryIoSession,
        executor: ExecutorService,
        pendingPlaintext: ByteArray = ByteArray(0),
    ): SslEngineUsbTlsEstablishedChannel = SslEngineUsbTlsEstablishedChannel(
        engine = engine,
        session = session,
        pendingCiphertext = ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES),
        adapter = UsbTlsCiphertextIoAdapter(),
        readExecutor = executor,
    ).also { channel ->
        pendingPlaintext.forEach { byte ->
            val field = channel.javaClass.getDeclaredField("pendingPlaintext")
            field.isAccessible = true
            field.set(channel, byteArrayOf(byte))
        }
    }

    private data class FakeUnwrap(
        val status: SSLEngineResult.Status,
        val handshakeStatus: SSLEngineResult.HandshakeStatus = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
        val bytesProduced: Int = 0,
    )

    private class FakeEngine(
        private val unwraps: List<FakeUnwrap>,
        private val wrapBytes: ByteArray = ByteArray(0),
        private val noProgressWrapsBeforeThrow: Int? = null,
    ) : SSLEngine() {
        private val sslSession: SSLSession = SSLContext.getDefault().createSSLEngine().session
        private var unwrapIndex = 0
        var unwrapCalls = 0
            private set
        var wrapCalls = 0
            private set

        override fun wrap(srcs: Array<out ByteBuffer>, offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult {
            wrapCalls += 1
            noProgressWrapsBeforeThrow?.let { limit ->
                if (wrapCalls > limit) throw IllegalStateException("test guard: no progress loop")
                return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0)
            }
            if (wrapBytes.size > dst.remaining()) {
                return SSLEngineResult(SSLEngineResult.Status.BUFFER_OVERFLOW, SSLEngineResult.HandshakeStatus.NEED_WRAP, 0, 0)
            }
            dst.put(wrapBytes)
            return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, wrapBytes.size)
        }

        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, offset: Int, length: Int): SSLEngineResult {
            unwrapCalls += 1
            val next = unwraps.getOrElse(unwrapIndex) { FakeUnwrap(SSLEngineResult.Status.CLOSED) }
            unwrapIndex += 1
            return SSLEngineResult(next.status, next.handshakeStatus, 0, next.bytesProduced)
        }

        override fun getDelegatedTask(): Runnable? = null
        override fun closeInbound() = Unit
        override fun isInboundDone(): Boolean = false
        override fun closeOutbound() = Unit
        override fun isOutboundDone(): Boolean = false
        override fun getSupportedCipherSuites(): Array<String> = arrayOf("TLS_FAKE")
        override fun getEnabledCipherSuites(): Array<String> = supportedCipherSuites
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")
        override fun getEnabledProtocols(): Array<String> = supportedProtocols
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun getSession(): SSLSession = sslSession
        override fun beginHandshake() = Unit
        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
    }

    private data class SessionPair(
        val client: AccessoryIoSession,
        val server: AccessoryIoSession,
        val clientCloseable: RecordingCloseable,
    ) : AutoCloseable {
        override fun close() {
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }

    private class RecordingCloseable : java.io.Closeable {
        var closed = false
            private set
        override fun close() { closed = true }
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("write failed")
        }
    }

    private class ThrowingCloseable : java.io.Closeable {
        override fun close() {
            throw IOException("close failed")
        }
    }

    private class TlsFixture(val context: SSLContext, val certificate: X509Certificate) {
        companion object {
            fun create(alias: String): TlsFixture {
                val temp = createTempDir(prefix = "cc-engine")
                val store = File(temp, "$alias.p12")
                val keytool = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool").absolutePath
                val command = listOf(
                    keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=$alias", "-keystore", store.absolutePath, "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-storetype", "PKCS12", "-startdate", "2026/01/01 00:00:00", "-validity", "36500",
                )
                val exit = ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
                require(exit == 0) { "keytool failed" }
                val keyStore = KeyStore.getInstance("PKCS12")
                store.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, PASSWORD.toCharArray())
                val context = SSLContext.getInstance("TLS")
                context.init(kmf.keyManagers, null, null)
                val certificate = keyStore.getCertificate(alias) as X509Certificate
                temp.deleteRecursively()
                return TlsFixture(context, certificate)
            }
            private const val PASSWORD = "changeit"
        }
    }
}
