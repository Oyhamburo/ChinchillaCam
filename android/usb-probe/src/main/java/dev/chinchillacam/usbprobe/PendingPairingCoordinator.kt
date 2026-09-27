package dev.chinchillacam.usbprobe

import java.time.Clock
import java.util.Base64

interface ChallengeNonceSource {
    fun nextChallenge(): PairingChallengeMaterial
}

data class PairingChallengeMaterial(
    val challengeNonce: ByteArray,
    val sessionId: String,
    val expiresAtEpochSeconds: Long,
)

interface PairingProofVerifier {
    fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult
}

class PairingProofChallenge(
    val desktopId: String,
    val trustMaterialFingerprint: PairingTrustFingerprint,
    qrNonce: ByteArray,
    challengeNonce: ByteArray,
    val sessionId: String,
    val qrExpiresAtEpochSeconds: Long,
    val challengeExpiresAtEpochSeconds: Long,
) {
    private val qrNonceBytes = qrNonce.copyOf()
    private val challengeNonceBytes = challengeNonce.copyOf()
    val qrNonce: ByteArray get() = qrNonceBytes.copyOf()
    val challengeNonce: ByteArray get() = challengeNonceBytes.copyOf()
}

sealed class PairingProofVerificationResult {
    data class Verified(
        val desktopId: String,
        val trustMaterialFingerprint: PairingTrustFingerprint,
        val qrNonce: ByteArray,
        val challengeNonce: ByteArray,
        val sessionId: String,
        val verifiedAtEpochSeconds: Long,
        val expiresAtEpochSeconds: Long,
    ) : PairingProofVerificationResult()

    data class Rejected(val reason: String) : PairingProofVerificationResult()
}

class PendingPairingSummary(
    val desktopId: String,
    val desktopName: String,
    val trustMaterialFingerprint: PairingTrustFingerprint,
    qrNonce: ByteArray,
    challengeNonce: ByteArray,
    val sessionId: String,
    val expiresAtEpochSeconds: Long,
) {
    private val qrNonceBytes = qrNonce.copyOf()
    private val challengeNonceBytes = challengeNonce.copyOf()
    val qrNonce: ByteArray get() = qrNonceBytes.copyOf()
    val challengeNonce: ByteArray get() = challengeNonceBytes.copyOf()
}

sealed class PendingPairingState {
    object Idle : PendingPairingState()
    data class PendingConfirmation(val summary: PendingPairingSummary) : PendingPairingState()
}

sealed class PendingPairingStartResult {
    data class PendingConfirmation(val summary: PendingPairingSummary) : PendingPairingStartResult()

    sealed class Rejected : PendingPairingStartResult() {
        data class ProofRejected(val reason: String) : Rejected()
        data class BindingMismatch(val field: String) : Rejected()
        data class Expired(val field: String) : Rejected()
        object NonceReplay : Rejected()
        object NonceCacheFull : Rejected()
        object AlreadyPending : Rejected()
    }
}

sealed class PendingPairingCancelResult {
    object Cancelled : PendingPairingCancelResult()
    object NoPendingPairing : PendingPairingCancelResult()
}

