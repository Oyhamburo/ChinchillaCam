package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * TDD for task s2 (`odd/tasks/usb-authenticated-session.md` §4.3, §7): [TlsSessionFrameIoAdapter]
 * frames CCSF v1 [SessionFrame]s over an already-authenticated [SslEngineUsbTlsEstablishedChannel]:
 * a 4-byte big-endian length prefix (`1..=1048576`) followed by exactly one encoded frame. Uses the
 * same fake-desktop JSSE harness pattern as [UsbPairingFlowTest]/[SslEngineUsbTlsChannelTest]
 * (`needClientAuth = true`, raw [SSLEngine] wrap/unwrap over [UsbTlsCiphertextIoAdapter], keytool
 * PKCS12 identities), so both sides exchange real TLS-protected bytes. The desktop side parses the
 * wire format with its own independent big-endian logic (never the adapter's private helpers) to
 * prove genuine on-the-wire interop, not just the adapter round-tripping with itself.
 */
class TlsSessionFrameIoAdapterTest {
    @Test
    fun roundTripsSessionFrameOverTls() {
        val outbound = SessionFrame(
            sequence = 1,
            sessionId = "session-1",
            payload = SessionPayload.HandshakeHello("phone-1", "ChinchillaCam", listOf("h264")),
        )
        val inbound = SessionFrame(
            sequence = 2,
            sessionId = "session-1",
            payload = SessionPayload.HandshakeAccept("pc-1", "welcome"),
        )

        val session = connect("s2-roundtrip-desktop", "s2-roundtrip-phone") { tls ->
            val received = tls.readApplicationFrame()
            val declaredLength = received.copyOfRange(0, 4).toBigEndianInt()
            assertEquals(received.size - 4, declaredLength)
            assertEquals(outbound, SessionFrameCodec.decode(received.copyOfRange(4, received.size)).getOrThrow())

            val encodedInbound = SessionFrameCodec.encode(inbound)
            tls.writeApplicationFrame(encodedInbound.size.toBigEndianBytes() + encodedInbound)
            tls.close()
        }

        val adapter = TlsSessionFrameIoAdapter()
        try {
            adapter.write(session.channel, outbound)
            assertEquals(inbound, adapter.read(session.channel))
        } finally {
            session.channel.close()
            session.finish()
        }
    }

    @Test
    fun rejectsOversizedLengthBeforeAllocating() {
        val session = connect("s2-oversize-desktop", "s2-oversize-phone") { tls ->
            // Declares a length far above the adapter's max and sends nothing else: if the
            // implementation validated the length only after trying to read/allocate the (huge,
            // nonexistent) payload, this would hang until the read timeout instead of failing fast.
            tls.writeApplicationFrame((TLS_SESSION_FRAME_MAX_BYTES + 1).toBigEndianBytes())
            tls.close()
        }

        val adapter = TlsSessionFrameIoAdapter(readTimeoutMillis = 2_000)
        try {
            val startNanos = System.nanoTime()
            assertThrowsTlsSessionFrameIoException { adapter.read(session.channel) }
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            assertTrue(
                "expected the declared length to be rejected before attempting to read/allocate the payload (took ${elapsedMillis}ms)",
                elapsedMillis < 500,
            )
            assertTrue(session.pair.clientCloseable.closed)
        } finally {
            session.finish()
        }
    }

    @Test
    fun rejectsZeroLength() {
        val session = connect("s2-zero-length-desktop", "s2-zero-length-phone") { tls ->
            tls.writeApplicationFrame(0.toBigEndianBytes())
            tls.close()
        }

        val adapter = TlsSessionFrameIoAdapter()
        try {
            assertThrowsTlsSessionFrameIoException { adapter.read(session.channel) }
            assertTrue(session.pair.clientCloseable.closed)
        } finally {
            session.finish()
        }
    }

    @Test
    fun truncatedFrameClosesChannel() {
        val session = connect("s2-truncated-desktop", "s2-truncated-phone") { tls ->
            // Declares 20 payload bytes but sends only 5, then closes before the rest ever arrives.
            tls.writeApplicationFrame(20.toBigEndianBytes() + byteArrayOf(1, 2, 3, 4, 5))
            tls.close()
        }

        val adapter = TlsSessionFrameIoAdapter()
        try {
            assertThrowsTlsSessionFrameIoException { adapter.read(session.channel) }
            assertTrue(session.pair.clientCloseable.closed)
        } finally {
            session.finish()
        }
    }

    // ---- Two-phase read tests (task l2, `session-liveness` §4.1): the idle budget alone governs
    // waiting for the NEXT frame's first byte(s); once any byte arrives, a fresh frame deadline
    // governs completing the rest of that frame. ----

    @Test
    fun idleSessionDoesNotTimeOutWaitingForNextFrame() {
        val inbound = SessionFrame(sequence = 1, sessionId = "idle-ok", payload = SessionPayload.CameraControlCommand("ping", emptyMap()))
        val session = connect("s-l2-idle-ok-desktop", "s-l2-idle-ok-phone") { tls ->
            // Delayed well past what the frame deadline alone would tolerate, but still inside the
            // idle budget: proves the idle budget -- not the frame deadline -- governs this wait.
            Thread.sleep(800)
            val encoded = SessionFrameCodec.encode(inbound)
            tls.writeApplicationFrame(encoded.size.toBigEndianBytes() + encoded)
            tls.close()
        }

        val adapter = TlsSessionFrameIoAdapter(readTimeoutMillis = 300, idleBudgetMillis = 2_000)
        try {
            assertEquals(inbound, adapter.read(session.channel))
        } finally {
            session.channel.close()
            session.finish()
        }
    }

    @Test
    fun idleBeyondThresholdFailsAsPeerIdle() {
        val session = connect("s-l2-idle-fail-desktop", "s-l2-idle-fail-phone") { _ ->
            // Never sends anything, but stays alive well past the idle budget: if this thread exited
            // early instead, PipedInputStream would raise its own immediate "write end dead" failure,
            // which would mask the bounded-idle-budget path this test exists to prove (matching
            // UsbTrustedReconnectTest.reconnectTimesOutWithoutAccept's documented convention).
            Thread.sleep(1_000)
        }

        val adapter = TlsSessionFrameIoAdapter(readTimeoutMillis = 5_000, idleBudgetMillis = 300)
        try {
            val startNanos = System.nanoTime()
            val error = assertThrowsTlsSessionFrameIoException { adapter.read(session.channel) }
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            assertEquals(TlsSessionFrameIoFailureReason.PEER_IDLE, error.reason)
            // The lower bound tolerates timer granularity: the bounded read may time out a few
            // milliseconds before the nominal budget (millisecond truncation of the remaining time).
            // The upper bound is what proves the idle budget, not the 5000ms frame deadline, governed.
            assertTrue(
                "expected the idle budget (300ms) to govern, not the frame deadline (5000ms); took ${elapsedMillis}ms",
                elapsedMillis in 250..2_000,
            )
            assertTrue(session.pair.clientCloseable.closed)
        } finally {
            session.finish()
        }
    }

    @Test
    fun stalledFrameAfterFirstByteTimesOut() {
        val session = connect("s-l2-stall-desktop", "s-l2-stall-phone") { tls ->
            // Sends only the first byte of the length prefix, then goes silent (but stays alive,
            // same reasoning as idleBeyondThresholdFailsAsPeerIdle) well past the frame deadline.
            tls.writeApplicationFrame(byteArrayOf(0))
            Thread.sleep(1_000)
        }

        val adapter = TlsSessionFrameIoAdapter(readTimeoutMillis = 300, idleBudgetMillis = 5_000)
        try {
            val startNanos = System.nanoTime()
            val error = assertThrowsTlsSessionFrameIoException { adapter.read(session.channel) }
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            assertEquals(TlsSessionFrameIoFailureReason.GENERAL, error.reason)
            assertTrue(
                "expected the frame deadline (300ms) to govern once the first byte arrived, not the idle budget (5000ms); took ${elapsedMillis}ms",
                elapsedMillis in 0..2_000,
            )
            assertTrue(session.pair.clientCloseable.closed)
        } finally {
            session.finish()
        }
    }

    private fun assertThrowsTlsSessionFrameIoException(block: () -> Unit): TlsSessionFrameIoException {
        try {
            block()
        } catch (error: TlsSessionFrameIoException) {
            return error
        }
        fail("expected TlsSessionFrameIoException")
        error("unreachable")
    }

    private fun connect(
        desktopAlias: String,
        phoneAlias: String,
        serverAction: (ServerTlsChannel) -> Unit,
    ): ConnectedSession {
        val desktop = desktopFixture(desktopAlias)
        val phone = phoneFixture(phoneAlias)
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val tls = handshakeAsServer(desktop.context, pair.server)
                serverAction(tls)
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val handshake = SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(pair.client, desktop.spki)
        val channel = (handshake as SslEngineUsbTlsHandshakeResult.Authenticated).channel
        return ConnectedSession(channel, pair, server, serverError, serverCompleted)
    }

    private class ConnectedSession(
        val channel: SslEngineUsbTlsEstablishedChannel,
        val pair: SessionPair,
        private val server: Thread,
        private val serverError: AtomicReference<Throwable?>,
        private val serverCompleted: AtomicBoolean,
    ) {
        fun finish() {
            server.join(2_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    // ---- Fake-desktop JSSE harness: same pattern as UsbPairingFlowTest/SslEngineUsbTlsChannelTest
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
        val temp = createTempDir(prefix = "cc-tls-session-frame")
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
        private val engine: SSLEngine,
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

    /** Server-side [X509TrustManager] that accepts any presented phone client chain (the phone's real identity is already proven in [SslEngineUsbTlsChannelTest]; this harness only needs `needClientAuth` satisfied). */
    private object AcceptAnyClientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            throw CertificateException("not used: the client pins the server SPKI directly")

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
