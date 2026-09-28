package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class PinnedDesktopTlsTrustManagerTest {
    @Test
    fun acceptsLeafCertificateWhenSubjectPublicKeyInfoMatchesPinnedDesktopIdentity() {
        val pinnedIdentity = DesktopTlsIdentityMaterial.validate(VALID_DESKTOP_CERT.publicKey.encoded).getOrThrow()
        val trustManager = PinnedDesktopTlsTrustManager(pinnedIdentity)

        trustManager.checkServerTrusted(arrayOf(VALID_DESKTOP_CERT), "ECDHE_ECDSA")

        assertArrayEquals(emptyArray<X509Certificate>(), trustManager.acceptedIssuers)
    }

    @Test
    fun rejectsEmptyServerCertificateChainBeforeTrustingAnyDesktop() {
        val trustManager = PinnedDesktopTlsTrustManager(pinnedValidDesktopIdentity())

        val error = assertCertificateException {
            trustManager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }

        assertEquals("desktop TLS server did not present a leaf certificate", error.message)
    }

    @Test
    fun rejectsLeafCertificateWithDifferentSubjectPublicKeyInfo() {
        val trustManager = PinnedDesktopTlsTrustManager(pinnedValidDesktopIdentity())

        val error = assertCertificateException {
            trustManager.checkServerTrusted(arrayOf(MISMATCH_DESKTOP_CERT), "ECDHE_ECDSA")
        }

        assertEquals("desktop TLS leaf SubjectPublicKeyInfo does not match pinned QR trust material", error.message)
    }

    @Test
    fun rejectsExpiredLeafCertificateEvenWhenSubjectPublicKeyInfoIsPinned() {
        val pinnedExpiredIdentity = DesktopTlsIdentityMaterial.validate(EXPIRED_DESKTOP_CERT.publicKey.encoded).getOrThrow()
        val trustManager = PinnedDesktopTlsTrustManager(pinnedExpiredIdentity)

        val error = assertCertificateException {
            trustManager.checkServerTrusted(arrayOf(EXPIRED_DESKTOP_CERT), "ECDHE_ECDSA")
        }

        assertEquals("desktop TLS leaf certificate is not currently valid", error.message)
    }

    @Test
    fun rejectsNotYetValidLeafCertificateEvenWhenSubjectPublicKeyInfoIsPinned() {
        val pinnedFutureIdentity = DesktopTlsIdentityMaterial.validate(FUTURE_DESKTOP_CERT.publicKey.encoded).getOrThrow()
        val trustManager = PinnedDesktopTlsTrustManager(pinnedFutureIdentity)

        val error = assertCertificateException {
            trustManager.checkServerTrusted(arrayOf(FUTURE_DESKTOP_CERT), "ECDHE_ECDSA")
        }

        assertEquals("desktop TLS leaf certificate is not currently valid", error.message)
    }

    @Test
    fun clientCertificateTrustIsNotSupportedForPinnedDesktopServerTrust() {
        val trustManager = PinnedDesktopTlsTrustManager(pinnedValidDesktopIdentity())

        val error = assertCertificateException {
            trustManager.checkClientTrusted(arrayOf(VALID_DESKTOP_CERT), "ECDHE_ECDSA")
        }

        assertEquals("pinned desktop TLS trust manager does not trust client certificates", error.message)
    }

    private fun pinnedValidDesktopIdentity(): DesktopTlsIdentityMaterial =
        DesktopTlsIdentityMaterial.validate(VALID_DESKTOP_CERT.publicKey.encoded).getOrThrow()

    private fun assertCertificateException(block: () -> Unit): CertificateException {
        try {
            block()
            fail("expected CertificateException")
        } catch (error: CertificateException) {
            return error
        }
        throw AssertionError("unreachable")
    }

    private companion object {
        val VALID_DESKTOP_CERT: X509Certificate = cert("MIIBVTCB/KADAgECAgkAwL54de2nIvkwCgYIKoZIzj0EAwIwHjEcMBoGA1UEAxMTQ2hpbmNoaWxsYUNhbSBWYWxpZDAgFw0yNjAxMDEwMzAwMDBaGA8yMTI1MTIwODAzMDAwMFowHjEcMBoGA1UEAxMTQ2hpbmNoaWxsYUNhbSBWYWxpZDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABPkI8X+u+Cjx/TpC+Cc3tKSzBeP+k9hE3kU4xqcG5tjXwOS3O8dUthZaSBg5++ut9KO0bzaxQK8DC+XCF0JMJnqjITAfMB0GA1UdDgQWBBQ9SeyVt8H2Kb8y7PRxD8JbjflenzAKBggqhkjOPQQDAgNIADBFAiARb3sqvpuvjhWPtO4vYoTHpMnlxI3pw1bNkOs8NuPp/wIhAN5GpQqEPO0YGhghIQ8kHM0IFGDRzbOs039FFB3W7QVa")
        val MISMATCH_DESKTOP_CERT: X509Certificate = cert("MIIBXDCCAQGgAwIBAgIIQOh+1cogslcwCgYIKoZIzj0EAwIwITEfMB0GA1UEAxMWQ2hpbmNoaWxsYUNhbSBNaXNtYXRjaDAgFw0yNjAxMDEwMzAwMDBaGA8yMTI1MTIwODAzMDAwMFowITEfMB0GA1UEAxMWQ2hpbmNoaWxsYUNhbSBNaXNtYXRjaDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDTT4Qkkfh4AUcIG/w6GDNxI2jKaAVlI0pfnxhVeiQzCqOxtlYDKXJYAubwGH/AEVLoT2Y06o4IAFNcNLrT1maujITAfMB0GA1UdDgQWBBQcphnrSUj83N4Hz0poeT7NhDHq+jAKBggqhkjOPQQDAgNJADBGAiEA2mIiBaVqs5/5G0f7D3ZzqblDkqpQx+v5Jxr51+mTdf0CIQDXj/MkbLyc4a84CUmU5ZACasyYujdLm8O/Ji37ytRn8w==")
        val EXPIRED_DESKTOP_CERT: X509Certificate = cert("MIIBVzCB/qADAgECAgkA0YzaHcxMlccwCgYIKoZIzj0EAwIwIDEeMBwGA1UEAxMVQ2hpbmNoaWxsYUNhbSBFeHBpcmVkMB4XDTIwMDEwMTAzMDAwMFoXDTIwMDEwMjAzMDAwMFowIDEeMBwGA1UEAxMVQ2hpbmNoaWxsYUNhbSBFeHBpcmVkMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwoklt+6yr3uT94wxwOtn991oAk717NNYMuS5Yg5rzlqEEr9x8SyNy5ksmiE8e0ElITWJheeH1tv6nfV6zCBxcqMhMB8wHQYDVR0OBBYEFMEN+gQbomj+WcBV91vijzp6iOzhMAoGCCqGSM49BAMCA0gAMEUCIGprb8smY2gL6MdgNunfS6xPtxr1ZH9Q4WvyUKZTRbxGAiEAp1LBPA5S0gEVK5GxZCJxkDO+btnFpGLN3hBY2Ybn6Es=")
        val FUTURE_DESKTOP_CERT: X509Certificate = cert("MIIBWTCCAQCgAwIBAgIJAJZfNuQ9ehX0MAoGCCqGSM49BAMCMB8xHTAbBgNVBAMTFENoaW5jaGlsbGFDYW0gRnV0dXJlMCIYDzIxMjYwMTAxMDMwMDAwWhgPMjEyNjAxMzEwMzAwMDBaMB8xHTAbBgNVBAMTFENoaW5jaGlsbGFDYW0gRnV0dXJlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOGOqrXc4vBkTmcmrL7vzgKau+Vvmyh5bfFgGa6S80Fkom0jJvncxw6UeBkJDwIJf6ZkafWkZJVIu1zjL8z/9mKMhMB8wHQYDVR0OBBYEFHnph0iWq3YBYinMkn+pb2mdF5QcMAoGCCqGSM49BAMCA0cAMEQCIDC5IUr8axVwgR2ULdMoAd1y3GBtJgYncHYCBpere9dzAiA+x2UUxI3seqnt49FQMA7x0tounA5I7lRHkYh2aVMH1w==")

        fun cert(base64Der: String): X509Certificate {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            return certificateFactory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64Der))) as X509Certificate
        }
    }
}
