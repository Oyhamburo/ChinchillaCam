package dev.chinchillacam.usbprobe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * TDD for task c3 (`odd/tasks/android-production-connection.md` §4.3): [PhoneConnectionController]
 * composed from the real pairing/session collaborators over raw-stream transports
 * ([RawStreamTlsTestSupport]) against a fake desktop thread per accessory open.
 */
class PhoneConnectionControllerTest {
    private val desktop = RawStreamTlsTestSupport.desktopFixture("c3-desktop")
    private val phone = RawStreamTlsTestSupport.phoneFixture("c3-phone")
    private val worker = Executors.newSingleThreadExecutor()
    private val store = InMemoryTrustedDesktopStore()
    private val authority = ActiveDesktopAuthority()
    private val source = FakeAccessorySource()
    private val launcher = FakeSessionLauncher()
    private val clock = EpochSecondsSource { 1_000 }

    private val controller: PhoneConnectionController by lazy {
        val coordinator = PendingPairingCoordinator(
            epochSecondsSource = clock,
            challengeNonceSource = object : ChallengeNonceSource {
                override fun nextChallenge() = PairingChallengeMaterial(CHALLENGE_NONCE, "session-1", expiresAtEpochSeconds = 1_200)
            },
            proofVerifier = object : PairingProofVerifier {
                override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult =
                    error("byte-based proof path must not be used")
            },
        )
        PhoneConnectionController(
            accessorySource = source,
            pairingFlow = UsbPairingFlow(coordinator, UsbTlsPairingProofVerifier(clock, SslEngineUsbTlsChannel(phoneTlsIdentity = phone))),
            pairedSessionStarter = PairedSessionStarter(phoneTlsIdentity = phone, helloTimeoutMillis = 3_000),
            reconnect = UsbTrustedReconnect(clock, phone, helloTimeoutMillis = 3_000),
            store = store,
            authority = authority,
            phoneSpki = phone.subjectPublicKeyInfoDer,
            sessionLauncher = launcher,
            worker = worker,
            epochSecondsSource = clock,
        )
    }

    @After
    fun tearDown() {
        launcher.launches.forEach { runCatching { it.reconnected.channel.close() } }
        worker.shutdownNow()
        worker.awaitTermination(2, TimeUnit.SECONDS)
        source.finish()
    }

