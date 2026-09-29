package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * TDD for task s1 (`odd/tasks/usb-authenticated-session.md` §4, §7): [UsbPairingFlow] composes
 * [PendingPairingCoordinator]'s existing rules with [UsbTlsPairingProofVerifier] over a fake
 * in-process desktop: a JSSE server [SSLEngine] that requires the phone's client certificate
 * (contract §4.2, like [SslEngineUsbTlsChannelTest]'s `needClientAuth` tests) and answers the
 * CCP1 proof protocol (`PairingProofProtocol`, like [UsbTlsPairingProofVerifierTest]'s harness).
 * Proves the live TLS channel's lifecycle: retained until confirm, closed on reject, closed on
 * expiry, and handed back alive on a successful confirm.
 */
class UsbPairingFlowTest {
    @Test
    fun usbPairingKeepsLiveChannelUntilConfirm() {
        val desktop = desktopFixture("s1-keep-desktop")
        val phone = phoneFixture("s1-keep-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
                tls.writeApplicationFrame(statusZeroResponse(request))
                assertArrayEquals(PRE_CONFIRM_PING, tls.readApplicationFrame())
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val clock = MutableEpochSecondsSource(1_000)
        val flow = UsbPairingFlow(newCoordinator(clock), tlsVerifier(phone, clock))

        try {
            val started = flow.start(qr(desktop.spki), pair.client)
            assertTrue(started.result is PendingPairingStartResult.PendingConfirmation)
            assertTrue(flow.state() is PendingPairingState.PendingConfirmation)

            val channel = requireNotNull(started.channel) { "a PendingConfirmation must retain a live channel" }
            channel.writeApplicationData(PRE_CONFIRM_PING)
            channel.close()
        } finally {
            server.join(2000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun usbPairingRejectClosesChannel() {
        val desktop = desktopFixture("s1-reject-desktop")
        val phone = phoneFixture("s1-reject-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
                tls.writeApplicationFrame(statusZeroResponse(request))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val clock = MutableEpochSecondsSource(1_000)
        val flow = UsbPairingFlow(newCoordinator(clock), tlsVerifier(phone, clock))

        try {
            val started = flow.start(qr(desktop.spki), pair.client)
            assertTrue(started.result is PendingPairingStartResult.PendingConfirmation)
        } finally {
            server.join(2000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }

        assertEquals(PendingPairingCancelResult.Cancelled, flow.reject())
        assertTrue(pair.clientCloseable.closed)
        // Consumed once: rejecting an already-rejected pending finds nothing left to cancel.
        assertEquals(PendingPairingCancelResult.NoPendingPairing, flow.reject())
    }

    @Test
    fun usbPairingExpiryClosesChannel() {
        val desktop = desktopFixture("s1-expiry-desktop")
        val phone = phoneFixture("s1-expiry-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
                tls.writeApplicationFrame(statusZeroResponse(request))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val clock = MutableEpochSecondsSource(1_000)
        val flow = UsbPairingFlow(newCoordinator(clock), tlsVerifier(phone, clock))

        try {
            val started = flow.start(qr(desktop.spki), pair.client)
            assertTrue(started.result is PendingPairingStartResult.PendingConfirmation)
        } finally {
            server.join(2000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }

        clock.now = 1_300
        assertTrue(flow.expire())
        assertTrue(pair.clientCloseable.closed)
        assertEquals(PendingPairingState.Idle, flow.state())
        // Nothing left to expire the second time around.
        assertFalse(flow.expire())
    }

    @Test
    fun usbPairingConfirmPersistsTrustAndActivates() {
        val desktop = desktopFixture("s1-confirm-desktop")
        val phone = phoneFixture("s1-confirm-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val request = PairingProofProtocol.parseRequest(tls.readApplicationFrame())
                tls.writeApplicationFrame(statusZeroResponse(request))
                assertArrayEquals(POST_CONFIRM_PONG, tls.readApplicationFrame())
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val clock = MutableEpochSecondsSource(1_000)
        val flow = UsbPairingFlow(newCoordinator(clock), tlsVerifier(phone, clock))
        val store = InMemoryTrustedDesktopStore()
        val authority = ActiveDesktopAuthority()
        var channel: SslEngineUsbTlsEstablishedChannel? = null

        try {
            val started = flow.start(qr(desktop.spki), pair.client)
            val pendingSummary = (started.result as PendingPairingStartResult.PendingConfirmation).summary

            val outcome = flow.confirm(pendingSummary.pendingId, store, authority)
            val activated = outcome.result as PendingPairingConfirmResult.Activated
            assertEquals("pc-1", activated.desktopId)
            channel = requireNotNull(outcome.channel) { "an activated pairing must hand back its live channel" }
            channel.writeApplicationData(POST_CONFIRM_PONG)

            val stored = store.lookup("pc-1")
            assertEquals("pc-1", stored?.desktopId)
            assertEquals("Studio", stored?.desktopName)
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)

            // Consumed once: confirming the same pending again finds nothing left to confirm.
            val second = flow.confirm(pendingSummary.pendingId, store, authority)
            assertEquals(PendingPairingConfirmResult.Rejected.NoPendingPairing, second.result)
            assertNull(second.channel)
        } finally {
            channel?.let { runCatching { it.close() } }
            server.join(2000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    private fun newCoordinator(clock: EpochSecondsSource): PendingPairingCoordinator = PendingPairingCoordinator(
        epochSecondsSource = clock,
        challengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(CHALLENGE_NONCE, "session-1", expiresAtEpochSeconds = 1_200)),
        proofVerifier = NeverCalledProofVerifier,
    )

    private fun tlsVerifier(phoneIdentity: PhoneTlsIdentity, clock: EpochSecondsSource): ChannelPairingProofVerifier {
        val verifier = UsbTlsPairingProofVerifier(clock, SslEngineUsbTlsChannel(phoneTlsIdentity = phoneIdentity))
        return ChannelPairingProofVerifier(verifier::verify)
    }

    private fun qr(desktopSpki: ByteArray, nonce: ByteArray = QR_NONCE, expiresAt: Long = 1_200) = PairingQrPayload(
        desktopId = "pc-1",
        desktopName = "Studio",
        trustMaterial = desktopSpki.copyOf(),
        expiresAtEpochSeconds = expiresAt,
        nonce = nonce,
    )

    private fun statusZeroResponse(request: PairingProofProtocol.ProofRequest): ByteArray =
        PairingProofProtocol.encodeResponse(
            PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId),
        )

    private fun desktopFixture(alias: String): DesktopFixture {
        val identity = keytoolIdentity(alias)
        val context = SSLContext.getInstance("TLS").apply {
            init(identity.keyManagers, arrayOf(AcceptAnyClientTrustManager), null)
        }
        return DesktopFixture(context, identity.certificate.publicKey.encoded)
    }

    private fun phoneFixture(alias: String): PhoneTlsIdentity {
        val identity = keytoolIdentity(alias)
        return object : PhoneTlsIdentity {
            override fun keyManagers(): Array<KeyManager> = identity.keyManagers
            override val subjectPublicKeyInfoDer: ByteArray = identity.certificate.publicKey.encoded
        }
    }

    /** Same keytool recipe as [SslEngineUsbTlsChannelTest]'s `TlsFixture` and [UsbTlsPairingProofVerifierTest]'s. */
    private fun keytoolIdentity(alias: String): KeytoolIdentity {
        val temp = createTempDir(prefix = "cc-usb-pairing-flow")
        try {
            val store = File(temp, "$alias.p12")
            val keytool = File(
                File(System.getProperty("java.home"), "bin"),
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool",
            ).absolutePath
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
            val certificate = keyStore.getCertificate(alias) as X509Certificate
            return KeytoolIdentity(kmf.keyManagers, certificate)
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun handshakeAsServer(context: SSLContext, session: AccessoryIoSession): ServerTlsChannel {
        val engine = context.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            beginHandshake()
        }
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
    ) {
        fun readApplicationFrame(): ByteArray {
            val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (app.position() == 0) unwrapServer(engine, session, adapter, peer, app)
            app.flip()
            return ByteArray(app.remaining()).also { app.get(it) }
        }

        fun writeApplicationFrame(bytes: ByteArray) {
            wrapServer(engine, session, adapter, java.nio.ByteBuffer.wrap(bytes))
        }

        fun close() = session.close()
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
    )

    private class RecordingCloseable : java.io.Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class DesktopFixture(val context: SSLContext, val spki: ByteArray)

    private class KeytoolIdentity(val keyManagers: Array<KeyManager>, val certificate: X509Certificate)

    /** Server-side [X509TrustManager] that accepts any presented phone client chain (the phone's real identity is already proven in [SslEngineUsbTlsChannelTest]; this harness only needs `needClientAuth` satisfied). */
    private object AcceptAnyClientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            throw CertificateException("not used: the client pins the server SPKI directly")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class QueueChallengeSource(
        private val challenge: PairingChallengeMaterial,
    ) : ChallengeNonceSource {
        override fun nextChallenge(): PairingChallengeMaterial = challenge
    }

    private class MutableEpochSecondsSource(var now: Long) : EpochSecondsSource {
        override fun nowEpochSeconds(): Long = now
    }

    /** [UsbPairingFlow] must only ever use the channel-capable verify path; the byte-based path must stay untouched. */
    private object NeverCalledProofVerifier : PairingProofVerifier {
        override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult =
            error("byte-based proof path must not be used by UsbPairingFlow")
    }

    private companion object {
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
        val PRE_CONFIRM_PING = byteArrayOf(0x50, 0x49, 0x4e, 0x47)
        val POST_CONFIRM_PONG = byteArrayOf(0x50, 0x4f, 0x4e, 0x47)
        const val PASSWORD = "changeit"
    }
}
