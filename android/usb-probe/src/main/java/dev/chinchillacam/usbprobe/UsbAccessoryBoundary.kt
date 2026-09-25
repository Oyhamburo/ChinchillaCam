package dev.chinchillacam.usbprobe

import android.app.PendingIntent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@JvmInline
value class AoaProtocolVersion(val value: Int) {
    init {
        require(value > 0) { "AOA protocol version must be positive" }
    }

    companion object {
        fun fromLittleEndian(bytes: ByteArray): AoaProtocolVersion {
            require(bytes.size == 2) { "AOA protocol version response must be exactly two bytes" }

            val parsed = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            return AoaProtocolVersion(parsed)
        }
    }
}

data class AccessoryIdentity(
    val manufacturer: String,
    val model: String,
    val description: String,
    val version: String,
    val uri: String,
    val serial: String,
) {
    fun isValidForAoaHandshake(): Boolean =
        manufacturer.isNotBlank() &&
            model.isNotBlank() &&
            description.isNotBlank() &&
            version.isNotBlank()
}

data class UsbDeviceSummary(
    val vendorId: Int,
    val productId: Int,
    val protocolVersion: AoaProtocolVersion,
) {
    override fun toString(): String =
        "USB device ${vendorId.toFourDigitHex()}:${productId.toFourDigitHex()} reports " +
            "AOA protocol v${protocolVersion.value}; hardware transport remains unvalidated"
}

class UsbAccessoryBoundary<T>(
    private val gateway: UsbAccessoryGateway<T>,
) {
    fun open(accessory: T, hasPermission: Boolean): AccessoryOpenResult<T> {
        if (!hasPermission) return AccessoryOpenResult.PermissionDenied(accessory)

        val streams = gateway.openAccessory(accessory)
            ?: return AccessoryOpenResult.OpenFailed(accessory)

        return AccessoryOpenResult.Opened(AccessoryIoSession(streams.input, streams.output, streams.closeable))
    }
}

class AndroidUsbAccessoryBoundary(
    private val usbManager: UsbManager,
) {
    private val boundary = UsbAccessoryBoundary(AndroidUsbAccessoryGateway(usbManager))

    fun open(accessory: UsbAccessory): AccessoryOpenResult<UsbAccessory> =
        boundary.open(accessory, usbManager.hasPermission(accessory))
}

fun interface UsbAccessoryGateway<T> {
    fun openAccessory(accessory: T): AccessoryStreams?
}

data class AccessoryStreams(
    val input: InputStream,
    val output: OutputStream,
    val closeable: Closeable,
)

sealed class AccessoryOpenResult<out T> {
    data class PermissionDenied<T>(val accessory: T) : AccessoryOpenResult<T>()
    data class OpenFailed<T>(val accessory: T) : AccessoryOpenResult<T>()
    data class Opened(val session: AccessoryIoSession) : AccessoryOpenResult<Nothing>()
}

class AccessoryIoSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
) : Closeable {
    private var closed = false

    fun readExactly(expectedBytes: Int): AccessoryReadResult {
        require(expectedBytes > 0) { "expectedBytes must be positive" }

        val buffer = ByteArray(expectedBytes)
        var offset = 0
        while (offset < expectedBytes) {
            val read = input.read(buffer, offset, expectedBytes - offset)
            if (read == -1) {
                return if (offset == 0) {
                    AccessoryReadResult.Eof
                } else {
                    AccessoryReadResult.ShortRead(expectedBytes = expectedBytes, actualBytes = offset)
                }
            }
            offset += read
        }
        return AccessoryReadResult.Complete(buffer)
    }

    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
        output.close()
        closeable.close()
    }
}

sealed class AccessoryReadResult {
    data class Complete(val bytes: ByteArray) : AccessoryReadResult() {
        override fun equals(other: Any?): Boolean = other is Complete && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class ShortRead(val expectedBytes: Int, val actualBytes: Int) : AccessoryReadResult()
    object Eof : AccessoryReadResult()

