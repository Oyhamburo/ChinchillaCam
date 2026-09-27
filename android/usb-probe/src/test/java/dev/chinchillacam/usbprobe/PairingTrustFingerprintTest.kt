package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTrustFingerprintTest {
    @Test
    fun derivesSha256FingerprintFromTrustMaterial() {
        val fingerprint = PairingTrustFingerprint.fromTrustMaterial(byteArrayOf(0x01, 0x02, 0x03))

        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", fingerprint.hex)
        assertArrayEquals(
            byteArrayOf(
                0x03, 0x90.toByte(), 0x58, 0xc6.toByte(), 0xf2.toByte(), 0xc0.toByte(), 0xcb.toByte(), 0x49,
                0x2c, 0x53, 0x3b, 0x0a, 0x4d, 0x14, 0xef.toByte(), 0x77,
                0xcc.toByte(), 0x0f, 0x78, 0xab.toByte(), 0xcc.toByte(), 0xce.toByte(), 0xd5.toByte(), 0x28,
                0x7d, 0x84.toByte(), 0xa1.toByte(), 0xa2.toByte(), 0x01, 0x1c, 0xfb.toByte(), 0x81.toByte(),
            ),
            fingerprint.bytes,
        )
    }

    @Test
    fun copiesInputAndOutputBytesDefensively() {
        val trustMaterial = byteArrayOf(0x01, 0x02, 0x03)
        val fingerprint = PairingTrustFingerprint.fromTrustMaterial(trustMaterial)
        val originalHex = fingerprint.hex

        trustMaterial.fill(0x7f)
        val firstRead = fingerprint.bytes
        firstRead.fill(0x55)
        val secondRead = fingerprint.bytes

        assertEquals(originalHex, fingerprint.hex)
        assertNotSame(firstRead, secondRead)
        assertArrayEquals(PairingTrustFingerprint.fromTrustMaterial(byteArrayOf(0x01, 0x02, 0x03)).bytes, secondRead)
    }

    @Test
    fun rejectsEmptyTrustMaterialAndDoesNotClaimAuthentication() {
        assertTrue(runCatching { PairingTrustFingerprint.fromTrustMaterial(byteArrayOf()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(PairingTrustFingerprint.SECURITY_NOTE.contains("not authentication"))
    }
}
