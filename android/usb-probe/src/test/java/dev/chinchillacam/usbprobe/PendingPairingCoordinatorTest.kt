package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PendingPairingCoordinatorTest {
    @Test
    fun matchingVerifiedProofCreatesPendingConfirmationWithDefensiveBytes() {
        val verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge) }
        val coordinator = coordinator(verifier = verifier)
        val qr = qr(nonce = byteArrayOf(0x01, 0x02))
        val proof = byteArrayOf(0x55)

        val result = coordinator.start(qr, proof)

        val pending = result as PendingPairingStartResult.PendingConfirmation
        assertEquals("pc-1", pending.summary.desktopId)
        assertEquals("session-1", pending.summary.sessionId)
        assertEquals(PairingTrustFingerprint.fromTrustMaterial(qr.trustMaterial), pending.summary.trustMaterialFingerprint)
        assertArrayEquals(byteArrayOf(0x01, 0x02), pending.summary.qrNonce)
        assertArrayEquals(byteArrayOf(0x10), pending.summary.challengeNonce)
        proof[0] = 0x66
        assertArrayEquals(byteArrayOf(0x55), verifier.proofs.single())
        val firstNonceRead = pending.summary.qrNonce
        firstNonceRead[0] = 0x7f
        assertArrayEquals(byteArrayOf(0x01, 0x02), pending.summary.qrNonce)
        assertNotSame(firstNonceRead, pending.summary.qrNonce)
    }

    @Test
    fun unverifiedProofAndBindingMismatchDoNotCreatePending() {
        val rejected = coordinator(verifier = RecordingVerifier { _, _ -> PairingProofVerificationResult.Rejected("tls rejected") })
        assertEquals(PendingPairingStartResult.Rejected.ProofRejected("tls rejected"), rejected.start(qr(), byteArrayOf(1)))
        assertEquals(PendingPairingState.Idle, rejected.state())

        val mismatch = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(desktopId = "other") })
        assertEquals(PendingPairingStartResult.Rejected.BindingMismatch("desktopId"), mismatch.start(qr(), byteArrayOf(1)))
        assertEquals(PendingPairingState.Idle, mismatch.state())
    }

    @Test
    fun expiredQrProofOrChallengeAreRejected() {
        assertTrue(coordinator().start(qr(expiresAt = 100), byteArrayOf(1)) is PendingPairingStartResult.Rejected.Expired)

        val expiredProof = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(expiresAtEpochSeconds = 100) })
        assertTrue(expiredProof.start(qr(), byteArrayOf(1)) is PendingPairingStartResult.Rejected.Expired)

        val expiredChallenge = coordinator(challenges = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(1), "s", expiresAtEpochSeconds = 100)))
        assertTrue(expiredChallenge.start(qr(), byteArrayOf(1)) is PendingPairingStartResult.Rejected.Expired)
    }

    @Test
    fun onePendingAtATimeCancelAndNonceReplayAreTyped() {
        val coordinator = coordinator()
        assertTrue(coordinator.start(qr(nonce = byteArrayOf(1)), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)
        assertEquals(PendingPairingStartResult.Rejected.AlreadyPending, coordinator.start(qr(nonce = byteArrayOf(2)), byteArrayOf(1)))
        assertEquals(PendingPairingCancelResult.Cancelled, coordinator.cancel())
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(nonce = byteArrayOf(1)), byteArrayOf(1)))
        assertEquals(PendingPairingState.Idle, coordinator.state())
    }

    @Test
    fun nonceCacheCapacityFailsClosedWithoutEvictingLiveNonces() {
        val coordinator = coordinator(challenges = CountingChallengeSource())
        repeat(64) { index ->
            assertTrue(coordinator.start(qr(nonce = byteArrayOf(index.toByte())), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)
            coordinator.cancel()
        }

        assertEquals(PendingPairingStartResult.Rejected.NonceCacheFull, coordinator.start(qr(nonce = byteArrayOf(64)), byteArrayOf(1)))
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(nonce = byteArrayOf(0)), byteArrayOf(1)))
    }

    private fun coordinator(
        verifier: PairingProofVerifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge) },
        challenges: ChallengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", expiresAtEpochSeconds = 200)),
    ) = PendingPairingCoordinator(
        clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC),
        challengeNonceSource = challenges,
        proofVerifier = verifier,
    )

    private fun qr(
        nonce: ByteArray = byteArrayOf(0x01),
        expiresAt: Long = 150,
    ) = PairingQrPayload(
        desktopId = "pc-1",
        desktopName = "Studio",
        trustMaterial = byteArrayOf(0x05, 0x06),
        expiresAtEpochSeconds = expiresAt,
        nonce = nonce,
    )

    private class RecordingVerifier(
        private val response: (PairingProofChallenge, ByteArray) -> PairingProofVerificationResult,
    ) : PairingProofVerifier {
        val proofs = mutableListOf<ByteArray>()
        override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult {
            proofs += proofBytes.copyOf()
            return response(challenge, proofBytes)
        }
    }

    private class QueueChallengeSource(
        private val challenge: PairingChallengeMaterial,
    ) : ChallengeNonceSource {
        override fun nextChallenge(): PairingChallengeMaterial = challenge
    }

    private class CountingChallengeSource : ChallengeNonceSource {
        private var next = 0
        override fun nextChallenge(): PairingChallengeMaterial = PairingChallengeMaterial(byteArrayOf((next++).toByte()), "session-$next", 200)
    }

    private companion object {
        fun verifiedFrom(challenge: PairingProofChallenge) = PairingProofVerificationResult.Verified(
            desktopId = challenge.desktopId,
            trustMaterialFingerprint = challenge.trustMaterialFingerprint,
            qrNonce = challenge.qrNonce,
            challengeNonce = challenge.challengeNonce,
            sessionId = challenge.sessionId,
            verifiedAtEpochSeconds = 100,
            expiresAtEpochSeconds = 150,
        )
    }
}