class PendingPairingCoordinator(
    private val clock: Clock,
    private val challengeNonceSource: ChallengeNonceSource,
    private val proofVerifier: PairingProofVerifier,
    private val maxLiveNonces: Int = 64,
) {
    private val liveNonceExpiries = linkedMapOf<String, Long>()
    private var pending: PendingPairingSummary? = null

    init {
        require(maxLiveNonces > 0) { "maxLiveNonces must be positive" }
    }

    @Synchronized
    fun state(): PendingPairingState = pending?.let { PendingPairingState.PendingConfirmation(it) } ?: PendingPairingState.Idle

    @Synchronized
    fun start(qrPayload: PairingQrPayload, proofBytes: ByteArray): PendingPairingStartResult {
        val now = clock.instant().epochSecond
        pruneExpiredNonces(now)
        if (pending != null) return PendingPairingStartResult.Rejected.AlreadyPending
        if (qrPayload.expiresAtEpochSeconds <= now) return PendingPairingStartResult.Rejected.Expired("qr")
        val nonceKey = nonceKey(qrPayload.nonce)
        if (liveNonceExpiries.containsKey(nonceKey)) return PendingPairingStartResult.Rejected.NonceReplay
        if (liveNonceExpiries.size >= maxLiveNonces) return PendingPairingStartResult.Rejected.NonceCacheFull

        val fingerprint = PairingTrustFingerprint.fromTrustMaterial(qrPayload.trustMaterial)
        val challengeMaterial = challengeNonceSource.nextChallenge()
        if (challengeMaterial.expiresAtEpochSeconds <= now) return PendingPairingStartResult.Rejected.Expired("challenge")
        require(challengeMaterial.challengeNonce.isNotEmpty()) { "challenge nonce must not be empty" }
        require(challengeMaterial.sessionId.isNotBlank()) { "session id must not be blank" }
        val challenge = PairingProofChallenge(
            desktopId = qrPayload.desktopId,
            trustMaterialFingerprint = fingerprint,
            qrNonce = qrPayload.nonce,
            challengeNonce = challengeMaterial.challengeNonce,
            sessionId = challengeMaterial.sessionId,
            qrExpiresAtEpochSeconds = qrPayload.expiresAtEpochSeconds,
            challengeExpiresAtEpochSeconds = challengeMaterial.expiresAtEpochSeconds,
        )
        val verification = proofVerifier.verify(challenge, proofBytes.copyOf())
        val verified = when (verification) {
            is PairingProofVerificationResult.Rejected -> return PendingPairingStartResult.Rejected.ProofRejected(verification.reason)
            is PairingProofVerificationResult.Verified -> verification
        }
        if (verified.expiresAtEpochSeconds <= now) return PendingPairingStartResult.Rejected.Expired("proof")
        bindingMismatch(qrPayload, fingerprint, challengeMaterial, verified)?.let { return it }

        val expiresAt = minOf(qrPayload.expiresAtEpochSeconds, challengeMaterial.expiresAtEpochSeconds, verified.expiresAtEpochSeconds)
        liveNonceExpiries[nonceKey] = expiresAt
        val summary = PendingPairingSummary(qrPayload.desktopId, qrPayload.desktopName, fingerprint, qrPayload.nonce, challengeMaterial.challengeNonce, challengeMaterial.sessionId, expiresAt)
        pending = summary
        return PendingPairingStartResult.PendingConfirmation(summary)
    }

    @Synchronized
    fun cancel(): PendingPairingCancelResult {
        if (pending == null) return PendingPairingCancelResult.NoPendingPairing
        pending = null
        return PendingPairingCancelResult.Cancelled
    }

    private fun bindingMismatch(
        qrPayload: PairingQrPayload,
        fingerprint: PairingTrustFingerprint,
        challenge: PairingChallengeMaterial,
        verified: PairingProofVerificationResult.Verified,
    ): PendingPairingStartResult.Rejected.BindingMismatch? = when {
        verified.desktopId != qrPayload.desktopId -> PendingPairingStartResult.Rejected.BindingMismatch("desktopId")
        verified.trustMaterialFingerprint != fingerprint -> PendingPairingStartResult.Rejected.BindingMismatch("trustMaterialFingerprint")
        !verified.qrNonce.contentEquals(qrPayload.nonce) -> PendingPairingStartResult.Rejected.BindingMismatch("qrNonce")
        !verified.challengeNonce.contentEquals(challenge.challengeNonce) -> PendingPairingStartResult.Rejected.BindingMismatch("challengeNonce")
        verified.sessionId != challenge.sessionId -> PendingPairingStartResult.Rejected.BindingMismatch("sessionId")
        else -> null
    }

    private fun pruneExpiredNonces(now: Long) {
        liveNonceExpiries.entries.removeAll { it.value <= now }
    }

    private fun nonceKey(nonce: ByteArray): String = Base64.getEncoder().encodeToString(nonce)
}
