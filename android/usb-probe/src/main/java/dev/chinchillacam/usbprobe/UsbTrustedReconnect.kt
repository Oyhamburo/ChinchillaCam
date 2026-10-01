package dev.chinchillacam.usbprobe

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Outcome of [UsbTrustedReconnect.reconnect]. [Reconnected.channel] is the live, authenticated TLS
 * channel and [Reconnected.frameAdapter] is ready to frame further [SessionFrame]s over it for the
 * ongoing session. Every rejection closes the channel (or, for [Rejected.NotTrusted], the raw
 * [AccessoryIoSession] handed in, since no TLS was ever opened) before returning -- callers never
 * need to close a rejected attempt themselves.
 */
sealed class UsbTrustedReconnectResult {
    data class Reconnected(
        val desktopId: String,
        val channel: SslEngineUsbTlsEstablishedChannel,
        val frameAdapter: TlsSessionFrameIoAdapter,
        /** The session identity carried by the `HANDSHAKE_HELLO` this reconnection sent (contract §4.1); every session frame reuses it. */
        val sessionId: String,
        /** Sequence the phone must put on its next outbound frame (= hello.seq + 1). */
        val nextOutboundSequence: Int,
        /** Sequence the phone must expect on the next inbound frame (= accept.seq + 1 = hello.seq + 2). */
        val nextInboundSequence: Int,
    ) : UsbTrustedReconnectResult()

    sealed class Rejected : UsbTrustedReconnectResult() {
        /** The store does not consider [desktopId] trusted (unknown, revoked, expired, or fingerprint mismatch); no TLS was attempted. */
        data class NotTrusted(val desktopId: String, val reason: TrustedDesktopAuthResult) : Rejected()

        /** The pinned-fingerprint TLS handshake itself failed (see [SslEngineUsbTlsChannel.handshakeWithPinnedFingerprint]). */
        data class TlsRejected(val desktopId: String, val reason: String) : Rejected()

        /** The desktop answered `HANDSHAKE_HELLO` with an explicit `HANDSHAKE_REJECT`. */
        data class DesktopRejected(val desktopId: String, val reasonCode: String, val message: String) : Rejected()

        /** The desktop answered with a decodable [SessionFrame] that is neither an accept nor a reject. */
        data class UnexpectedFrame(val desktopId: String, val type: SessionFrameType) : Rejected()

        /** The `HANDSHAKE_ACCEPT` violated the session-identity contract (§4.1): wrong sequence or a sessionId that is not the HELLO's. */
        data class InvalidHandshakeAccept(val desktopId: String, val detail: String) : Rejected()

        /** No answer (accept, reject, or otherwise) arrived within the configured deadline. */
        data class TimedOut(val desktopId: String) : Rejected()

        /** The desktop accepted the handshake, but [ActiveDesktopAuthority] rejected activation (e.g. a second live desktop). */
        data class ActivationRejected(val desktopId: String, val activation: ActiveDesktopAuthority.ActivationResult.Rejected) : Rejected()

        /** [ActiveDesktopAuthority.requestActivation] itself threw (task l1, `session-liveness` §2, §7: this used to leak the channel). */
        data class ActivationFailed(val desktopId: String, val cause: Throwable) : Rejected()
    }
}

/**
 * Reconnects to an already-paired, trusted desktop over USB mTLS (task s3,
 * `usb-authenticated-session` §4.4): unlike [UsbPairingFlow]/[UsbTlsPairingProofVerifier] (pairing,
 * fresh QR, exact SPKI pinning), reconnection has no fresh trust material -- only
 * [TrustedDesktopStore]'s persisted [PairingTrustFingerprint] for the given desktop id. Order of
 * operations:
 *
 * 1. [TrustedDesktopStore] must report the desktop trusted (present, not revoked, not expired, and
 *    fingerprint-consistent) *before* any TLS I/O -- an untrusted or revoked desktop never gets a
 *    handshake attempt, and [session] is closed immediately.
 * 2. TLS is opened via [SslEngineUsbTlsChannel.handshakeWithPinnedFingerprint], pinning the server's
 *    certificate by that stored fingerprint and presenting [phoneTlsIdentity]'s client certificate.
 * 3. Once authenticated, the phone sends `HANDSHAKE_HELLO` (`deviceId` is the phone's own trust
 *    fingerprint hex -- its `phone_id`; the contract has the desktop authenticate it against the TLS
 *    client certificate it just saw and reject a mismatch) and waits for `HANDSHAKE_ACCEPT` within
 *    [helloTimeoutMillis]. Under TLS 1.3 a server-side rejection is only visible to the client on a
 *    read, so `HANDSHAKE_REJECT`, a closed/truncated channel, and a plain deadline are all read
 *    failures here, not write failures -- each fails closed with a distinct typed rejection.
 * 4. Only after `HANDSHAKE_ACCEPT` is activation requested from [ActiveDesktopAuthority]; a second
 *    live desktop closes the channel and rejects instead of silently displacing the active one.
 * 5. On success, the live channel and a [TlsSessionFrameIoAdapter] ready for that channel are
 *    handed back for the ongoing session.
 */
