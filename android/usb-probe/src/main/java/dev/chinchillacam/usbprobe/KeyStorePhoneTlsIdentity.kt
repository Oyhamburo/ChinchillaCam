package dev.chinchillacam.usbprobe

import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * JVM-testable core of the phone's Android Keystore TLS identity (contract §4.1). Ensures a usable
 * EC P-256 key pair exists under [alias] in [keyStore]: calls [generateKeyPair] when the alias is
 * missing, and deletes then regenerates once when the alias exists but is unusable (no certificate
 * after an interrupted generation, not a private-key entry, or a non-canonical/non-P-256 key). If
 * the alias is still unusable after that single regeneration attempt, construction fails closed
 * with a clear [IllegalStateException] instead of retrying forever.
 *
 * [keyManagers] returns a single alias-pinned [X509ExtendedKeyManager]: it only ever offers
 * [alias]'s own certificate chain and private key, and only when an EC client certificate is
 * requested, so JSSE can never present a different key that happens to live in the same
 * [keyStore] (contract §4.1.3; closes review finding R1-001).
 *
 * Regenerating the key invalidates every desktop's existing trust in this phone: a new key means a
 * new [subjectPublicKeyInfoDer], and therefore a new `phone_id` (contract §4.1.4, the SHA-256 of
 * the SPKI). Callers MUST read [regenerated] after construction and prompt the user to re-pair
 * with every already-trusted desktop when it is `true`; this class only reports the fact, the
 * re-pairing UI itself is out of scope here (contract §4.1.8, future task).
 *
 * Ensuring the key and reading it back run under a process-wide lock (shared by every instance of
 * this class) so two identities constructed concurrently against the same underlying keystore can
 * never both observe a missing or unusable alias and both generate a key, overwriting one another
 * (closes review finding R4-keystore-check-then-generate-race).
 *
 * [keyProtection] defaults to `null`, the only value the real `"AndroidKeyStore"` provider accepts
 * (it does not support passwords). JVM tests targeting a password-protected PKCS12 keystore pass a
 * [KeyStore.PasswordProtection] instead; see `KeyStorePhoneTlsIdentityTest`.
 */
class KeyStorePhoneTlsIdentity(
    keyStore: KeyStore,
    alias: String,
    generateKeyPair: () -> Unit,
    keyProtection: KeyStore.ProtectionParameter? = null,
) : PhoneTlsIdentity {

    val regenerated: Boolean

    override val subjectPublicKeyInfoDer: ByteArray

    private val keyManager: X509ExtendedKeyManager

    init {
        val ensured = synchronized(ensureLock) { ensure(keyStore, alias, keyProtection, generateKeyPair) }
        regenerated = ensured.regenerated
        subjectPublicKeyInfoDer = ensured.subjectPublicKeyInfoDer
        keyManager = AliasPinnedX509KeyManager(alias, ensured.certificateChain, ensured.privateKey)
    }

    override fun keyManagers(): Array<KeyManager> = arrayOf(keyManager)

    private class UsableEntry(
        val subjectPublicKeyInfoDer: ByteArray,
        val certificateChain: Array<X509Certificate>,
        val privateKey: PrivateKey,
    )

    private class EnsureResult(
        val regenerated: Boolean,
        val subjectPublicKeyInfoDer: ByteArray,
        val certificateChain: Array<X509Certificate>,
        val privateKey: PrivateKey,
    )

    private companion object {
        val ensureLock = Any()

        fun ensure(
            keyStore: KeyStore,
            alias: String,
            keyProtection: KeyStore.ProtectionParameter?,
            generateKeyPair: () -> Unit,
        ): EnsureResult {
            usableEntry(keyStore, alias, keyProtection)?.let {
                return EnsureResult(
                    regenerated = false,
                    subjectPublicKeyInfoDer = it.subjectPublicKeyInfoDer,
                    certificateChain = it.certificateChain,
                    privateKey = it.privateKey,
                )
            }
            val alreadyExisted = keyStore.containsAlias(alias)
            if (alreadyExisted) keyStore.deleteEntry(alias)
            generateKeyPair()
            val entry = usableEntry(keyStore, alias, keyProtection) ?: throw IllegalStateException(
                "Keystore alias \"$alias\" is still unusable after " +
                    if (alreadyExisted) "regeneration" else "generation",
            )
            return EnsureResult(
                regenerated = alreadyExisted,
                subjectPublicKeyInfoDer = entry.subjectPublicKeyInfoDer,
                certificateChain = entry.certificateChain,
                privateKey = entry.privateKey,
            )
        }

        /**
         * Null when [alias] does not hold a usable private-key entry: absent, not a
         * [KeyStore.PrivateKeyEntry], missing its certificate, or holding a SPKI that
         * [DesktopTlsIdentityMaterial.validate] rejects (wrong algorithm or curve). Any
         * [GeneralSecurityException] raised while probing is treated the same way: fail closed
         * into "unusable" rather than surfacing a partially-read, possibly-corrupted entry.
         */
        fun usableEntry(
            keyStore: KeyStore,
            alias: String,
            keyProtection: KeyStore.ProtectionParameter?,
        ): UsableEntry? {
            try {
                if (!keyStore.entryInstanceOf(alias, KeyStore.PrivateKeyEntry::class.java)) return null
                val entry = keyStore.getEntry(alias, keyProtection) as? KeyStore.PrivateKeyEntry ?: return null
                val leaf = entry.certificate as? X509Certificate ?: return null
                val validated = DesktopTlsIdentityMaterial.validate(leaf.publicKey.encoded).getOrNull() ?: return null
                return UsableEntry(
                    subjectPublicKeyInfoDer = validated.subjectPublicKeyInfoDer,
                    certificateChain = entry.certificateChain.map { it as X509Certificate }.toTypedArray(),
                    privateKey = entry.privateKey,
                )
            } catch (_: GeneralSecurityException) {
                return null
            }
        }
    }
}

/**
 * [X509ExtendedKeyManager] that only ever offers [alias]'s own certificate chain and private key,
 * and only for an EC client certificate request: `chooseClientAlias`/`chooseEngineClientAlias`
 * return [alias] solely when `"EC"` is among the requested key types, `getCertificateChain`/
 * `getPrivateKey` answer only for [alias], and every server-side method returns `null` (this
 * identity is a TLS client identity, never used to authenticate a server). This is what makes
 * [KeyStorePhoneTlsIdentity.keyManagers] safe even when [alias] is not the only entry in the
 * underlying keystore.
 */
private class AliasPinnedX509KeyManager(
    private val alias: String,
    private val certificateChain: Array<X509Certificate>,
    private val privateKey: PrivateKey,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(keyType: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? =
        aliasIfKeyTypeRequested(keyType)

    override fun chooseEngineClientAlias(keyType: Array<out String>, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        aliasIfKeyTypeRequested(keyType)

    override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun chooseEngineServerAlias(keyType: String, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

    override fun getCertificateChain(chosenAlias: String): Array<X509Certificate>? = certificateChain.takeIf { chosenAlias == alias }

    override fun getPrivateKey(chosenAlias: String): PrivateKey? = privateKey.takeIf { chosenAlias == alias }

    override fun getClientAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? =
        arrayOf(alias).takeIf { keyTypeRequested(keyType) }

    override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null

    private fun aliasIfKeyTypeRequested(keyType: Array<out String>): String? = alias.takeIf { keyType.any(::keyTypeRequested) }

    private fun keyTypeRequested(keyType: String): Boolean = keyType.equals(EC_KEY_ALGORITHM, ignoreCase = true)

    private companion object {
        const val EC_KEY_ALGORITHM: String = "EC"
    }
}
