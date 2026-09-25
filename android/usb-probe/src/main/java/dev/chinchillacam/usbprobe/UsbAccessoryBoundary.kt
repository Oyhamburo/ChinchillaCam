package dev.chinchillacam.usbprobe

import android.app.PendingIntent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

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
        pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

data class UsbProbeUiState(
    val title: String,
    val statusText: String,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val safetyNotice: String,
)

object UsbProbeScreenPlanner {
    fun plan(
        accessoryAvailable: Boolean,
        permissionRequested: Boolean,
        busy: Boolean,
        lastResult: String?,
    ): UsbProbeUiState {
        val status = lastResult ?: if (accessoryAvailable) {
            "Accesorio USB detectado. Tocá el botón para pedir permiso."
        } else {
            "Conectá el accesorio USB para iniciar la prueba."
        }
        return UsbProbeUiState(
            title = "ChinchillaCam prueba USB",
            statusText = status,
            primaryActionLabel = "Solicitar permiso y abrir accesorio USB",
            primaryActionEnabled = accessoryAvailable && !permissionRequested && !busy,
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
