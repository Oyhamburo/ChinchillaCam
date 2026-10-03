package dev.chinchillacam.usbprobe

/**
 * Outcome of [UsbPairingFlow.confirm]: [channel] is the live TLS channel established during
 * [UsbPairingFlow.start], handed back to the caller when [result] is
 * [PendingPairingConfirmResult.Activated] (contract `usb-authenticated-session` §4.2 and §4.5, for
 * a later HELLO/ACCEPT over the same channel) and a live channel is still held for that pending.
 * Every other outcome — a rejection, or [PendingPairingConfirmResult.TrustedButInactive] (a second
 * desktop cannot use this channel while another one is already active) — closes the channel before
 * returning `null`. Under exclusive ownership of the wrapped [PendingPairingCoordinator] (see
 * [UsbPairingFlow]'s class doc), [result] is never [PendingPairingConfirmResult.Activated] without a
 * held channel, so [channel] is `null` there only as a non-throwing fallback if that invariant were
 * ever violated — not a case normal callers need to handle.
 */
data class UsbPairingConfirmOutcome(
    val result: PendingPairingConfirmResult,
    val channel: SslEngineUsbTlsEstablishedChannel?,
)

/**
 * Application-level USB pairing flow (task s1, `odd/tasks/usb-authenticated-session.md` §4):
 * composes [PendingPairingCoordinator]'s existing rules (QR/trust-material validation, challenge
 * generation, nonce bookkeeping, trust persistence, one-active-desktop activation) with
 * [UsbTlsPairingProofVerifier] over an [AccessoryIoSession], and owns the lifecycle of the live
 * [SslEngineUsbTlsEstablishedChannel] the proof is verified over:
 *
 * - [start] keeps the channel alive across a pending confirmation. A rejected attempt — including
 *   [PendingPairingStartResult.Rejected.AlreadyPending] while another pairing is still pending —
 *   never disturbs that held channel; only a new [PendingPairingStartResult.PendingConfirmation]
 *   replaces it.
 * - [confirm] persists trust and activates exactly as [PendingPairingCoordinator.confirm] does
 *   today, and additionally hands the live channel to the caller once activated.
 * - [reject] cancels the pending pairing and closes the channel.
 * - An expired pending (per the coordinator's own clock/TTL rules) closes its channel on the next
 *   call to [start], [confirm], [reject], or [state], or immediately via the explicit [expire].
 *
 * A pending pairing — and the channel that came with it — can be consumed at most once: [confirm]
 * and [reject] both clear it, so a second call on the same pending finds nothing left.
 *
 * Does not duplicate any coordinator rule: every decision is delegated to [coordinator]; this
 * class only tracks which live channel belongs to the coordinator's current pending pairing. It
 * expects exclusive ownership of the [coordinator] instance it wraps — nothing else should call
 * [PendingPairingCoordinator.cancel] or [PendingPairingCoordinator.confirm] directly on it.
 *
 * Transport-neutral pairing (task g1, `android-followups` §4.1): [start] also accepts any
 * [TlsCiphertextTransport] (e.g. a raw stream) through [transportVerifier]; both overloads share the
 * same held-channel lifecycle. Build the flow from a [UsbTlsPairingProofVerifier] to get both paths
 * from one verifier: its session overload wraps the session with its channel's configured USB
 * adapter (`tlsChannel.usbTransport`) and delegates to its transport overload, so the USB path keeps
 * identical behaviour. A flow built with only a [ChannelPairingProofVerifier] (no
 * [transportVerifier]) rejects a transport [start] with [IllegalStateException] before touching the
 * coordinator or the transport.
 */
