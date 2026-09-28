package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext
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