    fun getOrThrow(): ByteArray = when (this) {
        is Complete -> bytes
        Eof -> error("EOF before reading any bytes")
        is ShortRead -> error("Short read: expected $expectedBytes bytes, got $actualBytes")
    }
}

class AndroidUsbAccessoryGateway(
    private val usbManager: UsbManager,
) : UsbAccessoryGateway<UsbAccessory> {
    override fun openAccessory(accessory: UsbAccessory): AccessoryStreams? {
        val descriptor = usbManager.openAccessory(accessory) ?: return null
        return AccessoryStreams(
            input = FileInputStream(descriptor.fileDescriptor),
            output = FileOutputStream(descriptor.fileDescriptor),
            closeable = descriptor,
        )
    }
}

data class AccessoryFrame(
    val streamId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is AccessoryFrame &&
        streamId == other.streamId &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * streamId + payload.contentHashCode()
}

sealed class FrameDecodeResult {
    data class Complete(val frame: AccessoryFrame) : FrameDecodeResult()
    data class ShortHeader(val actualBytes: Int) : FrameDecodeResult()
    data class ShortPayload(val expectedBytes: Int, val actualBytes: Int) : FrameDecodeResult()
    data class OversizePayload(val declaredBytes: Int, val maxPayloadBytes: Int) : FrameDecodeResult()

    fun getOrThrow(): AccessoryFrame = when (this) {
        is Complete -> frame
        is ShortHeader -> error("Short frame header: got $actualBytes bytes")
        is ShortPayload -> error("Short frame payload: expected $expectedBytes bytes, got $actualBytes")
        is OversizePayload -> error("Oversize frame payload: declared $declaredBytes bytes, max $maxPayloadBytes")
    }
}

object AccessoryFrameCodec {
    private val ackPayload = byteArrayOf(0x41, 0x43, 0x4b)

    fun decode(bytes: ByteArray, maxPayloadBytes: Int): FrameDecodeResult {
        require(maxPayloadBytes >= 0) { "maxPayloadBytes must be non-negative" }
        if (bytes.size < 8) return FrameDecodeResult.ShortHeader(bytes.size)

        val streamId = bytes.readLittleEndianInt(offset = 0)
        val payloadLength = bytes.readLittleEndianInt(offset = 4)
        if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
            return FrameDecodeResult.OversizePayload(payloadLength, maxPayloadBytes)
        }

        val actualPayloadBytes = bytes.size - 8
        if (actualPayloadBytes < payloadLength) {
            return FrameDecodeResult.ShortPayload(expectedBytes = payloadLength, actualBytes = actualPayloadBytes)
        }

        return FrameDecodeResult.Complete(AccessoryFrame(streamId, bytes.copyOfRange(8, 8 + payloadLength)))
    }

    fun encode(frame: AccessoryFrame, maxPayloadBytes: Int): ByteArray {
        require(frame.payload.size <= maxPayloadBytes) { "payload exceeds maxPayloadBytes" }
        return frame.streamId.toLittleEndianBytes() + frame.payload.size.toLittleEndianBytes() + frame.payload
    }

    fun encodeAck(streamId: Int, maxPayloadBytes: Int): ByteArray =
        encode(AccessoryFrame(streamId, ackPayload), maxPayloadBytes)
}

data class PermissionRequestPlan(
    val action: String,
    val packageName: String,
    val receiverClassName: String,
    val pendingIntentFlags: Int,
)

