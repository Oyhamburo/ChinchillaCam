package dev.chinchillacam.usbprobe

/**
 * Pure, thread-free tracker of per-session keepalive/dead-peer timing (task l3, `session-liveness`
 * §4.2, §4.4, §4.5). Every method takes an explicit [nowMillis] instead of reading a clock
 * internally, so both production callers and tests fully control time.
 *
 * Symmetric on both sides of a session: [recordSent] tracks when this side last sent *any* frame
 * (not just [SessionPayload.Keepalive]) so [shouldSendKeepalive] only fires after a genuine
 * interval of silence, and [recordReceived] tracks when *any* frame last arrived from the peer
 * (contract §4.4 -- not just a `KEEPALIVE` specifically) so [isPeerDead] does not misfire while the
 * peer is merely sending other session traffic instead of keepalives. Sending/reading the actual
 * `KEEPALIVE` frames and looping on this tracker is out of scope here (contract §6, a later unit);
 * this class only decides *when* to send one and *whether* the peer is still alive.
 */
class SessionLivenessTracker(
    private val keepaliveIntervalMillis: Long = DEFAULT_KEEPALIVE_INTERVAL_MILLIS,
    private val deadPeerThresholdMillis: Long = DEFAULT_DEAD_PEER_THRESHOLD_MILLIS,
) {
    init {
        require(keepaliveIntervalMillis > 0) { "keepaliveIntervalMillis must be positive" }
        require(deadPeerThresholdMillis > keepaliveIntervalMillis) {
            "deadPeerThresholdMillis must exceed keepaliveIntervalMillis"
        }
    }

    private var lastSentAtMillis: Long? = null
    private var lastReceivedAtMillis: Long? = null

    /** Records that this side sent a frame (any [SessionFrameType], not just `KEEPALIVE`) at [nowMillis]. */
    fun recordSent(nowMillis: Long) {
        lastSentAtMillis = nowMillis
    }

    /** Records that a frame arrived from the peer (any [SessionFrameType]; contract §4.4) at [nowMillis]. */
    fun recordReceived(nowMillis: Long) {
        lastReceivedAtMillis = nowMillis
    }

    /** True once [keepaliveIntervalMillis] has elapsed since the last [recordSent] call, or if nothing has been sent yet. */
    fun shouldSendKeepalive(nowMillis: Long): Boolean {
        val lastSent = lastSentAtMillis ?: return true
        return nowMillis - lastSent >= keepaliveIntervalMillis
    }

    /** True once [deadPeerThresholdMillis] has elapsed since the last [recordReceived] call; false if nothing has been received yet. */
    fun isPeerDead(nowMillis: Long): Boolean {
        val lastReceived = lastReceivedAtMillis ?: return false
        return nowMillis - lastReceived >= deadPeerThresholdMillis
    }

    private companion object {
        /** Contract default (`session-liveness` §4.2). */
        const val DEFAULT_KEEPALIVE_INTERVAL_MILLIS: Long = 2_000

        /** Contract default (`session-liveness` §4.2): three keepalive intervals. */
        const val DEFAULT_DEAD_PEER_THRESHOLD_MILLIS: Long = 6_000
    }
}
