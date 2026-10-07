package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureSessionBoundaryTest {
    @Test
    fun capture_request_targets_planned_fps_range() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val range = CameraFpsRange(24, 30)

        CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"), range)

        assertTrue(gateway.fpsRangeProvided)
        assertEquals(range, gateway.targetFpsRange)
    }

    @Test
    fun capture_request_omits_fps_range_when_plan_has_none() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)

        CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"), null)

        assertTrue(gateway.fpsRangeProvided)
        assertEquals(null, gateway.targetFpsRange)
    }

    @Test
    fun configuresRepeatingRequestToInjectedSurfaceWhenOpenSessionActive() {
        val openSession = activeOpenSession()
        val surface = FakeCaptureSurface("encoder-input")
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)

        val result = CameraCaptureSessionBoundary(gateway).startRepeating(openSession, surface)

        assertTrue(result is CameraCaptureStartResult.ConfigurationSubmitted)
        assertEquals(listOf(CaptureSessionRequest("camera-1", "encoder-input")), gateway.requests)
    }

    @Test
    fun rejectsMissingSurfaceWithoutConfiguringSession() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val result = CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), null)

        assertEquals(CameraCaptureStartResult.MissingSurface, result)
        assertEquals(emptyList<CaptureSessionRequest>(), gateway.requests)
    }

    @Test
    fun rejectsInactiveOpenSessionWithoutConfiguringSession() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val openSession = activeOpenSession().also { it.cancel() }

        val result = CameraCaptureSessionBoundary(gateway).startRepeating(openSession, FakeCaptureSurface("encoder-input"))

        assertEquals(CameraCaptureStartResult.CameraNotActive("camera-1"), result)
        assertEquals(emptyList<CaptureSessionRequest>(), gateway.requests)
    }

    @Test
    fun mapsConfigureFailureToTypedResult() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Failed("surface rejected"))

        val result = CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"))

        assertEquals(CameraCaptureStartResult.ConfigurationFailed("camera-1", "surface rejected"), result)
    }

    @Test
    fun configuredCallbackActivatesAndStopClosesResources() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val result = CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"))
        val submitted = result as CameraCaptureStartResult.ConfigurationSubmitted
        val repeatingSession = CloseTrackingRepeatingSession("camera-1")

        assertEquals(CameraCaptureSessionCallbackResult.RepeatingStarted("camera-1"), gateway.callbacks.single().onConfigured(repeatingSession))
        assertTrue(submitted.session.isRepeating)

        submitted.session.stop()

        assertFalse(submitted.session.isRepeating)
        assertEquals(1, repeatingSession.stopRepeatingCount)
        assertEquals(1, repeatingSession.closeCount)
    }

    @Test
    fun cancelBeforeConfiguredPreventsStaleRepeatingAndClosesLateSession() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val result = CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"))
        val submitted = result as CameraCaptureStartResult.ConfigurationSubmitted
        val repeatingSession = CloseTrackingRepeatingSession("camera-1")

        submitted.session.cancel()
        val callbackResult = gateway.callbacks.single().onConfigured(repeatingSession)

        assertEquals(CameraCaptureSessionCallbackResult.StaleIgnored("camera-1"), callbackResult)
        assertFalse(submitted.session.isRepeating)
        assertEquals(1, repeatingSession.closeCount)
    }

    @Test
    fun configureFailedAfterSubmissionClosesPendingSession() {
        val gateway = RecordingCaptureSessionGateway(CaptureSessionRequestOutcome.Submitted)
        val result = CameraCaptureSessionBoundary(gateway).startRepeating(activeOpenSession(), FakeCaptureSurface("encoder-input"))
        val submitted = result as CameraCaptureStartResult.ConfigurationSubmitted
        val failedSession = CloseTrackingRepeatingSession("camera-1")

        assertEquals(CameraCaptureSessionCallbackResult.ConfigurationClosed("camera-1"), gateway.callbacks.single().onConfigureFailed(failedSession))
        assertFalse(submitted.session.isRepeating)
        assertTrue(submitted.session.isClosed)
        assertEquals(1, failedSession.closeCount)
    }

    private fun activeOpenSession(): CameraOpenSession {
        val session = CameraOpenSession("camera-1")
        session.callbacks.onOpened(OpenTestCameraDevice("camera-1"))
        return session
    }
}

private data class CaptureSessionRequest(val cameraId: String, val surfaceLabel: String)

private data class FakeCaptureSurface(
    override val label: String,
) : CaptureTargetSurface

private class RecordingCaptureSessionGateway(
    private val outcome: CaptureSessionRequestOutcome,
) : CameraCaptureSessionGateway {
    val requests = mutableListOf<CaptureSessionRequest>()
    val callbacks = mutableListOf<CaptureSessionCallbacks>()
    var targetFpsRange: CameraFpsRange? = null
    var fpsRangeProvided = false
    override fun configureRepeating(
        cameraId: String,
        targetSurface: CaptureTargetSurface,
        callbacks: CaptureSessionCallbacks,
    ): CaptureSessionRequestOutcome {
        requests += CaptureSessionRequest(cameraId, targetSurface.label)
        this.callbacks += callbacks
        return outcome
    }

    override fun configureRepeating(
        cameraId: String,
        targetSurface: CaptureTargetSurface,
        callbacks: CaptureSessionCallbacks,
        fpsRange: CameraFpsRange?,
    ): CaptureSessionRequestOutcome {
        fpsRangeProvided = true
        targetFpsRange = fpsRange
        return configureRepeating(cameraId, targetSurface, callbacks)
    }
}

private class CloseTrackingRepeatingSession(
    override val cameraId: String,
) : CloseableRepeatingCaptureSession {
    var stopRepeatingCount = 0
    var closeCount = 0
    override fun stopRepeating() {
        stopRepeatingCount += 1
    }
    override fun close() {
        closeCount += 1
    }
}


private class OpenTestCameraDevice(
    override val cameraId: String,
) : CloseableCameraDevice {
    override fun close() = Unit
}
