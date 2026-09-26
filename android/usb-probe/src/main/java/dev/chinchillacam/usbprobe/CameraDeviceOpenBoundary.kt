package dev.chinchillacam.usbprobe

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler

class CameraDeviceOpenBoundary(
    private val gateway: CameraDeviceOpenGateway,
) {
    fun requestOpenSelected(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
    ): CameraDeviceOpenResult {
        val cameraId = selectedCameraId ?: return CameraDeviceOpenResult.MissingSelection
        if (!cameraPermissionGranted) return CameraDeviceOpenResult.CameraPermissionMissing
        if (!snapshot.isDirectOpenCandidate(cameraId)) {
            return CameraDeviceOpenResult.SelectionNotDirectOpenCandidate(cameraId)
        }

        val session = CameraOpenSession(cameraId)
        return when (val outcome = gateway.requestOpen(cameraId, session.callbacks)) {
            CameraDeviceOpenRequestOutcome.Submitted -> CameraDeviceOpenResult.OpenRequestSubmitted(cameraId, session)
            is CameraDeviceOpenRequestOutcome.Failed -> CameraDeviceOpenResult.OpenRequestFailed(cameraId, outcome.reason)
        }
    }

    private fun CameraCatalogSnapshot.isDirectOpenCandidate(cameraId: String): Boolean = entries.any { entry ->
        entry.id == cameraId && entry.role is CameraIdRole.DirectOpenCandidate
    }
}

interface CameraDeviceOpenGateway {
    fun requestOpen(cameraId: String, callbacks: CameraOpenCallbacks): CameraDeviceOpenRequestOutcome
}

sealed class CameraDeviceOpenRequestOutcome {
    object Submitted : CameraDeviceOpenRequestOutcome()
    data class Failed(val reason: String) : CameraDeviceOpenRequestOutcome()
}

sealed class CameraDeviceOpenResult {
    object MissingSelection : CameraDeviceOpenResult()
    object CameraPermissionMissing : CameraDeviceOpenResult()
    data class SelectionNotDirectOpenCandidate(val cameraId: String) : CameraDeviceOpenResult()
    data class OpenRequestFailed(val cameraId: String, val reason: String) : CameraDeviceOpenResult()
    data class OpenRequestSubmitted(val cameraId: String, val session: CameraOpenSession) : CameraDeviceOpenResult()
}

sealed class CameraOpenCallbackResult {
    data class Activated(val cameraId: String) : CameraOpenCallbackResult()
    data class Closed(val cameraId: String) : CameraOpenCallbackResult()
    data class StaleIgnored(val cameraId: String) : CameraOpenCallbackResult()
}

interface CloseableCameraDevice {
    val cameraId: String
    fun close()
}

class CameraOpenCallbacks internal constructor(
    private val session: CameraOpenSession,
) {
    fun onOpened(device: CloseableCameraDevice): CameraOpenCallbackResult = session.onOpened(device)
    fun onDisconnected(device: CloseableCameraDevice? = null): CameraOpenCallbackResult = session.onDisconnectedOrError(device)
    fun onError(device: CloseableCameraDevice? = null): CameraOpenCallbackResult = session.onDisconnectedOrError(device)
}

class CameraOpenSession(
    val cameraId: String,
) {
    val callbacks: CameraOpenCallbacks = CameraOpenCallbacks(this)
    private var device: CloseableCameraDevice? = null
    private var canceled: Boolean = false

    val isActive: Boolean
        @Synchronized get() = device != null && !canceled

    @Synchronized
    fun cancel() {
        canceled = true
        device?.close()
        device = null
    }

    @Synchronized
    internal fun onOpened(openedDevice: CloseableCameraDevice): CameraOpenCallbackResult {
        if (canceled) {
            openedDevice.close()
            return CameraOpenCallbackResult.StaleIgnored(cameraId)
        }
        device = openedDevice
        return CameraOpenCallbackResult.Activated(cameraId)
    }

    @Synchronized
    internal fun onDisconnectedOrError(terminalDevice: CloseableCameraDevice? = null): CameraOpenCallbackResult {
        val deviceToClose = device ?: terminalDevice
        deviceToClose?.close()
        device = null
        canceled = true
        return CameraOpenCallbackResult.Closed(cameraId)
    }
}

class AndroidCameraDeviceOpenGateway(
    private val cameraManager: CameraManager,
    private val handler: Handler,
) : CameraDeviceOpenGateway {
    override fun requestOpen(cameraId: String, callbacks: CameraOpenCallbacks): CameraDeviceOpenRequestOutcome = try {
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    callbacks.onOpened(AndroidCloseableCameraDevice(camera))
                }

                override fun onDisconnected(camera: CameraDevice) {
                    callbacks.onDisconnected(AndroidCloseableCameraDevice(camera))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    callbacks.onError(AndroidCloseableCameraDevice(camera))
                }
            },
            handler,
        )
        CameraDeviceOpenRequestOutcome.Submitted
    } catch (_: SecurityException) {
        CameraDeviceOpenRequestOutcome.Failed("camera permission missing")
    } catch (_: CameraAccessException) {
        CameraDeviceOpenRequestOutcome.Failed("camera access failed")
    } catch (_: IllegalArgumentException) {
        CameraDeviceOpenRequestOutcome.Failed("camera id rejected")
    }
}

private class AndroidCloseableCameraDevice(
    private val camera: CameraDevice,
) : CloseableCameraDevice {
    override val cameraId: String = camera.id
    override fun close() {
        camera.close()
    }
}
