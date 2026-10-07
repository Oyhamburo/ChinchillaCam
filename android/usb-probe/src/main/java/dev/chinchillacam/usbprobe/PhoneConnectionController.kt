package dev.chinchillacam.usbprobe

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Outcome of [AccessoryTransportSource.open]. */
sealed class AccessoryTransportOpenResult {
    data class Opened(val transport: TlsCiphertextTransport) : AccessoryTransportOpenResult()
    object NotAttached : AccessoryTransportOpenResult()
    object PermissionDenied : AccessoryTransportOpenResult()
    data class Failed(val detail: String) : AccessoryTransportOpenResult()
}

/** Port that opens a fresh ciphertext transport over the attached accessory (production adapter over `UsbManager` in c4). */
fun interface AccessoryTransportSource {
    fun open(): AccessoryTransportOpenResult
}

/** Why a launched session ended on its own; [message] is the Spanish text shown to the user. */
data class SessionEndNotice(val message: String, val cause: FailureCause? = null)

/** A running session (camera, encoder, egress). [close] stops all of it; it may block, so it is only called off the main thread. */
fun interface ActiveSessionHandle {
    fun close()
}

/** Port that starts the session composition over an authenticated channel (production in c4). */
fun interface SessionLauncher {
    /** [onEnded] may be invoked from any thread when the session ends without [ActiveSessionHandle.close]. */
    fun launch(reconnected: UsbTrustedReconnectResult.Reconnected, onEnded: (SessionEndNotice) -> Unit): ActiveSessionHandle
}

sealed class AccessoryPurpose {
    object Pairing : AccessoryPurpose()
    data class Connect(val desktopId: String) : AccessoryPurpose()
}

sealed class PhoneConnectionState {
    data class Idle(val notice: String? = null, val cause: FailureCause? = null) : PhoneConnectionState()
    data class AwaitingAccessory(val purpose: AccessoryPurpose) : PhoneConnectionState()
    data class ConfirmPairing(val desktopName: String, val shortCode: PairingShortCode) : PhoneConnectionState()
    data class AwaitingDesktopConfirmation(val desktopName: String) : PhoneConnectionState()
    data class Connecting(val desktopName: String) : PhoneConnectionState()
    data class Connected(val desktopId: String, val desktopName: String) : PhoneConnectionState()
    data class Failed(val message: String, val kind: ConnectionFailureKind? = null) : PhoneConnectionState()
}

/**
 * Process-level owner of pairing and connection (task c3, `android-production-connection` §4.3).
 * Pure domain: every collaborator is injected and no Android type is referenced.
 *
 * Threading: every operation is posted to [worker], which must be a serial executor that queues
 * (single thread in production; never the main thread). All mutable state is confined to it, and
 * listeners are invoked on it. Blocking steps (TLS, HELLO/ACCEPT, [ActiveSessionHandle.close])
 * therefore run off the main thread, and an operation queued behind one runs once it finishes. An
 * operation that is not valid in the current state is ignored. Every way out of `Connected`
 * (disconnect, forget, cable detached, session ended) releases [authority].
 *
 * Fail closed: once [UsbPairingFlow.confirm] has persisted and activated a desktop, any failure to
 * start its first session rolls back both the authority and the phone-side trust, so both sides
 * agree that nothing was paired.
 */
