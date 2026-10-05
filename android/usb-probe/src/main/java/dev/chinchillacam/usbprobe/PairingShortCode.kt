package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Six-digit pairing short authentication string (SAS v1) shown on both the phone and the
 * desktop so the user can compare them before trusting each other.
 *
 * Derivation:
 * `SHA-256("CHINCHILLACAM-SAS-v1" || len32(desktopSpki) || desktopSpki || len32(phoneSpki) ||
 * phoneSpki || len32(qrNonce) || qrNonce || len32(challengeNonce) || challengeNonce)`, where the
 * ASCII label has no length prefix and every `len32` is a 4-byte big-endian length. The first
 * four digest bytes, read as an unsigned big-endian integer, are reduced modulo 1 000 000.
 *
 * Both ends know all four inputs: the phone gets the desktop SPKI and QR nonce from the QR
 * payload and its own SPKI locally; the desktop sees the phone SPKI through mTLS and both
 * nonces through CCP1. The SAS complements, and does not replace, the QR pin: the pin protects
 * the phone side, while the human comparison protects desktop-side trust against a third party
 * that has seen the QR.
 */
class PairingShortCode private constructor(
    private val value: Int,
) {
    init {
        require(value in 0 until MODULUS) { "short code value must be in 0..999999" }
    }

    /** Six zero-padded digits, e.g. `"841406"`. */
    val digits: String
        get() = value.toString().padStart(DIGIT_COUNT, '0')

    /** Human-friendly grouping, e.g. `"841 406"`. */
    val display: String
        get() = digits.let { "${it.substring(0, 3)} ${it.substring(3)}" }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingShortCode) return false
        return value == other.value
    }

    override fun hashCode(): Int = value

    override fun toString(): String = display

    companion object {
        private const val LABEL: String = "CHINCHILLACAM-SAS-v1"
        private const val MODULUS: Int = 1_000_000
        private const val DIGIT_COUNT: Int = 6

        fun derive(
            desktopSpki: ByteArray,
            phoneSpki: ByteArray,
            qrNonce: ByteArray,
            challengeNonce: ByteArray,
        ): PairingShortCode {
            require(desktopSpki.isNotEmpty()) { "desktop SPKI must not be empty" }
            require(phoneSpki.isNotEmpty()) { "phone SPKI must not be empty" }
            require(qrNonce.isNotEmpty()) { "QR nonce must not be empty" }
            require(challengeNonce.isNotEmpty()) { "challenge nonce must not be empty" }

            val transcript = ByteArrayOutputStream()
            transcript.write(LABEL.toByteArray(Charsets.US_ASCII))
            for (field in listOf(desktopSpki, phoneSpki, qrNonce, challengeNonce)) {
                transcript.writeLength32(field.size)
                transcript.write(field)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())

            val prefix = ((digest[0].toLong() and 0xFF) shl 24) or
                ((digest[1].toLong() and 0xFF) shl 16) or
                ((digest[2].toLong() and 0xFF) shl 8) or
                (digest[3].toLong() and 0xFF)
            return PairingShortCode((prefix % MODULUS).toInt())
        }

        internal fun fromValue(value: Int): PairingShortCode = PairingShortCode(value)

        private fun ByteArrayOutputStream.writeLength32(length: Int) {
            write((length ushr 24) and 0xFF)
            write((length ushr 16) and 0xFF)
            write((length ushr 8) and 0xFF)
            write(length and 0xFF)
        }
    }
}
