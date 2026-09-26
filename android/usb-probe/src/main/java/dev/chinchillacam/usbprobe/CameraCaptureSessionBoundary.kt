package dev.chinchillacam.usbprobe

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.view.Surface

class CameraCaptureSessionBoundary(
    private val gateway: CameraCaptureSessionGateway,
) {
    fun startRepeating(
        openSession: CameraOpenSession,
        targetSurface: CaptureTargetSurface?,
    ): CameraCaptureStartResult {
        val surface = targetSurface ?: return CameraCaptureStartResult.MissingSurface
        if (!openSession.isActive) return CameraCaptureStartResult.CameraNotActive(openSession.cameraId)

        val session = RepeatingCaptureSession(openSession.cameraId)
        return when (val outcome = gateway.configureRepeating(openSession.cameraId, surface, session.callbacks)) {
            CaptureSessionRequestOutcome.Submitted -> CameraCaptureStartResult.ConfigurationSubmitted(openSession.cameraId, session)
            is CaptureSessionRequestOutcome.Failed -> CameraCaptureStartResult.ConfigurationFailed(openSession.cameraId, outcome.reason)
        }
    }
}

interface CaptureTargetSurface {
    val label: String
}

interface CameraCaptureSessionGateway {
    fun configureRepeating(
        cameraId: String,
        targetSurface: CaptureTargetSurface,
        callbacks: CaptureSessionCallbacks,
    ): CaptureSessionRequestOutcome
}

sealed class CaptureSessionRequestOutcome {
    object Submitted : CaptureSessionRequestOutcome()
    data class Failed(val reason: String) : CaptureSessionRequestOutcome()
}

sealed class CameraCaptureStartResult {
    object MissingSurface : CameraCaptureStartResult()
    data class CameraNotActive(val cameraId: String) : CameraCaptureStartResult()
    data class ConfigurationFailed(val cameraId: String, val reason: String) : CameraCaptureStartResult()
    data class ConfigurationSubmitted(val cameraId: String, val session: RepeatingCaptureSession) : CameraCaptureStartResult()
}

sealed class CameraCaptureSessionCallbackResult {
    data class RepeatingStarted(val cameraId: String) : CameraCaptureSessionCallbackResult()
    data class ConfigurationClosed(val cameraId: String) : CameraCaptureSessionCallbackResult()
    data class StaleIgnored(val cameraId: String) : CameraCaptureSessionCallbackResult()
}

sealed class RepeatingCaptureStopResult {
    object Stopped : RepeatingCaptureStopResult()
    object AlreadyStopped : RepeatingCaptureStopResult()
    data class Failed(val reasons: List<String>) : RepeatingCaptureStopResult()
}

interface CloseableRepeatingCaptureSession {
    val cameraId: String
    fun stopRepeating()
    fun close()
}

class CaptureSessionCallbacks internal constructor(
    private val session: RepeatingCaptureSession,
) {
    fun onConfigured(repeatingSession: CloseableRepeatingCaptureSession): CameraCaptureSessionCallbackResult = session.onConfigured(repeatingSession)
    fun onConfigureFailed(repeatingSession: CloseableRepeatingCaptureSession? = null): CameraCaptureSessionCallbackResult = session.onConfigureFailed(repeatingSession)
}

class RepeatingCaptureSession(
    val cameraId: String,
) {
    val callbacks: CaptureSessionCallbacks = CaptureSessionCallbacks(this)
    private var repeatingSession: CloseableRepeatingCaptureSession? = null
    private var canceled: Boolean = false
    private var closed: Boolean = false

    val isRepeating: Boolean
        @Synchronized get() = repeatingSession != null && !closed && !canceled

    val isClosed: Boolean
        @Synchronized get() = closed

    @Synchronized
    fun stop(): RepeatingCaptureStopResult {
        if (closed && canceled && repeatingSession == null) return RepeatingCaptureStopResult.AlreadyStopped
        val session = repeatingSession
        val failures = mutableListOf<String>()
        if (session != null) {
            try {
                session.stopRepeating()
            } catch (_: RuntimeException) {
                failures += "stop repeating failed"
            }
            try {
                session.close()
            } catch (_: RuntimeException) {
                failures += "capture session close failed"
            }
        }
        repeatingSession = null
        closed = true
        canceled = true
        return if (failures.isEmpty()) RepeatingCaptureStopResult.Stopped else RepeatingCaptureStopResult.Failed(failures)
    }

    @Synchronized
    fun cancel() {
        repeatingSession?.close()
        repeatingSession = null
        canceled = true
        closed = true
    }

    @Synchronized
    internal fun onConfigured(configuredSession: CloseableRepeatingCaptureSession): CameraCaptureSessionCallbackResult {
        if (canceled || closed) {
            configuredSession.close()
            return CameraCaptureSessionCallbackResult.StaleIgnored(cameraId)
        }
        repeatingSession = configuredSession
        return CameraCaptureSessionCallbackResult.RepeatingStarted(cameraId)
    }

    @Synchronized
    internal fun onConfigureFailed(failedSession: CloseableRepeatingCaptureSession? = null): CameraCaptureSessionCallbackResult {
        val sessionToClose = repeatingSession ?: failedSession
        sessionToClose?.close()
        repeatingSession = null
        closed = true
        canceled = true
        return CameraCaptureSessionCallbackResult.ConfigurationClosed(cameraId)
    }
}

class AndroidCaptureTargetSurface(
    val surface: Surface,
    override val label: String,
) : CaptureTargetSurface

class AndroidCameraCaptureSessionGateway(
    private val cameraDevice: CameraDevice,
    private val handler: Handler,
) : CameraCaptureSessionGateway {
    override fun configureRepeating(
        cameraId: String,
        targetSurface: CaptureTargetSurface,
        callbacks: CaptureSessionCallbacks,
    ): CaptureSessionRequestOutcome {
        val androidSurface = targetSurface as? AndroidCaptureTargetSurface
            ?: return CaptureSessionRequestOutcome.Failed("target surface is not Android Surface")
        return try {
            cameraDevice.createCaptureSession(
                listOf(androidSurface.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(androidSurface.surface)
                            }.build()
                            session.setRepeatingRequest(request, null, handler)
                            callbacks.onConfigured(AndroidCloseableRepeatingCaptureSession(cameraId, session))
                        } catch (_: CameraAccessException) {
                            session.close()
                            callbacks.onConfigureFailed(AndroidCloseableRepeatingCaptureSession(cameraId, session))
                        } catch (_: IllegalStateException) {
                            session.close()
                            callbacks.onConfigureFailed(AndroidCloseableRepeatingCaptureSession(cameraId, session))
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        callbacks.onConfigureFailed(AndroidCloseableRepeatingCaptureSession(cameraId, session))
                    }
                },
                handler,
            )
            CaptureSessionRequestOutcome.Submitted
        } catch (_: CameraAccessException) {
            CaptureSessionRequestOutcome.Failed("capture session access failed")
        } catch (_: IllegalArgumentException) {
            CaptureSessionRequestOutcome.Failed("capture session arguments rejected")
        } catch (_: IllegalStateException) {
            CaptureSessionRequestOutcome.Failed("camera device is not ready")
        }
    }
}

private class AndroidCloseableRepeatingCaptureSession(
    override val cameraId: String,
    private val session: CameraCaptureSession,
) : CloseableRepeatingCaptureSession {
    override fun stopRepeating() {
        session.stopRepeating()
    }

    override fun close() {
        session.close()
    }
}
