package dev.chinchillacam.usbprobe

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

private fun Int.toFourDigitHex(): String = toString(16).padStart(4, '0')
