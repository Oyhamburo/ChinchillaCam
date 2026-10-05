package dev.chinchillacam.usbprobe

import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin Camera2 adapter that scans pairing QR codes from the Y plane of `YUV_420_888` frames.
 *
 * Not covered by JVM tests: decoding lives in [QrLuminanceDecoder]. The caller must hold the
 * CAMERA permission before [start] (a missing permission is reported through [onError]).
 * Camera callbacks and resource ownership run on one camera thread; decoding runs on a separate
 * thread, one frame at a time (frames arriving while a decode is busy are dropped).
 * [onQrText] (first text with the pairing prefix) or [onError] is called at most once, on the
 * main thread, and then scanning stops by itself. Nothing is delivered after [close].
 */
class Camera2QrScanner(
    private val cameraManager: CameraManager,
    private val cameraId: String,
    private val previewSurface: Surface?,
    private val onQrText: (String) -> Unit,
    private val onError: (String) -> Unit,
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = QrLuminanceDecoder() // only used on the decode thread
    private val decodeBusy = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    @Volatile private var closed = false
    @Volatile private var cancelledByCaller = false
    private var started = false
    private var cameraHandler: Handler? = null
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    // Owned by the camera thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var openPending = false

    // Written by the camera thread only while decodeBusy is held, read by the decode thread.
    private var luma = ByteArray(0)

    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
        val cameraThread = HandlerThread("qr-scan-camera").also { it.start() }
        val decodes = HandlerThread("qr-scan-decode").also { it.start() }
        decodeThread = decodes
        decodeHandler = Handler(decodes.looper)
        val handler = Handler(cameraThread.looper)
        cameraHandler = handler
        handler.post { openCamera(handler) }
    }

    /** Idempotent; releases the camera on its own thread and suppresses pending deliveries. */
    override fun close() {
        cancelledByCaller = true
        shutdown()
    }

    private fun shutdown() {
        synchronized(this) {
            if (closed) return
            closed = true
            cameraHandler?.post { releaseCamera() }
            decodeThread?.quitSafely()
        }
    }

    private fun openCamera(handler: Handler) {
        if (closed) return
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val size = chooseFrameSize(characteristics)
            val continuousAf = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
            openPending = true
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openPending = false
                    if (closed) return closeAndQuit(camera)
                    device = camera
                    createSession(camera, imageReader, continuousAf, handler)
                }

                override fun onDisconnected(camera: CameraDevice) = onLost(camera)

                override fun onError(camera: CameraDevice, error: Int) = onLost(camera)
            }, handler)
        } catch (_: SecurityException) {
            fail(OPEN_FAILED)
        } catch (_: CameraAccessException) {
            fail(OPEN_FAILED)
        } catch (_: IllegalArgumentException) {
            fail(OPEN_FAILED)
        }
    }

    private fun onLost(camera: CameraDevice) {
        openPending = false
        if (device === camera) device = null
        if (closed) return closeAndQuit(camera)
        camera.close()
        fail(OPEN_FAILED)
    }

    /** Pre-API-28 session API on purpose: minSdk is 23. */
    private fun createSession(camera: CameraDevice, imageReader: ImageReader, continuousAf: Boolean, handler: Handler) {
        val targets = listOfNotNull(imageReader.surface, previewSurface)
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (closed) return configured.close()
                    session = configured
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            targets.forEach { addTarget(it) }
                            if (continuousAf) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                        }.build()
                        configured.setRepeatingRequest(request, null, handler)
                    } catch (_: CameraAccessException) {
                        fail(OPEN_FAILED)
                    } catch (_: IllegalStateException) {
                        fail(OPEN_FAILED)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) = fail(OPEN_FAILED)
            }, handler)
        } catch (_: CameraAccessException) {
            fail(OPEN_FAILED)
        } catch (_: IllegalArgumentException) {
            fail(OPEN_FAILED)
        } catch (_: IllegalStateException) {
            fail(OPEN_FAILED)
        }
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            if (closed || !decodeBusy.compareAndSet(false, true)) return
            // Plane 0 of YUV_420_888 is Y with pixelStride 1; rows may carry stride padding.
            val plane = image.planes[0]
            val source = plane.buffer.duplicate().apply { rewind() }
            val length = source.remaining()
            if (luma.size < length) luma = ByteArray(length)
            source.get(luma, 0, length)
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            if (decodeHandler?.post { decodeFrame(width, height, rowStride) } != true) decodeBusy.set(false)
        } finally {
            image.close()
        }
    }

    private fun decodeFrame(width: Int, height: Int, rowStride: Int) {
        val text = try {
            decoder.decode(luma, width, height, rowStride)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            decodeBusy.set(false)
        }
        if (text == null || !text.startsWith(PAIRING_TEXT_PREFIX) || closed) return
        if (reported.compareAndSet(false, true)) {
            mainHandler.post { if (!cancelledByCaller) onQrText(text) }
        }
        shutdown()
    }

    private fun fail(message: String) {
        if (closed) return
        if (reported.compareAndSet(false, true)) {
            mainHandler.post { if (!cancelledByCaller) onError(message) }
        }
        shutdown()
    }

    /** Camera thread. Waits for a pending open to resolve so the device is never leaked. */
    private fun releaseCamera() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        if (!openPending) Looper.myLooper()?.quitSafely()
    }

    private fun closeAndQuit(camera: CameraDevice) {
        camera.close()
        Looper.myLooper()?.quitSafely()
    }

    private companion object {
        const val PAIRING_TEXT_PREFIX = "CHINCHILLACAM-PAIR:"
        const val OPEN_FAILED = "No se pudo abrir la cámara para escanear."
        const val MAX_WIDTH = 1280
        const val MAX_HEIGHT = 720

        /** Largest YUV size within 1280×720, else the smallest one offered. */
        fun chooseFrameSize(characteristics: CameraCharacteristics): Size {
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
            require(!sizes.isNullOrEmpty()) { "camera offers no YUV_420_888 sizes" }
            return sizes.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
        }
    }
}
