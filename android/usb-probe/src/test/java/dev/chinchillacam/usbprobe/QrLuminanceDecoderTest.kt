package dev.chinchillacam.usbprobe

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLuminanceDecoderTest {
    private val pairingText = PairingQrPayloadCodec.encode(
        PairingQrPayload(
            desktopId = "desktop-01",
            desktopName = "Studio Desktop",
            trustMaterial = ByteArray(91) { (0x10 + it).toByte() },
            expiresAtEpochSeconds = 1_700_000_600L,
            nonce = ByteArray(16) { 0xA5.toByte() },
        ),
    )

    @Test
    fun decodesGeneratedPairingQr() {
        assertTrue(pairingText.startsWith("CHINCHILLACAM-PAIR:v1:"))
        val matrix = QRCodeWriter().encode(pairingText, BarcodeFormat.QR_CODE, 400, 400)

        val decoded = QrLuminanceDecoder().decode(frameWith(matrix), FRAME_WIDTH, FRAME_HEIGHT, ROW_STRIDE)

        assertEquals(pairingText, decoded)
    }

    @Test
    fun rotatedQrStillDecodes() {
        val matrix = QRCodeWriter().encode(pairingText, BarcodeFormat.QR_CODE, 400, 400)
        val decoder = QrLuminanceDecoder()

        // Transposing is a mirror plus a quarter turn: the camera may see the code either way.
        assertEquals(pairingText, decoder.decode(frameWith(matrix, transpose = true), FRAME_WIDTH, FRAME_HEIGHT, ROW_STRIDE))
        // The reused reader must still decode a second, upright frame.
        assertEquals(pairingText, decoder.decode(frameWith(matrix), FRAME_WIDTH, FRAME_HEIGHT, ROW_STRIDE))
    }

    @Test
    fun blankFrameReturnsNull() {
        val blank = ByteArray(ROW_STRIDE * FRAME_HEIGHT) { 0xFF.toByte() }

        assertNull(QrLuminanceDecoder().decode(blank, FRAME_WIDTH, FRAME_HEIGHT, ROW_STRIDE))
    }

    @Test
    fun invalidStrideIsRejected() {
        val decoder = QrLuminanceDecoder()
        val frame = ByteArray(ROW_STRIDE * FRAME_HEIGHT)

        assertThrows(IllegalArgumentException::class.java) {
            decoder.decode(frame, FRAME_WIDTH, FRAME_HEIGHT, rowStride = FRAME_WIDTH - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decoder.decode(ByteArray(ROW_STRIDE * (FRAME_HEIGHT - 1)), FRAME_WIDTH, FRAME_HEIGHT, ROW_STRIDE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decoder.decode(frame, 0, FRAME_HEIGHT, ROW_STRIDE)
        }
    }

    /** White frame (0xFF) with stride padding bytes set to gray noise and the QR centered (black 0). */
    private fun frameWith(matrix: BitMatrix, transpose: Boolean = false): ByteArray {
        val frame = ByteArray(ROW_STRIDE * FRAME_HEIGHT)
        for (y in 0 until FRAME_HEIGHT) {
            for (x in 0 until ROW_STRIDE) {
                frame[y * ROW_STRIDE + x] = if (x < FRAME_WIDTH) 0xFF.toByte() else ((x * 37 + y * 11) and 0xFF).toByte()
            }
        }
        val left = (FRAME_WIDTH - matrix.width) / 2
        val top = (FRAME_HEIGHT - matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                val black = if (transpose) matrix.get(y, x) else matrix.get(x, y)
                if (black) frame[(top + y) * ROW_STRIDE + left + x] = 0
            }
        }
        return frame
    }

    private companion object {
        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480
        const val ROW_STRIDE = 704
    }
}
