package dev.chinchillacam.usbprobe

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec

private const val DEFAULT_MAX_DESKTOP_TLS_IDENTITY_BYTES = 512

/**
 * Canonical DER SubjectPublicKeyInfo validator for the mutual-TLS pinned-peer contract (§4.1):
 * [validate] accepts only a non-empty, size-bounded, canonically-encoded EC P-256
 * SubjectPublicKeyInfo, and rejects certificates, oversized or malformed input, and any other
 * algorithm or curve.
 *
 * The name is historical: this was written first to validate the desktop's SPKI, pinned by the
 * phone from the pairing QR code. The check itself is peer-agnostic -- [KeyStorePhoneTlsIdentity]
 * reuses it verbatim to validate the phone's own Android Keystore key before presenting it as a
 * TLS client certificate. Not renamed here to keep this task's diff scoped to its stated goal.
 */
class DesktopTlsIdentityMaterial private constructor(
    subjectPublicKeyInfoDer: ByteArray,
    val publicKey: PublicKey,
) {
    private val subjectPublicKeyInfoDerBytes = subjectPublicKeyInfoDer.copyOf()

    val subjectPublicKeyInfoDer: ByteArray
        get() = subjectPublicKeyInfoDerBytes.copyOf()

    val fingerprint: PairingTrustFingerprint = PairingTrustFingerprint.fromTrustMaterial(subjectPublicKeyInfoDerBytes)

    companion object {
        fun validate(
            subjectPublicKeyInfoDer: ByteArray,
            maxBytes: Int = DEFAULT_MAX_DESKTOP_TLS_IDENTITY_BYTES,
        ): Result<DesktopTlsIdentityMaterial> {
            val candidate = subjectPublicKeyInfoDer.copyOf()
            if (candidate.isEmpty()) return Result.failure(DesktopTlsIdentityMaterialError.Empty)
            if (candidate.size > maxBytes) {
                return Result.failure(DesktopTlsIdentityMaterialError.Oversized(actualSize = candidate.size, maxSize = maxBytes))
            }
            val topLevelLength = derTopLevelLength(candidate) ?: return Result.failure(DesktopTlsIdentityMaterialError.MalformedSubjectPublicKeyInfo)
            if (topLevelLength != candidate.size) return Result.failure(DesktopTlsIdentityMaterialError.NonCanonicalSubjectPublicKeyInfo)
            if (isX509Certificate(candidate)) return Result.failure(DesktopTlsIdentityMaterialError.CertificateNotSubjectPublicKeyInfo)

            val publicKey = parsePublicKey(candidate).getOrElse { return Result.failure(it) }
            if (publicKey.algorithm != "EC") {
                return Result.failure(DesktopTlsIdentityMaterialError.UnsupportedAlgorithm(publicKey.algorithm))
            }
            val ecPublicKey = publicKey as? ECPublicKey
                ?: return Result.failure(DesktopTlsIdentityMaterialError.UnsupportedAlgorithm(publicKey.algorithm))
            if (!publicKey.encoded.contentEquals(candidate)) {
                return Result.failure(DesktopTlsIdentityMaterialError.NonCanonicalSubjectPublicKeyInfo)
            }
            val namedCurve = namedCurveOid(candidate)
                ?: return Result.failure(DesktopTlsIdentityMaterialError.MalformedSubjectPublicKeyInfo)
            if (!namedCurve.contentEquals(P256_OID_VALUE)) {
                return Result.failure(DesktopTlsIdentityMaterialError.UnsupportedCurve(curveName(namedCurve, ecPublicKey)))
            }

            return Result.success(DesktopTlsIdentityMaterial(candidate, publicKey))
        }

        private fun parsePublicKey(candidate: ByteArray): Result<PublicKey> {
            val spec = X509EncodedKeySpec(candidate)
            listOf("EC", "RSA").forEach { algorithm ->
                try {
                    return Result.success(KeyFactory.getInstance(algorithm).generatePublic(spec))
                } catch (_: InvalidKeySpecException) {
                    // Try the next supported key factory so RSA material can get a typed rejection.
                }
            }
            return Result.failure(DesktopTlsIdentityMaterialError.MalformedSubjectPublicKeyInfo)
        }

        private fun isX509Certificate(candidate: ByteArray): Boolean = try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(candidate))
            true
        } catch (_: CertificateException) {
            false
        }

        private fun curveName(namedCurve: ByteArray, publicKey: ECPublicKey): String = when {
            namedCurve.contentEquals(P384_OID_VALUE) -> "secp384r1"
            namedCurve.contentEquals(SECP256K1_OID_VALUE) -> "secp256k1"
            publicKey.params.curve.field.fieldSize == 521 -> "secp521r1"
            else -> "fieldSize-${publicKey.params.curve.field.fieldSize}"
        }

        private fun namedCurveOid(candidate: ByteArray): ByteArray? {
            val top = readDerElement(candidate, 0)?.takeIf { it.tag == SEQUENCE_TAG && it.totalLength == candidate.size } ?: return null
            val algorithm = readDerElement(candidate, top.contentStart)?.takeIf { it.tag == SEQUENCE_TAG && it.end <= top.end } ?: return null
            var cursor = algorithm.contentStart
            val keyAlgorithm = readDerElement(candidate, cursor)?.takeIf { it.tag == OID_TAG && it.end <= algorithm.end } ?: return null
            if (!candidate.copyOfRange(keyAlgorithm.contentStart, keyAlgorithm.end).contentEquals(EC_PUBLIC_KEY_OID_VALUE)) return null
            cursor = keyAlgorithm.end
            val namedCurve = readDerElement(candidate, cursor)?.takeIf { it.tag == OID_TAG && it.end == algorithm.end } ?: return null
            return candidate.copyOfRange(namedCurve.contentStart, namedCurve.end)
        }

        private fun derTopLevelLength(candidate: ByteArray): Int? = readDerElement(candidate, 0)?.takeIf { it.tag == SEQUENCE_TAG }?.totalLength

        private fun readDerElement(bytes: ByteArray, offset: Int): DerElement? {
            if (offset < 0 || offset + 2 > bytes.size) return null
            val tag = bytes[offset].toInt() and 0xff
            val firstLength = bytes[offset + 1].toInt() and 0xff
            val lengthBytes: Int
            val length: Int
            if (firstLength < 0x80) {
                lengthBytes = 0
                length = firstLength
            } else {
                lengthBytes = firstLength and 0x7f
                if (lengthBytes == 0 || lengthBytes > 4 || offset + 2 + lengthBytes > bytes.size) return null
                if ((bytes[offset + 2].toInt() and 0xff) == 0) return null
                var parsedLength = 0
                repeat(lengthBytes) { index ->
                    parsedLength = (parsedLength shl 8) or (bytes[offset + 2 + index].toInt() and 0xff)
                }
                if (parsedLength < 0x80) return null
                length = parsedLength
            }
            val contentStart = offset + 2 + lengthBytes
            val end = contentStart + length
            if (end < contentStart || end > bytes.size) return null
            return DerElement(tag = tag, contentStart = contentStart, end = end, totalLength = end - offset)
        }

        private data class DerElement(
            val tag: Int,
            val contentStart: Int,
            val end: Int,
            val totalLength: Int,
        )

        private const val SEQUENCE_TAG = 0x30
        private const val OID_TAG = 0x06
        private val EC_PUBLIC_KEY_OID_VALUE = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)
        private val P256_OID_VALUE = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)
        private val P384_OID_VALUE = byteArrayOf(0x2b, 0x81.toByte(), 0x04, 0x00, 0x22)
        private val SECP256K1_OID_VALUE = byteArrayOf(0x2b, 0x81.toByte(), 0x04, 0x00, 0x0a)
    }
}

sealed class DesktopTlsIdentityMaterialError(message: String) : Exception(message) {
    object Empty : DesktopTlsIdentityMaterialError("desktop TLS identity material is empty")
    data class Oversized(val actualSize: Int, val maxSize: Int) : DesktopTlsIdentityMaterialError("desktop TLS identity material size $actualSize exceeds max $maxSize")
    object MalformedSubjectPublicKeyInfo : DesktopTlsIdentityMaterialError("desktop TLS identity material is not DER SubjectPublicKeyInfo")
    object NonCanonicalSubjectPublicKeyInfo : DesktopTlsIdentityMaterialError("desktop TLS identity material is not canonical DER SubjectPublicKeyInfo")
    object CertificateNotSubjectPublicKeyInfo : DesktopTlsIdentityMaterialError("desktop TLS identity material must be SubjectPublicKeyInfo, not certificate")
    data class UnsupportedAlgorithm(val algorithm: String) : DesktopTlsIdentityMaterialError("unsupported desktop TLS identity algorithm: $algorithm")
    data class UnsupportedCurve(val curve: String) : DesktopTlsIdentityMaterialError("unsupported desktop TLS identity EC curve: $curve")
}
