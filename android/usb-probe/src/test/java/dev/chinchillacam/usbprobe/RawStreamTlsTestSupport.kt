package dev.chinchillacam.usbprobe

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Shared harness for the raw-stream ([StreamTlsCiphertextTransport]) tests (contract
 * `wifi-loopback-transport` §4, task w2): one crossed in-memory pipe pair, the keytool PKCS12
 * fake-desktop/phone TLS identities, and a fake desktop that speaks raw TLS records directly over
 * the stream -- with NO AccessoryFrame -- so the same phone-side TLS/session stack can be exercised
 * over a byte stream that models Wi-Fi LAN instead of USB. Kept in one place instead of copied into
 * each new test, matching §3's helper decision.
 */
object RawStreamTlsTestSupport {
    private const val PIPE_BUFFER_BYTES: Int = 256 * 1024
    private const val PACKET_BUFFER_BYTES: Int = 64 * 1024
    private const val PASSWORD: String = "changeit"

    /** [Closeable] that only records whether it was closed, to assert the fail-closed contract. */
    class RecordingCloseable : Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    /**
     * Crossed [PipedInputStream]/[PipedOutputStream] pair with generous buffers (contract §5: pipes
     * in memory, fat buffers to avoid "write end dead"/backpressure flakiness). The client side is
     * what [StreamTlsCiphertextTransport] drives; the server side is what [RawStreamServerPeer] reads.
     */
    class RawStreamEndpoints(
        val clientInput: InputStream,
        val clientOutput: OutputStream,
        val clientCloseable: RecordingCloseable,
        val serverInput: InputStream,
        val serverOutput: OutputStream,
        val serverCloseable: RecordingCloseable,
    )

    fun rawStreamPair(): RawStreamEndpoints {
        val clientIn = PipedInputStream(PIPE_BUFFER_BYTES)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(PIPE_BUFFER_BYTES)
        val clientOut = PipedOutputStream(serverIn)
        return RawStreamEndpoints(
            clientInput = clientIn,
            clientOutput = clientOut,
            clientCloseable = RecordingCloseable(),
            serverInput = serverIn,
            serverOutput = serverOut,
            serverCloseable = RecordingCloseable(),
        )
    }

    /** Builds the phone-side raw-stream ciphertext transport over the client end of [endpoints]. */
    fun clientTransport(endpoints: RawStreamEndpoints): StreamTlsCiphertextTransport =
        StreamTlsCiphertextTransport(endpoints.clientInput, endpoints.clientOutput, endpoints.clientCloseable)

    /** Builds the fake-desktop raw-stream TLS peer over the server end of [endpoints]. */
    fun serverPeer(endpoints: RawStreamEndpoints, desktop: DesktopFixture): RawStreamServerPeer =
        RawStreamServerPeer(desktop.context, endpoints.serverInput, endpoints.serverOutput, endpoints.serverCloseable)

    /** Fake desktop: a TLS server context plus the SubjectPublicKeyInfo DER the phone pins against. */
    class DesktopFixture(val context: SSLContext, val spki: ByteArray)

    fun desktopFixture(alias: String): DesktopFixture {
        val identity = keytoolIdentity(alias)
        val context = SSLContext.getInstance("TLS").apply {
            init(identity.keyManagers, arrayOf(AcceptAnyClientTrustManager), null)
        }
        return DesktopFixture(context, identity.certificate.publicKey.encoded)
    }

    fun phoneFixture(alias: String): PhoneTlsIdentity {
        val identity = keytoolIdentity(alias)
        return object : PhoneTlsIdentity {
            override fun keyManagers(): Array<KeyManager> = identity.keyManagers
            override val subjectPublicKeyInfoDer: ByteArray = identity.certificate.publicKey.encoded
        }
    }

    /** Length-prefixes (4-byte big-endian) a [SessionFrame] the same way [TlsSessionFrameIoAdapter] does. */
    fun encodeFramed(frame: SessionFrame): ByteArray {
        val encoded = SessionFrameCodec.encode(frame)
        return intToBigEndian(encoded.size) + encoded
    }

    fun decodeFramed(bytes: ByteArray): SessionFrame {
        val declaredLength = bigEndianToInt(bytes.copyOfRange(0, 4))
        require(bytes.size - 4 == declaredLength) { "framed payload length mismatch" }
        return SessionFrameCodec.decode(bytes.copyOfRange(4, bytes.size)).getOrThrow()
    }

    private fun intToBigEndian(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    private fun bigEndianToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)

    private fun keytoolIdentity(alias: String): KeytoolIdentity {
        val temp = createTempDir(prefix = "cc-raw-stream-tls")
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

    private class KeytoolIdentity(val keyManagers: Array<KeyManager>, val certificate: X509Certificate)

    /** Server-side trust manager that accepts any presented phone chain (the phone pins the server directly). */
    private object AcceptAnyClientTrustManager : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            throw CertificateException("not used: the client pins the server fingerprint directly")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Fake desktop that reads/writes raw TLS records straight off the byte stream -- no AccessoryFrame,
     * no [UsbTlsCiphertextIoAdapter] -- so it models the Wi-Fi wire from contract §4.1. Mirrors the
     * USB server harnesses' raw [SSLEngine] wrap/unwrap, but over the stream instead of the adapter.
     */
    class RawStreamServerPeer(
        context: SSLContext,
        private val input: InputStream,
        private val output: OutputStream,
        private val closeable: Closeable,
    ) {
        val engine: SSLEngine = context.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
        }
        private val peer = ByteBuffer.allocate(PACKET_BUFFER_BYTES)
        private val readBuffer = ByteArray(PACKET_BUFFER_BYTES)

        fun handshake() {
            engine.beginHandshake()
            val empty = ByteBuffer.allocate(0)
            val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
                engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
            ) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrapStep(app)
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach { it.run() }
                    else -> Unit
                }
            }
        }

        fun readApplicationFrame(): ByteArray {
            val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (app.position() == 0) unwrapStep(app)
            app.flip()
            return ByteArray(app.remaining()).also { app.get(it) }
        }

        fun writeApplicationFrame(bytes: ByteArray) = wrap(ByteBuffer.wrap(bytes))

        fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { closeable.close() }
        }

        private fun wrap(src: ByteBuffer) {
            val out = ByteBuffer.allocate(engine.session.packetBufferSize)
            engine.wrap(src, out)
            out.flip()
            if (out.hasRemaining()) {
                output.write(ByteArray(out.remaining()).also { out.get(it) })
                output.flush()
            }
        }

        private fun unwrapStep(app: ByteBuffer) {
            if (peer.position() == 0) fill()
            peer.flip()
            val result = engine.unwrap(peer, app)
            peer.compact()
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) fill()
        }

        private fun fill() {
            val read = input.read(readBuffer)
            if (read == -1) throw EOFException("raw stream peer reached end of input")
            require(read <= peer.remaining()) { "incoming record larger than the peer buffer" }
            peer.put(readBuffer, 0, read)
        }
    }
}
