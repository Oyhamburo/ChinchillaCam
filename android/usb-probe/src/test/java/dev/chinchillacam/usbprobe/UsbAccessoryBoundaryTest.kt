package dev.chinchillacam.usbprobe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessoryBoundaryTest {
    @Test
    fun parsesLittleEndianAoaProtocolVersion() {
        val parsed = AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x02, 0x00))

        assertEquals(AoaProtocolVersion(2), parsed)
    }

    @Test
    fun rejectsZeroProtocolVersion() {
        val result = runCatching { AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x00, 0x00)) }

        assertTrue(result.isFailure)
    }

    @Test
    fun validatesAccessoryIdentityRequiredFields() {
        val identity = AccessoryIdentity(
            manufacturer = "ChinchillaCam",
            model = "USB Probe",
            description = "AOA feasibility boundary",
            version = "0.1.0",
            uri = "https://example.invalid/chinchillacam",
            serial = "prototype",
        )

        assertTrue(identity.isValidForAoaHandshake())
        assertFalse(identity.copy(manufacturer = "").isValidForAoaHandshake())
    }

    @Test
    fun formatsDeviceSummaryWithoutHardwareClaims() {
        val summary = UsbDeviceSummary(
            vendorId = 0x18D1,
            productId = 0x2D00,
            protocolVersion = AoaProtocolVersion(2),
        )

        assertEquals(
            "USB device 18d1:2d00 reports AOA protocol v2; hardware transport remains unvalidated",
            summary.toString(),
        )
    }

    @Test
    fun permissionDeniedDoesNotOpenAccessory() {
        val gateway = RecordingAccessoryGateway()
        val accessory = TestAccessoryHandle("phone")

        val result = UsbAccessoryBoundary(gateway).open(accessory, hasPermission = false)

        assertEquals(AccessoryOpenResult.PermissionDenied(accessory), result)
        assertEquals(0, gateway.openAttempts)
    }

    @Test
    fun permissionGrantedOpensAccessoryAndExposesSessionStreams() {
        val input = ByteArrayInputStream(byteArrayOf(0x11, 0x22))
        val output = FlushTrackingOutputStream()
        val gatewaySession = RecordingCloseable()
        val gateway = RecordingAccessoryGateway(AccessoryStreams(input, output, gatewaySession))
        val accessory = TestAccessoryHandle("phone")

        val result = UsbAccessoryBoundary(gateway).open(accessory, hasPermission = true)

        assertTrue(result is AccessoryOpenResult.Opened)
        val session = (result as AccessoryOpenResult.Opened).session
        assertArrayEquals(byteArrayOf(0x11, 0x22), session.readExactly(2).getOrThrow())
        session.write(byteArrayOf(0x33, 0x44))
        assertArrayEquals(byteArrayOf(0x33, 0x44), output.toByteArray())
        assertEquals(1, output.flushCount)
        assertEquals(1, gateway.openAttempts)
        assertSame(accessory, gateway.openedAccessories.single())
    }

    @Test
    fun shortReadIsExplicitAndDoesNotPretendFrameIsComplete() {
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf(0x01, 0x02)),
            output = ByteArrayOutputStream(),
            closeable = RecordingCloseable(),
        )

        val result = session.readExactly(4)

        assertEquals(AccessoryReadResult.ShortRead(expectedBytes = 4, actualBytes = 2), result)
    }

    @Test
    fun eofReadIsExplicit() {
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            closeable = RecordingCloseable(),
        )

        val result = session.readExactly(1)

        assertEquals(AccessoryReadResult.Eof, result)
    }

    @Test
    fun closeIsIdempotentAndClosesStreamsAndGatewaySessionOnce() {
        val input = CloseTrackingInputStream(byteArrayOf(0x01))
        val output = CloseTrackingOutputStream()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(input, output, closeable)

        session.close()
        session.close()

        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)
        assertEquals(1, closeable.closeCount)
    }

    @Test
    fun writeFlushesExpectedBytes() {
        val output = FlushTrackingOutputStream()
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf()),
            output = output,
            closeable = RecordingCloseable(),
        )

        session.write(byteArrayOf(0x55, 0x66))

        assertArrayEquals(byteArrayOf(0x55, 0x66), output.toByteArray())
        assertEquals(1, output.flushCount)
    }

    @Test
    fun decodesLittleEndianFrameAndEncodesAckWithSameStreamId() {
        val frameBytes = littleEndianFrame(streamId = 0x01020304, payload = byteArrayOf(0x41, 0x42))

        val frame = AccessoryFrameCodec.decode(frameBytes, maxPayloadBytes = 8).getOrThrow()
        val ack = AccessoryFrameCodec.encodeAck(streamId = frame.streamId, maxPayloadBytes = 8)

        assertEquals(0x01020304, frame.streamId)
        assertArrayEquals(byteArrayOf(0x41, 0x42), frame.payload)
        assertArrayEquals(littleEndianFrame(streamId = 0x01020304, payload = byteArrayOf(0x41, 0x43, 0x4b)), ack)
    }

    @Test
    fun rejectsOversizeAndShortFramesDeterministically() {
        val oversizeHeader = littleEndianHeader(streamId = 7, payloadLength = 9)
        val shortPayload = littleEndianFrame(streamId = 7, payload = byteArrayOf(0x01)).dropLast(1).toByteArray()
        val shortHeader = byteArrayOf(0x01, 0x00, 0x00)

        assertEquals(FrameDecodeResult.OversizePayload(declaredBytes = 9, maxPayloadBytes = 8), AccessoryFrameCodec.decode(oversizeHeader, maxPayloadBytes = 8))
        assertEquals(FrameDecodeResult.ShortPayload(expectedBytes = 1, actualBytes = 0), AccessoryFrameCodec.decode(shortPayload, maxPayloadBytes = 8))
        assertEquals(FrameDecodeResult.ShortHeader(actualBytes = 3), AccessoryFrameCodec.decode(shortHeader, maxPayloadBytes = 8))
    }

    @Test
    fun permissionRequestPlanUsesExplicitReceiverActionAndMutableResultFlags() {
        val plan = AccessoryPermissionPlanner.plan(
            packageName = "dev.chinchillacam.usbprobe",
            receiverClassName = "dev.chinchillacam.usbprobe.UsbAccessoryPermissionReceiver",
        )

        assertEquals("dev.chinchillacam.usbprobe.action.USB_ACCESSORY_PERMISSION", plan.action)
        assertEquals("dev.chinchillacam.usbprobe", plan.packageName)
        assertEquals("dev.chinchillacam.usbprobe.UsbAccessoryPermissionReceiver", plan.receiverClassName)
        assertEquals(0x0a000000, plan.pendingIntentFlags)
    }

    @Test
    fun spanishUiStateRequiresExplicitUserActionBeforeOpeningAccessory() {
        val noAccessory = UsbProbeScreenPlanner.plan(accessoryAvailable = false, permissionRequested = false, busy = false, lastResult = null)
        val ready = UsbProbeScreenPlanner.plan(accessoryAvailable = true, permissionRequested = false, busy = false, lastResult = null)
        val waiting = UsbProbeScreenPlanner.plan(accessoryAvailable = true, permissionRequested = true, busy = true, lastResult = "Esperando permiso")

        assertEquals("ChinchillaCam prueba USB", ready.title)
        assertEquals("Solicitar permiso y abrir accesorio USB", ready.primaryActionLabel)
        assertTrue(ready.primaryActionEnabled)
        assertFalse(noAccessory.primaryActionEnabled)
        assertFalse(waiting.primaryActionEnabled)
        assertEquals("No se abre nada hasta que toques el botón y Android apruebe el permiso.", ready.safetyNotice)
        assertEquals("Esperando permiso", waiting.statusText)
    }

    @Test
    fun approvedAccessoryIoIsPlannedAwayFromAndroidMainThreadCallbacks() {
        val plan = AccessoryExecutionPlanner.planApprovedSmokeIo()

        assertFalse(plan.runsOnAndroidMainThread)
        assertFalse(plan.holdsBroadcastPendingResultForUsbIo)
        assertEquals(10_000L, plan.appLevelTimeoutMillis)
        assertFalse(plan.readCancellationGuaranteed)
    }

    @Test
    fun approvedAccessorySmokeReadsOneBoundedFrameAndWritesAck() {
        val output = FlushTrackingOutputStream()
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(littleEndianFrame(streamId = 42, payload = byteArrayOf(0x10, 0x11))),
            output = output,
            closeable = RecordingCloseable(),
        )

        val result = AccessorySmokeRunner(maxPayloadBytes = 8).runApprovedSession(session)

        assertEquals(AccessorySmokeResult.AckWritten(streamId = 42, payloadBytes = 2), result)
        assertArrayEquals(littleEndianFrame(streamId = 42, payload = byteArrayOf(0x41, 0x43, 0x4b)), output.toByteArray())
        assertEquals(1, output.flushCount)
    }

    @Test
    fun permissionLifecycleReducerResolvesGrantDenyMissingAndMissingCallback() {
        val requested = AccessoryPermissionLifecycleReducer.reduce(
            AccessoryPermissionUiModel.Idle,
            AccessoryPermissionEvent.Requested,
        )
        val granted = AccessoryPermissionLifecycleReducer.reduce(
            requested,
            AccessoryPermissionEvent.Callback(AccessoryPermissionCallback.Granted),
        )
        val denied = AccessoryPermissionLifecycleReducer.reduce(
            requested,
            AccessoryPermissionEvent.Callback(AccessoryPermissionCallback.Denied),
        )
        val missingAccessory = AccessoryPermissionLifecycleReducer.reduce(
            requested,
            AccessoryPermissionEvent.Callback(AccessoryPermissionCallback.MissingAccessory),
        )
        val missingCallback = AccessoryPermissionLifecycleReducer.reduce(
            requested,
            AccessoryPermissionEvent.CallbackTimedOut,
        )

        assertEquals(AccessoryPermissionUiModel.WaitingForCallback, requested)
        assertEquals(AccessoryPermissionUiModel.Granted, granted)
        assertEquals(AccessoryPermissionUiModel.Denied, denied)
        assertEquals(AccessoryPermissionUiModel.MissingAccessory, missingAccessory)
        assertEquals(AccessoryPermissionUiModel.CallbackMissingOrCanceled, missingCallback)
    }

    @Test
    fun permissionUiDoesNotWaitForeverAfterMissingCallback() {
        val ui = UsbProbeScreenPlanner.plan(
            accessoryAvailable = true,
            permissionState = AccessoryPermissionUiModel.CallbackMissingOrCanceled,
            busy = false,
            lastResult = null,
        )

        assertTrue(ui.primaryActionEnabled)
        assertEquals("Android no devolvió el resultado de permiso; podés intentar de nuevo.", ui.statusText)
    }

    @Test
    fun permissionReceiverPlannerReturnsImmediatelyAfterLightweightCallbackClassification() {
        val granted = AccessoryPermissionCallbackPlanner.plan(
            actionMatches = true,
            hasGrantExtra = true,
            permissionGranted = true,
            hasAccessory = true,
        )
        val denied = AccessoryPermissionCallbackPlanner.plan(
            actionMatches = true,
            hasGrantExtra = true,
            permissionGranted = false,
            hasAccessory = true,
        )
        val missing = AccessoryPermissionCallbackPlanner.plan(
            actionMatches = true,
            hasGrantExtra = false,
            permissionGranted = false,
            hasAccessory = false,
        )

        assertEquals(AccessoryPermissionReceiverPlan.RecordAndLaunchActivity(AccessoryPermissionCallback.Granted), granted)
        assertEquals(AccessoryPermissionReceiverPlan.RecordAndLaunchActivity(AccessoryPermissionCallback.Denied), denied)
        assertEquals(AccessoryPermissionReceiverPlan.RecordAndLaunchActivity(AccessoryPermissionCallback.MissingPermissionResult), missing)
        assertFalse(granted.holdsBroadcastPendingResultForUsbIo)
    }

    @Test
    fun validPermissionGrantActivatesOnceAndRejectsDuplicateReplay() {
        val phone = AccessoryFingerprint.fromFields(
            manufacturer = "ChinchillaCam",
            model = "USB Probe",
            description = "AOA feasibility boundary",
            version = "0.1.0",
            uri = "https://example.invalid/chinchillacam",
            serial = "phone-1",
        )
        val gate = AccessoryPermissionRequestGate(timeoutMillis = 1_000)
        val request = gate.beginRequest(phone, nowMillis = 10, token = PermissionRequestToken("token-1"))

        val first = gate.classifyCallback(
            token = request.token,
            fingerprint = phone,
            callback = AccessoryPermissionCallback.Granted,
            nowMillis = 20,
        )
        val duplicate = gate.classifyCallback(
            token = request.token,
            fingerprint = phone,
            callback = AccessoryPermissionCallback.Granted,
            nowMillis = 30,
        )

        assertEquals(PermissionCallbackDecision.Valid(AccessoryPermissionCallback.Granted), first)
        assertEquals(PermissionCallbackDecision.DuplicateOrConsumed, duplicate)
    }

    @Test
    fun wrongAccessoryPermissionCallbackIsRejectedWithoutActivation() {
        val requested = AccessoryFingerprint.fromFields("ChinchillaCam", "USB Probe", "AOA", "0.1.0", "", "phone-1")
        val other = requested.copy(serial = "phone-2")
        val gate = AccessoryPermissionRequestGate(timeoutMillis = 1_000)
        val request = gate.beginRequest(requested, nowMillis = 10, token = PermissionRequestToken("token-1"))

        val decision = gate.classifyCallback(
            token = request.token,
            fingerprint = other,
            callback = AccessoryPermissionCallback.Granted,
            nowMillis = 20,
        )

        assertEquals(PermissionCallbackDecision.WrongAccessory, decision)
    }

    @Test
    fun lateOrMissingPermissionTokenIsRejectedAfterTimeout() {
        val phone = AccessoryFingerprint.fromFields("ChinchillaCam", "USB Probe", "AOA", "0.1.0", "", "phone-1")
        val gate = AccessoryPermissionRequestGate(timeoutMillis = 50)
        val request = gate.beginRequest(phone, nowMillis = 10, token = PermissionRequestToken("token-1"))

        assertEquals(PermissionCallbackDecision.StaleOrMissingRequest, gate.classifyCallback(
            token = null,
            fingerprint = phone,
            callback = AccessoryPermissionCallback.Granted,
            nowMillis = 20,
        ))
        gate.retireExpired(nowMillis = 61)
        assertEquals(PermissionCallbackDecision.StaleOrMissingRequest, gate.classifyCallback(
            token = request.token,
            fingerprint = phone,
            callback = AccessoryPermissionCallback.Granted,
            nowMillis = 62,
        ))
    }

    @Test
    fun deniedPermissionCallbackResolvesVisiblyAndAllowsRetry() {
        val phone = AccessoryFingerprint.fromFields("ChinchillaCam", "USB Probe", "AOA", "0.1.0", "", "phone-1")
        val gate = AccessoryPermissionRequestGate(timeoutMillis = 1_000)
        val first = gate.beginRequest(phone, nowMillis = 10, token = PermissionRequestToken("token-1"))

        val denied = gate.classifyCallback(first.token, phone, AccessoryPermissionCallback.Denied, nowMillis = 20)
        val deniedUi = AccessoryPermissionLifecycleReducer.reduce(
            AccessoryPermissionUiModel.WaitingForCallback,
            AccessoryPermissionEvent.CallbackDecision(denied),
        )
        val retry = gate.beginRequest(phone, nowMillis = 30, token = PermissionRequestToken("token-2"))
        val granted = gate.classifyCallback(retry.token, phone, AccessoryPermissionCallback.Granted, nowMillis = 40)

        assertEquals(AccessoryPermissionUiModel.Denied, deniedUi)
        assertEquals(PermissionCallbackDecision.Valid(AccessoryPermissionCallback.Granted), granted)
    }

    @Test
    fun missingAccessoryCallbackWithMatchingTokenResolvesVisiblyWithoutActivation() {
        val phone = AccessoryFingerprint.fromFields("ChinchillaCam", "USB Probe", "AOA", "0.1.0", "", "phone-1")
        val gate = AccessoryPermissionRequestGate(timeoutMillis = 1_000)
        val request = gate.beginRequest(phone, nowMillis = 10, token = PermissionRequestToken("token-1"))

        val decision = gate.classifyCallback(
            token = request.token,
            fingerprint = null,
            callback = AccessoryPermissionCallback.MissingAccessory,
            nowMillis = 20,
        )

        assertEquals(PermissionCallbackDecision.Valid(AccessoryPermissionCallback.MissingAccessory), decision)
        assertEquals(
            AccessoryPermissionUiModel.MissingAccessory,
            AccessoryPermissionLifecycleReducer.reduce(
                AccessoryPermissionUiModel.WaitingForCallback,
                AccessoryPermissionEvent.CallbackDecision(decision),
            ),
        )
    }

    @Test
    fun rejectedPermissionCallbacksHaveVisibleUiStates() {
        assertEquals(
            AccessoryPermissionUiModel.RejectedWrongAccessory,
            AccessoryPermissionLifecycleReducer.reduce(
                AccessoryPermissionUiModel.WaitingForCallback,
                AccessoryPermissionEvent.CallbackDecision(PermissionCallbackDecision.WrongAccessory),
            ),
        )
        assertEquals(
            AccessoryPermissionUiModel.RejectedDuplicateOrConsumed,
            AccessoryPermissionLifecycleReducer.reduce(
                AccessoryPermissionUiModel.Granted,
                AccessoryPermissionEvent.CallbackDecision(PermissionCallbackDecision.DuplicateOrConsumed),
            ),
        )
        assertEquals(
            AccessoryPermissionUiModel.RejectedStaleOrMissingRequest,
            AccessoryPermissionLifecycleReducer.reduce(
                AccessoryPermissionUiModel.WaitingForCallback,
                AccessoryPermissionEvent.CallbackDecision(PermissionCallbackDecision.StaleOrMissingRequest),
            ),
        )
    }

    @Test
    fun boundedSmokeSessionTimesOutClosesSessionAndReportsCancellationLimit() {
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            closeable = closeable,
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = BoundedAccessorySmokeSession(timeoutMillis = 25, executor = executor).run(session) {
                Thread.sleep(5_000)
                AccessorySmokeResult.AckWritten(streamId = 1, payloadBytes = 0)
            }

            assertEquals(BoundedAccessorySmokeResult.TimedOut(readCancellationGuaranteed = false), result)
            assertEquals(1, closeable.closeCount)
        } finally {
            executor.shutdownNow()
        }
    }
}