    @Test
    fun scannedQrPairsConfirmsAndConnects() {
        source.enqueue(Desktop.PairThenAccept)
        controller.qrScanned(qrText())

        val confirm = awaitState<PhoneConnectionState.ConfirmPairing>()
        val expected = PairingShortCode.derive(desktop.spki, phone.subjectPublicKeyInfoDer, QR_NONCE, CHALLENGE_NONCE)
        assertEquals(PhoneConnectionState.ConfirmPairing("Studio", expected), confirm)

        controller.confirmPairing()
        assertEquals(PhoneConnectionState.Connected("pc-1", "Studio"), awaitState<PhoneConnectionState.Connected>())
        assertEquals(listOf("pc-1"), launcher.launches.map { it.reconnected.desktopId })
        assertNotNull(store.lookup("pc-1"))
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun qrBeforeCableWaitsForAccessory() {
        source.attached = false
        source.enqueue(Desktop.PairOnly)
        controller.qrScanned(qrText())
        assertEquals(PhoneConnectionState.AwaitingAccessory(AccessoryPurpose.Pairing), awaitState<PhoneConnectionState.AwaitingAccessory>())

        source.attached = true
        controller.accessoryAttached()
        assertEquals("Studio", awaitState<PhoneConnectionState.ConfirmPairing>().desktopName)
        controller.rejectPairing()
        assertEquals(PhoneConnectionState.Idle(), awaitState<PhoneConnectionState.Idle>())
    }

    @Test
    fun cancelWhileAwaitingAccessoryReturnsToIdle() {
        source.attached = false
        controller.qrScanned(qrText())
        awaitState<PhoneConnectionState.AwaitingAccessory>()

        controller.cancel()
        assertEquals(PhoneConnectionState.Idle(), awaitState<PhoneConnectionState.Idle>())
        // The pending QR is gone: a later cable attach neither pairs nor opens the accessory.
        source.attached = true
        controller.accessoryAttached()
        drainWorker()
        assertEquals(PhoneConnectionState.Idle(), controller.snapshot())
    }

    @Test
    fun desktopRejectingFirstSessionRollsBackTrustAndAuthority() {
        source.enqueue(Desktop.PairThenReject)
        controller.qrScanned(qrText())
        awaitState<PhoneConnectionState.ConfirmPairing>()
        controller.confirmPairing()

        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.DESKTOP_REJECTED, ConnectionFailureKind.DESKTOP_REJECTED), awaitState<PhoneConnectionState.Failed>())
        assertEquals(null, store.lookup("pc-1"))
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertTrue(launcher.launches.isEmpty())
    }

    @Test
    fun controlTargetsOnlyCurrentLiveSession() {
        assertFalse(controller.sendControl(QUALITY_STATE, mapOf("v" to "1")))
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept)
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()
        val payload = QUALITY_STATE to mapOf("v" to "1")
        assertTrue(controller.sendControl(payload.first, payload.second))
        assertEquals(listOf(payload), launcher.launches[0].controls.toList())
        controller.disconnect()
        awaitState<PhoneConnectionState.Idle>()
        assertFalse(controller.sendControl(payload.first, payload.second))
    }

    @Test
    fun connectToTrustedDesktopReconnects() {
        seedTrust()
        assertEquals(listOf("pc-1"), controller.trustedDesktops().map { it.desktopId })
        source.enqueue(Desktop.ReconnectAccept)
        controller.connect("pc-1")

        assertEquals(PhoneConnectionState.Connected("pc-1", "Studio"), awaitState<PhoneConnectionState.Connected>())
        assertEquals(listOf("pc-1"), launcher.launches.map { it.reconnected.desktopId })
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun retryReconnectsLastDesktopAfterSessionFailureAndForgetClearsIt() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept, Desktop.ReconnectAccept)
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()
        assertEquals("pc-1", controller.lastDesktopId())
        launcher.launches[0].onEnded(SessionEndNotice("Se perdió la conexión.", FailureCause.SessionPeerDead))
        awaitState<PhoneConnectionState.Idle>()
        controller.lastDesktopId()?.let(controller::connect)
        awaitState<PhoneConnectionState.Connected>()
        assertEquals(listOf("pc-1", "pc-1"), launcher.launches.map { it.reconnected.desktopId })
        controller.forget("pc-1")
        drainWorker()
        assertEquals(null, controller.lastDesktopId())
    }

    @Test
    fun failedConnectRemembersAttemptedDesktopForManualRetry() {
        seedTrust()
        source.attached = false
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.AwaitingAccessory>()
        assertEquals("pc-1", controller.lastDesktopId())
    }

    @Test
    fun launchFailureClosesChannelAndReleasesAuthority() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept)
        launcher.failNext = true
        controller.connect("pc-1")

        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.SESSION_START_FAILED, ConnectionFailureKind.SESSION_START_FAILED), awaitState<PhoneConnectionState.Failed>())
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertNotNull("trust survives a launch failure", store.lookup("pc-1"))
    }

    @Test
    fun sessionEndReturnsToIdleWithNoticeAndReleasesAuthority() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept, Desktop.ReconnectAccept)
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()

        thread { launcher.launches[0].onEnded(SessionEndNotice("La sesión terminó.")) }.join(1_000)
        assertEquals(PhoneConnectionState.Idle("La sesión terminó."), awaitState<PhoneConnectionState.Idle>())
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)

        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()
        assertEquals(2, launcher.launches.size)
        // A late end notice from the first session must not end the second one.
        launcher.launches[0].onEnded(SessionEndNotice("tarde"))
        drainWorker()
        assertEquals(PhoneConnectionState.Connected("pc-1", "Studio"), controller.snapshot())
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun sessionFailuresRetainTheirCauseInIdleState() {
        seedTrust()
        val ends = listOf(
            SessionEnd.PeerDead, SessionEnd.Backpressure,
            SessionEnd.ReadFailed("platform-secret"), SessionEnd.WriteFailed("platform-secret"),
            SessionEnd.ProtocolViolation("platform-secret"),
        )
        ends.forEachIndexed { index, end ->
            source.enqueue(Desktop.ReconnectAccept)
            controller.connect("pc-1")
            awaitState<PhoneConnectionState.Connected>()
            val cause = UserFailureCatalog.causeFor(end)
            launcher.launches[index].onEnded(SessionEndNotice(SessionEgressBinding.messageForEnd(end), cause))
            val idle = awaitState<PhoneConnectionState.Idle>()
            assertEquals(cause, idle.cause)
            assertEquals(UserFailureCatalog.messageFor(cause), idle.notice)
            assertTrue(idle.notice?.contains("platform-secret") == false)
        }
    }

    @Test
    fun disconnectClosesHandleAndReleasesAuthority() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept)
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()

        controller.disconnect()
        assertEquals(PhoneConnectionState.Idle(PhoneConnectionMessages.DISCONNECTED), awaitState<PhoneConnectionState.Idle>())
        val launch = launcher.launches[0]
        assertEquals("close() runs on the worker", workerThread(), launch.closedOn)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        launch.onEnded(SessionEndNotice("tarde"))
        drainWorker()
        assertEquals(PhoneConnectionState.Idle(PhoneConnectionMessages.DISCONNECTED), controller.snapshot())
    }

    @Test
    fun forgetConnectedDesktopDisconnectsThenForgets() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept)
        controller.connect("pc-1")
        awaitState<PhoneConnectionState.Connected>()

        controller.forget("pc-1")
        assertEquals(PhoneConnectionState.Idle(PhoneConnectionMessages.DISCONNECTED), awaitState<PhoneConnectionState.Idle>())
        drainWorker()
        assertEquals("the session closes before trust is removed", true, launcher.launches[0].trustedAtClose)
        assertEquals(null, store.lookup("pc-1"))
        assertTrue(controller.trustedDesktops().isEmpty())
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    @Test
    fun cableDetachedDuringConfirmationFails() {
        source.enqueue(Desktop.PairOnly)
        controller.qrScanned(qrText())
        awaitState<PhoneConnectionState.ConfirmPairing>()

        controller.accessoryDetached()
        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.USB_DETACHED, ConnectionFailureKind.USB_DETACHED), awaitState<PhoneConnectionState.Failed>())
        controller.confirmPairing()
        drainWorker()
        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.USB_DETACHED, ConnectionFailureKind.USB_DETACHED), controller.snapshot())
        assertEquals(null, store.lookup("pc-1"))
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        // tearDown() then proves the pairing channel was closed: the fake desktop only exits on close.
    }

    @Test
    fun invalidQrFails() {
        controller.qrScanned("CHINCHILLACAM-PAIR:v1:garbage")
        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.QR_INVALID, ConnectionFailureKind.QR_INVALID), awaitState<PhoneConnectionState.Failed>())
        controller.qrScanned(qrText(expiresAt = 900))
        awaitState<PhoneConnectionState.Failed> { it.message == PhoneConnectionMessages.QR_EXPIRED }
    }

    @Test
    fun removedListenerIsNoLongerNotified() {
        val kept = CopyOnWriteArrayList<PhoneConnectionState>()
        val removed = CopyOnWriteArrayList<PhoneConnectionState>()
        val removedListener: (PhoneConnectionState) -> Unit = { removed += it }
        controller.addListener { kept += it }
        controller.addListener(removedListener)
        controller.removeListener(removedListener)

        controller.qrScanned("CHINCHILLACAM-PAIR:v1:garbage")
        awaitState<PhoneConnectionState.Failed>()
        drainWorker()
        assertEquals(listOf<PhoneConnectionState>(PhoneConnectionState.Failed(PhoneConnectionMessages.QR_INVALID, ConnectionFailureKind.QR_INVALID)), kept)
        assertTrue(removed.isEmpty())
    }

    private fun workerThread(): Thread = worker.submit<Thread> { Thread.currentThread() }.get(5, TimeUnit.SECONDS)

    /** Waits until every operation already posted to the worker has run. */
    private fun drainWorker() {
        workerThread()
    }

    private fun seedTrust() = store.save(
        TrustedDesktopRecord("pc-1", "Studio", PairingTrustFingerprint.fromTrustMaterial(desktop.spki).bytes, 1_000, 1_000),
    )

    private fun qrText(expiresAt: Long = 1_200): String =
        PairingQrPayloadCodec.encode(PairingQrPayload("pc-1", "Studio", desktop.spki.copyOf(), expiresAt, QR_NONCE))

    private inline fun <reified T : PhoneConnectionState> awaitState(matches: (T) -> Boolean = { true }): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            (controller.snapshot() as? T)?.takeIf(matches)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("expected ${T::class.simpleName}, still ${controller.snapshot()}")
    }

    /** Scripted fake-desktop behaviours, one consumed per accessory open. */
    private enum class Desktop { PairOnly, PairThenAccept, PairThenReject, ReconnectAccept }

    private inner class FakeAccessorySource : AccessoryTransportSource {
        @Volatile var attached = true
        private val scripts = CopyOnWriteArrayList<Desktop>()
        private val servers = CopyOnWriteArrayList<Thread>()
        private val serverError = AtomicReference<Throwable?>(null)

        fun enqueue(vararg desktops: Desktop) = scripts.addAll(desktops)

        override fun open(): AccessoryTransportOpenResult {
            if (!attached) return AccessoryTransportOpenResult.NotAttached
            val script = scripts.removeAt(0)
            val endpoints = RawStreamTlsTestSupport.rawStreamPair()
            servers += thread {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                try {
                    peer.handshake()
                    if (script != Desktop.ReconnectAccept) answerPairingProof(peer)
                    if (script == Desktop.PairOnly) return@thread waitForPhoneClose(peer)
                    val hello = RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame())
                    val answer = if (script == Desktop.PairThenReject) {
                        SessionPayload.HandshakeReject("PAIRING_DECLINED", "no")
                    } else {
                        SessionPayload.HandshakeAccept("pc-1", "welcome")
                    }
                    peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(hello.copy(sequence = hello.sequence + 1, payload = answer)))
                    waitForPhoneClose(peer)
                } catch (error: Throwable) {
                    serverError.set(error)
                } finally {
                    peer.close()
                }
            }
            return AccessoryTransportOpenResult.Opened(RawStreamTlsTestSupport.clientTransport(endpoints))
        }

        private fun waitForPhoneClose(peer: RawStreamTlsTestSupport.RawStreamServerPeer) {
            runCatching { while (true) peer.readApplicationFrame() }
        }

        private fun answerPairingProof(peer: RawStreamTlsTestSupport.RawStreamServerPeer) {
            val request = PairingProofProtocol.parseRequest(peer.readApplicationFrame())
            peer.writeApplicationFrame(
                PairingProofProtocol.encodeResponse(
                    PairingProofProtocol.ProofResponse(0, request.desktopId, request.qrNonce, request.challengeNonce, request.sessionId),
                ),
            )
        }

        fun finish() {
            servers.forEach { it.join(2_000) }
            serverError.get()?.let { throw it }
            assertTrue("fake desktops must not hang", servers.none { it.isAlive })
        }
    }

    private class Launch(val reconnected: UsbTrustedReconnectResult.Reconnected, val onEnded: (SessionEndNotice) -> Unit) {
        @Volatile var closedOn: Thread? = null
        @Volatile var trustedAtClose: Boolean? = null
        val controls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    }

    private inner class FakeSessionLauncher : SessionLauncher {
        val launches = CopyOnWriteArrayList<Launch>()
        @Volatile var failNext = false

        override fun launch(
            reconnected: UsbTrustedReconnectResult.Reconnected,
            onEnded: (SessionEndNotice) -> Unit,
        ): ActiveSessionHandle {
            val launch = Launch(reconnected, onEnded).also { launches += it }
            if (failNext) error("launch failed")
            return object : ActiveSessionHandle {
                override fun close() {
                    launch.closedOn = Thread.currentThread()
                    launch.trustedAtClose = store.lookup(reconnected.desktopId) != null
                    runCatching { reconnected.channel.close() }
                }

                override fun sendControl(command: String, arguments: Map<String, String>): Boolean {
                    launch.controls += command to arguments
                    return true
                }
            }
        }
    }

    private companion object {
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
    }
}
