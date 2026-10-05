package dev.chinchillacam.usbprobe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Task c4a (`android-production-connection.md` §4.3, §4.4): the production [SessionLauncher] starts the
 * real session composition over a raw-stream TLS channel to a silent fake desktop, registers its sink
 * factory for the session-mode service, and drives a fake [CameraServiceControl].
 */
class ServiceSessionLauncherTest {
    private val registry = ActiveSessionSlot()
    private val camera = FakeCameraService()
    private val endExecutor = Executors.newSingleThreadExecutor { Thread(it, "c4a-end") }
    private val notices = CopyOnWriteArrayList<SessionEndNotice>()
    private val ended = CountDownLatch(1)
    private val onEnded: (SessionEndNotice) -> Unit = { notices += it; ended.countDown() }
    private val peer = AtomicReference<RawStreamTlsTestSupport.RawStreamServerPeer?>(null)
    private var server: Thread? = null

    @After
    fun tearDown() {
        endExecutor.shutdownNow()
        peer.get()?.close()
        server?.join(4_000)
    }

    private fun config(deadPeerThresholdMillis: Long) = SessionRuntimeConfig(
        keepaliveIntervalMillis = 100,
        deadPeerThresholdMillis = deadPeerThresholdMillis,
        writerTickMillis = 20,
        joinTimeoutMillis = 2_000,
    )

    private fun launcher(deadPeerThresholdMillis: Long = 5_000, cameraId: String? = "camera-1") = ServiceSessionLauncher(
        cameraService = camera,
        cameraIdProvider = { cameraId },
        registry = registry,
        endExecutor = endExecutor,
        onCameraControlCommand = {},
        config = config(deadPeerThresholdMillis),
    )

    /** A fake desktop that completes TLS and then never answers. */
    private fun reconnected(): UsbTrustedReconnectResult.Reconnected {
        val desktop = RawStreamTlsTestSupport.desktopFixture("c4a-desktop")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        server = thread {
            runCatching {
                val serverPeer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop).also { peer.set(it) }
                serverPeer.handshake()
                while (true) serverPeer.readApplicationFrame()
            }
        }
        val handshake = SslEngineUsbTlsChannel(phoneTlsIdentity = RawStreamTlsTestSupport.phoneFixture("c4a-phone"))
            .handshake(RawStreamTlsTestSupport.clientTransport(endpoints), desktop.spki)
        handshake as SslEngineUsbTlsHandshakeResult.Authenticated
        return UsbTrustedReconnectResult.Reconnected(
            desktopId = "c4a-desktop",
            channel = handshake.channel,
            frameAdapter = TlsSessionFrameIoAdapter(idleBudgetMillis = 3_000, readTimeoutMillis = 3_000),
            sessionId = "c4a-session",
            nextOutboundSequence = 1,
            nextInboundSequence = 2,
        )
    }

    @Test
    fun launchRegistersSinkAndStartsCamera() {
        val handle = launcher().launch(reconnected(), onEnded)
        try {
            assertEquals(listOf("camera-1"), camera.started)
            val registered = registry.current()
            assertNotNull("the session sink factory must be registered for the service", registered)
            assertNotNull(registered!!.entry.encodedVideoSinkFactory())
            assertEquals(0, camera.stops)
        } finally {
            handle.close()
        }
    }

    @Test
    fun peerSilenceStopsCameraAndNotifiesEndedWithMessage() {
        VisibleCameraServiceStatusStore.clearStopped("reset for test")
        val handle = launcher(deadPeerThresholdMillis = 400).launch(reconnected(), onEnded)
        try {
            assertTrue("the session end was not notified", ended.await(4, TimeUnit.SECONDS))
            val published = VisibleCameraServiceStatusStore.snapshot()
            assertEquals(VisibleCameraServiceState.Error, published.state)
            assertEquals(listOf(SessionEndNotice(published.message)), notices.toList())
            assertEquals("Se perdió la conexión con la computadora.", published.message)
            assertEquals(1, camera.stops)
            assertNull("a failed session must not stay registered", registry.current())
        } finally {
            handle.close()
        }
    }

    @Test
    fun serviceStoppedNotifiesEnded() {
        val handle = launcher().launch(reconnected(), onEnded)
        try {
            registry.notifyServiceStopped(registry.current()!!.token, null)

            assertTrue(ended.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(SessionEndNotice("Se detuvo la cámara.")), notices.toList())
        } finally {
            handle.close()
        }
    }

    @Test
    fun closeIsIdempotentAndClearsRegistry() {
        val handle = launcher().launch(reconnected(), onEnded)
        val token = registry.current()!!.token

        handle.close()
        handle.close()
        registry.notifyServiceStopped(token, "late")
        endExecutor.shutdown()
        assertTrue(endExecutor.awaitTermination(2, TimeUnit.SECONDS))

        assertNull(registry.current())
        assertEquals(1, camera.stops)
        assertTrue("a local close must not report an end", notices.isEmpty())
    }

    @Test
    fun missingCameraIdFailsLaunch() {
        val reconnected = reconnected()
        try {
            assertThrows(IllegalStateException::class.java) { launcher(cameraId = null).launch(reconnected, onEnded) }
            assertTrue(camera.started.isEmpty())
            assertNull(registry.current())
        } finally {
            reconnected.channel.close()
        }
    }

    @Test
    fun cameraStartFailureClosesCompositionAndClearsRegistry() {
        camera.failStart = true
        assertThrows(IllegalStateException::class.java) { launcher().launch(reconnected(), onEnded) }

        assertNull(registry.current())
        endExecutor.shutdown()
        assertTrue(endExecutor.awaitTermination(2, TimeUnit.SECONDS))
        assertTrue("a rolled-back launch must not report an end", notices.isEmpty())
    }

    private class FakeCameraService : CameraServiceControl {
        val started = CopyOnWriteArrayList<String>()
        @Volatile var stops = 0
        @Volatile var failStart = false

        override fun start(cameraId: String) {
            check(!failStart) { "camera service refused to start" }
            started += cameraId
        }

        override fun stop() {
            stops += 1
        }
    }
}
