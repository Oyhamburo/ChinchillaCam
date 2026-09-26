package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrPayloadTest {
    private val payload = PairingQrPayload(
        desktopId = "desktop-01",
        desktopName = "Studio Desktop",
        trustMaterial = byteArrayOf(0x01, 0x23, 0x45, 0x67),
        expiresAtEpochSeconds = 1_700_000_600L,
        nonce = byteArrayOf(0x10, 0x20, 0x30, 0x40),
    )

    @Test
    fun roundtripsPayloadFields() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val decoded = PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1_700_000_000L).getOrThrow()

        assertEquals(payload.desktopId, decoded.desktopId)
        assertEquals(payload.desktopName, decoded.desktopName)
        assertArrayEquals(payload.trustMaterial, decoded.trustMaterial)
        assertEquals(payload.expiresAtEpochSeconds, decoded.expiresAtEpochSeconds)
        assertArrayEquals(payload.nonce, decoded.nonce)
        assertEquals(1, decoded.version)
    }

    @Test
    fun encodingHasDeterministicQrPrefixAndStableFieldOrder() {
        val first = PairingQrPayloadCodec.encode(payload)
        val second = PairingQrPayloadCodec.encode(payload)

        assertEquals(first, second)
        assertTrue(first.startsWith("CHINCHILLACAM-PAIR:v1:"))
        assertTrue(first.contains("desktopId=desktop-01&desktopName=Studio%20Desktop&expiresAt=1700000600&nonce=ECAwQA&trustMaterial=ASNFZw"))
    }

    @Test
    fun rejectsExpiredPayloadUsingInjectedClock() {
        val encoded = PairingQrPayloadCodec.encode(payload)

        assertEquals(
            PairingQrPayloadDecodeError.Expired(expiresAtEpochSeconds = 1_700_000_600L, nowEpochSeconds = 1_700_000_601L),
            PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1_700_000_601L).exceptionOrNull(),
        )
    }

    @Test
    fun rejectsPayloadAtExpiryBoundary() {
        val encoded = PairingQrPayloadCodec.encode(payload)

        assertEquals(
            PairingQrPayloadDecodeError.Expired(expiresAtEpochSeconds = 1_700_000_600L, nowEpochSeconds = 1_700_000_600L),
            PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1_700_000_600L).exceptionOrNull(),
        )
    }

    @Test
    fun rejectsMalformedPercentEncodingWithTypedFieldError() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val malformed = encoded.replace("desktopName=Studio%20Desktop", "desktopName=Studio%2GDesktop")

        assertEquals(
            PairingQrPayloadDecodeError.InvalidField("percentEncoding"),
            PairingQrPayloadCodec.decode(malformed, nowEpochSeconds = 1L).exceptionOrNull(),
        )
    }

    @Test
    fun rejectsInvalidUtf8PercentDecodedBytesWithTypedFieldError() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val invalidUtf8 = encoded.replace("desktopName=Studio%20Desktop", "desktopName=%C3%28")

        assertEquals(
            PairingQrPayloadDecodeError.InvalidField("percentEncoding"),
            PairingQrPayloadCodec.decode(invalidUtf8, nowEpochSeconds = 1L).exceptionOrNull(),
        )
    }

    @Test
    fun rejectsChecksumMismatchFromAccidentalCorruption() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val corrupted = encoded.replace("desktop-01", "desktop-02")

        assertEquals(PairingQrPayloadDecodeError.ChecksumMismatch, PairingQrPayloadCodec.decode(corrupted, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsUnknownExtraField() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val withExtraField = encoded.replace("&checksum=", "&extra=value&checksum=")

        assertEquals(PairingQrPayloadDecodeError.InvalidFormatOrPrefix, PairingQrPayloadCodec.decode(withExtraField, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val encoded = PairingQrPayloadCodec.encode(payload).replace("CHINCHILLACAM-PAIR:v1:", "CHINCHILLACAM-PAIR:v2:")

        assertEquals(PairingQrPayloadDecodeError.UnsupportedVersion(2), PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsInvalidPrefix() {
        val encoded = PairingQrPayloadCodec.encode(payload).replace("CHINCHILLACAM-PAIR", "OTHER")

        assertEquals(PairingQrPayloadDecodeError.InvalidFormatOrPrefix, PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsMissingRequiredField() {
        val encoded = PairingQrPayloadCodec.encode(payload)
        val withoutName = encoded.replace("desktopName=Studio%20Desktop&", "")

        assertEquals(PairingQrPayloadDecodeError.MissingField("desktopName"), PairingQrPayloadCodec.decode(withoutName, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsInvalidTrustMaterial() {
        val invalid = "CHINCHILLACAM-PAIR:v1:desktopId=desktop-01&desktopName=Studio%20Desktop&expiresAt=1700000600&nonce=ECAwQA&trustMaterial=*&checksum=00"

        assertEquals(PairingQrPayloadDecodeError.InvalidField("trustMaterial"), PairingQrPayloadCodec.decode(invalid, nowEpochSeconds = 1L).exceptionOrNull())
    }

    @Test
    fun rejectsOversizedEncodedPayloadBeforeParsing() {
        val oversized = "CHINCHILLACAM-PAIR:v1:" + "a".repeat(4096)

        assertEquals(
            PairingQrPayloadDecodeError.PayloadTooLarge(actualSize = oversized.length, maxSize = 1024),
            PairingQrPayloadCodec.decode(oversized, nowEpochSeconds = 1L).exceptionOrNull(),
        )
    }

    @Test
    fun rejectsOversizedFieldsOnEncodeAndDecode() {
        val tooLongName = "n".repeat(65)
        val encodeError = runCatching {
            PairingQrPayloadCodec.encode(payload.copy(desktopName = tooLongName))
        }.exceptionOrNull()
        assertTrue(encodeError is IllegalArgumentException)

        val encoded = PairingQrPayloadCodec.encode(payload.copy(trustMaterial = ByteArray(128) { 1 }))
        assertEquals(
            PairingQrPayloadDecodeError.InvalidField("trustMaterial"),
            PairingQrPayloadCodec.decode(encoded, nowEpochSeconds = 1L, maxTrustMaterialBytes = 64).exceptionOrNull(),
        )
    }
}
