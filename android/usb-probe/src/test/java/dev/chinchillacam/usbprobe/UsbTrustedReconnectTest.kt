package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
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
 * TDD for task s3 (`odd/tasks/usb-authenticated-session.md` §4.4, §7): [UsbTrustedReconnect]
 * reconnects to an already-paired, trusted desktop using only what [TrustedDesktopStore] persisted
 * at pairing time (no fresh QR): the store gates trust *before* any TLS I/O, then TLS is pinned by
 * the stored fingerprint (contract addendum: since the store only ever persists a
 * [PairingTrustFingerprint], not the raw SPKI, reconnection pins by that fingerprint via
 * [SslEngineUsbTlsChannel.handshakeWithPinnedFingerprint] -- see that method's KDoc), then
 * `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT` is exchanged (the phone's `deviceId` is its own trust
 * fingerprint hex -- the desktop authenticates it against the TLS client certificate), then
 * [ActiveDesktopAuthority] is asked to activate. Uses the same fake-desktop JSSE harness pattern as
 * [UsbPairingFlowTest]/[TlsSessionFrameIoAdapterTest] (`needClientAuth = true`, raw [SSLEngine]
 * wrap/unwrap, keytool PKCS12 identities).
 */
class UsbTrustedReconnectTest {
    @Test
    fun reconnectsToTrustedDesktopAfterAccept() {
        val desktop = desktopFixture("s3-accept-desktop")
        val phone = phoneFixture("s3-accept-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val expectedPhoneId = PairingTrustFingerprint.fromTrustMaterial(phone.subjectPublicKeyInfoDer).hex
        val ping = SessionFrame(sequence = 100, sessionId = "reconnect-ping", payload = SessionPayload.CameraControlCommand("ping", emptyMap()))

        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)

                // Contract addendum: the desktop authenticates HANDSHAKE_HELLO's deviceId against
                // the TLS client certificate it just saw, not merely against whatever the frame
                // self-declares.
                val peerLeaf = tls.engine.session.peerCertificates.first() as X509Certificate
                val phoneIdSeenOverTls = PairingTrustFingerprint.fromTrustMaterial(peerLeaf.publicKey.encoded).hex
                assertEquals(expectedPhoneId, phoneIdSeenOverTls)

                val helloFrame = decodeFrame(tls.readApplicationFrame())
                val hello = helloFrame.payload as SessionPayload.HandshakeHello
                assertEquals(phoneIdSeenOverTls, hello.deviceId)

                val accept = SessionFrame(sequence = helloFrame.sequence + 1, sessionId = helloFrame.sessionId, payload = SessionPayload.HandshakeAccept("pc-1", "welcome back"))
                tls.writeApplicationFrame(encodeFrame(accept))

                // Proves the returned adapter/channel genuinely stay usable for the session, not
                // just that reconnect() returns a Reconnected value.
                assertEquals(ping, decodeFrame(tls.readApplicationFrame()))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val store = trustedStoreFor("pc-1", "Studio", desktop.spki)
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)
        var channel: SslEngineUsbTlsEstablishedChannel? = null

        try {
            val result = reconnect.reconnect("pc-1", pair.client, store, authority)
            val reconnected = result as UsbTrustedReconnectResult.Reconnected
            assertEquals("pc-1", reconnected.desktopId)
            channel = reconnected.channel
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)

            reconnected.frameAdapter.write(reconnected.channel, ping)
        } finally {
            channel?.let { runCatching { it.close() } }
            server.join(2_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun reconnectRejectedByDesktopFailsClosed() {
        val desktop = desktopFixture("s3-reject-desktop")
        val phone = phoneFixture("s3-reject-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val helloFrame = decodeFrame(tls.readApplicationFrame())
                val reject = SessionFrame(sequence = helloFrame.sequence + 1, sessionId = helloFrame.sessionId, payload = SessionPayload.HandshakeReject("busy", "Ya hay una computadora activa"))
                tls.writeApplicationFrame(encodeFrame(reject))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val store = trustedStoreFor("pc-1", "Studio", desktop.spki)
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)

        try {
            val result = reconnect.reconnect("pc-1", pair.client, store, authority)
            val rejected = result as UsbTrustedReconnectResult.Rejected.DesktopRejected
            assertEquals("pc-1", rejected.desktopId)
            assertEquals("busy", rejected.reasonCode)
        } finally {
            server.join(2_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }

        assertTrue(pair.clientCloseable.closed)
        // Failing closed on a desktop rejection must never reach activation.
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    @Test
    fun reconnectTimesOutWithoutAccept() {
        val desktop = desktopFixture("s3-timeout-desktop")
        val phone = phoneFixture("s3-timeout-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                // Reads the HELLO but deliberately never answers and never closes. Stays alive well
                // past the phone's configured deadline on purpose: if this thread exited early
                // instead, PipedInputStream would raise its own immediate "write end dead" failure,
                // which would mask the bounded-deadline path this test exists to prove.
                decodeFrame(tls.readApplicationFrame())
                Thread.sleep(1_000)
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val store = trustedStoreFor("pc-1", "Studio", desktop.spki)
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone, helloTimeoutMillis = 300)

        val startNanos = System.nanoTime()
        val result = reconnect.reconnect("pc-1", pair.client, store, authority)
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        val rejected = result as UsbTrustedReconnectResult.Rejected.TimedOut
        assertEquals("pc-1", rejected.desktopId)
        // Lower bound tolerates timer granularity (millisecond truncation of the remaining time).
        assertTrue("expected a bounded wait near the configured 300ms deadline, took ${elapsedMillis}ms", elapsedMillis in 250..2_000)
        assertTrue(pair.clientCloseable.closed)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)

        server.join(2_000)
        serverError.get()?.let { throw it }
        assertTrue(serverCompleted.get())
    }

    @Test
    fun revokedDesktopIsNotReconnected() {
        val phone = phoneFixture("s3-revoked-phone")
        val pair = sessionPair()
        val store = InMemoryTrustedDesktopStore()
        val arbitraryFingerprint = ByteArray(32) { it.toByte() }
        store.save(
            TrustedDesktopRecord(
                desktopId = "pc-1",
                desktopName = "Studio",
                trustMaterialFingerprint = arbitraryFingerprint,
                createdAtEpochSeconds = 1_000,
                lastSeenAtEpochSeconds = 1_000,
            ),
        )
        assertTrue(store.revoke("pc-1", revokedAtEpochSeconds = 1_500))
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)

        // No fake desktop is started at all: a correct implementation never attempts TLS here, so
        // nothing needs to be on the other end of the pipe.
        val result = reconnect.reconnect("pc-1", pair.client, store, authority)

        val rejected = result as UsbTrustedReconnectResult.Rejected.NotTrusted
        assertEquals("pc-1", rejected.desktopId)
        assertEquals(TrustedDesktopAuthResult.Revoked, rejected.reason)
        assertTrue(pair.clientCloseable.closed)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertNotEquals(0, arbitraryFingerprint.size) // sanity: the fixture fingerprint is non-empty, not itself under test
    }

    // ---- Channel-leak-on-activation tests (task l1, `session-liveness` §2, §7): before this task
    // requestActivation() was called outside any try/finally relative to the already-open channel,
    // so a thrown exception leaked it. Both tests below drive reconnect() all the way through a
    // successful handshake/hello exchange, only diverging at the activation step itself. ----

    @Test
    fun activationExceptionClosesChannel() {
        val desktop = desktopFixture("l1-activation-exception-desktop")
        val phone = phoneFixture("l1-activation-exception-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val helloFrame = decodeFrame(tls.readApplicationFrame())
                val accept = SessionFrame(sequence = helloFrame.sequence + 1, sessionId = helloFrame.sessionId, payload = SessionPayload.HandshakeAccept("pc-1", "welcome back"))
                tls.writeApplicationFrame(encodeFrame(accept))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        // Succeeds trust's own pre-TLS evaluate() call (reconnect()'s own gate, before any I/O),
        // then throws on the second evaluate() call -- the one requestActivation makes internally --
        // so the exception genuinely originates from activation, with the channel already open.
        val store = EvaluateFailsAfterFirstCallTrustedDesktopStore(trustedStoreFor("pc-1", "Studio", desktop.spki))
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)

        try {
            val result = reconnect.reconnect("pc-1", pair.client, store, authority)

            val rejected = result as UsbTrustedReconnectResult.Rejected.ActivationFailed
            assertEquals("pc-1", rejected.desktopId)
            assertTrue(pair.clientCloseable.closed)
        } finally {
            server.join(2_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun activationRejectedClosesChannel() {
        val desktop = desktopFixture("l1-activation-rejected-desktop")
        val phone = phoneFixture("l1-activation-rejected-phone")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                val helloFrame = decodeFrame(tls.readApplicationFrame())
                val accept = SessionFrame(sequence = helloFrame.sequence + 1, sessionId = helloFrame.sessionId, payload = SessionPayload.HandshakeAccept("pc-1", "welcome back"))
                tls.writeApplicationFrame(encodeFrame(accept))
                tls.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val store = trustedStoreFor("pc-1", "Studio", desktop.spki)
        // A different desktop is already active, so requestActivation returns Rejected.SecondActiveDesktop.
        val authority = ActiveDesktopAuthority(initialState = ActiveDesktopAuthority.State.ActiveDesktop("other-desktop"))
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)

        try {
            val result = reconnect.reconnect("pc-1", pair.client, store, authority)

            val rejected = result as UsbTrustedReconnectResult.Rejected.ActivationRejected
            assertEquals("pc-1", rejected.desktopId)
            assertTrue(pair.clientCloseable.closed)
        } finally {
            server.join(2_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    /** Delegates every [TrustedDesktopStore] operation except [evaluate], which throws from its second call onward (its first call is reconnect()'s own pre-TLS trust gate). */
    private class EvaluateFailsAfterFirstCallTrustedDesktopStore(
        private val delegate: TrustedDesktopStore,
    ) : TrustedDesktopStore by delegate {
        private var evaluateCallCount = 0

        override fun evaluate(desktopId: String, presentedTrustMaterialFingerprint: ByteArray, nowEpochSeconds: Long): TrustedDesktopAuthResult {
            evaluateCallCount += 1
            if (evaluateCallCount > 1) throw IllegalStateException("simulated trusted desktop store failure")
            return delegate.evaluate(desktopId, presentedTrustMaterialFingerprint, nowEpochSeconds)
        }
    }

    private fun trustedStoreFor(desktopId: String, desktopName: String, desktopSpki: ByteArray): TrustedDesktopStore {
        val store = InMemoryTrustedDesktopStore()
        store.save(
            TrustedDesktopRecord(
                desktopId = desktopId,
                desktopName = desktopName,
                trustMaterialFingerprint = PairingTrustFingerprint.fromTrustMaterial(desktopSpki).bytes,
                createdAtEpochSeconds = 1_000,
                lastSeenAtEpochSeconds = 1_000,
            ),
        )
        return store
    }

    private fun encodeFrame(frame: SessionFrame): ByteArray {
        val encoded = SessionFrameCodec.encode(frame)
        return encoded.size.toBigEndianBytes() + encoded
    }

    private fun decodeFrame(bytes: ByteArray): SessionFrame {
        val declaredLength = bytes.copyOfRange(0, 4).toBigEndianInt()
        assertEquals(bytes.size - 4, declaredLength)
        return SessionFrameCodec.decode(bytes.copyOfRange(4, bytes.size)).getOrThrow()
    }

    // ---- Fake-desktop JSSE harness: same pattern as UsbPairingFlowTest/TlsSessionFrameIoAdapterTest
    // (needClientAuth = true, raw SSLEngine wrap/unwrap over UsbTlsCiphertextIoAdapter, keytool
    // PKCS12 identities) -- duplicated deliberately, matching this module's established convention. ----

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

    /** Same keytool recipe as [UsbPairingFlowTest]'s and [SslEngineUsbTlsChannelTest]'s `TlsFixture`. */
    private fun keytoolIdentity(alias: String): KeytoolIdentity {
        val temp = createTempDir(prefix = "cc-usb-trusted-reconnect")
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
        val empty = ByteBuffer.allocate(0)
        val peer = ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
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

    private fun wrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, src: ByteBuffer) {
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(src, out)
        out.flip()
        if (out.hasRemaining()) adapter.write(session, ByteArray(out.remaining()).also { out.get(it) })
    }

    private fun unwrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, peer: ByteBuffer, app: ByteBuffer) {
        if (peer.position() == 0) peer.put((adapter.read(session) as UsbTlsCiphertextReadResult.Received).ciphertext)
        peer.flip()
        val result = engine.unwrap(peer, app)
        peer.compact()
        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            peer.put((adapter.read(session) as UsbTlsCiphertextReadResult.Received).ciphertext)
        }
    }

    private inner class ServerTlsChannel(
        val engine: SSLEngine,
        private val session: AccessoryIoSession,
        private val adapter: UsbTlsCiphertextIoAdapter,
        private val peer: ByteBuffer,
    ) {
        fun readApplicationFrame(): ByteArray {
            val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (app.position() == 0) unwrapServer(engine, session, adapter, peer, app)
            app.flip()
            return ByteArray(app.remaining()).also { app.get(it) }
        }

        fun writeApplicationFrame(bytes: ByteArray) {
            wrapServer(engine, session, adapter, ByteBuffer.wrap(bytes))
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

    /** Server-side [X509TrustManager] that accepts any presented phone client chain (the phone's real identity is checked independently via its certificate's public key, see the tests). */
    private object AcceptAnyClientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            throw CertificateException("not used: the client pins the server fingerprint directly")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val PASSWORD = "changeit"
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
