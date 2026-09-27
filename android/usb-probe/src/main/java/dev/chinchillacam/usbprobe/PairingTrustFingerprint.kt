package dev.chinchillacam.usbprobe

import java.security.MessageDigest

class PairingTrustFingerprint private constructor(
    bytes: ByteArray,
) {
    private val fingerprintBytes: ByteArray = bytes.copyOf()

    val bytes: ByteArray
        get() = fingerprintBytes.copyOf()

    val hex: String
        get() = fingerprintBytes.joinToString("") { byte -> "%02x".format(byte) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingTrustFingerprint) return false
        return fingerprintBytes.contentEquals(other.fingerprintBytes)
    }

    override fun hashCode(): Int = fingerprintBytes.contentHashCode()

    override fun toString(): String = hex

    companion object {
        const val SECURITY_NOTE: String = "SHA-256 trust-material fingerprints are identity binding inputs, not authentication or proof of possession."

        fun fromTrustMaterial(trustMaterial: ByteArray): PairingTrustFingerprint {
            require(trustMaterial.isNotEmpty()) { "trust material must not be empty" }
            val digest = MessageDigest.getInstance("SHA-256").digest(trustMaterial.copyOf())
            return PairingTrustFingerprint(digest)
        }
    }
}
