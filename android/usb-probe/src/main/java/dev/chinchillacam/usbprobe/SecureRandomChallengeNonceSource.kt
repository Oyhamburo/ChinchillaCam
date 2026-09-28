package dev.chinchillacam.usbprobe

import java.security.SecureRandom

class SecureRandomChallengeNonceSource private constructor(
    private val epochSecondsSource: EpochSecondsSource,
    private val ttlSeconds: Long,
    private val randomBytes: (ByteArray) -> Unit,
) : ChallengeNonceSource {
    constructor(
        epochSecondsSource: EpochSecondsSource,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ) : this(epochSecondsSource, ttlSeconds, SecureRandom()::nextBytes)

    init {
        require(ttlSeconds in MIN_TTL_SECONDS..MAX_TTL_SECONDS) { "challenge TTL must be between 30 and 120 seconds" }
    }

    override fun nextChallenge(): PairingChallengeMaterial {
        val now = epochSecondsSource.nowEpochSeconds()
        if (now < 0) throw ChallengeNonceSourceException("clock must not be negative")
        if (now > Long.MAX_VALUE - ttlSeconds) throw ChallengeNonceSourceException("challenge expiry overflow")
        val challengeNonce = ByteArray(CHALLENGE_NONCE_BYTES)
        val sessionIdBytes = ByteArray(SESSION_ID_BYTES)
        try {
            randomBytes(challengeNonce)
            randomBytes(sessionIdBytes)
        } catch (error: RuntimeException) {
            throw ChallengeNonceSourceException("secure random failed", error)
        }
        return PairingChallengeMaterial(
            challengeNonce = challengeNonce,
            sessionId = Base64UrlNoPadding.encode(sessionIdBytes),
            expiresAtEpochSeconds = now + ttlSeconds,
        )
    }

    internal companion object {
        fun forTesting(
            epochSecondsSource: EpochSecondsSource,
            ttlSeconds: Long,
            randomBytes: (ByteArray) -> Unit,
        ) = SecureRandomChallengeNonceSource(epochSecondsSource, ttlSeconds, randomBytes)

        const val CHALLENGE_NONCE_BYTES = 32
        const val SESSION_ID_BYTES = 16
        const val MIN_TTL_SECONDS = 30L
        const val DEFAULT_TTL_SECONDS = 60L
        const val MAX_TTL_SECONDS = 120L
    }
}
