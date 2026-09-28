package dev.chinchillacam.usbprobe

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Production [PhoneTlsIdentity] backed by a non-exportable EC P-256 key in the Android Keystore
 * (contract §4.1): alias [ALIAS], [KeyProperties.PURPOSE_SIGN], digests
 * [KeyProperties.DIGEST_NONE] (required for TLS client auth per the AOSP [KeyGenParameterSpec]
 * javadoc) and [KeyProperties.DIGEST_SHA256]. Generates the key pair on first use if the alias is
 * missing; never exports the private key and never logs key material.
 *
 * Not exercised by JVM unit tests: "AndroidKeyStore" is a platform provider unavailable outside a
 * device or emulator. This is a declared TDD deviation (no RED/GREEN for this file); behavior on
 * device (key generation, TLS client auth with DIGEST_NONE, TLS 1.3 only from API 29+) is pending
 * physical validation (M9). See odd/tasks/phone-mtls-identity.md, task m2.
 */
class AndroidKeyStorePhoneTlsIdentity : PhoneTlsIdentity {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) generateKey()
    }

    override fun keyManagers(): Array<KeyManager> =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, null)
        }.keyManagers

    override val subjectPublicKeyInfoDer: ByteArray = run {
        val certificate = keyStore.getCertificate(ALIAS)
            ?: throw IllegalStateException("Android Keystore alias \"$ALIAS\" has no certificate")
        DesktopTlsIdentityMaterial.validate(certificate.publicKey.encoded).getOrElse {
            throw IllegalStateException(
                "Android Keystore alias \"$ALIAS\" is not a canonical P-256 SubjectPublicKeyInfo",
                it,
            )
        }.subjectPublicKeyInfoDer
    }

    private fun generateKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE_PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE_NAME))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
                .build(),
        )
        generator.generateKeyPair()
    }

    private companion object {
        const val ALIAS: String = "chinchillacam-phone-tls-v1"
        const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        const val P256_CURVE_NAME: String = "secp256r1"
        const val CERTIFICATE_SUBJECT: String = "CN=ChinchillaCam Phone"
    }
}
