package dev.chinchillacam.usbprobe

fun interface EpochSecondsSource {
    fun nowEpochSeconds(): Long
}

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
        data class InvalidProofTime(val field: String) : Rejected()
        data class InvalidQr(val field: String) : Rejected()
    }
}

sealed class PendingPairingCancelResult {
    object Cancelled : PendingPairingCancelResult()
    object NoPendingPairing : PendingPairingCancelResult()
}

class PendingPairingCoordinator(
    private val epochSecondsSource: EpochSecondsSource,
    private val challengeNonceSource: ChallengeNonceSource,
    private val proofVerifier: PairingProofVerifier,
    private val maxLiveNonces: Int = 64,
) {
    private val liveNonceExpiries = linkedMapOf<NonceKey, Long>()
    private var pending: PendingPairingSummary? = null

    init {
        require(maxLiveNonces in 1..64) { "maxLiveNonces must be between 1 and 64" }
    }

    @Synchronized
    fun state(): PendingPairingState = pending?.let { PendingPairingState.PendingConfirmation(it) } ?: PendingPairingState.Idle

    @Synchronized
    fun start(qrPayload: PairingQrPayload, proofBytes: ByteArray): PendingPairingStartResult {
        val now = epochSecondsSource.nowEpochSeconds()
        pruneExpiredNonces(now)
        if (pending != null) return PendingPairingStartResult.Rejected.AlreadyPending
        validateQrPayload(qrPayload)?.let { return it }
        if (qrPayload.expiresAtEpochSeconds <= now) return PendingPairingStartResult.Rejected.Expired("qr")
        val qrNonce = qrPayload.nonce.copyOf()
        val fingerprint = PairingTrustFingerprint.fromTrustMaterial(qrPayload.trustMaterial.copyOf())
        val nonceKey = nonceKey(qrNonce)
        if (liveNonceExpiries.containsKey(nonceKey)) return PendingPairingStartResult.Rejected.NonceReplay
        if (liveNonceExpiries.size >= maxLiveNonces) return PendingPairingStartResult.Rejected.NonceCacheFull

        val challengeMaterial = challengeNonceSource.nextChallenge()
        if (challengeMaterial.expiresAtEpochSeconds <= now) return PendingPairingStartResult.Rejected.Expired("challenge")
        require(challengeMaterial.challengeNonce.isNotEmpty()) { "challenge nonce must not be empty" }
        require(challengeMaterial.sessionId.isNotBlank()) { "session id must not be blank" }
        liveNonceExpiries[nonceKey] = qrPayload.expiresAtEpochSeconds
        val challenge = PairingProofChallenge(
            desktopId = qrPayload.desktopId,
            trustMaterialFingerprint = fingerprint,
            qrNonce = qrNonce,
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
        val nowAfterVerify = epochSecondsSource.nowEpochSeconds()
        if (qrPayload.expiresAtEpochSeconds <= nowAfterVerify) return PendingPairingStartResult.Rejected.Expired("qr")
        if (challengeMaterial.expiresAtEpochSeconds <= nowAfterVerify) return PendingPairingStartResult.Rejected.Expired("challenge")
        if (verified.expiresAtEpochSeconds <= nowAfterVerify) return PendingPairingStartResult.Rejected.Expired("proof")
        if (verified.verifiedAtEpochSeconds < 0 || verified.verifiedAtEpochSeconds > nowAfterVerify || verified.verifiedAtEpochSeconds >= verified.expiresAtEpochSeconds) {
            return PendingPairingStartResult.Rejected.InvalidProofTime("verifiedAt")
        }
        bindingMismatch(qrPayload, fingerprint, qrNonce, challengeMaterial, verified)?.let { return it }

        val expiresAt = minOf(qrPayload.expiresAtEpochSeconds, challengeMaterial.expiresAtEpochSeconds, verified.expiresAtEpochSeconds)
        val summary = PendingPairingSummary(qrPayload.desktopId, qrPayload.desktopName, fingerprint, qrNonce, challengeMaterial.challengeNonce, challengeMaterial.sessionId, expiresAt)
        pending = summary
        return PendingPairingStartResult.PendingConfirmation(summary)
    }

    @Synchronized
    fun cancel(): PendingPairingCancelResult {
        if (pending == null) return PendingPairingCancelResult.NoPendingPairing
        pending = null
        return PendingPairingCancelResult.Cancelled
    }

    private fun validateQrPayload(qrPayload: PairingQrPayload): PendingPairingStartResult.Rejected.InvalidQr? = when {
        qrPayload.desktopId.isEmpty() -> PendingPairingStartResult.Rejected.InvalidQr("desktopId")
        qrPayload.nonce.isEmpty() -> PendingPairingStartResult.Rejected.InvalidQr("nonce")
        qrPayload.trustMaterial.isEmpty() -> PendingPairingStartResult.Rejected.InvalidQr("trustMaterial")
        else -> null
    }

    private fun bindingMismatch(
        qrPayload: PairingQrPayload,
        fingerprint: PairingTrustFingerprint,
        qrNonce: ByteArray,
        challenge: PairingChallengeMaterial,
        verified: PairingProofVerificationResult.Verified,
    ): PendingPairingStartResult.Rejected.BindingMismatch? = when {
        verified.desktopId != qrPayload.desktopId -> PendingPairingStartResult.Rejected.BindingMismatch("desktopId")
        verified.trustMaterialFingerprint != fingerprint -> PendingPairingStartResult.Rejected.BindingMismatch("trustMaterialFingerprint")
        !verified.qrNonce.contentEquals(qrNonce) -> PendingPairingStartResult.Rejected.BindingMismatch("qrNonce")
        !verified.challengeNonce.contentEquals(challenge.challengeNonce) -> PendingPairingStartResult.Rejected.BindingMismatch("challengeNonce")
        verified.sessionId != challenge.sessionId -> PendingPairingStartResult.Rejected.BindingMismatch("sessionId")
        else -> null
    }

    private fun pruneExpiredNonces(now: Long) {
        liveNonceExpiries.entries.removeAll { it.value <= now }
    }

    private fun nonceKey(nonce: ByteArray): NonceKey = NonceKey(nonce)
}

private class NonceKey(nonce: ByteArray) {
    private val bytes = nonce.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NonceKey) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
