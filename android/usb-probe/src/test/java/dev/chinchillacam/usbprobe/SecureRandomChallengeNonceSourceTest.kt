package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRandomChallengeNonceSourceTest {
    @Test
    fun generatesThirtyTwoByteChallengeAndIndependentBase64UrlSessionId() {
        val draws = ArrayDeque(listOf(ByteArray(32) { 0x01 }, ByteArray(16) { 0x02 }))
        val source = SecureRandomChallengeNonceSource.forTesting(EpochSecondsSource { 100 }, ttlSeconds = 60) { target ->
            draws.removeFirst().copyInto(target)
        }

        val challenge = source.nextChallenge()

        assertArrayEquals(ByteArray(32) { 0x01 }, challenge.challengeNonce)
        assertEquals("AgICAgICAgICAgICAgICAg", challenge.sessionId)
        assertFalse(challenge.sessionId.contains("="))
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(challenge.sessionId))
        assertEquals(160, challenge.expiresAtEpochSeconds)
    }

    @Test
    fun productionSecureRandomValuesDifferAcrossCalls() {
        val source = SecureRandomChallengeNonceSource(EpochSecondsSource { 100 }, ttlSeconds = 30)

        val first = source.nextChallenge()
        val second = source.nextChallenge()

        assertEquals(32, first.challengeNonce.size)
        assertEquals(32, second.challengeNonce.size)
        assertEquals(22, first.sessionId.length)
        assertEquals(22, second.sessionId.length)
        assertFalse(first.challengeNonce.contentEquals(second.challengeNonce) && first.sessionId == second.sessionId)
    }

    @Test
    fun rejectsInvalidTtlClockAndExpiryOverflow() {
        assertTrue(runCatching { SecureRandomChallengeNonceSource(EpochSecondsSource { 0 }, ttlSeconds = 29) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SecureRandomChallengeNonceSource(EpochSecondsSource { 0 }, ttlSeconds = 121) }.exceptionOrNull() is IllegalArgumentException)

        val invalidClock = SecureRandomChallengeNonceSource.forTesting(EpochSecondsSource { -1 }, ttlSeconds = 30) { target -> target.fill(0x01) }
        assertTrue(runCatching { invalidClock.nextChallenge() }.exceptionOrNull() is ChallengeNonceSourceException)

        val overflowClock = SecureRandomChallengeNonceSource.forTesting(EpochSecondsSource { Long.MAX_VALUE - 10 }, ttlSeconds = 30) { target -> target.fill(0x01) }
        assertTrue(runCatching { overflowClock.nextChallenge() }.exceptionOrNull() is ChallengeNonceSourceException)

        val failingRandom = SecureRandomChallengeNonceSource.forTesting(EpochSecondsSource { 100 }, ttlSeconds = 30) { throw IllegalStateException("rng unavailable") }
        assertTrue(runCatching { failingRandom.nextChallenge() }.exceptionOrNull() is ChallengeNonceSourceException)
    }
}
