package dev.chinchillacam.usbprobe

class CameraEncoderPipeline(
    private val cameraOpenBoundary: CameraDeviceOpenBoundary,
    private val encoderBoundary: H264EncoderBoundary,
    private val captureSessionBoundary: CameraCaptureSessionBoundary,
) {
    fun start(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
        encoderConfig: H264EncoderConfig,
    ): CameraEncoderPipelineStartResult {
        return when (val openResult = cameraOpenBoundary.requestOpenSelected(snapshot, selectedCameraId, cameraPermissionGranted)) {
            CameraDeviceOpenResult.MissingSelection -> CameraEncoderPipelineStartResult.Failed("camera selection missing")
            CameraDeviceOpenResult.CameraPermissionMissing -> CameraEncoderPipelineStartResult.Failed("camera permission missing")
            is CameraDeviceOpenResult.SelectionNotDirectOpenCandidate -> {
                CameraEncoderPipelineStartResult.Failed("selected camera is not directly openable: ${openResult.cameraId}")
            }
            is CameraDeviceOpenResult.OpenRequestFailed -> CameraEncoderPipelineStartResult.Failed(openResult.reason)
            is CameraDeviceOpenResult.OpenRequestSubmitted -> {
                val pending = PendingCameraEncoderPipelineStart(
                    openSession = openResult.session,
                    encoderConfig = encoderConfig,
                    encoderBoundary = encoderBoundary,
                    captureSessionBoundary = captureSessionBoundary,
                )
                if (openResult.session.isActive) pending.continueAfterCameraOpened() else CameraEncoderPipelineStartResult.Opening(pending)
            }
        }
    }
}

sealed class CameraEncoderPipelineStartResult {
    data class Opening(
        private val pendingStart: PendingCameraEncoderPipelineStart,
    ) : CameraEncoderPipelineStartResult() {
        val openSession: CameraOpenSession = pendingStart.openSession
        fun continueAfterCameraOpened(): CameraEncoderPipelineStartResult = pendingStart.continueAfterCameraOpened()
        fun cancel(): CameraEncoderPipelineStopResult = pendingStart.cancel()
    }

    data class Started(val session: CameraEncoderPipelineSession) : CameraEncoderPipelineStartResult()
    data class Failed(val reason: String) : CameraEncoderPipelineStartResult()
}

sealed class CameraEncoderPipelineStopResult {
    object Stopped : CameraEncoderPipelineStopResult()
    object AlreadyStopped : CameraEncoderPipelineStopResult()
    data class Failed(val reasons: List<String>) : CameraEncoderPipelineStopResult()
}

class PendingCameraEncoderPipelineStart internal constructor(
    val openSession: CameraOpenSession,
    private val encoderConfig: H264EncoderConfig,
    private val encoderBoundary: H264EncoderBoundary,
    private val captureSessionBoundary: CameraCaptureSessionBoundary,
) {
    private var completed: Boolean = false

    @Synchronized
    fun continueAfterCameraOpened(): CameraEncoderPipelineStartResult {
        if (completed) return CameraEncoderPipelineStartResult.Failed("pipeline start already completed")
        if (!openSession.isActive) return CameraEncoderPipelineStartResult.Opening(this)
        completed = true
        return startEncoderAndCapture()
    }

    @Synchronized
    fun cancel(): CameraEncoderPipelineStopResult {
        if (completed) return CameraEncoderPipelineStopResult.AlreadyStopped
        completed = true
        return closeCameraOnly(openSession)
    }

    private fun startEncoderAndCapture(): CameraEncoderPipelineStartResult {
        val encoderStarted = when (val encoderResult = encoderBoundary.start(encoderConfig)) {
            is H264EncoderStartResult.Failed -> {
                closeCameraOnly(openSession)
                return CameraEncoderPipelineStartResult.Failed(encoderResult.reason)
            }
            is H264EncoderStartResult.Started -> encoderResult
        }
        return when (val captureResult = captureSessionBoundary.startRepeating(openSession, encoderStarted.inputSurface)) {
            CameraCaptureStartResult.MissingSurface -> {
                stopEncoderAndCamera(encoderStarted.session, openSession)
                CameraEncoderPipelineStartResult.Failed("encoder input surface missing")
            }
            is CameraCaptureStartResult.CameraNotActive -> {
                stopEncoderAndCamera(encoderStarted.session, openSession)
                CameraEncoderPipelineStartResult.Failed("camera is not active: ${captureResult.cameraId}")
            }
            is CameraCaptureStartResult.ConfigurationFailed -> {
                stopEncoderAndCamera(encoderStarted.session, openSession)
                CameraEncoderPipelineStartResult.Failed(captureResult.reason)
            }
            is CameraCaptureStartResult.ConfigurationSubmitted -> CameraEncoderPipelineStartResult.Started(
                CameraEncoderPipelineSession(
                    openSession = openSession,
                    encoderSession = encoderStarted.session,
                    captureSession = captureResult.session,
                ),
            )
        }
    }
}

class CameraEncoderPipelineSession internal constructor(
    private val openSession: CameraOpenSession,
    private val encoderSession: H264EncoderSession,
    private val captureSession: RepeatingCaptureSession,
) {
    private var stopped: Boolean = false

    @Synchronized
    fun drainEncoded(maxOutputs: Int): H264DrainResult = if (stopped) H264DrainResult.Stopped else encoderSession.drain(maxOutputs)

    @Synchronized
    fun consumeEncoded(count: Int) {
        encoderSession.consumePending(count)
    }

    @Synchronized
    fun stop(): CameraEncoderPipelineStopResult {
        if (stopped) return CameraEncoderPipelineStopResult.AlreadyStopped
        stopped = true
        val failures = mutableListOf<String>()
        when (val captureStop = captureSession.stop()) {
            RepeatingCaptureStopResult.Stopped,
            RepeatingCaptureStopResult.AlreadyStopped -> Unit
            is RepeatingCaptureStopResult.Failed -> failures += captureStop.reasons
        }
        when (val encoderStop = encoderSession.stop()) {
            H264EncoderStopResult.Stopped,
            H264EncoderStopResult.AlreadyStopped -> Unit
            is H264EncoderStopResult.Failed -> failures += encoderStop.reasons
        }
        try {
            openSession.cancel()
        } catch (_: RuntimeException) {
            failures += "camera close failed"
        }
        return if (failures.isEmpty()) CameraEncoderPipelineStopResult.Stopped else CameraEncoderPipelineStopResult.Failed(failures)
    }
}

private fun stopEncoderAndCamera(
    encoderSession: H264EncoderSession,
    openSession: CameraOpenSession,
): CameraEncoderPipelineStopResult {
    val failures = mutableListOf<String>()
    when (val encoderStop = encoderSession.stop()) {
        H264EncoderStopResult.Stopped,
        H264EncoderStopResult.AlreadyStopped -> Unit
        is H264EncoderStopResult.Failed -> failures += encoderStop.reasons
    }
    try {
        openSession.cancel()
    } catch (_: RuntimeException) {
        failures += "camera close failed"
    }
    return if (failures.isEmpty()) CameraEncoderPipelineStopResult.Stopped else CameraEncoderPipelineStopResult.Failed(failures)
}

private fun closeCameraOnly(openSession: CameraOpenSession): CameraEncoderPipelineStopResult = try {
    openSession.cancel()
    CameraEncoderPipelineStopResult.Stopped
} catch (_: RuntimeException) {
    CameraEncoderPipelineStopResult.Failed(listOf("camera close failed"))
}
