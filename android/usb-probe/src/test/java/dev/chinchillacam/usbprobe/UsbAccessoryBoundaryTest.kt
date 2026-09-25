package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessoryBoundaryTest {
    @Test
    fun parsesLittleEndianAoaProtocolVersion() {
        val parsed = AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x02, 0x00))

        assertEquals(AoaProtocolVersion(2), parsed)
    }

    @Test
    fun rejectsZeroProtocolVersion() {
        val result = runCatching { AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x00, 0x00)) }

        assertTrue(result.isFailure)
    }

    @Test
    fun validatesAccessoryIdentityRequiredFields() {
        val identity = AccessoryIdentity(
            manufacturer = "ChinchillaCam",
            model = "USB Probe",
            description = "AOA feasibility boundary",
            version = "0.1.0",
            uri = "https://example.invalid/chinchillacam",
            serial = "prototype",
        )

        assertTrue(identity.isValidForAoaHandshake())
        assertFalse(identity.copy(manufacturer = "").isValidForAoaHandshake())
    }

    @Test
    fun formatsDeviceSummaryWithoutHardwareClaims() {
        val summary = UsbDeviceSummary(
            vendorId = 0x18D1,
            productId = 0x2D00,
            protocolVersion = AoaProtocolVersion(2),
        )

        assertEquals(
            "USB device 18d1:2d00 reports AOA protocol v2; hardware transport remains unvalidated",
            summary.toString(),
        )
    }
}
