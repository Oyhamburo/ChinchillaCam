package dev.chinchillacam.usbprobe

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code from a luminance (Y) plane, e.g. plane 0 of a Camera2 `YUV_420_888` image.
 *
 * Pure JVM code over ZXing core. The QR reader is reused across calls, so an instance is NOT
 * thread-safe: use one decoder per thread.
 */
class QrLuminanceDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * Returns the QR text found in the [width]×[height] luminance image, or `null` when no
     * readable QR code is present. Rows start every [rowStride] bytes (padding is ignored).
     *
     * @throws IllegalArgumentException when the dimensions, stride or buffer size are invalid.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(rowStride >= width) { "rowStride ($rowStride) must be at least width ($width)" }
        val required = rowStride.toLong() * (height - 1) + width
        require(luminance.size >= required) { "luminance buffer has ${luminance.size} bytes, needs $required" }

        // PlanarYUVLuminanceSource treats dataWidth as the row stride and only reads the Y plane.
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }
}
