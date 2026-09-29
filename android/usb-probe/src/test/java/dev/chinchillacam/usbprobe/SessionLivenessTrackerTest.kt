package dev.chinchillacam.usbprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for task l3 (`odd/tasks/session-liveness.md` §4.2, §4.4, §4.5, §7): [SessionLivenessTracker]
 * is a pure, thread-free tracker of keepalive/dead-peer timing, driven entirely by explicit
 * `nowMillis` timestamps instead of an internal clock, so every test below is fully deterministic.
 */
class SessionLivenessTrackerTest {
    @Test
    fun sendsKeepaliveAfterIntervalOfSilence() {
        val tracker = SessionLivenessTracker(keepaliveIntervalMillis = 100, deadPeerThresholdMillis = 300)

        // Nothing has ever been sent on this tracker: a keepalive is due immediately.
        assertTrue(tracker.shouldSendKeepalive(0))

        tracker.recordSent(0)
        assertFalse(tracker.shouldSendKeepalive(99))
        assertTrue(tracker.shouldSendKeepalive(100))
    }

    @Test
    fun declaresDeadPeerAfterThreshold() {
        val tracker = SessionLivenessTracker(keepaliveIntervalMillis = 100, deadPeerThresholdMillis = 300)
        tracker.recordReceived(0)

        assertFalse(tracker.isPeerDead(299))
        assertTrue(tracker.isPeerDead(300))
    }

    @Test
    fun receivingAnyFrameResetsPeerDeadline() {
        val tracker = SessionLivenessTracker(keepaliveIntervalMillis = 100, deadPeerThresholdMillis = 300)
        tracker.recordReceived(0)

        // Any frame counts as a life signal (contract §4.4), not just a KEEPALIVE specifically.
        tracker.recordReceived(250)

        assertFalse(tracker.isPeerDead(549))
        assertTrue(tracker.isPeerDead(550))
    }

    @Test
    fun neverDeclaresPeerDeadBeforeAnyFrameWasEverReceived() {
        val tracker = SessionLivenessTracker(keepaliveIntervalMillis = 100, deadPeerThresholdMillis = 300)

        assertFalse(tracker.isPeerDead(10_000))
    }

    @Test
    fun rejectsDeadPeerThresholdNotExceedingInterval() {
        val error = runCatching { SessionLivenessTracker(keepaliveIntervalMillis = 300, deadPeerThresholdMillis = 300) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