private fun littleEndianFrame(streamId: Int, payload: ByteArray): ByteArray =
    littleEndianHeader(streamId, payload.size) + payload

private fun littleEndianHeader(streamId: Int, payloadLength: Int): ByteArray = byteArrayOf(
    (streamId and 0xff).toByte(),
    ((streamId ushr 8) and 0xff).toByte(),
    ((streamId ushr 16) and 0xff).toByte(),
    ((streamId ushr 24) and 0xff).toByte(),
    (payloadLength and 0xff).toByte(),
    ((payloadLength ushr 8) and 0xff).toByte(),
    ((payloadLength ushr 16) and 0xff).toByte(),
    ((payloadLength ushr 24) and 0xff).toByte(),
)

private data class TestAccessoryHandle(val name: String)

private class RecordingAccessoryGateway(
    private val streams: AccessoryStreams? = null,
) : UsbAccessoryGateway<TestAccessoryHandle> {
    var openAttempts = 0
    val openedAccessories = mutableListOf<TestAccessoryHandle>()

    override fun openAccessory(accessory: TestAccessoryHandle): AccessoryStreams? {
        openAttempts += 1
        openedAccessories += accessory
        return streams
    }
}

private class RecordingCloseable : Closeable {
    var closeCount = 0
        private set

    override fun close() {
        closeCount += 1
    }
}

private class FlushTrackingOutputStream : ByteArrayOutputStream() {
    var flushCount = 0
        private set

    override fun flush() {
        flushCount += 1
        super.flush()
    }
}

private class CloseTrackingOutputStream : OutputStream() {
    var closeCount = 0
        private set

    override fun write(b: Int) = Unit

    override fun close() {
        closeCount += 1
    }
}

private class CloseTrackingInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var closeCount = 0
        private set

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun close() {
        closeCount += 1
    }
}