object AccessoryPermissionPlanner {
    fun plan(packageName: String, receiverClassName: String): PermissionRequestPlan = PermissionRequestPlan(
        action = "$packageName.action.USB_ACCESSORY_PERMISSION",
        packageName = packageName,
        receiverClassName = receiverClassName,
        pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

data class UsbProbeUiState(
    val title: String,
    val statusText: String,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val safetyNotice: String,
)

data class AccessorySmokeExecutionPlan(
    val runsOnAndroidMainThread: Boolean,
    val holdsBroadcastPendingResultForUsbIo: Boolean,
    val appLevelTimeoutMillis: Long,
    val readCancellationGuaranteed: Boolean,
)

object AccessoryExecutionPlanner {
    const val DEFAULT_SMOKE_TIMEOUT_MILLIS: Long = 10_000

    fun planApprovedSmokeIo(): AccessorySmokeExecutionPlan = AccessorySmokeExecutionPlan(
        runsOnAndroidMainThread = false,
        holdsBroadcastPendingResultForUsbIo = false,
        appLevelTimeoutMillis = DEFAULT_SMOKE_TIMEOUT_MILLIS,
        readCancellationGuaranteed = false,
    )
}

sealed class AccessoryPermissionCallback {
    object Granted : AccessoryPermissionCallback()
    object Denied : AccessoryPermissionCallback()
    object MissingAccessory : AccessoryPermissionCallback()
    object MissingPermissionResult : AccessoryPermissionCallback()
}

sealed class AccessoryPermissionEvent {
    object Requested : AccessoryPermissionEvent()
    data class Callback(val callback: AccessoryPermissionCallback) : AccessoryPermissionEvent()
    object CallbackTimedOut : AccessoryPermissionEvent()
}

sealed class AccessoryPermissionUiModel {
    object Idle : AccessoryPermissionUiModel()
    object WaitingForCallback : AccessoryPermissionUiModel()
    object Granted : AccessoryPermissionUiModel()
    object Denied : AccessoryPermissionUiModel()
    object MissingAccessory : AccessoryPermissionUiModel()
    object MissingPermissionResult : AccessoryPermissionUiModel()
    object CallbackMissingOrCanceled : AccessoryPermissionUiModel()
}

object AccessoryPermissionLifecycleReducer {
    fun reduce(current: AccessoryPermissionUiModel, event: AccessoryPermissionEvent): AccessoryPermissionUiModel = when (event) {
        AccessoryPermissionEvent.Requested -> AccessoryPermissionUiModel.WaitingForCallback
        AccessoryPermissionEvent.CallbackTimedOut -> if (current == AccessoryPermissionUiModel.WaitingForCallback) {
            AccessoryPermissionUiModel.CallbackMissingOrCanceled
        } else {
            current
        }
        is AccessoryPermissionEvent.Callback -> when (event.callback) {
            AccessoryPermissionCallback.Granted -> AccessoryPermissionUiModel.Granted
            AccessoryPermissionCallback.Denied -> AccessoryPermissionUiModel.Denied
            AccessoryPermissionCallback.MissingAccessory -> AccessoryPermissionUiModel.MissingAccessory
            AccessoryPermissionCallback.MissingPermissionResult -> AccessoryPermissionUiModel.MissingPermissionResult
        }
    }
}

sealed class AccessoryPermissionReceiverPlan {
    object Ignore : AccessoryPermissionReceiverPlan()
    data class RecordAndLaunchActivity(val callback: AccessoryPermissionCallback) : AccessoryPermissionReceiverPlan()

    val holdsBroadcastPendingResultForUsbIo: Boolean
        get() = false
}

object AccessoryPermissionCallbackPlanner {
    fun plan(
        actionMatches: Boolean,
        hasGrantExtra: Boolean,
        permissionGranted: Boolean,
        hasAccessory: Boolean,
    ): AccessoryPermissionReceiverPlan {
        if (!actionMatches) return AccessoryPermissionReceiverPlan.Ignore
        val callback = when {
            !hasGrantExtra -> AccessoryPermissionCallback.MissingPermissionResult
            permissionGranted && hasAccessory -> AccessoryPermissionCallback.Granted
            permissionGranted && !hasAccessory -> AccessoryPermissionCallback.MissingAccessory
            else -> AccessoryPermissionCallback.Denied
        }
        return AccessoryPermissionReceiverPlan.RecordAndLaunchActivity(callback)
    }
}

object UsbProbeScreenPlanner {
    fun plan(
        accessoryAvailable: Boolean,
        permissionRequested: Boolean,
        busy: Boolean,
        lastResult: String?,
    ): UsbProbeUiState = plan(
        accessoryAvailable = accessoryAvailable,
        permissionState = if (permissionRequested) AccessoryPermissionUiModel.WaitingForCallback else AccessoryPermissionUiModel.Idle,
        busy = busy,
        lastResult = lastResult,
    )

    fun plan(
        accessoryAvailable: Boolean,
        permissionState: AccessoryPermissionUiModel,
        busy: Boolean,
        lastResult: String?,
    ): UsbProbeUiState {
        val status = lastResult ?: when {
            permissionState == AccessoryPermissionUiModel.WaitingForCallback -> "Esperando permiso del sistema Android."
            permissionState == AccessoryPermissionUiModel.Denied -> "Android denegó el permiso del accesorio; podés intentar de nuevo."
            permissionState == AccessoryPermissionUiModel.MissingAccessory -> "Android aprobó el permiso, pero no devolvió el accesorio."
            permissionState == AccessoryPermissionUiModel.MissingPermissionResult -> "Android devolvió un callback de permiso incompleto; podés intentar de nuevo."
            permissionState == AccessoryPermissionUiModel.CallbackMissingOrCanceled -> "Android no devolvió el resultado de permiso; podés intentar de nuevo."
            accessoryAvailable -> "Accesorio USB detectado. Tocá el botón para pedir permiso."
            else -> "Conectá el accesorio USB para iniciar la prueba."
        }
        return UsbProbeUiState(
            title = "ChinchillaCam prueba USB",
            statusText = status,
            primaryActionLabel = "Solicitar permiso y abrir accesorio USB",
            primaryActionEnabled = accessoryAvailable && !busy && permissionState != AccessoryPermissionUiModel.WaitingForCallback,
            safetyNotice = "No se abre nada hasta que toques el botón y Android apruebe el permiso.",
        )
    }
}

sealed class AccessorySmokeResult {
    data class AckWritten(val streamId: Int, val payloadBytes: Int) : AccessorySmokeResult()
    data class ReadFailed(val reason: AccessoryReadResult) : AccessorySmokeResult()
    data class DecodeFailed(val reason: FrameDecodeResult) : AccessorySmokeResult()
}

class AccessorySmokeRunner(
    private val maxPayloadBytes: Int,
) {
    fun runApprovedSession(session: AccessoryIoSession): AccessorySmokeResult {
        val header = session.readExactly(8)
        if (header !is AccessoryReadResult.Complete) return AccessorySmokeResult.ReadFailed(header)
        val declaredLength = header.bytes.readLittleEndianInt(offset = 4)
        if (declaredLength < 0 || declaredLength > maxPayloadBytes) {
            return AccessorySmokeResult.DecodeFailed(FrameDecodeResult.OversizePayload(declaredLength, maxPayloadBytes))
        }
        val payloadBytes = if (declaredLength == 0) {
            ByteArray(0)
        } else {
            val payload = session.readExactly(declaredLength)
            if (payload !is AccessoryReadResult.Complete) return AccessorySmokeResult.ReadFailed(payload)
            payload.bytes
        }
        val decoded = AccessoryFrameCodec.decode(header.bytes + payloadBytes, maxPayloadBytes)
        if (decoded !is FrameDecodeResult.Complete) return AccessorySmokeResult.DecodeFailed(decoded)

        session.write(AccessoryFrameCodec.encodeAck(decoded.frame.streamId, maxPayloadBytes))
        return AccessorySmokeResult.AckWritten(decoded.frame.streamId, decoded.frame.payload.size)
    }
}

private fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)

private fun Int.toLittleEndianBytes(): ByteArray = byteArrayOf(
    (this and 0xff).toByte(),
    ((this ushr 8) and 0xff).toByte(),
    ((this ushr 16) and 0xff).toByte(),
    ((this ushr 24) and 0xff).toByte(),
)

private fun Int.toFourDigitHex(): String = toString(16).padStart(4, '0')


sealed class BoundedAccessorySmokeResult {
    data class Completed(val result: AccessorySmokeResult) : BoundedAccessorySmokeResult()
    data class TimedOut(val readCancellationGuaranteed: Boolean) : BoundedAccessorySmokeResult()
}

class BoundedAccessorySmokeSession(
    private val timeoutMillis: Long,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    fun run(
        session: AccessoryIoSession,
        operation: (AccessoryIoSession) -> AccessorySmokeResult,
    ): BoundedAccessorySmokeResult {
        val future = executor.submit<AccessorySmokeResult> { operation(session) }
        return try {
            BoundedAccessorySmokeResult.Completed(future.get(timeoutMillis, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            session.close()
            future.cancel(true)
            BoundedAccessorySmokeResult.TimedOut(readCancellationGuaranteed = false)
        }
    }
}
