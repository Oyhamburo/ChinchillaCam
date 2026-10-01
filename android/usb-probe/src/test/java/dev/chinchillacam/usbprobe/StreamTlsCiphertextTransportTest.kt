package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task w2 (`odd/tasks/wifi-loopback-transport.md` §4, §7): the same phone-side mTLS/session
 * stack that runs over USB must run unchanged over [StreamTlsCiphertextTransport], a raw byte stream
 * that models Wi-Fi LAN -- TLS records written directly to the stream, no AccessoryFrame. The fake
 * desktop ([RawStreamTlsTestSupport.RawStreamServerPeer]) speaks raw TLS records over the same pipes.
 */
class StreamTlsCiphertextTransportTest {
    @Test
    fun pinnedHandshakeOverRawStreamTransport() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("w2-pinned-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("w2-pinned-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                assertTrue(peer.readApplicationFrame().contentEquals("ping".toByteArray()))
                peer.writeApplicationFrame("pong".toByteArray())
                // Stay alive until the client has read the response, then close explicitly so the
                // pipe writer end never dies mid-flight (contract §5).
                Thread.sleep(200)
                peer.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        val result = SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(transport, desktop.spki)

        try {
            assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
            result as SslEngineUsbTlsHandshakeResult.Authenticated
            assertTrue(result.protocol == "TLSv1.2" || result.protocol == "TLSv1.3")
            result.channel.writeApplicationData("ping".toByteArray())
            val echoed = result.channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(4))
            assertTrue(echoed.contentEquals("pong".toByteArray()))
            result.close()
        } finally {
            server.join(4_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun sessionFramesRoundTripOverRawStreamTransport() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("w2-frames-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("w2-frames-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)

        val request = SessionFrame(
            sequence = 7,
            sessionId = "raw-stream-session",
            payload = SessionPayload.CameraControlCommand("start", mapOf("fps" to "30")),
        )
        val response = SessionFrame(
            sequence = 8,
            sessionId = "raw-stream-session",
            payload = SessionPayload.HandshakeAccept("pc-raw", "frames ok"),
        )

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                assertEquals(request, RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame()))
                peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(response))
                Thread.sleep(200)
                peer.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        val result = SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(transport, desktop.spki)
            as SslEngineUsbTlsHandshakeResult.Authenticated
        val adapter = TlsSessionFrameIoAdapter()

        try {
            adapter.write(result.channel, request)
            assertEquals(response, adapter.read(result.channel))
            result.close()
        } finally {
            server.join(4_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun trustedReconnectOverRawStreamTransport() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("w2-reconnect-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("w2-reconnect-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val expectedPhoneId = PairingTrustFingerprint.fromTrustMaterial(phone.subjectPublicKeyInfoDer).hex
        val ping = SessionFrame(
            sequence = 100,
            sessionId = "reconnect-raw-ping",
            payload = SessionPayload.CameraControlCommand("ping", emptyMap()),
        )

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val helloFrame = RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
                val hello = helloFrame.payload as SessionPayload.HandshakeHello
                assertEquals(expectedPhoneId, hello.deviceId)
                val accept = SessionFrame(
                    sequence = helloFrame.sequence + 1,
                    sessionId = helloFrame.sessionId,
                    payload = SessionPayload.HandshakeAccept("pc-raw", "welcome back"),
                )
                peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(accept))
                assertEquals(ping, RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame()))
                Thread.sleep(200)
                peer.close()
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val store = InMemoryTrustedDesktopStore().apply {
            save(
                TrustedDesktopRecord(
                    desktopId = "pc-raw",
                    desktopName = "Studio",
                    trustMaterialFingerprint = PairingTrustFingerprint.fromTrustMaterial(desktop.spki).bytes,
                    createdAtEpochSeconds = 1_000,
                    lastSeenAtEpochSeconds = 1_000,
                ),
            )
        }
        val authority = ActiveDesktopAuthority()
        val reconnect = UsbTrustedReconnect(epochSecondsSource = { 2_000 }, phoneTlsIdentity = phone)
        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        var channel: SslEngineUsbTlsEstablishedChannel? = null

        try {
            val result = reconnect.reconnect("pc-raw", transport, store, authority)
            val reconnected = result as UsbTrustedReconnectResult.Reconnected
            assertEquals("pc-raw", reconnected.desktopId)
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-raw"), authority.state)
            channel = reconnected.channel
            reconnected.frameAdapter.write(reconnected.channel, ping)
        } finally {
            channel?.let { runCatching { it.close() } }
            server.join(4_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun rawStreamTransportFailsClosedOnPeerEof() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("w2-eof-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("w2-eof-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()

        // The peer closes its streams without ever completing the handshake: the phone's handshake
        // read must observe end-of-stream and fail closed (never hang) rather than authenticate.
        val server = thread {
            val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
            runCatching { peer.readApplicationFrame() }
            peer.close()
        }

        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        val result = SslEngineUsbTlsChannel(phoneTlsIdentity = phone, readTimeoutMillis = 4_000)
            .handshake(transport, desktop.spki)

        assertTrue("expected a fail-closed rejection, got $result", result is SslEngineUsbTlsHandshakeResult.Rejected)
        assertTrue("transport must be closed after a fail-closed handshake", endpoints.clientCloseable.closed)

        // A read on the already-closed transport reports end-of-stream, not a hang or application bytes.
        val read = transport.readCiphertext()
        assertTrue(
            "expected Eof/Failed after peer EOF, got $read",
            read is TlsCiphertextReadResult.Eof || read is TlsCiphertextReadResult.Failed,
        )
        server.join(4_000)
    }
}
