package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task c2 (`odd/tasks/android-production-connection.md` §3.2, §4.2): after
 * [UsbPairingFlow.confirm] activates a pairing, [PairedSessionStarter] starts the session on the
 * same live channel with `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT`, exercised over the raw-stream
 * transport ([RawStreamTlsTestSupport]) against a fake desktop that answers CCP1 and the hello.
 */
class PairedSessionStarterTest {
    @Test
    fun confirmedPairingChannelStartsSessionAfterDesktopAccept() {
        val ping = SessionFrame(sequence = 1, sessionId = "unused", payload = SessionPayload.Keepalive)
        val helloSessionId = AtomicReference<String?>(null)
        val run = pairThenStart(alias = "c2-accept", starterTimeoutMillis = 120_000) { peer, phoneId ->
            val helloFrame = RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
            assertEquals(phoneId, (helloFrame.payload as SessionPayload.HandshakeHello).deviceId)
            assertEquals(listOf(QUALITY_CONTROL_CAPABILITY), (helloFrame.payload as SessionPayload.HandshakeHello).capabilities)
            helloSessionId.set(helloFrame.sessionId)
            val accept = helloFrame.copy(sequence = helloFrame.sequence + 1, payload = SessionPayload.HandshakeAccept("pc-1", "welcome"))
            peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(accept))
            val next = RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
            assertEquals(SessionPayload.Keepalive, next.payload)
            assertEquals(helloFrame.sessionId, next.sessionId)
            assertEquals(helloFrame.sequence + 1, next.sequence)
        }

        try {
            val started = run.result as PairedSessionStartResult.Started
            val reconnected = started.reconnected
            assertEquals("pc-1", reconnected.desktopId)
            assertEquals(helloSessionId.get(), reconnected.sessionId)
            assertEquals(1, reconnected.nextOutboundSequence)
            assertEquals(2, reconnected.nextInboundSequence)
            assertTrue(reconnected.channel === run.channel)
            reconnected.frameAdapter.write(
                reconnected.channel,
                ping.copy(sequence = reconnected.nextOutboundSequence, sessionId = reconnected.sessionId),
            )
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), run.authority.state)
        } finally {
            run.finish()
        }
    }

    @Test
    fun desktopRejectClosesChannel() {
        val run = pairThenStart(alias = "c2-reject", starterTimeoutMillis = 120_000) { peer, _ ->
            val helloFrame = RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
            val reject = helloFrame.copy(sequence = helloFrame.sequence + 1, payload = SessionPayload.HandshakeReject("PAIRING_DECLINED", "no"))
            peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(reject))
            runCatching { peer.readApplicationFrame() }
        }

        try {
            val rejected = run.result as PairedSessionStartResult.Rejected
            assertEquals(UsbTrustedReconnectResult.Rejected.DesktopRejected("pc-1", "PAIRING_DECLINED", "no"), rejected.rejection)
            assertTrue("a rejected start must close the channel", run.endpoints.clientCloseable.closed)
            // The starter never touches the authority: releasing it is the caller's job.
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), run.authority.state)
        } finally {
            run.finish()
        }
    }

    @Test
    fun silentDesktopTimesOutAndClosesChannel() {
        val run = pairThenStart(alias = "c2-silent", starterTimeoutMillis = 300) { peer, _ ->
            RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
            // Never answers; returns once the phone closes its end.
            runCatching { peer.readApplicationFrame() }
        }

        try {
            assertEquals(PairedSessionStartResult.Rejected(UsbTrustedReconnectResult.Rejected.TimedOut("pc-1")), run.result)
            assertTrue("a timed-out start must close the channel", run.endpoints.clientCloseable.closed)
            // Well under the session adapter's 5s/6s defaults: the configured deadline is the one honored.
            assertTrue("start took ${run.startMillis}ms", run.startMillis < 3_000)
        } finally {
            run.finish()
        }
    }

    private class Run(
        val result: PairedSessionStartResult,
        val channel: SslEngineUsbTlsEstablishedChannel,
        val endpoints: RawStreamTlsTestSupport.RawStreamEndpoints,
        val authority: ActiveDesktopAuthority,
        val startMillis: Long,
        private val server: Thread,
        private val serverError: AtomicReference<Throwable?>,
    ) {
        fun finish() {
            runCatching { channel.close() }
            server.join(4_000)
            serverError.get()?.let { throw it }
            assertTrue("the fake desktop must not hang", !server.isAlive)
        }
    }

    /** Real pairing start + confirm over a raw stream, then [PairedSessionStarter.start] on the confirmed channel. */
    private fun pairThenStart(
        alias: String,
        starterTimeoutMillis: Long,
        desktopAfterPairing: (RawStreamTlsTestSupport.RawStreamServerPeer, String) -> Unit,
    ): Run {
        val desktop = RawStreamTlsTestSupport.desktopFixture("$alias-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("$alias-phone")
        val phoneId = PairingTrustFingerprint.fromTrustMaterial(phone.subjectPublicKeyInfoDer).hex
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val server = thread {
            val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
            try {
                peer.handshake()
                val request = PairingProofProtocol.parseRequest(peer.readApplicationFrame())
                peer.writeApplicationFrame(
                    PairingProofProtocol.encodeResponse(
                        PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId),
                    ),
                )
                desktopAfterPairing(peer, phoneId)
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                peer.close()
            }
        }

        val clock = EpochSecondsSource { 1_000 }
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = clock,
            challengeNonceSource = object : ChallengeNonceSource {
                override fun nextChallenge() = PairingChallengeMaterial(CHALLENGE_NONCE, "session-1", expiresAtEpochSeconds = 1_200)
            },
            proofVerifier = object : PairingProofVerifier {
                override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult =
                    error("byte-based proof path must not be used")
            },
        )
        val flow = UsbPairingFlow(coordinator, UsbTlsPairingProofVerifier(clock, SslEngineUsbTlsChannel(phoneTlsIdentity = phone)))
        val qr = PairingQrPayload("pc-1", "Studio", desktop.spki.copyOf(), expiresAtEpochSeconds = 1_200, nonce = QR_NONCE)
        val authority = ActiveDesktopAuthority()
        val started = flow.start(qr, RawStreamTlsTestSupport.clientTransport(endpoints))
        val pendingId = (started.result as PendingPairingStartResult.PendingConfirmation).summary.pendingId
        val confirmed = flow.confirm(pendingId, InMemoryTrustedDesktopStore(), authority)
        assertEquals(PendingPairingConfirmResult.Activated("pc-1"), confirmed.result)
        val channel = requireNotNull(confirmed.channel)

        val startNanos = System.nanoTime()
        val result = PairedSessionStarter(phoneTlsIdentity = phone, helloTimeoutMillis = starterTimeoutMillis).start("pc-1", channel)
        val startMillis = (System.nanoTime() - startNanos) / 1_000_000
        return Run(result, channel, endpoints, authority, startMillis, server, serverError)
    }

    private companion object {
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
    }
}
