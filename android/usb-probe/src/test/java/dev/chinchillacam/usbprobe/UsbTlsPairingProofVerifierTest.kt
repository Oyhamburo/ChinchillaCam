package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

class UsbTlsPairingProofVerifierTest {
    @Test
    fun rejectsExpiredChallengeWhenUsbCloseThrowsIOException() {
        val fixture = TlsFixture.create("usb-proof-expired")
        val closeable = ThrowingCloseable(IOException("usb close failed"))
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), closeable)
        val expiredChallenge = challenge(
            fixture.certificate,
            qrExpiresAtEpochSeconds = 999,
            challengeExpiresAtEpochSeconds = 999,
        )

        val result = try {
            UsbTlsPairingProofVerifier(clock).verify(expiredChallenge, session)
        } catch (error: IOException) {
            throw AssertionError("verify must return Rejected when session close throws IOException", error)
        }

        assertTrue(result is UsbTlsPairingProofVerificationResult.Rejected)
        result as UsbTlsPairingProofVerificationResult.Rejected
        assertEquals("qr expired", result.reason)
        assertTrue(closeable.closeAttempted)
    }

    @Test
    fun rejectsNonzeroCcp1StatusAndClosesClientUsbSession() {
        val fixture = TlsFixture.create("usb-proof-nonzero-status")
        val pair = sessionPair()
        val challenge = challenge(fixture.certificate)
        val server = thread {
            val tls = serverHandshake(fixture.context, pair.server)
            val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
            tls.writeApplicationFrame(PairingProofProtocol.encodeResponse(PairingProofProtocol.ProofResponse(7, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId)))
            tls.close()
        }

        val result = UsbTlsPairingProofVerifier(clock).verify(challenge, pair.client)

        assertTrue(result is UsbTlsPairingProofVerificationResult.Rejected)
        result as UsbTlsPairingProofVerificationResult.Rejected
        assertEquals("desktop rejected proof", result.reason)
        assertTrue(pair.clientCloseable.closed)
        server.join(2000)
        assertEquals(false, server.isAlive)
    }

    @Test
    fun rejectsStatusZeroCcp1ProofWithMismatchedChallengeNonceAndClosesClientUsbSession() {
        val fixture = TlsFixture.create("usb-proof-wrong-echo")
        val pair = sessionPair()
        val challenge = challenge(fixture.certificate)
        val server = thread {
            val tls = serverHandshake(fixture.context, pair.server)
            val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
            val wrongChallengeNonce = request.challengeNonce.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
            tls.writeApplicationFrame(PairingProofProtocol.encodeResponse(PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, wrongChallengeNonce, request.sessionId)))
            tls.close()
        }

        val result = UsbTlsPairingProofVerifier(clock).verify(challenge, pair.client)

        assertTrue(result is UsbTlsPairingProofVerificationResult.Rejected)
        result as UsbTlsPairingProofVerificationResult.Rejected
        assertEquals("proof response mismatch", result.reason)
        assertTrue(pair.clientCloseable.closed)
        server.join(2000)
        assertEquals(false, server.isAlive)
    }

    @Test
    fun verifiesCcp1ProofInsideEstablishedUsbTlsChannel() {
        val fixture = TlsFixture.create("usb-proof")
        val pair = sessionPair()
        val challenge = challenge(fixture.certificate)
        val server = thread {
            val tls = serverHandshake(fixture.context, pair.server)
            val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
            tls.writeApplicationFrame(PairingProofProtocol.encodeResponse(PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId)))
            assertArrayEquals(POST_PROOF_APP_BYTES, tls.readApplicationFrame())
            tls.close()
        }

        val result = UsbTlsPairingProofVerifier(clock).verify(challenge, pair.client)

        assertTrue(result is UsbTlsPairingProofVerificationResult.Verified)
        result as UsbTlsPairingProofVerificationResult.Verified
        assertEquals("pc-1", result.proof.desktopId)
        assertEquals("session-1", result.proof.sessionId)
        assertArrayEquals(QR_NONCE, result.proof.qrNonce)
        assertArrayEquals(CHALLENGE_NONCE, result.proof.challengeNonce)
        result.channel.writeApplicationData(POST_PROOF_APP_BYTES)
        result.channel.close()
        server.join(2000)
        assertEquals(false, server.isAlive)
    }

    private fun challenge(
        certificate: X509Certificate,
        qrExpiresAtEpochSeconds: Long = 1100,
        challengeExpiresAtEpochSeconds: Long = 1100,
    ): PairingProofChallenge {
        val spki = certificate.publicKey.encoded
        return PairingProofChallenge(
            desktopId = "pc-1",
            trustMaterialFingerprint = PairingTrustFingerprint.fromTrustMaterial(spki),
            desktopSubjectPublicKeyInfoDer = spki,
            qrNonce = QR_NONCE,
            challengeNonce = CHALLENGE_NONCE,
            sessionId = "session-1",
            qrExpiresAtEpochSeconds = qrExpiresAtEpochSeconds,
            challengeExpiresAtEpochSeconds = challengeExpiresAtEpochSeconds,
        )
    }

    private fun serverHandshake(context: SSLContext, session: AccessoryIoSession): ServerTlsChannel {
        val engine = context.createSSLEngine().apply { useClientMode = false; beginHandshake() }
        val adapter = UsbTlsCiphertextIoAdapter()
        val empty = java.nio.ByteBuffer.allocate(0)
        val peer = java.nio.ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapServer(engine, session, adapter, empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrapServer(engine, session, adapter, peer, app)
                SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach { it.run() }
                else -> Unit
            }
        }
        return ServerTlsChannel(engine, session, adapter, peer)
    }

    private fun wrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, src: java.nio.ByteBuffer) {
        val out = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(src, out)
        out.flip()
        if (out.hasRemaining()) adapter.write(session, ByteArray(out.remaining()).also { out.get(it) })
    }

    private fun unwrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, peer: java.nio.ByteBuffer, app: java.nio.ByteBuffer) {
        if (peer.position() == 0) peer.put((adapter.read(session) as UsbTlsCiphertextReadResult.Received).ciphertext)
        peer.flip()
        val result = engine.unwrap(peer, app)
        peer.compact()
        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            peer.put((adapter.read(session) as UsbTlsCiphertextReadResult.Received).ciphertext)
        }
    }

    private inner class ServerTlsChannel(
        private val engine: SSLEngine,
        private val session: AccessoryIoSession,
        private val adapter: UsbTlsCiphertextIoAdapter,
        private val peer: java.nio.ByteBuffer,
    ) : AutoCloseable {
        fun readApplicationFrame(): ByteArray {
            val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (app.position() == 0) unwrapServer(engine, session, adapter, peer, app)
            app.flip()
            return ByteArray(app.remaining()).also { app.get(it) }
        }

        fun writeApplicationFrame(bytes: ByteArray) {
            wrapServer(engine, session, adapter, java.nio.ByteBuffer.wrap(bytes))
        }

        override fun close() = session.close()
    }

    private fun sessionPair(): SessionPair {
        val clientIn = PipedInputStream(64 * 1024)
        val serverIn = PipedInputStream(64 * 1024)
        val clientCloseable = RecordingCloseable()
        val serverCloseable = RecordingCloseable()
        return SessionPair(
            AccessoryIoSession(clientIn, PipedOutputStream(serverIn), clientCloseable),
            AccessoryIoSession(serverIn, PipedOutputStream(clientIn), serverCloseable),
            clientCloseable,
            serverCloseable,
        )
    }

    private data class SessionPair(
        val client: AccessoryIoSession,
        val server: AccessoryIoSession,
        val clientCloseable: RecordingCloseable,
        val serverCloseable: RecordingCloseable,
    )

    private class RecordingCloseable : java.io.Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class ThrowingCloseable(private val error: IOException) : java.io.Closeable {
        var closeAttempted = false
            private set

        override fun close() {
            closeAttempted = true
            throw error
        }
    }

    private class TlsFixture(val context: SSLContext, val certificate: X509Certificate) {
        companion object {
            fun create(alias: String): TlsFixture {
                val temp = createTempDir(prefix = "cc-usb-proof")
                val store = File(temp, "$alias.p12")
                val keytool = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool").absolutePath
                val command = listOf(keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$alias", "-keystore", store.absolutePath, "-storepass", PASSWORD, "-keypass", PASSWORD, "-storetype", "PKCS12", "-startdate", "2026/01/01 00:00:00", "-validity", "36500")
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

    private companion object {
        val clock = EpochSecondsSource { 1000 }
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
        val POST_PROOF_APP_BYTES = byteArrayOf(0x43, 0x43, 0x53, 0x46)
    }
}
