package dev.chinchillacam.usbprobe

/**
 * Outcome of [PairedSessionStarter.start]. Every non-[Started] outcome has already closed the
 * channel handed in.
 */
sealed class PairedSessionStartResult {
    /** The desktop accepted the HELLO; [reconnected] carries the live channel and session identity, exactly like a reconnection. */
    data class Started(val reconnected: UsbTrustedReconnectResult.Reconnected) : PairedSessionStartResult()

    /**
     * The desktop answered with a reject, an unexpected frame, or an invalid accept, or did not answer
     * in time: one of [UsbTrustedReconnectResult.Rejected.DesktopRejected], `UnexpectedFrame`,
     * `InvalidHandshakeAccept`, or `TimedOut`.
     */
    data class Rejected(val rejection: UsbTrustedReconnectResult.Rejected) : PairedSessionStartResult()

    /** Writing the HELLO or reading the answer failed before the deadline (closed/truncated channel, invalid frame). */
    data class ExchangeFailed(val desktopId: String, val detail: String) : PairedSessionStartResult()
}

/**
 * Starts the session right after a confirmed pairing (task c2, `android-production-connection`
 * §3.2, §4.2; desktop side `usb-authenticated-session` §4 item 5): on the same live channel
 * returned by [UsbPairingFlow.confirm] (`Activated`), the phone sends `HANDSHAKE_HELLO` (`deviceId`
 * = its own trust fingerprint hex, the `phone_id`) and waits for `HANDSHAKE_ACCEPT` through the
 * shared [SessionHelloExchange], producing the same [UsbTrustedReconnectResult.Reconnected] a
 * reconnection does.
 *
 * Unlike [UsbTrustedReconnect] this never calls [ActiveDesktopAuthority]: `confirm` already
 * activated the desktop. On any rejection the channel is closed here, and the **caller** must
 * release the authority it activated (e.g. `stopActiveDesktop`).
 *
 * [helloTimeoutMillis] defaults to a long wait ([DEFAULT_HELLO_TIMEOUT_MILLIS]) because the user may
 * confirm on the desktop well after confirming on the phone; it bounds the whole exchange on its own
 * (see [SessionHelloExchange]), independent of [sessionFrameAdapter]'s session-traffic timeouts.
 */
class PairedSessionStarter(
    phoneTlsIdentity: PhoneTlsIdentity,
    helloTimeoutMillis: Long = DEFAULT_HELLO_TIMEOUT_MILLIS,
    private val sessionFrameAdapter: TlsSessionFrameIoAdapter = TlsSessionFrameIoAdapter(),
) {
    private val helloExchange = SessionHelloExchange(
        phoneId = PairingTrustFingerprint.fromTrustMaterial(phoneTlsIdentity.subjectPublicKeyInfoDer).hex,
        helloTimeoutMillis = helloTimeoutMillis,
    )

    fun start(desktopId: String, channel: SslEngineUsbTlsEstablishedChannel): PairedSessionStartResult =
        when (val outcome = helloExchange.exchange(desktopId, channel)) {
            is SessionHelloExchange.Outcome.Accepted -> PairedSessionStartResult.Started(
                UsbTrustedReconnectResult.Reconnected(
                    desktopId,
                    channel,
                    sessionFrameAdapter,
                    sessionId = outcome.sessionId,
                    nextOutboundSequence = outcome.nextOutboundSequence,
                    nextInboundSequence = outcome.nextInboundSequence,
                ),
            )
            is SessionHelloExchange.Outcome.Rejected -> {
                runCatching { channel.close() }
                PairedSessionStartResult.Rejected(outcome.rejection)
            }
            is SessionHelloExchange.Outcome.Failed -> {
                runCatching { channel.close() }
                PairedSessionStartResult.ExchangeFailed(desktopId, outcome.detail)
            }
        }

    companion object {
        /** Default wait for the desktop's `HANDSHAKE_ACCEPT`: long enough for the user to confirm on the desktop too. */
        const val DEFAULT_HELLO_TIMEOUT_MILLIS: Long = 120_000
    }
}