class UsbTrustedReconnect(
    private val epochSecondsSource: EpochSecondsSource,
    private val phoneTlsIdentity: PhoneTlsIdentity,
    private val helloTimeoutMillis: Long = DEFAULT_HELLO_TIMEOUT_MILLIS,
    private val tlsChannel: SslEngineUsbTlsChannel = SslEngineUsbTlsChannel(phoneTlsIdentity = phoneTlsIdentity),
    private val sessionFrameAdapter: TlsSessionFrameIoAdapter = TlsSessionFrameIoAdapter(),
) {
    init {
        require(helloTimeoutMillis > 0) { "helloTimeoutMillis must be positive" }
    }

    /** The phone's own trust fingerprint, hex-encoded -- the `phone_id` the desktop authenticates `HANDSHAKE_HELLO.deviceId` against. */
    private val phoneId: String = PairingTrustFingerprint.fromTrustMaterial(phoneTlsIdentity.subjectPublicKeyInfoDer).hex

    /**
     * Bounded strictly by [helloTimeoutMillis], independent of [sessionFrameAdapter]'s own (typically
     * more generous) session-traffic timeout. Passes its own idle budget too (task l2,
     * `session-liveness` §4.1): [TlsSessionFrameIoAdapter]'s default idle budget (6s) would otherwise
     * outlast a short [helloTimeoutMillis] (e.g. the 300ms used by
     * [UsbTrustedReconnectTest.reconnectTimesOutWithoutAccept]), so both phases of the hello read
     * share the same single [helloTimeoutMillis] deadline, matching this class's pre-l2 behavior.
     */
    private val helloFrameAdapter = TlsSessionFrameIoAdapter(readTimeoutMillis = helloTimeoutMillis, idleBudgetMillis = helloTimeoutMillis)

    /**
     * Reconnects over an [AccessoryIoSession] (USB). Wraps it in a [UsbAccessoryTlsCiphertextTransport]
     * and delegates to the transport-neutral overload, so behaviour is identical to running directly
     * over the USB transport.
     */
    fun reconnect(
        desktopId: String,
        session: AccessoryIoSession,
        trustedDesktopStore: TrustedDesktopStore,
        activeDesktopAuthority: ActiveDesktopAuthority,
    ): UsbTrustedReconnectResult = reconnect(
        desktopId,
        tlsChannel.usbTransport(session),
        trustedDesktopStore,
        activeDesktopAuthority,
    )

    /**
     * Transport-neutral counterpart (contract `wifi-loopback-transport` §4.3): runs the same
     * trust-gate, pinned-fingerprint handshake, `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT` exchange, and
     * activation over any [TlsCiphertextTransport]. An untrusted/revoked desktop still gets no TLS
     * I/O -- [transport] is closed immediately -- matching the USB behaviour before the seam.
     */
    fun reconnect(
        desktopId: String,
        transport: TlsCiphertextTransport,
        trustedDesktopStore: TrustedDesktopStore,
        activeDesktopAuthority: ActiveDesktopAuthority,
    ): UsbTrustedReconnectResult {
        val now = epochSecondsSource.nowEpochSeconds()
        val record = trustedDesktopStore.lookup(desktopId)
        if (record == null) {
            runCatching { transport.close() }
            return UsbTrustedReconnectResult.Rejected.NotTrusted(desktopId, TrustedDesktopAuthResult.Unknown)
        }
        // Re-evaluating against the record's own stored fingerprint reuses the store's existing
        // revoked/expired rules (never re-derives them here) while still reporting Unknown/Revoked/
        // Expired precisely; a fingerprint compared against itself is by construction never a
        // mismatch, so Trusted is the only remaining non-rejection outcome.
        val trustResult = trustedDesktopStore.evaluate(desktopId, record.trustMaterialFingerprint, now)
        if (trustResult != TrustedDesktopAuthResult.Trusted) {
            runCatching { transport.close() }
            return UsbTrustedReconnectResult.Rejected.NotTrusted(desktopId, trustResult)
        }

        val handshake = tlsChannel.handshakeWithPinnedFingerprint(transport, record.trustMaterialFingerprint)
        val channel = when (handshake) {
            is SslEngineUsbTlsHandshakeResult.Rejected ->
                return UsbTrustedReconnectResult.Rejected.TlsRejected(desktopId, handshake.reason)
            is SslEngineUsbTlsHandshakeResult.Authenticated -> handshake.channel
        }

        val accepted = when (val outcome = exchangeHandshakeHello(desktopId, channel)) {
            is HelloExchange.Rejected -> {
                runCatching { channel.close() }
                return outcome.rejection
            }
            is HelloExchange.Accepted -> outcome
        }

        return try {
            when (
                val activation = activeDesktopAuthority.requestActivation(desktopId, record.trustMaterialFingerprint, now, trustedDesktopStore)
            ) {
                is ActiveDesktopAuthority.ActivationResult.Activated,
                is ActiveDesktopAuthority.ActivationResult.KeptActive,
                -> UsbTrustedReconnectResult.Reconnected(
                    desktopId,
                    channel,
                    sessionFrameAdapter,
                    sessionId = accepted.sessionId,
                    nextOutboundSequence = accepted.nextOutboundSequence,
                    nextInboundSequence = accepted.nextInboundSequence,
                )
                is ActiveDesktopAuthority.ActivationResult.Rejected -> {
                    runCatching { channel.close() }
                    UsbTrustedReconnectResult.Rejected.ActivationRejected(desktopId, activation)
                }
            }
        } catch (error: Exception) {
            // Activation happens after the channel is already open (step 4): a thrown exception must
            // not leak it (task l1, `session-liveness` §2) -- close it and report a typed rejection,
            // matching exchangeHandshakeHello's own catch-and-classify style above.
            runCatching { channel.close() }
            UsbTrustedReconnectResult.Rejected.ActivationFailed(desktopId, error)
        }
    }

    private fun exchangeHandshakeHello(
        desktopId: String,
        channel: SslEngineUsbTlsEstablishedChannel,
    ): HelloExchange {
        val helloSequence = 0
        val sessionId = UUID.randomUUID().toString()
        val hello = SessionFrame(
            sequence = helloSequence,
            sessionId = sessionId,
            payload = SessionPayload.HandshakeHello(deviceId = phoneId, appName = APP_NAME, capabilities = emptyList()),
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
            // genuine timeout from being reported as a TLS rejection.
            return HelloExchange.Rejected(
                if (System.nanoTime() >= deadlineNanos - HELLO_DEADLINE_TOLERANCE_NANOS) {
                    UsbTrustedReconnectResult.Rejected.TimedOut(desktopId)
                } else {
                    UsbTrustedReconnectResult.Rejected.TlsRejected(desktopId, error.message ?: "hello exchange failed")
                },
            )
        }
        return classifyHelloResponse(desktopId, sessionId, helloSequence, response)
    }

    private fun classifyHelloResponse(
        desktopId: String,
        sessionId: String,
        helloSequence: Int,
        response: SessionFrame,
    ): HelloExchange =
        when (val payload = response.payload) {
            is SessionPayload.HandshakeAccept -> {
                // Contract §4.1: the ACCEPT uses hello.seq + 1 and carries the HELLO's sessionId.
                val expectedAcceptSequence = helloSequence + 1
                when {
                    response.sessionId != sessionId -> HelloExchange.Rejected(
                        UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept(
                            desktopId,
                            "accept sessionId ${response.sessionId} does not match hello sessionId $sessionId",
                        ),
                    )
                    response.sequence != expectedAcceptSequence -> HelloExchange.Rejected(
                        UsbTrustedReconnectResult.Rejected.InvalidHandshakeAccept(
                            desktopId,
                            "accept sequence ${response.sequence} is not hello.seq + 1 ($expectedAcceptSequence)",
                        ),
                    )
                    else -> HelloExchange.Accepted(
                        sessionId = sessionId,
                        nextOutboundSequence = helloSequence + 1,
                        nextInboundSequence = response.sequence + 1,
                    )
                }
            }
            is SessionPayload.HandshakeReject -> HelloExchange.Rejected(
                UsbTrustedReconnectResult.Rejected.DesktopRejected(desktopId, payload.reasonCode, payload.message),
            )
            else -> HelloExchange.Rejected(UsbTrustedReconnectResult.Rejected.UnexpectedFrame(desktopId, response.type))
        }

    /** Result of the `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT` exchange: the validated session identity, or a typed rejection. */
    private sealed class HelloExchange {
        data class Accepted(val sessionId: String, val nextOutboundSequence: Int, val nextInboundSequence: Int) : HelloExchange()
        data class Rejected(val rejection: UsbTrustedReconnectResult.Rejected) : HelloExchange()
    }

    private companion object {
        const val DEFAULT_HELLO_TIMEOUT_MILLIS: Long = 5_000
        const val APP_NAME: String = "ChinchillaCam"

        /** Granularity margin for classifying a hello read failure as a deadline timeout. */
        val HELLO_DEADLINE_TOLERANCE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(25)
    }
}