class UsbPairingFlow(
    private val coordinator: PendingPairingCoordinator,
    private val channelVerifier: ChannelPairingProofVerifier,
    private val transportVerifier: TransportPairingProofVerifier? = null,
) {
    /** Uses [verifier] for both the [AccessoryIoSession] and the [TlsCiphertextTransport] paths. */
    constructor(coordinator: PendingPairingCoordinator, verifier: UsbTlsPairingProofVerifier) : this(
        coordinator,
        ChannelPairingProofVerifier(verifier::verify),
        TransportPairingProofVerifier(verifier::verify),
    )

    private data class HeldChannel(val pendingId: String, val channel: SslEngineUsbTlsEstablishedChannel)

    private var held: HeldChannel? = null

    /**
     * [session] is only ever read from or written to on the path that leads to a
     * [PendingPairingStartResult.PendingConfirmation] (via [channelVerifier], from
     * [PendingPairingCoordinator.startCore]). Every rejection path — including
     * [PendingPairingStartResult.Rejected.AlreadyPending], which returns before [channelVerifier] is
     * ever invoked — leaves [session] completely untouched; its ownership and lifecycle stay with
     * the caller in that case.
     */
    @Synchronized
    fun start(qrPayload: PairingQrPayload, session: AccessoryIoSession): ChannelPairingStartResult =
        startHolding { coordinator.start(qrPayload, session, channelVerifier) }

    /**
     * Transport-neutral overload (task g1): same contract as the session overload, with [transport]
     * in place of the session — untouched on every rejection reached before the proof step, and its
     * ownership stays with the caller there.
     *
     * @throws IllegalStateException if this flow was built without a [transportVerifier].
     */
    @Synchronized
    fun start(qrPayload: PairingQrPayload, transport: TlsCiphertextTransport): ChannelPairingStartResult {
        val verifier = checkNotNull(transportVerifier) { "UsbPairingFlow was built without a TransportPairingProofVerifier" }
        return startHolding { coordinator.start(qrPayload, transport, verifier) }
    }

    private fun startHolding(coordinatorStart: () -> ChannelPairingStartResult): ChannelPairingStartResult {
        // Reconcile a previous, never-confirmed/rejected pending that has since expired: without
        // this, the coordinator would still see it as pending and reject this new attempt with
        // AlreadyPending even though its own state()/TTL rules already consider it gone.
        expireIfNeeded()
        val outcome = coordinatorStart()
        val summary = (outcome.result as? PendingPairingStartResult.PendingConfirmation)?.summary
        // Only a new PendingConfirmation replaces `held`. A rejection — including AlreadyPending,
        // the coordinator's answer while a different pending is still live — must not disturb that
        // still-valid pending's held channel (defect s1b: this used to unconditionally null it out
        // here, leaking the channel and later making confirm() throw after the coordinator had
        // already persisted trust and activated). By construction there cannot be another live
        // pending at this point unless `held` is already stale, and `expireIfNeeded` above already
        // closed and cleared a stale one on entry.
        if (summary != null) {
            val channel = requireNotNull(outcome.channel) { "a PendingConfirmation must carry a live channel" }
            held = HeldChannel(summary.pendingId, channel)
        }
        return outcome
    }

    @Synchronized
    fun confirm(
        pendingId: String,
        trustedDesktopStore: TrustedDesktopStore,
        activeDesktopAuthority: ActiveDesktopAuthority,
    ): UsbPairingConfirmOutcome {
        // Deliberately does not pre-reconcile expiry here: PendingPairingCoordinator.confirm
        // already re-checks the pending's own expiry and reports the precise Rejected.Expired
        // reason; pre-clearing it first would only widen that to a less specific NoPendingPairing.
        val ours = held?.takeIf { it.pendingId == pendingId }
        val result = coordinator.confirm(pendingId, trustedDesktopStore, activeDesktopAuthority)
        if (ours != null) held = null
        if (result is PendingPairingConfirmResult.Activated) {
            // `ours` is guaranteed non-null here under exclusive ownership (see start()'s doc and
            // UsbPairingConfirmOutcome's): the coordinator only activates a pending that start()
            // itself created together with this held channel, and a rejected start no longer clears
            // it (defect s1b). The coordinator has already persisted trust and activated by this
            // point, so if that invariant were ever violated regardless, degrade to a null channel
            // instead of throwing — those side effects cannot be undone from here, and a crash would
            // only hide a successful activation from the caller.
            return UsbPairingConfirmOutcome(result, ours?.channel)
        }
        ours?.let { runCatching { it.channel.close() } }
        return UsbPairingConfirmOutcome(result, null)
    }

    @Synchronized
    fun reject(): PendingPairingCancelResult {
        val result = coordinator.cancel()
        held?.let { runCatching { it.channel.close() } }
        held = null
        return result
    }

    /**
     * Forces the same expiry reconciliation [start]/[state] already perform on entry. Returns
     * whether a stale channel was found and closed.
     */
    @Synchronized
    fun expire(): Boolean = expireIfNeeded()

    @Synchronized
    fun state(): PendingPairingState {
        expireIfNeeded()
        return coordinator.state()
    }

    private fun expireIfNeeded(): Boolean {
        val current = held ?: return false
        val stillPending = (coordinator.state() as? PendingPairingState.PendingConfirmation)?.summary?.pendingId == current.pendingId
        if (stillPending) return false
        runCatching { current.channel.close() }
        held = null
        return true
    }
}