class PhoneConnectionController(
    private val accessorySource: AccessoryTransportSource,
    private val pairingFlow: UsbPairingFlow,
    private val pairedSessionStarter: PairedSessionStarter,
    private val reconnect: UsbTrustedReconnect,
    private val store: TrustedDesktopStore,
    private val authority: ActiveDesktopAuthority,
    phoneSpki: ByteArray,
    private val sessionLauncher: SessionLauncher,
    private val worker: Executor,
    private val epochSecondsSource: EpochSecondsSource,
) {
    private class LiveSession(val desktopId: String, val handle: ActiveSessionHandle)

    private val phoneSpki = phoneSpki.copyOf()
    private val listeners = CopyOnWriteArrayList<(PhoneConnectionState) -> Unit>()

    @Volatile private var state: PhoneConnectionState = PhoneConnectionState.Idle()
    @Volatile private var lastDesktop: String? = null

    fun lastDesktopId(): String? = lastDesktop

    // Worker-confined.
    private var pendingQr: PairingQrPayload? = null
    private var pendingId: String? = null
    private var session: LiveSession? = null

    fun snapshot(): PhoneConnectionState = state

    /** [listener] is called on the worker thread after every state change. */
    fun addListener(listener: (PhoneConnectionState) -> Unit) {
        listeners += listener
    }

    /** Stops notifying [listener] (same instance passed to [addListener]); a notification already running may still complete. */
    fun removeListener(listener: (PhoneConnectionState) -> Unit) {
        listeners -= listener
    }

    /** Trusted (non-revoked) desktops to offer for [connect]. */
    fun trustedDesktops(): List<TrustedDesktopRecord> = store.list().filter { it.revokedAtEpochSeconds == null }

    /** Valid from `Idle`/`Failed`. */
    fun qrScanned(text: String) = post {
        if (!isIdle()) return@post
        val qr = PairingQrPayloadCodec.decode(text, epochSecondsSource.nowEpochSeconds()).getOrElse { error ->
            fail(if (error is PairingQrPayloadDecodeError.Expired) ConnectionFailureKind.QR_EXPIRED else ConnectionFailureKind.QR_INVALID)
            return@post
        }
        pendingQr = qr
        openFor(AccessoryPurpose.Pairing)
    }

    /** Resumes an `AwaitingAccessory` pairing or connection. */
    fun accessoryAttached() = post {
        val awaiting = state as? PhoneConnectionState.AwaitingAccessory ?: return@post
        openFor(awaiting.purpose)
    }

    /** Valid in `ConfirmPairing`. */
    fun confirmPairing() = post {
        val confirming = state as? PhoneConnectionState.ConfirmPairing ?: return@post
        val id = pendingId ?: return@post
        clearPending()
        val outcome = runCatching { pairingFlow.confirm(id, store, authority) }.getOrElse {
            fail(ConnectionFailureKind.PAIRING_FAILED)
            return@post
        }
        val channel = outcome.channel
        when (val result = outcome.result) {
            is PendingPairingConfirmResult.Activated -> if (channel == null) {
                rollBackPairing(result.desktopId)
                return@post fail(ConnectionFailureKind.PAIRING_FAILED)
            } else {
                startFirstSession(result.desktopId, confirming.desktopName, channel)
            }
            is PendingPairingConfirmResult.TrustedButInactive -> {
                runCatching { store.forget(result.desktopId) }
                fail(ConnectionFailureKind.SECOND_ACTIVE_DESKTOP)
            }
            is PendingPairingConfirmResult.Rejected -> fail(ConnectionFailureKind.PAIRING_FAILED)
        }
    }

    /** Valid in `ConfirmPairing`. */
    fun rejectPairing() = post {
        if (state !is PhoneConnectionState.ConfirmPairing) return@post
        runCatching { pairingFlow.reject() }
        clearPending()
        publish(PhoneConnectionState.Idle())
    }

    /** Valid in `AwaitingAccessory`: drops any pending QR so a later cable attach does nothing. */
    fun cancel() = post {
        if (state !is PhoneConnectionState.AwaitingAccessory) return@post
        clearPending()
        publish(PhoneConnectionState.Idle())
    }

    /** Valid from `Idle`/`Failed`. */
    fun connect(desktopId: String) = post {
        if (!isIdle()) return@post
        lastDesktop = desktopId
        openFor(AccessoryPurpose.Connect(desktopId))
    }

    /** Valid in `Connected`: closes the session and releases the authority. */
    fun disconnect() = post { endSession(PhoneConnectionMessages.DISCONNECTED) }

    /** Disconnects first if connected to [desktopId], then forgets it; listeners are re-notified so the list can refresh. */
    fun forget(desktopId: String) = post {
        if (session?.desktopId == desktopId) endSession(PhoneConnectionMessages.DISCONNECTED)
        if (runCatching { store.forget(desktopId) }.isFailure) fail(ConnectionFailureKind.FORGET_FAILED) else {
            if (lastDesktop == desktopId) lastDesktop = null
            publish(state)
        }
    }

    /**
     * Ends a `Connected` session or cancels a `ConfirmPairing`. `AwaitingDesktopConfirmation` and
     * `Connecting` only exist while the worker is blocked on the channel, which a detached cable
     * fails on its own (handled like any other failure there).
     */
    fun accessoryDetached() = post {
        when (state) {
            is PhoneConnectionState.Connected -> endSession(PhoneConnectionMessages.USB_DETACHED)
            is PhoneConnectionState.ConfirmPairing -> {
                runCatching { pairingFlow.reject() }
                clearPending()
                fail(ConnectionFailureKind.USB_DETACHED)
            }
            else -> Unit
        }
    }

    private fun openFor(purpose: AccessoryPurpose) {
        when (val opened = runCatching { accessorySource.open() }.getOrElse { AccessoryTransportOpenResult.Failed(it.toString()) }) {
            is AccessoryTransportOpenResult.Opened -> when (purpose) {
                AccessoryPurpose.Pairing -> startPairing(opened.transport)
                is AccessoryPurpose.Connect -> reconnectTo(purpose.desktopId, opened.transport)
            }
            AccessoryTransportOpenResult.NotAttached -> publish(PhoneConnectionState.AwaitingAccessory(purpose))
            AccessoryTransportOpenResult.PermissionDenied -> abandon(ConnectionFailureKind.USB_PERMISSION_DENIED)
            is AccessoryTransportOpenResult.Failed -> abandon(ConnectionFailureKind.USB_OPEN_FAILED)
        }
    }

    private fun startPairing(transport: TlsCiphertextTransport) {
        val qr = pendingQr ?: run {
            runCatching { transport.close() }
            return
        }
        val started = runCatching { pairingFlow.start(qr, transport).result }.getOrNull()
        val summary = (started as? PendingPairingStartResult.PendingConfirmation)?.summary
        if (summary == null) {
            // Rejections before the proof step leave the transport with us; closing twice is harmless.
            runCatching { transport.close() }
            val expired = started is PendingPairingStartResult.Rejected.Expired
            return abandon(if (expired) ConnectionFailureKind.QR_EXPIRED else ConnectionFailureKind.PAIRING_FAILED)
        }
        pendingId = summary.pendingId
        val shortCode = PairingShortCode.derive(qr.trustMaterial, phoneSpki, summary.qrNonce, summary.challengeNonce)
        publish(PhoneConnectionState.ConfirmPairing(summary.desktopName, shortCode))
    }

    private fun startFirstSession(desktopId: String, desktopName: String, channel: SslEngineUsbTlsEstablishedChannel) {
        publish(PhoneConnectionState.AwaitingDesktopConfirmation(desktopName))
        val started = runCatching { pairedSessionStarter.start(desktopId, channel) }.getOrElse {
            runCatching { channel.close() }
            PairedSessionStartResult.ExchangeFailed(desktopId, it.toString())
        }
        when (started) {
            is PairedSessionStartResult.Started -> launchSession(started.reconnected, desktopName)
            is PairedSessionStartResult.Rejected -> {
                rollBackPairing(desktopId)
                fail(PhoneConnectionMessages.kindForRejection(started.rejection))
            }
            is PairedSessionStartResult.ExchangeFailed -> {
                rollBackPairing(desktopId)
                fail(ConnectionFailureKind.PAIRING_FAILED)
            }
        }
    }

    private fun reconnectTo(desktopId: String, transport: TlsCiphertextTransport) {
        val desktopName = runCatching { store.lookup(desktopId)?.desktopName }.getOrNull() ?: desktopId
        publish(PhoneConnectionState.Connecting(desktopName))
        val result = runCatching { reconnect.reconnect(desktopId, transport, store, authority) }.getOrElse {
            runCatching { transport.close() }
            return fail(ConnectionFailureKind.CONNECTION_FAILED)
        }
        when (result) {
            is UsbTrustedReconnectResult.Reconnected -> launchSession(result, desktopName)
            is UsbTrustedReconnectResult.Rejected -> fail(PhoneConnectionMessages.kindForRejection(result))
        }
    }

    private fun launchSession(reconnected: UsbTrustedReconnectResult.Reconnected, desktopName: String) {
        publish(PhoneConnectionState.Connecting(desktopName))
        var launched: LiveSession? = null
        val onEnded: (SessionEndNotice) -> Unit = { notice ->
            // Posted, so it runs after `launched` is set even if launch() calls back synchronously.
            runCatching { post { launched?.let { sessionEnded(it, notice) } } }
        }
        val handle = runCatching { sessionLauncher.launch(reconnected, onEnded) }.getOrElse {
            runCatching { reconnected.channel.close() }
            authority.stopActiveDesktop(reconnected.desktopId)
            return fail(ConnectionFailureKind.SESSION_START_FAILED)
        }
        lastDesktop = reconnected.desktopId
        launched = LiveSession(reconnected.desktopId, handle).also { session = it }
        publish(PhoneConnectionState.Connected(reconnected.desktopId, desktopName))
    }

    /** Only ends [live] if it is still the current session, so a late notice never ends a newer one. */
    private fun sessionEnded(live: LiveSession, notice: SessionEndNotice) {
        if (session !== live) return
        session = null
        // The session already ended; closing is idempotent and releases whatever it still holds.
        runCatching { live.handle.close() }
        authority.stopActiveDesktop(live.desktopId)
        publish(PhoneConnectionState.Idle(notice.message, notice.cause))
    }

    private fun endSession(notice: String) {
        val live = session ?: return
        session = null
        runCatching { live.handle.close() }
        authority.stopActiveDesktop(live.desktopId)
        publish(PhoneConnectionState.Idle(notice))
    }

    private fun rollBackPairing(desktopId: String) {
        authority.stopActiveDesktop(desktopId)
        runCatching { store.forget(desktopId) }
    }

    /** Fails a pairing/connection attempt that never got a pending, dropping any kept QR. */
    private fun abandon(kind: ConnectionFailureKind) {
        clearPending()
        fail(kind)
    }

    private fun clearPending() {
        pendingQr = null
        pendingId = null
    }

    private fun isIdle(): Boolean = state is PhoneConnectionState.Idle || state is PhoneConnectionState.Failed

    private fun fail(kind: ConnectionFailureKind) = publish(PhoneConnectionState.Failed(PhoneConnectionMessages.messageFor(kind), kind))

    private fun publish(next: PhoneConnectionState) {
        state = next
        listeners.forEach { listener -> runCatching { listener(next) } }
    }

    private fun post(action: () -> Unit) = worker.execute(action)
}
