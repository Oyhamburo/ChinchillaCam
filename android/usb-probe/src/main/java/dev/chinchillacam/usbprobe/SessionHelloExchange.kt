package dev.chinchillacam.usbprobe

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT` exchange that starts a session on an already
 * authenticated TLS channel (contract `usb-authenticated-session` §4.1), shared by
 * [UsbTrustedReconnect] (after a pinned reconnection) and [PairedSessionStarter] (after a confirmed
 * pairing, `android-production-connection` §3.2). The HELLO's `deviceId` is [phoneId], the phone's
 * own trust fingerprint hex.
 *
 * The whole exchange is bounded strictly by [helloTimeoutMillis], independent of the session
 * adapter's own (typically more generous) session-traffic timeout: the internal hello adapter uses
 * that one value both as its idle budget and as its frame deadline (task l2, `session-liveness`
 * §4.1), so neither a short deadline is outlasted by the adapter's 6s idle default nor a long one
 * (minutes, while the user confirms on the desktop) is cut short by its 5s read default.
 *
 * On a read/write failure the hello adapter has already closed the channel; on every other
 * non-accepted outcome the caller is responsible for closing it.
 */
internal class SessionHelloExchange(
    private val phoneId: String,
    private val helloTimeoutMillis: Long,
) {
    init {
        require(helloTimeoutMillis > 0) { "helloTimeoutMillis must be positive" }
    }

    private val helloFrameAdapter = TlsSessionFrameIoAdapter(readTimeoutMillis = helloTimeoutMillis, idleBudgetMillis = helloTimeoutMillis)

    /** Result of [exchange]: the validated session identity, a typed rejection, or an I/O failure before the deadline. */
    sealed class Outcome {
        data class Accepted(val sessionId: String, val nextOutboundSequence: Int, val nextInboundSequence: Int) : Outcome()

        /** One of [UsbTrustedReconnectResult.Rejected.DesktopRejected], `UnexpectedFrame`, `InvalidHandshakeAccept`, or `TimedOut`. */
        data class Rejected(val rejection: UsbTrustedReconnectResult.Rejected) : Outcome()

        /** Writing the HELLO or reading the answer failed before the deadline (closed/truncated channel, invalid frame). */
        data class Failed(val detail: String) : Outcome()
    }

    fun exchange(desktopId: String, channel: SslEngineUsbTlsEstablishedChannel): Outcome {
        val helloSequence = 0
        val sessionId = UUID.randomUUID().toString()
        val hello = SessionFrame(
            sequence = helloSequence,
            sessionId = sessionId,
            payload = SessionPayload.HandshakeHello(deviceId = phoneId, appName = APP_NAME, capabilities = listOf(QUALITY_CONTROL_CAPABILITY)),
        )
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(helloTimeoutMillis)
        val response = try {
            helloFrameAdapter.write(channel, hello)
            helloFrameAdapter.read(channel)
        } catch (error: Exception) {
            // helloFrameAdapter already closed the channel on this failure (see
            // TlsSessionFrameIoAdapter); classify by wall clock rather than by message text so a
            // genuine deadline (TimedOut) is distinguishable from an early close/EOF/invalid frame.
            // The adapter's bounded read can return a few milliseconds before this deadline because
            // its remaining time is truncated to whole milliseconds, so a small tolerance keeps a
            // genuine timeout from being reported as an I/O failure.
            return if (System.nanoTime() >= deadlineNanos - HELLO_DEADLINE_TOLERANCE_NANOS) {
                Outcome.Rejected(UsbTrustedReconnectResult.Rejected.TimedOut(desktopId))
            } else {
                Outcome.Failed(error.message ?: "hello exchange failed")
            }
        }
        return classifyResponse(desktopId, sessionId, helloSequence, response)
    }

    private fun classifyResponse(desktopId: String, sessionId: String, helloSequence: Int, response: SessionFrame): Outcome =
        when (val payload = response.payload) {
            is SessionPayload.HandshakeAccept -> {
                // Contract §4.1: the ACCEPT uses hello.seq + 1 and carries the HELLO's sessionId.
                val expectedAcceptSequence = helloSequence + 1
                when {
                    response.sessionId != sessionId -> Outcome.Rejected(
                        UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept(
                            desktopId,
                            "accept sessionId ${response.sessionId} does not match hello sessionId $sessionId",
                        ),
                    )
                    response.sequence != expectedAcceptSequence -> Outcome.Rejected(
                        UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept(
                            desktopId,
                            "accept sequence ${response.sequence} is not hello.seq + 1 ($expectedAcceptSequence)",
                        ),
                    )
                    else -> Outcome.Accepted(
                        sessionId = sessionId,
                        nextOutboundSequence = helloSequence + 1,
                        nextInboundSequence = response.sequence + 1,
                    )
                }
            }
            is SessionPayload.HandshakeReject -> Outcome.Rejected(
                UsbTrustedReconnectResult.Rejected.DesktopRejected(desktopId, payload.reasonCode, payload.message),
            )
            else -> Outcome.Rejected(UsbTrustedReconnectResult.Rejected.UnexpectedFrame(desktopId, response.type))
        }

    private companion object {
        const val APP_NAME: String = "ChinchillaCam"

        /** Granularity margin for classifying a hello read failure as a deadline timeout. */
        val HELLO_DEADLINE_TOLERANCE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(25)
    }
}
