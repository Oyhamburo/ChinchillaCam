package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingShortCodeTest {
    @Test
    fun shortCodeMatchesFixedVector() {
        val code = PairingShortCode.derive(DESKTOP_SPKI, PHONE_SPKI, QR_NONCE, CHALLENGE_NONCE)

        assertEquals("841406", code.digits)
        assertEquals("841 406", code.display)
    }

    @Test
    fun swappingSpkiRolesChangesCode() {
        val code = PairingShortCode.derive(PHONE_SPKI, DESKTOP_SPKI, QR_NONCE, CHALLENGE_NONCE)

        assertEquals("418534", code.digits)
        assertEquals("418 534", code.display)
    }

    @Test
    fun leadingZerosArePadded() {
        val code = PairingShortCode.fromValue(42)

        assertEquals("000042", code.digits)
        assertEquals("000 042", code.display)
        assertEquals(PairingShortCode.fromValue(42), code)
        assertEquals(PairingShortCode.fromValue(42).hashCode(), code.hashCode())
    }

    @Test
    fun emptyInputIsRejected() {
        val empty = ByteArray(0)

        assertThrows(IllegalArgumentException::class.java) {
            PairingShortCode.derive(empty, PHONE_SPKI, QR_NONCE, CHALLENGE_NONCE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingShortCode.derive(DESKTOP_SPKI, empty, QR_NONCE, CHALLENGE_NONCE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingShortCode.derive(DESKTOP_SPKI, PHONE_SPKI, empty, CHALLENGE_NONCE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingShortCode.derive(DESKTOP_SPKI, PHONE_SPKI, QR_NONCE, empty)
        }
    }

    private companion object {
        // Fixed SAS v1 vector shared with the desktop. Full digest:
        // 7371dabe90866b11a3e505c78991d0fe7699818a70029299bfc6b8de7d306d91
        val DESKTOP_SPKI: ByteArray = ByteArray(91) { i -> (0x10 + i).toByte() }
        val PHONE_SPKI: ByteArray = ByteArray(91) { i -> (0x80 + i).toByte() }
        val QR_NONCE: ByteArray = ByteArray(16) { 0xA5.toByte() }
        val CHALLENGE_NONCE: ByteArray = ByteArray(32) { 0x5A }
    }
}
