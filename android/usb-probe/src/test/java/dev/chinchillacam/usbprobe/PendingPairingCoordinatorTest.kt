package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

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
        assertArrayEquals(P256_SPKI, verifier.challenges.single().desktopSubjectPublicKeyInfoDer)
        assertEquals(PairingTrustFingerprint.fromTrustMaterial(verifier.challenges.single().desktopSubjectPublicKeyInfoDer), verifier.challenges.single().trustMaterialFingerprint)
        val firstSpkiRead = verifier.challenges.single().desktopSubjectPublicKeyInfoDer
        firstSpkiRead[0] = 0x00
        assertArrayEquals(P256_SPKI, verifier.challenges.single().desktopSubjectPublicKeyInfoDer)
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


    @Test
    fun rejectedOrMismatchedProofConsumesQrNonceUntilQrExpiry() {
        val rejected = coordinator(verifier = RecordingVerifier { _, _ -> PairingProofVerificationResult.Rejected("tls rejected") })
        assertEquals(PendingPairingStartResult.Rejected.ProofRejected("tls rejected"), rejected.start(qr(nonce = byteArrayOf(9)), byteArrayOf(1)))
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, rejected.start(qr(nonce = byteArrayOf(9)), byteArrayOf(1)))

        val mismatch = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(sessionId = "other") })
        assertEquals(PendingPairingStartResult.Rejected.BindingMismatch("sessionId"), mismatch.start(qr(nonce = byteArrayOf(10)), byteArrayOf(1)))
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, mismatch.start(qr(nonce = byteArrayOf(10)), byteArrayOf(1)))
    }

    @Test
    fun qrNonceReplayUsesQrLifetimeEvenWhenProofExpiresFirst() {
        val coordinator = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(expiresAtEpochSeconds = 110) })
        assertTrue(coordinator.start(qr(nonce = byteArrayOf(11), expiresAt = 200), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)
        coordinator.cancel()

        val later = PendingPairingCoordinator(
            epochSecondsSource = EpochSecondsSource { 120 },
            challengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", expiresAtEpochSeconds = 200)),
            proofVerifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(verifiedAtEpochSeconds = 120, expiresAtEpochSeconds = 190) },
        )
        // Same coordinator memory only: prove via original coordinator clock-independent nonce cache by using a proof that would otherwise be valid now.
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(nonce = byteArrayOf(11), expiresAt = 200), byteArrayOf(1)))
        assertTrue(later.start(qr(nonce = byteArrayOf(11), expiresAt = 200), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)
    }

    @Test
    fun rejectsInvalidVerifiedAtAndConstructorCap() {
        val future = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(verifiedAtEpochSeconds = 101) })
        assertEquals(PendingPairingStartResult.Rejected.InvalidProofTime("verifiedAt"), future.start(qr(nonce = byteArrayOf(12)), byteArrayOf(1)))

        val negative = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(verifiedAtEpochSeconds = -1) })
        assertEquals(PendingPairingStartResult.Rejected.InvalidProofTime("verifiedAt"), negative.start(qr(nonce = byteArrayOf(13)), byteArrayOf(1)))

        val atExpiry = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(verifiedAtEpochSeconds = 150, expiresAtEpochSeconds = 150) })
        assertEquals(PendingPairingStartResult.Rejected.InvalidProofTime("verifiedAt"), atExpiry.start(qr(nonce = byteArrayOf(14)), byteArrayOf(1)))

        assertTrue(runCatching { PendingPairingCoordinator(EpochSecondsSource { 100 }, QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(1), "s", 200)), RecordingVerifier { challenge, _ -> verifiedFrom(challenge) }, maxLiveNonces = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PendingPairingCoordinator(EpochSecondsSource { 100 }, QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(1), "s", 200)), RecordingVerifier { challenge, _ -> verifiedFrom(challenge) }, maxLiveNonces = 65) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun invalidDirectQrMetadataReturnsTypedRejection() {
        assertEquals(PendingPairingStartResult.Rejected.InvalidQr("desktopId"), coordinator().start(qr().copy(desktopId = ""), byteArrayOf(1)))
        assertEquals(PendingPairingStartResult.Rejected.InvalidQr("nonce"), coordinator().start(qr(nonce = byteArrayOf()), byteArrayOf(1)))
        assertEquals(PendingPairingStartResult.Rejected.InvalidQr("trustMaterial"), coordinator().start(qr().copy(trustMaterial = byteArrayOf()), byteArrayOf(1)))
    }

    @Test
    fun challengeSourceFailureRejectsWithoutProofOrPersistence() {
        val verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge) }
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = EpochSecondsSource { 100 },
            challengeNonceSource = SecureRandomChallengeNonceSource.forTesting(
                epochSecondsSource = EpochSecondsSource { -1 },
                ttlSeconds = 30,
            ) { target -> target.fill(0x01) },
            proofVerifier = verifier,
        )

        assertEquals(PendingPairingStartResult.Rejected.ChallengeSourceRejected("clock must not be negative"), coordinator.start(qr(), byteArrayOf(1)))
        assertEquals(emptyList<ByteArray>(), verifier.proofs)
        assertEquals(PendingPairingState.Idle, coordinator.state())
    }

    @Test
    fun invalidTrustMaterialIsRejectedBeforeChallengeProofOrPersistenceAndDoesNotConsumeNonce() {
        val challenges = RecordingChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", expiresAtEpochSeconds = 200))
        val verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge) }
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = EpochSecondsSource { 100 },
            challengeNonceSource = challenges,
            proofVerifier = verifier,
        )
        val invalidQr = qr(nonce = byteArrayOf(0x41)).copy(trustMaterial = byteArrayOf(0x01, 0x02, 0x03))
        val store = RecordingTrustedDesktopStore()

        assertEquals(PendingPairingStartResult.Rejected.InvalidQr("trustMaterial"), coordinator.start(invalidQr, byteArrayOf(1)))
        assertEquals(0, challenges.requests)
        assertEquals(emptyList<ByteArray>(), verifier.proofs)
        assertEquals(PendingPairingState.Idle, coordinator.state())
        assertEquals(PendingPairingConfirmResult.Rejected.NoPendingPairing, coordinator.confirm("invalid", store, ActiveDesktopAuthority()))
        assertEquals(emptyList<TrustedDesktopRecord>(), store.saved)

        assertTrue(coordinator.start(qr(nonce = byteArrayOf(0x41)), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)
        assertEquals(1, challenges.requests)
        assertEquals(1, verifier.proofs.size)
    }


    @Test
    fun snapshotsMutableQrBytesBeforeVerifierCallback() {
        lateinit var qr: PairingQrPayload
        val verifier = RecordingVerifier { challenge, _ ->
            qr.nonce[0] = 0x7f
            qr.trustMaterial[0] = 0x7e
            verifiedFrom(challenge)
        }
        val coordinator = coordinator(verifier = verifier)
        qr = qr(nonce = byteArrayOf(0x21), expiresAt = 200)

        val result = coordinator.start(qr, byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation

        assertArrayEquals(byteArrayOf(0x21), result.summary.qrNonce)
        assertEquals(PairingTrustFingerprint.fromTrustMaterial(P256_SPKI), result.summary.trustMaterialFingerprint)
        coordinator.cancel()
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(nonce = byteArrayOf(0x21), expiresAt = 200), byteArrayOf(1)))
    }


    @Test
    fun expiryDuringVerifierRejectsButKeepsNonceConsumedUntilQrExpiry() {
        val clock = MutableEpochSecondsSource(100)
        val verifier = RecordingVerifier { challenge, _ ->
            clock.now = 151
            verifiedFrom(challenge).copy(verifiedAtEpochSeconds = 100, expiresAtEpochSeconds = 160)
        }
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = clock,
            challengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", expiresAtEpochSeconds = 200)),
            proofVerifier = verifier,
        )

        assertEquals(PendingPairingStartResult.Rejected.Expired("qr"), coordinator.start(qr(nonce = byteArrayOf(0x31), expiresAt = 150), byteArrayOf(1)))
        clock.now = 120
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(nonce = byteArrayOf(0x31), expiresAt = 150), byteArrayOf(1)))
    }



    @Test
    fun confirmedTrustOutlivesEphemeralProofExpiryUntilExplicitRevocation() {
        val clock = MutableEpochSecondsSource(100)
        val store = RecordingTrustedDesktopStore()
        val authority = ActiveDesktopAuthority()
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = clock,
            challengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", 120)),
            proofVerifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(expiresAtEpochSeconds = 115) },
        )
        val pending = coordinator.start(qr(expiresAt = 130), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation

        assertEquals(PendingPairingConfirmResult.Activated("pc-1"), coordinator.confirm(pending.summary.pendingId, store, authority))
        clock.now = 200

        assertEquals(TrustedDesktopAuthResult.Trusted, store.evaluate("pc-1", PairingTrustFingerprint.fromTrustMaterial(P256_SPKI).bytes, nowEpochSeconds = 200))
        assertEquals(null, store.lookup("pc-1")?.expiresAtEpochSeconds)
    }

    @Test
    fun invalidDesktopIdIsTypedInvalidQrBeforeStoreLookupCanThrow() {
        val coordinator = coordinator()

        val result = coordinator.start(qr().copy(desktopId = "bad id"), byteArrayOf(1))

        assertEquals(PendingPairingStartResult.Rejected.InvalidQr("desktopId"), result)
    }

    @Test
    fun noConfirmOrPendingIdMismatchDoesNotSaveOrActivate() {
        val coordinator = coordinator()
        val pending = coordinator.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        val store = RecordingTrustedDesktopStore()
        val authority = ActiveDesktopAuthority()

        assertEquals(emptyList<TrustedDesktopRecord>(), store.saved)
        assertEquals(PendingPairingConfirmResult.Rejected.PendingIdMismatch, coordinator.confirm("wrong", store, authority))

        assertEquals(emptyList<TrustedDesktopRecord>(), store.saved)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertTrue(coordinator.state() is PendingPairingState.PendingConfirmation)
        assertEquals(PendingPairingCancelResult.Cancelled, coordinator.cancel())
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(), byteArrayOf(1)))
        assertTrue(pending.summary.pendingId.isNotBlank())
    }

    @Test
    fun expiredConfirmClearsPendingWithoutTrustOrActivation() {
        val clock = MutableEpochSecondsSource(100)
        val coordinator = PendingPairingCoordinator(clock, QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", 120)), RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(expiresAtEpochSeconds = 115) })
        val pending = coordinator.start(qr(expiresAt = 130), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        val store = RecordingTrustedDesktopStore()
        val authority = ActiveDesktopAuthority()

        clock.now = 116
        assertEquals(PendingPairingConfirmResult.Rejected.Expired, coordinator.confirm(pending.summary.pendingId, store, authority))

        assertEquals(emptyList<TrustedDesktopRecord>(), store.saved)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertEquals(PendingPairingState.Idle, coordinator.state())
        clock.now = 117
        assertEquals(PendingPairingStartResult.Rejected.NonceReplay, coordinator.start(qr(expiresAt = 130), byteArrayOf(1)))
    }

    @Test
    fun confirmRejectsRevokedOrDifferentFingerprintWithoutOverwrite() {
        val revokedStore = RecordingTrustedDesktopStore(existing = trustedRecord(revokedAt = 110))
        val revokedCoordinator = coordinator()
        val pending = revokedCoordinator.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        assertEquals(PendingPairingConfirmResult.Rejected.ExistingTrustRejected(TrustedDesktopAuthResult.Revoked), revokedCoordinator.confirm(pending.summary.pendingId, revokedStore, ActiveDesktopAuthority()))
        assertEquals(emptyList<TrustedDesktopRecord>(), revokedStore.saved)

        val mismatchStore = RecordingTrustedDesktopStore(existing = trustedRecord(fingerprint = byteArrayOf(0x7f)))
        val mismatchCoordinator = coordinator()
        val mismatchPending = mismatchCoordinator.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        assertEquals(PendingPairingConfirmResult.Rejected.ExistingTrustRejected(TrustedDesktopAuthResult.FingerprintMismatch), mismatchCoordinator.confirm(mismatchPending.summary.pendingId, mismatchStore, ActiveDesktopAuthority()))
        assertEquals(emptyList<TrustedDesktopRecord>(), mismatchStore.saved)
    }


    @Test
    fun stateDoesNotExposeExpiredPendingAsActionable() {
        val clock = MutableEpochSecondsSource(100)
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = clock,
            challengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", 120)),
            proofVerifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(expiresAtEpochSeconds = 115) },
        )
        assertTrue(coordinator.start(qr(expiresAt = 130), byteArrayOf(1)) is PendingPairingStartResult.PendingConfirmation)

        clock.now = 116

        assertEquals(PendingPairingState.Idle, coordinator.state())
    }

    @Test
    fun saveFailurePreventsActivation() {
        val coordinator = coordinator()
        val pending = coordinator.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        val store = RecordingTrustedDesktopStore(saveFailure = IllegalStateException("disk full"))
        val authority = ActiveDesktopAuthority()

        assertEquals(PendingPairingConfirmResult.Rejected.SaveFailed("disk full"), coordinator.confirm(pending.summary.pendingId, store, authority))

        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertEquals(1, store.saveAttempts)
    }

    @Test
    fun persistentConfirmSurvivesReloadWithoutActivatingOnSaveFailureOrRevokedRecord() {
        val storage = MutableSerializedTrustedDesktopStorage()
        val store = LocalPersistentTrustedDesktopStore(storage)
        val authority = ActiveDesktopAuthority()
        val first = coordinator()
        val firstPending = first.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation

        assertEquals(PendingPairingConfirmResult.Activated("pc-1"), first.confirm(firstPending.summary.pendingId, store, authority))
        assertEquals(TrustedDesktopAuthResult.Trusted, LocalPersistentTrustedDesktopStore(storage).evaluate("pc-1", PairingTrustFingerprint.fromTrustMaterial(P256_SPKI).bytes, nowEpochSeconds = 100))

        val failingStorage = FailingSerializedTrustedDesktopStorage()
        val failing = coordinator()
        val failingPending = failing.start(qr(nonce = byteArrayOf(0x41)), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        assertTrue(failing.confirm(failingPending.summary.pendingId, LocalPersistentTrustedDesktopStore(failingStorage), ActiveDesktopAuthority()) is PendingPairingConfirmResult.Rejected.SaveFailed)

        LocalPersistentTrustedDesktopStore(storage).revoke("pc-1", revokedAtEpochSeconds = 110)
        val revoked = coordinator()
        val revokedPending = revoked.start(qr(nonce = byteArrayOf(0x42)), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        assertEquals(PendingPairingConfirmResult.Rejected.ExistingTrustRejected(TrustedDesktopAuthResult.Revoked), revoked.confirm(revokedPending.summary.pendingId, LocalPersistentTrustedDesktopStore(storage), ActiveDesktopAuthority()))
    }

    @Test
    fun validConfirmSavesThenActivatesOrReturnsTrustedButInactiveForSecondDesktop() {
        val store = RecordingTrustedDesktopStore()
        val authority = ActiveDesktopAuthority()
        val first = coordinator()
        val firstPending = first.start(qr(), byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation

        assertEquals(PendingPairingConfirmResult.Activated("pc-1"), first.confirm(firstPending.summary.pendingId, store, authority))
        assertEquals(listOf("pc-1"), store.saved.map { it.desktopId })
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
        assertEquals(PendingPairingState.Idle, first.state())

        val second = coordinator(verifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge).copy(desktopId = "pc-2") })
        val secondQr = qr(nonce = byteArrayOf(0x22)).copy(desktopId = "pc-2", desktopName = "Second")
        val secondPending = second.start(secondQr, byteArrayOf(1)) as PendingPairingStartResult.PendingConfirmation
        assertEquals(PendingPairingConfirmResult.TrustedButInactive("pc-2", "pc-1"), second.confirm(secondPending.summary.pendingId, store, authority))
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
        assertEquals(listOf("pc-1", "pc-2"), store.saved.map { it.desktopId })
    }

    private fun coordinator(
        verifier: PairingProofVerifier = RecordingVerifier { challenge, _ -> verifiedFrom(challenge) },
        challenges: ChallengeNonceSource = QueueChallengeSource(PairingChallengeMaterial(byteArrayOf(0x10), "session-1", expiresAtEpochSeconds = 200)),
    ) = PendingPairingCoordinator(
        epochSecondsSource = EpochSecondsSource { 100 },
        challengeNonceSource = challenges,
        proofVerifier = verifier,
    )

    private fun qr(
        nonce: ByteArray = byteArrayOf(0x01),
        expiresAt: Long = 150,
    ) = PairingQrPayload(
        desktopId = "pc-1",
        desktopName = "Studio",
        trustMaterial = P256_SPKI.copyOf(),
        expiresAtEpochSeconds = expiresAt,
        nonce = nonce,
    )


    private fun trustedRecord(
        desktopId: String = "pc-1",
        fingerprint: ByteArray = PairingTrustFingerprint.fromTrustMaterial(P256_SPKI).bytes,
        revokedAt: Long? = null,
    ) = TrustedDesktopRecord(
        desktopId = desktopId,
        desktopName = "Stored",
        trustMaterialFingerprint = fingerprint,
        createdAtEpochSeconds = 90,
        lastSeenAtEpochSeconds = 90,
        revokedAtEpochSeconds = revokedAt,
    )

    private class MutableSerializedTrustedDesktopStorage(initialValue: String? = null) : SerializedTrustedDesktopStorage {
        private var value = initialValue
        override fun read(): String? = value
        override fun write(serialized: String) { value = serialized }
        override fun clear() { value = null }
    }

    private class FailingSerializedTrustedDesktopStorage : SerializedTrustedDesktopStorage {
        override fun read(): String? = null
        override fun write(serialized: String) { throw Exception("disk full") }
        override fun clear() { throw Exception("disk full") }
    }

    private class RecordingTrustedDesktopStore(
        private val existing: TrustedDesktopRecord? = null,
        private val saveFailure: RuntimeException? = null,
    ) : TrustedDesktopStore {
        val saved = mutableListOf<TrustedDesktopRecord>()
        var saveAttempts = 0
        private val records = linkedMapOf<String, TrustedDesktopRecord>()

        init { existing?.let { records[it.desktopId] = it } }

        override fun save(record: TrustedDesktopRecord) {
            saveAttempts += 1
            saveFailure?.let { throw it }
            saved += record
            records[record.desktopId] = record
        }

        override fun lookup(desktopId: String): TrustedDesktopRecord? = records[desktopId]
        override fun list(): List<TrustedDesktopRecord> = records.values.toList()
        override fun revoke(desktopId: String, revokedAtEpochSeconds: Long): Boolean = false
        override fun forget(desktopId: String): Boolean = records.remove(desktopId) != null
        override fun evaluate(desktopId: String, presentedTrustMaterialFingerprint: ByteArray, nowEpochSeconds: Long): TrustedDesktopAuthResult {
            val record = records[desktopId] ?: return TrustedDesktopAuthResult.Unknown
            return when {
                record.revokedAtEpochSeconds != null -> TrustedDesktopAuthResult.Revoked
                !record.trustMaterialFingerprint.contentEquals(presentedTrustMaterialFingerprint) -> TrustedDesktopAuthResult.FingerprintMismatch
                else -> TrustedDesktopAuthResult.Trusted
            }
        }
    }

    private class RecordingVerifier(
        private val response: (PairingProofChallenge, ByteArray) -> PairingProofVerificationResult,
    ) : PairingProofVerifier {
        val proofs = mutableListOf<ByteArray>()
        val challenges = mutableListOf<PairingProofChallenge>()
        override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult {
            proofs += proofBytes.copyOf()
            challenges += challenge
            return response(challenge, proofBytes)
        }
    }

    private class MutableEpochSecondsSource(var now: Long) : EpochSecondsSource {
        override fun nowEpochSeconds(): Long = now
    }

    private class QueueChallengeSource(
        private val challenge: PairingChallengeMaterial,
    ) : ChallengeNonceSource {
        override fun nextChallenge(): PairingChallengeMaterial = challenge
    }

    private class RecordingChallengeSource(
        private val challenge: PairingChallengeMaterial,
    ) : ChallengeNonceSource {
        var requests = 0
        override fun nextChallenge(): PairingChallengeMaterial {
            requests += 1
            return challenge
        }
    }

    private class CountingChallengeSource : ChallengeNonceSource {
        private var next = 0
        override fun nextChallenge(): PairingChallengeMaterial = PairingChallengeMaterial(byteArrayOf((next++).toByte()), "session-$next", 200)
    }

    private companion object {
        val P256_SPKI: ByteArray = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXZIEL3sbIUaOZyNTWLYtwIWBMp4UFPNgWrsZ/B+zNnzGOg4MgU1DNcrJLKyExFcEw9epY26aNitmckuetSi1BQ==")

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
