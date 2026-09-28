package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class DesktopTlsIdentityMaterialTest {
    @Test
    fun acceptsCanonicalDerSubjectPublicKeyInfoForEcP256AndComputesSha256Fingerprint() {
        val result = DesktopTlsIdentityMaterial.validate(P256_SPKI).getOrThrow()

        assertArrayEquals(P256_SPKI, result.subjectPublicKeyInfoDer)
        assertEquals("EC", result.publicKey.algorithm)
        assertEquals(32, result.fingerprint.bytes.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(P256_SPKI), result.fingerprint.bytes)
        assertEquals(PairingTrustFingerprint.fromTrustMaterial(P256_SPKI), result.fingerprint)
    }

    @Test
    fun returnsDefensiveCopies() {
        val mutable = P256_SPKI.copyOf()
        val material = DesktopTlsIdentityMaterial.validate(mutable).getOrThrow()

        mutable[0] = 0x00
        val firstRead = material.subjectPublicKeyInfoDer
        firstRead[0] = 0x00

        assertArrayEquals(P256_SPKI, material.subjectPublicKeyInfoDer)
    }

    @Test
    fun rejectsUnsupportedKeyTypesCurvesCertificatesRandomBytesOversizeAndNonCanonicalDer() {
        assertEquals(DesktopTlsIdentityMaterialError.UnsupportedAlgorithm("RSA"), DesktopTlsIdentityMaterial.validate(RSA_SPKI).exceptionOrNull())
        assertEquals(DesktopTlsIdentityMaterialError.UnsupportedCurve("secp384r1"), DesktopTlsIdentityMaterial.validate(P384_SPKI).exceptionOrNull())
        assertEquals(DesktopTlsIdentityMaterialError.UnsupportedCurve("secp256k1"), DesktopTlsIdentityMaterial.validate(SECP256K1_SPKI).exceptionOrNull())
        assertEquals(DesktopTlsIdentityMaterialError.CertificateNotSubjectPublicKeyInfo, DesktopTlsIdentityMaterial.validate(P256_CERTIFICATE).exceptionOrNull())
        assertEquals(DesktopTlsIdentityMaterialError.MalformedSubjectPublicKeyInfo, DesktopTlsIdentityMaterial.validate(byteArrayOf(0x01, 0x02, 0x03)).exceptionOrNull())
        assertEquals(DesktopTlsIdentityMaterialError.Oversized(actualSize = 513, maxSize = 512), DesktopTlsIdentityMaterial.validate(ByteArray(513) { 0x01 }).exceptionOrNull())

        val withTrailingAlias = P256_SPKI + byteArrayOf(0x00)
        assertEquals(DesktopTlsIdentityMaterialError.NonCanonicalSubjectPublicKeyInfo, DesktopTlsIdentityMaterial.validate(withTrailingAlias, maxBytes = 1024).exceptionOrNull())
    }

    @Test
    fun rejectsEmptyBeforeParsing() {
        assertEquals(DesktopTlsIdentityMaterialError.Empty, DesktopTlsIdentityMaterial.validate(byteArrayOf()).exceptionOrNull())
    }

    private companion object {
        val P256_SPKI: ByteArray = b64("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXZIEL3sbIUaOZyNTWLYtwIWBMp4UFPNgWrsZ/B+zNnzGOg4MgU1DNcrJLKyExFcEw9epY26aNitmckuetSi1BQ==")
        val P384_SPKI: ByteArray = b64("MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEt56Qp0hu9ZnlXwTfGrqJbOT2bY3nT5DoV/pDJiVjwk2UBn1EkKx49Z7BPO0lkQSzJWQYgf29qsFoZ3ve+41pOpgcdnJQYk8/vsTC85iEoXO2iBiX9YssWKFrtyYaZtSt")
        val SECP256K1_SPKI: ByteArray = b64("MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEm0qTZZ0Dr0bmASaIGRMlGSD8fGD/+Zt08udHKlK/JvJ0QNvmAHhLLmwrYtFq/+4Mpr7KVJsoJKaufMNOI4mYBw==")
        val RSA_SPKI: ByteArray = b64("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsmWrtc7WSYMZMzdzZWbIfS/QDHrO6CyWBzRocE9YyIOTzr2/u4hyUq1AZmYxf+2kcQqEy12a7LS470r3jPb5VWqcVn/ExRQqTfAYM8LWquBabRRomC6cX0rlfYX+oHJVcg0xHbABsRPiuqEMYuEpEfHQVEYVJZtUI+nq5Ce/Ue1rK/XJgbr0Z48F0Ha+efZOPrw7sKDOPmuuWN0G2u9p+JTsSHlmWjIPtYRKFXq67YLRVglmHMOTr7koLsyxLKErl859gKp8OkeXCQt2OgKFz4pYB1mDexSOwrDuZ1ulC9xqG/ft7Tm4drF7rSYAgAlslZthEekPebYkfdM4EsYs1wIDAQAB")
        val P256_CERTIFICATE: ByteArray = b64("MIIBjzCCATWgAwIBAgIUX8Hso+hxA+oYzDgHXuEnYATjafQwCgYIKoZIzj0EAwIwHTEbMBkGA1UEAwwSQ2hpbmNoaWxsYUNhbSBUZXN0MB4XDTI2MDkyODAwMDU1NFoXDTI2MDkyOTAwMDU1NFowHTEbMBkGA1UEAwwSQ2hpbmNoaWxsYUNhbSBUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXZIEL3sbIUaOZyNTWLYtwIWBMp4UFPNgWrsZ/B+zNnzGOg4MgU1DNcrJLKyExFcEw9epY26aNitmckuetSi1BaNTMFEwHQYDVR0OBBYEFFFt4y914oIwhb8FETM0KWGvySVnMB8GA1UdIwQYMBaAFFFt4y914oIwhb8FETM0KWGvySVnMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgQrZfAapdn4Q6+MlOJt4qnbJ5gRG7fWtUIwZHKSdm0GQCIQDq2s3dFRgGHKVf/YUnQ1FZ3gjau9A3pIDfl0xyTic8gA==")

        fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
