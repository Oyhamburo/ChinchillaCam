package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task g1 (`odd/tasks/android-followups.md` §4.1): the application-level pairing path
 * ([UsbPairingFlow] → [PendingPairingCoordinator] → [UsbTlsPairingProofVerifier]) runs over any
 * [TlsCiphertextTransport], not only an [AccessoryIoSession]. Exercised over the raw-stream
 * transport ([RawStreamTlsTestSupport]) against a fake desktop that speaks raw TLS records and
 * answers the CCP1 proof protocol, with no AccessoryFrame anywhere on the wire.
 */
class UsbPairingFlowRawStreamTest {
    @Test
    fun pairingFlowCompletesOverRawStreamTransport() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("g1-complete-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("g1-complete-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val request = PairingProofProtocol.parseRequest(peer.readApplicationFrame())
                peer.writeApplicationFrame(statusZeroResponse(request))
                assertArrayEquals(POST_CONFIRM_PONG, peer.readApplicationFrame())
                // Stay alive until the phone is done, then close explicitly (contract §5 pipes).
                Thread.sleep(200)
                peer.close()
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
            val started = flow.start(qr(desktop.spki), RawStreamTlsTestSupport.clientTransport(endpoints))
            val pendingSummary = (started.result as PendingPairingStartResult.PendingConfirmation).summary
            assertTrue(flow.state() is PendingPairingState.PendingConfirmation)
            val held = requireNotNull(started.channel) { "a PendingConfirmation must retain a live channel" }
            assertFalse("the live channel's transport must stay open while pending", endpoints.clientCloseable.closed)

            val outcome = flow.confirm(pendingSummary.pendingId, store, authority)
            assertEquals(PendingPairingConfirmResult.Activated("pc-1"), outcome.result)
            channel = requireNotNull(outcome.channel) { "an activated pairing must hand back its live channel" }
            assertTrue(channel === held)
            channel.writeApplicationData(POST_CONFIRM_PONG)

            val stored = requireNotNull(store.lookup("pc-1"))
            assertEquals("Studio", stored.desktopName)
            assertArrayEquals(PairingTrustFingerprint.fromTrustMaterial(desktop.spki).bytes, stored.trustMaterialFingerprint)
            assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
            assertEquals(PendingPairingState.Idle, flow.state())
        } finally {
            channel?.let { runCatching { it.close() } }
            server.join(4_000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
        }
    }

    @Test
    fun pairingFlowOverRawStreamRejectsWrongDesktopKey() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("g1-wrong-key-desktop")
        val impostorPin = RawStreamTlsTestSupport.desktopFixture("g1-wrong-key-other")
        val phone = RawStreamTlsTestSupport.phoneFixture("g1-wrong-key-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val server = thread {
            val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
            // The phone aborts the handshake on the pin mismatch and closes its end, so the fake
            // desktop observes an alert or end-of-stream; either way it must not hang.
            runCatching { peer.handshake() }
            peer.close()
        }

        val clock = MutableEpochSecondsSource(1_000)
        val flow = UsbPairingFlow(newCoordinator(clock), tlsVerifier(phone, clock))
        val store = InMemoryTrustedDesktopStore()

        try {
            // QR pins a different desktop key than the one the peer actually presents.
            val started = flow.start(qr(impostorPin.spki), RawStreamTlsTestSupport.clientTransport(endpoints))
            assertTrue("expected ProofRejected, got ${started.result}", started.result is PendingPairingStartResult.Rejected.ProofRejected)
            assertNull(started.channel)
            assertTrue("a rejected pairing must close its transport", endpoints.clientCloseable.closed)
            assertEquals(PendingPairingState.Idle, flow.state())
            assertNull(store.lookup("pc-1"))
        } finally {
            server.join(4_000)
        }
        assertFalse("the fake desktop must not hang on a rejected handshake", server.isAlive)
    }

    @Test
    fun transportStartWithoutTransportVerifierFailsBeforeTouchingTransport() {
        val desktop = RawStreamTlsTestSupport.desktopFixture("g1-no-verifier-desktop")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val clock = MutableEpochSecondsSource(1_000)
        val sessionOnly = ChannelPairingProofVerifier { _, _ -> error("session verifier must not be used for a transport start") }
        val flow = UsbPairingFlow(newCoordinator(clock), sessionOnly)

        val failure = runCatching { flow.start(qr(desktop.spki), RawStreamTlsTestSupport.clientTransport(endpoints)) }

        assertTrue("expected IllegalStateException, got $failure", failure.exceptionOrNull() is IllegalStateException)
        assertFalse(endpoints.clientCloseable.closed)
        assertEquals(PendingPairingState.Idle, flow.state())
    }

    private fun newCoordinator(clock: EpochSecondsSource): PendingPairingCoordinator = PendingPairingCoordinator(
        epochSecondsSource = clock,
        challengeNonceSource = FixedChallengeSource(PairingChallengeMaterial(CHALLENGE_NONCE, "session-1", expiresAtEpochSeconds = 1_200)),
        proofVerifier = NeverCalledProofVerifier,
    )

    private fun tlsVerifier(phoneIdentity: PhoneTlsIdentity, clock: EpochSecondsSource): UsbTlsPairingProofVerifier =
        UsbTlsPairingProofVerifier(clock, SslEngineUsbTlsChannel(phoneTlsIdentity = phoneIdentity))

    private fun qr(desktopSpki: ByteArray) = PairingQrPayload(
        desktopId = "pc-1",
        desktopName = "Studio",
        trustMaterial = desktopSpki.copyOf(),
        expiresAtEpochSeconds = 1_200,
        nonce = QR_NONCE,
    )

    private fun statusZeroResponse(request: PairingProofProtocol.ProofRequest): ByteArray =
        PairingProofProtocol.encodeResponse(
            PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId),
        )

    private class FixedChallengeSource(private val challenge: PairingChallengeMaterial) : ChallengeNonceSource {
        override fun nextChallenge(): PairingChallengeMaterial = challenge
    }

    private class MutableEpochSecondsSource(var now: Long) : EpochSecondsSource {
        override fun nowEpochSeconds(): Long = now
    }

    private object NeverCalledProofVerifier : PairingProofVerifier {
        override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult =
            error("byte-based proof path must not be used by UsbPairingFlow")
    }

    private companion object {
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
        val POST_CONFIRM_PONG = byteArrayOf(0x50, 0x4f, 0x4e, 0x47)
    }
}
