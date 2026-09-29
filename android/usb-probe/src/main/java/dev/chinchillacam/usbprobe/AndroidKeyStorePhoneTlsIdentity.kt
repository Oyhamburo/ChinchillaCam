package dev.chinchillacam.usbprobe

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.security.auth.x500.X500Principal

/**
 * Production [PhoneTlsIdentity]: wires [KeyStorePhoneTlsIdentity] to the real Android Keystore
 * (contract §4.1). Supplies `KeyStore.getInstance("AndroidKeyStore")` and the
 * [KeyGenParameterSpec] generator for the EC P-256 key -- alias [ALIAS],
 * [KeyProperties.PURPOSE_SIGN], digests [KeyProperties.DIGEST_NONE] (required for TLS client auth
 * per the AOSP [KeyGenParameterSpec] javadoc) and [KeyProperties.DIGEST_SHA256]. All ensure,
 * regenerate and key-pinning behavior lives in [KeyStorePhoneTlsIdentity]; this class stays free
 * of logging key material and of Activity/UI wiring.
 *
 * Not exercised by JVM unit tests: "AndroidKeyStore" is a platform provider unavailable outside a
 * device or emulator. This is a declared TDD deviation for this thin wrapper only (no RED/GREEN
 * for this file); the ensure/regenerate/pinning logic it delegates to is JVM-tested against a
 * PKCS12 keystore in `KeyStorePhoneTlsIdentityTest`. Behavior specific to the real provider (key
 * generation, TLS client auth with DIGEST_NONE, TLS 1.3 only from API 29+) is still pending
 * physical validation (M9). See odd/tasks/phone-mtls-identity.md, tasks m2 and m2b.
 */
class AndroidKeyStorePhoneTlsIdentity : PhoneTlsIdentity {
    private val delegate = KeyStorePhoneTlsIdentity(
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) },
        alias = ALIAS,
        generateKeyPair = ::generateKey,
    )

    /**
     * `true` when construction had to delete and regenerate an unusable [ALIAS] entry. A new key
     * invalidates every desktop's existing trust in this phone (contract §4.1.4): callers must
     * surface this to the user so they can re-pair. Re-pairing UI is out of scope here.
     */
    val regenerated: Boolean get() = delegate.regenerated

    override fun keyManagers(): Array<KeyManager> = delegate.keyManagers()

    override val subjectPublicKeyInfoDer: ByteArray get() = delegate.subjectPublicKeyInfoDer

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
