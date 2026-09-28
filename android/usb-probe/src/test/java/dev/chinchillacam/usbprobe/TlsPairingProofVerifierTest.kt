package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class TlsPairingProofVerifierTest {
    @Test
    fun verifiesProofOverRealPinnedLoopbackTls() {
        val fixture = TlsFixture.create("valid", expired = false)
        OneShotTlsServer(fixture) { request -> PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId) }.use { server ->
            val verifier = TlsPairingProofVerifier(clock)

            val result = verifier.verify(challenge(fixture.certificate), endpointBytes(server.port))

            val verified = result as PairingProofVerificationResult.Verified
            assertEquals("pc-1", verified.desktopId)
            assertEquals("session-1", verified.sessionId)
            assertArrayEquals(QR_NONCE, verified.qrNonce)
            assertArrayEquals(CHALLENGE_NONCE, verified.challengeNonce)
            assertEquals(1000, verified.verifiedAtEpochSeconds)
            assertEquals(1100, verified.expiresAtEpochSeconds)
        }
    }

    @Test
    fun rejectsInvalidBootstrapShortNoncesStatusMismatchExpiredCertAndTimeout() {
        val fixture = TlsFixture.create("valid", expired = false)
        val mismatchFixture = TlsFixture.create("mismatch", expired = false)
        val expiredFixture = TlsFixture.create("expired", expired = true)
        val verifier = TlsPairingProofVerifier(clock)

        assertRejected("invalid proof endpoint") { verifier.verify(challenge(fixture.certificate), byteArrayOf(0x00)) }
        assertRejected("qr nonce too short") { verifier.verify(challenge(fixture.certificate, qrNonce = ByteArray(15) { 1 }), endpointBytes(1)) }
        assertRejected("challenge nonce too short") { verifier.verify(challenge(fixture.certificate, challengeNonce = ByteArray(31) { 2 }), endpointBytes(1)) }

        OneShotTlsServer(fixture) { request -> PairingProofProtocol.ProofResponse(1, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId) }.use { server ->
            assertRejected("desktop rejected proof") { verifier.verify(challenge(fixture.certificate), endpointBytes(server.port)) }
        }
        OneShotTlsServer(fixture) { request -> PairingProofProtocol.ProofResponse(0, "other", request.qrNonce, request.challengeNonce, request.sessionId) }.use { server ->
            assertRejected("proof response mismatch") { verifier.verify(challenge(fixture.certificate), endpointBytes(server.port)) }
        }
        OneShotTlsServer(mismatchFixture) { request -> PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId) }.use { server ->
            assertRejected("tls proof failed") { verifier.verify(challenge(fixture.certificate), endpointBytes(server.port)) }
        }
        OneShotTlsServer(expiredFixture) { request -> PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId) }.use { server ->
            assertRejected("tls proof failed") { verifier.verify(challenge(expiredFixture.certificate), endpointBytes(server.port)) }
        }
        SleepingTlsServer(fixture).use { server ->
            assertRejected("tls proof timed out") { verifier.verify(challenge(fixture.certificate), endpointBytes(server.port, timeoutMillis = 250)) }
        }
    }

    private fun challenge(
        certificate: X509Certificate,
        qrNonce: ByteArray = QR_NONCE,
        challengeNonce: ByteArray = CHALLENGE_NONCE,
    ): PairingProofChallenge {
        val spki = certificate.publicKey.encoded
        return PairingProofChallenge(
            desktopId = "pc-1",
            trustMaterialFingerprint = PairingTrustFingerprint.fromTrustMaterial(spki),
            desktopSubjectPublicKeyInfoDer = spki,
            qrNonce = qrNonce,
            challengeNonce = challengeNonce,
            sessionId = "session-1",
            qrExpiresAtEpochSeconds = 1100,
            challengeExpiresAtEpochSeconds = 1100,
        )
    }

    private fun endpointBytes(port: Int, timeoutMillis: Int = 1000): ByteArray = PairingProofProtocol.encodeEndpoint(
        PairingProofProtocol.Endpoint("127.0.0.1", port, timeoutMillis),
    )

    private fun assertRejected(reason: String, block: () -> PairingProofVerificationResult) {
        val result = block()
        assertTrue(result is PairingProofVerificationResult.Rejected)
        assertEquals(reason, (result as PairingProofVerificationResult.Rejected).reason)
    }

    private class OneShotTlsServer(
        fixture: TlsFixture,
        private val responder: (PairingProofProtocol.ProofRequest) -> PairingProofProtocol.ProofResponse,
    ) : AutoCloseable {
        private val server = fixture.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port: Int = server.localPort
        private val worker = thread(start = true) {
            server.use { socket ->
                socket.accept().use { accepted ->
                    val request = PairingProofProtocol.parseRequest(readFrameFrom(accepted.getInputStream()))
                    accepted.getOutputStream().write(PairingProofProtocol.encodeResponse(responder(request)))
                    accepted.getOutputStream().flush()
                }
            }
        }
        override fun close() { server.close(); worker.join(1000) }

        private fun readFrameFrom(input: InputStream): ByteArray {
            val header = readExactly(input, 10)
            val length = ((header[6].toInt() and 0xff) shl 24) or
                ((header[7].toInt() and 0xff) shl 16) or
                ((header[8].toInt() and 0xff) shl 8) or
                (header[9].toInt() and 0xff)
            return header + readExactly(input, length)
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw IllegalStateException("unexpected EOF")
                offset += read
            }
            return bytes
        }
    }

    private class SleepingTlsServer(fixture: TlsFixture) : AutoCloseable {
        private val server = fixture.context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port: Int = server.localPort
        private val worker = thread(start = true) {
            server.use { socket -> socket.accept().use { Thread.sleep(1000) } }
        }
        override fun close() { server.close(); worker.join(1000) }
    }

    private class TlsFixture(val context: SSLContext, val certificate: X509Certificate) {
        companion object {
            fun create(alias: String, expired: Boolean): TlsFixture {
                val temp = createTempDir(prefix = "cc-tls")
                val store = File(temp, "$alias.p12")
                val command = mutableListOf(
                    "keytool", "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=$alias", "-keystore", store.absolutePath, "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-storetype", "PKCS12",
                )
                if (expired) command += listOf("-startdate", "2020/01/01 00:00:00", "-validity", "1") else command += listOf("-startdate", "2026/01/01 00:00:00", "-validity", "36500")
                val exit = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start().waitFor()
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
    }
}
