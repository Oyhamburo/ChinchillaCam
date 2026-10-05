package dev.chinchillacam.usbprobe

import org.junit.After
import org.junit.Assert.assertEquals
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
        launcher.launches.forEach { runCatching { it.channel.close() } }
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
        assertEquals(listOf("pc-1"), launcher.launches.map { it.desktopId })
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
    fun desktopRejectingFirstSessionRollsBackTrustAndAuthority() {
        source.enqueue(Desktop.PairThenReject)
        controller.qrScanned(qrText())
        awaitState<PhoneConnectionState.ConfirmPairing>()
        controller.confirmPairing()

        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.DESKTOP_REJECTED), awaitState<PhoneConnectionState.Failed>())
        assertEquals(null, store.lookup("pc-1"))
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertTrue(launcher.launches.isEmpty())
    }

    @Test
    fun connectToTrustedDesktopReconnects() {
        seedTrust()
        assertEquals(listOf("pc-1"), controller.trustedDesktops().map { it.desktopId })
        source.enqueue(Desktop.ReconnectAccept)
        controller.connect("pc-1")

        assertEquals(PhoneConnectionState.Connected("pc-1", "Studio"), awaitState<PhoneConnectionState.Connected>())
        assertEquals(listOf("pc-1"), launcher.launches.map { it.desktopId })
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun launchFailureClosesChannelAndReleasesAuthority() {
        seedTrust()
        source.enqueue(Desktop.ReconnectAccept)
        launcher.failNext = true
        controller.connect("pc-1")

        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.SESSION_START_FAILED), awaitState<PhoneConnectionState.Failed>())
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
        assertNotNull("trust survives a launch failure", store.lookup("pc-1"))
    }

    @Test
    fun invalidQrFails() {
        controller.qrScanned("CHINCHILLACAM-PAIR:v1:garbage")
        assertEquals(PhoneConnectionState.Failed(PhoneConnectionMessages.QR_INVALID), awaitState<PhoneConnectionState.Failed>())
        controller.qrScanned(qrText(expiresAt = 900))
        awaitState<PhoneConnectionState.Failed> { it.message == PhoneConnectionMessages.QR_EXPIRED }
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

    private class FakeSessionLauncher : SessionLauncher {
        val launches = CopyOnWriteArrayList<UsbTrustedReconnectResult.Reconnected>()
        @Volatile var failNext = false

        override fun launch(reconnected: UsbTrustedReconnectResult.Reconnected): ActiveSessionHandle {
            launches += reconnected
            if (failNext) error("launch failed")
            return ActiveSessionHandle { runCatching { reconnected.channel.close() } }
        }
    }

    private companion object {
        val QR_NONCE = ByteArray(16) { it.toByte() }
        val CHALLENGE_NONCE = ByteArray(32) { (it + 1).toByte() }
    }
}
