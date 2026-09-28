package dev.chinchillacam.usbprobe

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinnedDesktopTlsTrustManager(
    private val pinnedDesktopIdentity: DesktopTlsIdentityMaterial,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("pinned desktop TLS trust manager does not trust client certificates")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull()
            ?: throw CertificateException("desktop TLS server did not present a leaf certificate")

        try {
            leaf.checkValidity()
        } catch (error: CertificateException) {
            throw CertificateException("desktop TLS leaf certificate is not currently valid", error)
        }

        val leafSubjectPublicKeyInfo = leaf.publicKey.encoded
            ?: throw CertificateException("desktop TLS leaf certificate has no encoded SubjectPublicKeyInfo")
        if (!leafSubjectPublicKeyInfo.contentEquals(pinnedDesktopIdentity.subjectPublicKeyInfoDer)) {
            throw CertificateException("desktop TLS leaf SubjectPublicKeyInfo does not match pinned QR trust material")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
