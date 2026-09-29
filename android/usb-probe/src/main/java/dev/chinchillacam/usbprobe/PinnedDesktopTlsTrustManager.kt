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

/**
 * Fingerprint-pinned counterpart of [PinnedDesktopTlsTrustManager] (task s3,
 * `usb-authenticated-session` §4.4): trusted-desktop reconnection has no fresh QR scan to pin an
 * exact SubjectPublicKeyInfo against -- only the [PairingTrustFingerprint] [TrustedDesktopStore]
 * persisted at pairing time (a fingerprint cannot be reversed back into the original SPKI). Accepts
 * the server's leaf certificate exactly when the SHA-256 fingerprint of its SubjectPublicKeyInfo
 * equals [pinnedTrustMaterialFingerprint] -- the same digest [PairingTrustFingerprint.fromTrustMaterial]
 * and [DesktopTlsIdentityMaterial.fingerprint] both compute, so a desktop identity fingerprints the
 * same way whether it was just validated from a QR or looked up from the store.
 */
class PinnedDesktopFingerprintTrustManager(
    pinnedTrustMaterialFingerprint: ByteArray,
) : X509TrustManager {
    private val pinnedFingerprint = pinnedTrustMaterialFingerprint.copyOf()

    init {
        require(pinnedFingerprint.isNotEmpty()) { "pinnedTrustMaterialFingerprint must not be empty" }
    }

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
        val presentedFingerprint = PairingTrustFingerprint.fromTrustMaterial(leafSubjectPublicKeyInfo)
        if (!presentedFingerprint.bytes.contentEquals(pinnedFingerprint)) {
            throw CertificateException("desktop TLS leaf SubjectPublicKeyInfo fingerprint does not match trusted desktop store")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
