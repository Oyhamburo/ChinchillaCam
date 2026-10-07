package dev.chinchillacam.usbprobe

import android.hardware.camera2.CameraManager
import android.os.Handler

class AndroidVisibleCameraPipelineLauncher(
    private val cameraManager: CameraManager,
    private val handler: Handler,
    private val openWaitMillis: Long = 2_000L,
    private val captureWaitMillis: Long = 2_000L,
) : VisibleCameraPipelineLauncher {
    override fun start(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
        encoderConfig: H264EncoderConfig,
    ): VisibleCameraPipelineLaunchResult = start(snapshot, selectedCameraId, cameraPermissionGranted, CameraStreamConfig(encoderConfig))

    override fun start(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
        streamConfig: CameraStreamConfig,
    ): VisibleCameraPipelineLaunchResult {
        val encoderConfig = streamConfig.encoderConfig
        val openSession = when (val opened = CameraDeviceOpenBoundary(
            AndroidCameraDeviceOpenGateway(cameraManager, handler),
        ).requestOpenSelected(snapshot, selectedCameraId, cameraPermissionGranted)) {
            CameraDeviceOpenResult.MissingSelection -> return VisibleCameraPipelineLaunchResult.Failed("missing camera selection", FailureCause.CameraUnavailable)
            CameraDeviceOpenResult.CameraPermissionMissing -> return VisibleCameraPipelineLaunchResult.Failed("camera permission missing", FailureCause.CameraPermissionDenied)
            is CameraDeviceOpenResult.SelectionNotDirectOpenCandidate -> return VisibleCameraPipelineLaunchResult.Failed("camera selection unavailable", FailureCause.CameraUnavailable)
            is CameraDeviceOpenResult.OpenRequestFailed -> return VisibleCameraPipelineLaunchResult.Failed(opened.reason, FailureCause.CameraOpenFailed)
            is CameraDeviceOpenResult.OpenRequestSubmitted -> opened.session
        }
        if (!waitForOpen(openSession)) {
            openSession.cancel()
            return VisibleCameraPipelineLaunchResult.Failed("camera open timed out", FailureCause.CameraOpenFailed)
        }
        val androidDevice = openSession.activeDevice as? AndroidCloseableCameraDevice ?: run {
            openSession.cancel()
            return VisibleCameraPipelineLaunchResult.Failed("camera device incompatible", FailureCause.CameraOpenFailed)
        }
        val encoder = when (val started = H264EncoderBoundary(AndroidH264EncoderGateway()).start(encoderConfig)) {
            is H264EncoderStartResult.Failed -> {
                openSession.cancel()
                return VisibleCameraPipelineLaunchResult.Failed(started.reason, FailureCause.EncoderFailed)
            }
            is H264EncoderStartResult.Started -> started
        }
        val capture = when (val started = CameraCaptureSessionBoundary(
            AndroidCameraCaptureSessionGateway(androidDevice.camera, handler),
        ).startRepeating(openSession, encoder.inputSurface, streamConfig.fpsRange)) {
            CameraCaptureStartResult.MissingSurface -> {
                cleanupStartup(encoder.session, openSession)
                return VisibleCameraPipelineLaunchResult.Failed("encoder surface missing", FailureCause.EncoderFailed)
            }
            is CameraCaptureStartResult.CameraNotActive -> {
                cleanupStartup(encoder.session, openSession)
                return VisibleCameraPipelineLaunchResult.Failed("camera closed before capture", FailureCause.CaptureFailed)
            }
            is CameraCaptureStartResult.ConfigurationFailed -> {
                cleanupStartup(encoder.session, openSession)
                return VisibleCameraPipelineLaunchResult.Failed(started.reason, FailureCause.CaptureFailed)
            }
            is CameraCaptureStartResult.ConfigurationSubmitted -> started.session
        }
        if (!waitForCapture(capture)) {
            cleanupStartup(encoder.session, openSession, capture)
            return VisibleCameraPipelineLaunchResult.Failed("capture confirmation timed out", FailureCause.CaptureFailed)
        }
        return VisibleCameraPipelineLaunchResult.Running(
            CameraEncoderPipelineVisibleHandle(
                CameraEncoderPipelineSession(openSession, encoder.session, capture),
            ),
        )
    }

    private fun waitForOpen(session: CameraOpenSession): Boolean {
        val deadline = System.currentTimeMillis() + openWaitMillis
        while (System.currentTimeMillis() < deadline) {
            when {
                session.isActive -> return true
                session.isTerminal -> return false
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun waitForCapture(session: RepeatingCaptureSession): Boolean {
        val deadline = System.currentTimeMillis() + captureWaitMillis
        while (System.currentTimeMillis() < deadline) {
            when {
                session.isRepeating -> return true
                session.isClosed -> return false
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun cleanupStartup(
        encoderSession: H264EncoderSession,
        openSession: CameraOpenSession,
        captureSession: RepeatingCaptureSession? = null,
    ) {
        captureSession?.stop()
        encoderSession.stop()
        openSession.cancel()
    }
}
