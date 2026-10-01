package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SslEngineUsbTlsChannelTest {
    @Test
    fun completesPinnedClientHandshakeOverUsbCiphertextFrames() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        val server = thread { serverHandshake(fixture.context, pair.server) }

        val result = SslEngineUsbTlsChannel().handshake(pair.client, fixture.certificate.publicKey.encoded)

        assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
        result as SslEngineUsbTlsHandshakeResult.Authenticated
        assertTrue(result.protocol == "TLSv1.2" || result.protocol == "TLSv1.3")
        result.close()
        server.join(2000)
        assertEquals(false, server.isAlive)
    }

    @Test
    fun handshakeRunsOverInjectedCiphertextTransport() {
        val fixture = TlsFixture.create("transport-seam")
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val server = thread {
            try {
                val serverChannel = serverHandshake(fixture.context, pair.server)
                val request = serverChannel.readApplicationFrame()
                assertTrue(request.contentEquals("ping".toByteArray()))
                serverChannel.writeApplicationFrame("pong".toByteArray())
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val transport = RecordingCiphertextTransport(UsbAccessoryTlsCiphertextTransport(pair.client))
        val result = SslEngineUsbTlsChannel().handshake(transport, fixture.certificate.publicKey.encoded)

        try {
            assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
            result as SslEngineUsbTlsHandshakeResult.Authenticated
            result.channel.writeApplicationData("ping".toByteArray())
            val echoed = result.channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
            assertTrue(echoed.contentEquals("pong".toByteArray()))
            server.join(2000)
            serverError.get()?.let { throw it }
            // The handshake and the round trip both went through the injected transport.
            assertTrue("expected the injected transport to record ciphertext writes", transport.writes > 0)
            assertTrue("expected the injected transport to record ciphertext reads", transport.reads > 0)
            result.close()
        } finally {
            pair.close()
        }
    }

    @Test
    fun concurrentReadAndWritePreserveFrameIntegrity() {
        // Characterization pin (task r1): a phone reader and a phone writer run concurrently over
        // the serialized channel and every frame must arrive intact in both directions. A
        // post-handshake wrap on the read path (TLS 1.3 KeyUpdate) is NOT forced, because JSSE's
        // SSLEngine exposes no API to trigger one, so this covers concurrent read+write only; the
        // write lock itself is pinned by concurrentApplicationWritesAreSerialized below.
        val desktop = RawStreamTlsTestSupport.desktopFixture("r1-duplex-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r1-duplex-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val frames = 40
        val serverError = AtomicReference<Throwable?>(null)
        val fromPhone = java.util.concurrent.ConcurrentLinkedQueue<SessionFrame>()
        fun phoneFrame(i: Int) = SessionFrame(sequence = i, sessionId = "r1-phone", payload = SessionPayload.CameraControlCommand("p", mapOf("i" to i.toString())))
        fun peerFrame(i: Int) = SessionFrame(sequence = i, sessionId = "r1-peer", payload = SessionPayload.CameraControlCommand("d", mapOf("i" to i.toString())))

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                val peerWriter = thread {
                    for (i in 0 until frames) peer.writeApplicationFrame(RawStreamTlsTestSupport.encodeFramed(peerFrame(i)))
                }
                for (i in 0 until frames) fromPhone.add(RawStreamTlsTestSupport.decodeFramed(peer.readApplicationFrame()))
                peerWriter.join(4_000)
                Thread.sleep(200)
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val transport = RawStreamTlsTestSupport.clientTransport(endpoints)
        val channel = (SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(transport, desktop.spki)
            as SslEngineUsbTlsHandshakeResult.Authenticated).channel
        val adapter = TlsSessionFrameIoAdapter()
        val received = ArrayList<SessionFrame>(frames)

        try {
            val phoneWriter = thread {
                for (i in 0 until frames) adapter.write(channel, phoneFrame(i))
            }
            for (i in 0 until frames) received.add(adapter.read(channel))
            phoneWriter.join(4_000)
        } finally {
            runCatching { channel.close() }
            server.join(4_000)
        }

        serverError.get()?.let { throw it }
        assertEquals((0 until frames).map { peerFrame(it) }, received)
        assertEquals((0 until frames).map { phoneFrame(it) }, fromPhone.toList())
    }

    @Test
    fun concurrentApplicationWritesAreSerialized() {
        // Lock pin (task r1): two phone threads write frames concurrently. The single write lock
        // must serialize engine.wrap + transport.writeCiphertext so ciphertext production never
        // overlaps; without it, concurrent SSLEngine.wrap corrupts the stream or the overlap is
        // observed directly. Ciphertext piles in the 256 KiB pipe buffer (no peer reader is needed
        // for a serialization pin), so writers never block on a slow consumer.
        val desktop = RawStreamTlsTestSupport.desktopFixture("r1-serial-desktop")
        val phone = RawStreamTlsTestSupport.phoneFixture("r1-serial-phone")
        val endpoints = RawStreamTlsTestSupport.rawStreamPair()
        val perThread = 20
        val serverError = AtomicReference<Throwable?>(null)
        val handshakeDone = java.util.concurrent.CountDownLatch(1)
        val releasePeer = java.util.concurrent.CountDownLatch(1)

        val server = thread {
            try {
                val peer = RawStreamTlsTestSupport.serverPeer(endpoints, desktop)
                peer.handshake()
                handshakeDone.countDown()
                // Stay alive (streams open) until the writers finish; never read, so the ciphertext
                // simply buffers in the pipe.
                releasePeer.await(8, TimeUnit.SECONDS)
                peer.close()
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        val detector = ConcurrencyDetectingTransport(RawStreamTlsTestSupport.clientTransport(endpoints))
        val channel = (SslEngineUsbTlsChannel(phoneTlsIdentity = phone).handshake(detector, desktop.spki)
            as SslEngineUsbTlsHandshakeResult.Authenticated).channel
        val adapter = TlsSessionFrameIoAdapter()
        handshakeDone.await(4, TimeUnit.SECONDS)
        val writerError = AtomicReference<Throwable?>(null)

        try {
            val writers = (0 until 2).map { t ->
                thread {
                    try {
                        for (i in 0 until perThread) {
                            adapter.write(
                                channel,
                                SessionFrame(sequence = t * 1_000 + i, sessionId = "r1-serial", payload = SessionPayload.CameraControlCommand("w$t", mapOf("i" to i.toString()))),
                            )
                        }
                    } catch (error: Throwable) {
                        writerError.set(error)
                    }
                }
            }
            writers.forEach { it.join(6_000) }
        } finally {
            releasePeer.countDown()
            runCatching { channel.close() }
            server.join(4_000)
        }

        serverError.get()?.let { throw it }
        writerError.get()?.let { throw it }
        assertEquals("ciphertext production must never overlap", 1, detector.maxConcurrent)
    }

    @Test
    fun closeIsIdempotent() {
        val executor = Executors.newSingleThreadExecutor()
        val transport = CountingCloseTransport()
        val channel = SslEngineUsbTlsEstablishedChannel(
            engine = FakeEngine(emptyList()),
            transport = transport,
            pendingCiphertext = ByteBuffer.allocate(1024),
            readExecutor = executor,
        )

        channel.close()
        channel.close()

        assertEquals(1, transport.closeCount)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun rejectsPinnedSpkiMismatchAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val mismatch = TlsFixture.create("mismatch")
        val pair = sessionPair()
        val server = thread { runCatching { serverHandshake(fixture.context, pair.server) } }

        val result = SslEngineUsbTlsChannel().handshake(pair.client, mismatch.certificate.publicKey.encoded)

        assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
        assertEquals(true, pair.clientCloseable.closed)
        server.join(2000)
    }

    @Test
    fun blockedReadDeadlineRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        try {
            val result = SslEngineUsbTlsChannel(readTimeoutMillis = 50)
                .handshake(pair.client, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun usbWriteErrorRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ThrowingOutputStream(), closeable)
        try {
            val result = SslEngineUsbTlsChannel(readTimeoutMillis = 100)
                .handshake(session, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, closeable.closed)
        } finally {
            session.close()
        }
    }

    @Test
    fun maxHandshakeStepLimitRejectsAndClosesUsbSession() {
        val fixture = TlsFixture.create("valid")
        val pair = sessionPair()
        try {
            val result = SslEngineUsbTlsChannel(maxHandshakeSteps = 1)
                .handshake(pair.client, fixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
        } finally {
            pair.close()
        }
    }


    @Test
    fun readApplicationDataRejectsOversizeMaxBytesBeforeEngineUse() {
        val engine = FakeEngine(listOf())
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), RecordingCloseable())
        val channel = establishedChannel(engine, session, executor)
        try {
            try {
                channel.readApplicationData(64 * 1024 + 1, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
                fail("expected oversize maxBytes to be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("maxBytes"))
            }
            assertEquals(0, engine.unwrapCalls)
        } finally {
            channel.close()
        }
    }

    @Test
    fun blockedApplicationReadDeadlineClosesEstablishedChannel() {
        val engine = FakeEngine(listOf(FakeUnwrap(SSLEngineResult.Status.BUFFER_UNDERFLOW)))
        val executor = Executors.newSingleThreadExecutor()
        val input = PipedInputStream()
        val heldWriter = PipedOutputStream(input)
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(input, ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor)

        try {
            try {
                channel.readApplicationData(16, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))
                fail("expected blocked app-data read to time out")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("timed out"))
            }
            assertEquals(true, closeable.closed)
            assertEquals(true, executor.isShutdown)
        } finally {
            runCatching { heldWriter.close() }
            runCatching { channel.close() }
        }
    }

    @Test
    fun expiredApplicationReadDeadlineClosesBeforeReturningPendingPlaintext() {
        val engine = FakeEngine(listOf())
        val executor = Executors.newSingleThreadExecutor()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor, pendingPlaintext = byteArrayOf(1))

        try {
            channel.readApplicationData(1, System.nanoTime() - TimeUnit.SECONDS.toNanos(1))
            fail("expected expired app-data read to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("timed out"))
            assertEquals(true, closeable.closed)
        }
    }

    @Test
    fun applicationWriteRejectsEngineNoProgress() {
        val engine = FakeEngine(listOf(), noProgressWrapsBeforeThrow = 4)
        val executor = Executors.newSingleThreadExecutor()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), closeable)
        val channel = establishedChannel(engine, session, executor)

        try {
            channel.writeApplicationData(byteArrayOf(9))
            fail("expected no-progress app-data write to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("made no progress"))
            assertEquals(true, closeable.closed)
        }
    }

    @Test
    fun needWrapDuringApplicationReadPerformsActualEmptyWrap() {
        val output = ByteArrayOutputStream()
        val engine = FakeEngine(
            unwraps = listOf(
                FakeUnwrap(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP),
                FakeUnwrap(SSLEngineResult.Status.CLOSED),
            ),
            wrapBytes = byteArrayOf(1, 2, 3),
        )
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), output, RecordingCloseable())
        val channel = establishedChannel(engine, session, executor)

        try {
            try {
                channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
                fail("expected read to fail after empty wrap")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("closed"))
            }
            assertEquals(1, engine.wrapCalls)
            assertTrue(output.toByteArray().isNotEmpty())
        } finally {
            runCatching { channel.close() }
        }
    }

    @Test
    fun applicationReadFailureShutsDownExecutorWhenSessionCloseThrows() {
        val engine = FakeEngine(listOf(FakeUnwrap(SSLEngineResult.Status.CLOSED)))
        val executor = Executors.newSingleThreadExecutor()
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ThrowingCloseable())
        val channel = establishedChannel(engine, session, executor)

        try {
            channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
            fail("expected closed engine read to fail")
        } catch (_: Exception) {
            assertEquals(true, executor.isShutdown)
        }
    }

    @Test
    fun presentsPhoneClientCertificateWhenServerRequiresClientAuth() {
        val desktopFixture = TlsFixture.create("desktop-client-auth")
        val phoneFixture = TlsFixture.create("phone-client-auth")
        val phoneIdentity = TestPhoneTlsIdentity(phoneFixture)
        val trustManager = CapturingClientTrustManager()
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(desktopFixture.keyManagers, arrayOf(trustManager), null)
        }
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverCompleted = AtomicBoolean(false)
        val server = thread {
            try {
                serverHandshake(serverContext, pair.server, needClientAuth = true)
                serverCompleted.set(true)
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        try {
            val result = SslEngineUsbTlsChannel(phoneTlsIdentity = phoneIdentity)
                .handshake(pair.client, desktopFixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
            result as SslEngineUsbTlsHandshakeResult.Authenticated
            // Join before closing: TLS 1.3 clients finish as soon as they send their own Finished
            // message, without waiting on the server. The server still has to unwrap that Finished
            // and often makes one more opportunistic wrap to send a post-handshake NewSessionTicket;
            // closing the client side first can race that trailing write against a closed pipe.
            server.join(2000)
            serverError.get()?.let { throw it }
            assertTrue(serverCompleted.get())
            result.close()

            val capturedChain = trustManager.capturedClientChain
            assertTrue(capturedChain != null && capturedChain.isNotEmpty())
            val phoneLeafSubjectPublicKeyInfo = capturedChain!![0].publicKey.encoded
            assertTrue(phoneLeafSubjectPublicKeyInfo.contentEquals(phoneIdentity.subjectPublicKeyInfoDer))
        } finally {
            pair.close()
        }
    }

    @Test
    fun rejectsHandshakeWhenServerRequiresClientAuthWithoutPhoneIdentity() {
        val desktopFixture = TlsFixture.create("desktop-requires-auth")
        val trustManager = CapturingClientTrustManager()
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(desktopFixture.keyManagers, arrayOf(trustManager), null)
        }
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val serverReachedTerminalState = AtomicBoolean(false)
        val server = thread {
            try {
                // Force TLS 1.2: a TLS 1.3 client completes its own handshake as soon as it sends
                // its Finished message, without waiting for the server, so a server-side rejection
                // can never reach it in time (verified empirically). Under TLS 1.2 the server's own
                // Finished is the last message of the flow, so a rejection here still reaches the
                // client before its handshake() call returns.
                serverHandshake(serverContext, pair.server, needClientAuth = true, forceProtocol = "TLSv1.2")
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                serverReachedTerminalState.set(true)
            }
        }

        try {
            val result = SslEngineUsbTlsChannel().handshake(pair.client, desktopFixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
            server.join(2000)
            assertTrue(serverReachedTerminalState.get())
            // JSSE itself aborts the TLS 1.2 handshake with SSLHandshakeException("Empty client
            // certificate chain") as soon as needClientAuth=true and no certificate is presented,
            // before ever consulting the trust manager above. The server must actually reject:
            // this used to be a conditional assertion that passed vacuously if the server ever
            // completed without rejecting (see R2-server-error-optional-assertion).
            val observedServerError = serverError.get()
            assertTrue("expected the server to reject the handshake, but it completed without error", observedServerError != null)
            assertTrue(
                "expected an SSLHandshakeException on the server, got $observedServerError",
                observedServerError is SSLHandshakeException,
            )
        } finally {
            pair.close()
        }
    }

    @Test
    fun tls13ServerRequiringClientAuthRejectsMissingPhoneIdentityOnFirstRead() {
        val desktopFixture = TlsFixture.create("desktop-tls13-requires-auth")
        val trustManager = CapturingClientTrustManager()
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(desktopFixture.keyManagers, arrayOf(trustManager), null)
        }
        val pair = sessionPair()
        val serverError = AtomicReference<Throwable?>(null)
        val server = thread {
            try {
                // Force TLSv1.3 on the server: relying on default negotiation left this test's
                // name and comments claiming TLS 1.3 without ever proving it, so a JDK whose
                // default fell back to TLS 1.2 would have silently exercised the wrong protocol
                // (see R2-tls13-name-unasserted-protocol). Empirically (see below), this JDK's
                // SSLEngine rejects an empty client certificate chain the same way under TLS 1.3
                // as under TLS 1.2 above: sun.security.ssl.CertificateMessage$T13CertificateConsumer
                // throws SSLHandshakeException("Empty client certificate chain") from a delegated
                // task, confirming the trust manager above is never consulted (the engine itself
                // aborts first). This contradicts an earlier, non-committed exploration recorded
                // in m1's evidence, which assumed JSSE would let TLS 1.3 continue per RFC 8446
                // §4.4.2.4's discretion clause; this committed test replaces that assumption with
                // what this JDK actually does.
                serverHandshake(serverContext, pair.server, needClientAuth = true, forceProtocol = "TLSv1.3")
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                // Close the USB session from the server side: the exception above unwinds this
                // test's own handshake loop without ever wrapping and sending a TLS alert, so this
                // raw close is what makes the client's first application-data read observe
                // end-of-stream instead of blocking forever.
                runCatching { pair.server.close() }
            }
        }

        try {
            val result = SslEngineUsbTlsChannel().handshake(pair.client, desktopFixture.certificate.publicKey.encoded)

            // Empirically observed: the TLS 1.3 client finishes its own handshake state (it has
            // already sent its Finished message) without waiting for the server, so it reaches
            // Authenticated here even though the server is about to reject the connection for
            // missing client auth (see the server thread body above). This is the asymmetry left
            // unproved after m1 (WARNING R3-tls13-no-identity-unproved): a client without a phone
            // identity is not actually granted access, but the proof that it fails closed lives at
            // the first application read below, not at the handshake result.
            assertTrue(result is SslEngineUsbTlsHandshakeResult.Authenticated)
            result as SslEngineUsbTlsHandshakeResult.Authenticated
            // The server was forced to TLSv1.3 above and offers no other protocol, so a
            // successful negotiation here can only be TLSv1.3: assert it instead of merely
            // assuming it (see R2-tls13-name-unasserted-protocol).
            assertEquals("TLSv1.3", result.protocol)

            server.join(2000)
            val observedServerError = serverError.get()
            assertTrue("expected the server to reject the handshake, but it completed without error", observedServerError != null)
            assertTrue(
                "expected an SSLHandshakeException on the server, got $observedServerError",
                observedServerError is SSLHandshakeException,
            )

            // SslEngineUsbTlsEstablishedChannel reports failure through exceptions, not a typed
            // result the way handshake() does: the first application read must throw and must
            // never hand back application bytes. The server thread above closed the raw USB
            // session without ever wrapping a TLS alert (see its finally block), so the client's
            // first read observes end-of-stream and readCiphertext() throws this specific
            // IllegalStateException (SslEngineUsbTlsChannel.kt); narrowed from a generic
            // `catch (Exception)` so a different, unrelated failure would surface instead of
            // being swallowed here (see R2-broad-catch-first-read).
            try {
                val bytes = result.channel.readApplicationData(16, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
                fail("expected the first application read to fail closed, but got ${bytes.size} application bytes")
            } catch (error: IllegalStateException) {
                assertTrue(
                    "expected the USB ciphertext EOF message, got: ${error.message}",
                    error.message.orEmpty().contains("USB TLS ciphertext read failed: EofEmpty"),
                )
                assertEquals(true, pair.clientCloseable.closed)
            }
        } finally {
            pair.close()
        }
    }

    @Test
    fun keyManagersFailureRejectsHandshakeFailClosed() {
        val desktopFixture = TlsFixture.create("desktop-key-manager-failure")
        val phoneFixture = TlsFixture.create("phone-key-manager-failure")
        val throwingIdentity = ThrowingPhoneTlsIdentity(phoneFixture.certificate.publicKey.encoded)
        val pair = sessionPair()

        try {
            val result = SslEngineUsbTlsChannel(phoneTlsIdentity = throwingIdentity)
                .handshake(pair.client, desktopFixture.certificate.publicKey.encoded)

            assertTrue(result is SslEngineUsbTlsHandshakeResult.Rejected)
            assertEquals(true, pair.clientCloseable.closed)
        } finally {
            pair.close()
        }
    }

    private fun serverHandshake(context: SSLContext, session: AccessoryIoSession, needClientAuth: Boolean = false, forceProtocol: String? = null): ServerTlsChannel {
        val engine = context.createSSLEngine().apply {
            useClientMode = false
            this.needClientAuth = needClientAuth
            if (forceProtocol != null) enabledProtocols = arrayOf(forceProtocol)
            beginHandshake()
        }
        val adapter = UsbTlsCiphertextIoAdapter()
        val empty = java.nio.ByteBuffer.allocate(0)
        val peer = java.nio.ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES)
        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapServer(engine, session, adapter, empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
                    unwrapServer(engine, session, adapter, peer, app)
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach { it.run() }
                else -> Unit
            }
        }
        return ServerTlsChannel(engine, session, adapter, peer)
    }

    private fun wrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, src: java.nio.ByteBuffer) {
        val out = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(src, out)
        out.flip()
        if (out.hasRemaining()) adapter.write(session, ByteArray(out.remaining()).also { out.get(it) })
    }

    private fun unwrapServer(engine: SSLEngine, session: AccessoryIoSession, adapter: UsbTlsCiphertextIoAdapter, peer: java.nio.ByteBuffer, app: java.nio.ByteBuffer) {
        if (peer.position() == 0) {
            val read = adapter.read(session) as UsbTlsCiphertextReadResult.Received
            require(read.ciphertext.size <= peer.remaining())
            peer.put(read.ciphertext)
        }
        peer.flip()
        val result = engine.unwrap(peer, app)
        peer.compact()
        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            val read = adapter.read(session) as UsbTlsCiphertextReadResult.Received
            require(read.ciphertext.size <= peer.remaining())
            peer.put(read.ciphertext)
        }
    }

    /** Server-side counterpart used by tests to exchange application data after [serverHandshake]. */
    private inner class ServerTlsChannel(
        private val engine: SSLEngine,
        private val session: AccessoryIoSession,
        private val adapter: UsbTlsCiphertextIoAdapter,
        private val peer: java.nio.ByteBuffer,
    ) {
        fun readApplicationFrame(): ByteArray {
            val app = java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize)
            while (app.position() == 0) unwrapServer(engine, session, adapter, peer, app)
            app.flip()
            return ByteArray(app.remaining()).also { app.get(it) }
        }

        fun writeApplicationFrame(bytes: ByteArray) {
            wrapServer(engine, session, adapter, java.nio.ByteBuffer.wrap(bytes))
        }
    }

    private fun sessionPair(): SessionPair {
        val clientIn = PipedInputStream(64 * 1024)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(64 * 1024)
        val clientOut = PipedOutputStream(serverIn)
        val clientClose = RecordingCloseable()
        val serverClose = RecordingCloseable()
        return SessionPair(
            client = AccessoryIoSession(clientIn, clientOut, clientClose),
            server = AccessoryIoSession(serverIn, serverOut, serverClose),
            clientCloseable = clientClose,
        )
    }


    private fun establishedChannel(
        engine: SSLEngine,
        session: AccessoryIoSession,
        executor: ExecutorService,
        pendingPlaintext: ByteArray = ByteArray(0),
    ): SslEngineUsbTlsEstablishedChannel = SslEngineUsbTlsEstablishedChannel(
        engine = engine,
        transport = UsbAccessoryTlsCiphertextTransport(session),
        pendingCiphertext = ByteBuffer.allocate(USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES),
        readExecutor = executor,
    ).also { channel ->
        pendingPlaintext.forEach { byte ->
            val field = channel.javaClass.getDeclaredField("pendingPlaintext")
            field.isAccessible = true
            field.set(channel, byteArrayOf(byte))
        }
    }

    private data class FakeUnwrap(
        val status: SSLEngineResult.Status,
        val handshakeStatus: SSLEngineResult.HandshakeStatus = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
        val bytesProduced: Int = 0,
    )

    private class FakeEngine(
        private val unwraps: List<FakeUnwrap>,
        private val wrapBytes: ByteArray = ByteArray(0),
        private val noProgressWrapsBeforeThrow: Int? = null,
    ) : SSLEngine() {
        private val sslSession: SSLSession = SSLContext.getDefault().createSSLEngine().session
        private var unwrapIndex = 0
        var unwrapCalls = 0
            private set
        var wrapCalls = 0
            private set

        override fun wrap(srcs: Array<out ByteBuffer>, offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult {
            wrapCalls += 1
            noProgressWrapsBeforeThrow?.let { limit ->
                if (wrapCalls > limit) throw IllegalStateException("test guard: no progress loop")
                return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0)
            }
            if (wrapBytes.size > dst.remaining()) {
                return SSLEngineResult(SSLEngineResult.Status.BUFFER_OVERFLOW, SSLEngineResult.HandshakeStatus.NEED_WRAP, 0, 0)
            }
            dst.put(wrapBytes)
            return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, wrapBytes.size)
        }

        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, offset: Int, length: Int): SSLEngineResult {
            unwrapCalls += 1
            val next = unwraps.getOrElse(unwrapIndex) { FakeUnwrap(SSLEngineResult.Status.CLOSED) }
            unwrapIndex += 1
            return SSLEngineResult(next.status, next.handshakeStatus, 0, next.bytesProduced)
        }

        override fun getDelegatedTask(): Runnable? = null
        override fun closeInbound() = Unit
        override fun isInboundDone(): Boolean = false
        override fun closeOutbound() = Unit
        override fun isOutboundDone(): Boolean = false
        override fun getSupportedCipherSuites(): Array<String> = arrayOf("TLS_FAKE")
        override fun getEnabledCipherSuites(): Array<String> = supportedCipherSuites
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")
        override fun getEnabledProtocols(): Array<String> = supportedProtocols
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun getSession(): SSLSession = sslSession
        override fun beginHandshake() = Unit
        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
    }

    private data class SessionPair(
        val client: AccessoryIoSession,
        val server: AccessoryIoSession,
        val clientCloseable: RecordingCloseable,
    ) : AutoCloseable {
        override fun close() {
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }

    private class RecordingCloseable : java.io.Closeable {
        var closed = false
            private set
        override fun close() { closed = true }
    }

    /** [TlsCiphertextTransport] decorator that counts ciphertext writes/reads while delegating. */
    private class RecordingCiphertextTransport(
        private val delegate: TlsCiphertextTransport,
    ) : TlsCiphertextTransport {
        var writes = 0
            private set
        var reads = 0
            private set

        override val maxWriteBytes: Int = delegate.maxWriteBytes

        override fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult {
            writes += 1
            return delegate.writeCiphertext(ciphertext)
        }

        override fun readCiphertext(): TlsCiphertextReadResult {
            reads += 1
            return delegate.readCiphertext()
        }

        override fun close() = delegate.close()
    }

    /** [TlsCiphertextTransport] that counts how many times [close] actually runs, to pin close idempotence. */
    private class CountingCloseTransport : TlsCiphertextTransport {
        var closeCount = 0
            private set

        override val maxWriteBytes: Int = USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES

        override fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult = TlsCiphertextWriteResult.Sent

        override fun readCiphertext(): TlsCiphertextReadResult = TlsCiphertextReadResult.Eof("counting close transport does not read")

        override fun close() {
            closeCount += 1
        }
    }

    /** [TlsCiphertextTransport] decorator that records the peak number of overlapping [writeCiphertext] calls. */
    private class ConcurrencyDetectingTransport(
        private val delegate: TlsCiphertextTransport,
    ) : TlsCiphertextTransport {
        private val inFlight = AtomicInteger(0)

        @Volatile
        var maxConcurrent = 0
            private set

        override val maxWriteBytes: Int = delegate.maxWriteBytes

        override fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult {
            val now = inFlight.incrementAndGet()
            synchronized(this) { if (now > maxConcurrent) maxConcurrent = now }
            return try {
                // Widen the overlap window so an unserialized second writer is observed deterministically.
                Thread.sleep(2)
                delegate.writeCiphertext(ciphertext)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun readCiphertext(): TlsCiphertextReadResult = delegate.readCiphertext()

        override fun close() = delegate.close()
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("write failed")
        }
    }

    private class ThrowingCloseable : java.io.Closeable {
        override fun close() {
            throw IOException("close failed")
        }
    }

    /** Server-side [X509TrustManager] that accepts any presented client chain and records it for assertions. */
    private class CapturingClientTrustManager : X509TrustManager {
        var capturedClientChain: Array<out X509Certificate>? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            capturedClientChain = chain
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            throw CertificateException("capturing client trust manager does not trust server certificates")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Test-only PKCS12-backed [PhoneTlsIdentity] built from the same [TlsFixture] keytool recipe. */
    private class TestPhoneTlsIdentity(private val fixture: TlsFixture) : PhoneTlsIdentity {
        override fun keyManagers(): Array<KeyManager> = fixture.keyManagers
        override val subjectPublicKeyInfoDer: ByteArray = fixture.certificate.publicKey.encoded
    }

    /** [PhoneTlsIdentity] whose [keyManagers] always fails, to exercise the fail-closed contract. */
    private class ThrowingPhoneTlsIdentity(override val subjectPublicKeyInfoDer: ByteArray) : PhoneTlsIdentity {
        override fun keyManagers(): Array<KeyManager> = throw IllegalStateException("phone TLS identity key managers unavailable")
    }

    private class TlsFixture(val context: SSLContext, val certificate: X509Certificate, val keyManagers: Array<KeyManager>) {
        companion object {
            fun create(alias: String): TlsFixture {
                val temp = createTempDir(prefix = "cc-engine")
                val store = File(temp, "$alias.p12")
                val keytool = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool").absolutePath
                val command = listOf(
                    keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=$alias", "-keystore", store.absolutePath, "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-storetype", "PKCS12", "-startdate", "2026/01/01 00:00:00", "-validity", "36500",
                )
                val exit = ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
                require(exit == 0) { "keytool failed" }
                val keyStore = KeyStore.getInstance("PKCS12")
                store.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(keyStore, PASSWORD.toCharArray())
                val context = SSLContext.getInstance("TLS")
                context.init(kmf.keyManagers, null, null)
                val certificate = keyStore.getCertificate(alias) as X509Certificate
                temp.deleteRecursively()
                return TlsFixture(context, certificate, kmf.keyManagers)
            }
            private const val PASSWORD = "changeit"
        }
    }
}
