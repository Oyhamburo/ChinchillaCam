package dev.chinchillacam.usbprobe

import javax.net.ssl.KeyManager

/**
 * Stable per-device TLS client identity the phone presents in every mTLS handshake with a
 * desktop peer (pairing and reconnection, USB today, Wi-Fi later).
 *
 * The production implementation is backed by a non-exportable key in the Android Keystore
 * (added in a later task). Implementations must not expose private key material.
 */
interface PhoneTlsIdentity {
    fun keyManagers(): Array<KeyManager>

    val subjectPublicKeyInfoDer: ByteArray
}
