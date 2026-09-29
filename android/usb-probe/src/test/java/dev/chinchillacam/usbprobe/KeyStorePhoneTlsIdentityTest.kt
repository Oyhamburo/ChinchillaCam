package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class KeyStorePhoneTlsIdentityTest {
    @Test
    fun generatesKeyWhenAliasMissing() {
        val keyStore = emptyPkcs12KeyStore()
        var invocations = 0

        val identity = KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = ALIAS,
            generateKeyPair = {
                invocations += 1
                importGeneratedEcEntry(keyStore, ALIAS)
            },
            keyProtection = KeyStore.PasswordProtection(PASSWORD.toCharArray()),
        )

        assertEquals(1, invocations)
        assertFalse(identity.regenerated)
        assertTrue(keyStore.containsAlias(ALIAS))
        assertTrue(DesktopTlsIdentityMaterial.validate(identity.subjectPublicKeyInfoDer).isSuccess)
    }

    @Test
    fun reusesValidAliasWithoutRegenerating() {
        val keyStore = emptyPkcs12KeyStore()
        importGeneratedEcEntry(keyStore, ALIAS)
        val existingSpki = (keyStore.getCertificate(ALIAS) as X509Certificate).publicKey.encoded

        val identity = KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = ALIAS,
            generateKeyPair = { fail("must not regenerate an already-valid alias") },
            keyProtection = KeyStore.PasswordProtection(PASSWORD.toCharArray()),
        )

        assertFalse(identity.regenerated)
        assertTrue(existingSpki.contentEquals(identity.subjectPublicKeyInfoDer))
    }

    @Test
    fun readFailureDoesNotDeleteOrRegenerateExistingKey() {
        val keyStore = emptyPkcs12KeyStore()
        importGeneratedEcEntry(keyStore, ALIAS)
        val originalSpki = (keyStore.getCertificate(ALIAS) as X509Certificate).publicKey.encoded
        var invocations = 0

        val thrown = try {
            KeyStorePhoneTlsIdentity(
                keyStore = keyStore,
                alias = ALIAS,
                generateKeyPair = {
                    invocations += 1
                    importGeneratedEcEntry(keyStore, ALIAS)
                },
                keyProtection = KeyStore.PasswordProtection(WRONG_PASSWORD.toCharArray()),
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertEquals(0, invocations)
        assertTrue(originalSpki.contentEquals((keyStore.getCertificate(ALIAS) as X509Certificate).publicKey.encoded))
        assertTrue(thrown is GeneralSecurityException || thrown?.cause is GeneralSecurityException)
    }

    @Test
    fun regeneratesWhenAliasHoldsNonP256Key() {
        val keyStore = emptyPkcs12KeyStore()
        importGeneratedEcEntry(keyStore, ALIAS, groupName = "secp384r1")
        val nonP256Spki = (keyStore.getCertificate(ALIAS) as X509Certificate).publicKey.encoded

        val identity = KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = ALIAS,
            generateKeyPair = { importGeneratedEcEntry(keyStore, ALIAS) },
            keyProtection = KeyStore.PasswordProtection(PASSWORD.toCharArray()),
        )

        assertTrue(identity.regenerated)
        assertTrue(DesktopTlsIdentityMaterial.validate(identity.subjectPublicKeyInfoDer).isSuccess)
        assertFalse(nonP256Spki.contentEquals(identity.subjectPublicKeyInfoDer))
    }

    @Test
    fun regeneratesWhenAliasHasNoPrivateKeyEntry() {
        val keyStore = emptyPkcs12KeyStore()
        val trustedOnly = generateEcPrivateKeyEntry().certificate as X509Certificate
        keyStore.setCertificateEntry(ALIAS, trustedOnly)

        val identity = KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = ALIAS,
            generateKeyPair = { importGeneratedEcEntry(keyStore, ALIAS) },
            keyProtection = KeyStore.PasswordProtection(PASSWORD.toCharArray()),
        )

        assertTrue(identity.regenerated)
        assertTrue(DesktopTlsIdentityMaterial.validate(identity.subjectPublicKeyInfoDer).isSuccess)
        assertFalse(trustedOnly.publicKey.encoded.contentEquals(identity.subjectPublicKeyInfoDer))
    }

    @Test
    fun pinnedKeyManagerPresentsOnlyPinnedAliasWhenStoreHasOtherEcKeys() {
        val keyStore = emptyPkcs12KeyStore()
        importGeneratedEcEntry(keyStore, ALIAS)
        importGeneratedEcEntry(keyStore, OTHER_ALIAS)
        val otherSpki = (keyStore.getCertificate(OTHER_ALIAS) as X509Certificate).publicKey.encoded

        val identity = KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = ALIAS,
            generateKeyPair = { fail("must not regenerate an already-valid alias") },
            keyProtection = KeyStore.PasswordProtection(PASSWORD.toCharArray()),
        )

        val serverKeyStore = emptyPkcs12KeyStore().apply {
            setEntry(SERVER_ALIAS, generateEcPrivateKeyEntry(alias = SERVER_ALIAS), KeyStore.PasswordProtection(PASSWORD.toCharArray()))
        }
        val serverKeyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeyStore, PASSWORD.toCharArray())
        }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(serverKeyManagers, arrayOf(TrustAllTrustManager), null)
        }
        val serverSocket = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.needClientAuth = true
        val capturedChain = AtomicReference<Array<out X509Certificate>>()
        val serverError = AtomicReference<Throwable?>(null)
        val server = thread {
            try {
                (serverSocket.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    capturedChain.set(socket.session.peerCertificates.map { it as X509Certificate }.toTypedArray())
                }
            } catch (error: Throwable) {
                serverError.set(error)
            }
        }

        try {
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(identity.keyManagers(), arrayOf(TrustAllTrustManager), null)
            }
            (clientContext.socketFactory.createSocket("localhost", serverSocket.localPort) as SSLSocket).use { socket ->
                socket.startHandshake()
            }
            server.join(2000)
            serverError.get()?.let { throw it }

            val capturedSpki = capturedChain.get()!![0].publicKey.encoded
            assertTrue(capturedSpki.contentEquals(identity.subjectPublicKeyInfoDer))
            assertFalse(capturedSpki.contentEquals(otherSpki))
        } finally {
            serverSocket.close()
        }
    }

    /** Server-side and client-side [X509TrustManager] that accepts any presented chain: these fixtures are all self-signed and unrelated, only the chosen client alias is under test here. */
    private object TrustAllTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun emptyPkcs12KeyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }

    private fun importGeneratedEcEntry(keyStore: KeyStore, alias: String, groupName: String = "secp256r1") {
        keyStore.setEntry(alias, generateEcPrivateKeyEntry(alias, groupName), KeyStore.PasswordProtection(PASSWORD.toCharArray()))
    }

    /** Same keytool recipe as [SslEngineUsbTlsChannelTest]'s `TlsFixture`, adapted to hand back a [KeyStore.PrivateKeyEntry] this test can import into a target keystore instead of a ready [SSLContext]. */
    private fun generateEcPrivateKeyEntry(alias: String = "generated", groupName: String = "secp256r1"): KeyStore.PrivateKeyEntry {
        val temp = createTempDir(prefix = "cc-keystore-identity")
        try {
            val store = File(temp, "$alias.p12")
            val keytool = File(
                File(System.getProperty("java.home"), "bin"),
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool",
            ).absolutePath
            val command = listOf(
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", groupName,
                "-dname", "CN=$alias", "-keystore", store.absolutePath, "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-storetype", "PKCS12", "-startdate", "2026/01/01 00:00:00", "-validity", "36500",
            )
            val exit = ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
            require(exit == 0) { "keytool failed" }
            val loaded = KeyStore.getInstance("PKCS12")
            store.inputStream().use { loaded.load(it, PASSWORD.toCharArray()) }
            return loaded.getEntry(alias, KeyStore.PasswordProtection(PASSWORD.toCharArray())) as KeyStore.PrivateKeyEntry
        } finally {
            temp.deleteRecursively()
        }
    }

    private companion object {
        const val ALIAS = "chinchillacam-phone-tls-v1"
        const val OTHER_ALIAS = "other-ec-key"
        const val SERVER_ALIAS = "server"
        const val PASSWORD = "changeit"
        const val WRONG_PASSWORD = "not-the-real-password"
    }
}
