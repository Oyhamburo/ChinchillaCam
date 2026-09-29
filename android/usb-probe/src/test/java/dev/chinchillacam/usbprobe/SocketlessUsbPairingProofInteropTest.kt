package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class SocketlessUsbPairingProofInteropTest {
    @Test
    fun rustlsHelperCompletesUsbTlsPairingProofOverStdio() {
        val helperEnv = System.getenv(HELPER_ENV).orEmpty()
        assumeTrue("$HELPER_ENV not set; skipping opt-in socketless rustls interop", helperEnv.isNotBlank())

        val phoneIdentity = createPhoneTlsIdentity()
        val processRef = AtomicReference<Process?>()
        val watchdog = Executors.newSingleThreadExecutor()
        try {
            watchdog.submit(Callable { runInterop(helperEnv, phoneIdentity, processRef) })
                .get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            processRef.get()?.destroyForcibly()
            throw AssertionError("socketless rustls interop exceeded ${PROCESS_TIMEOUT_SECONDS}s watchdog", error)
        } finally {
            watchdog.shutdownNow()
            processRef.get()?.destroyForcibly()
            processRef.get()?.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun runInterop(helperEnv: String, phoneIdentity: PhoneTlsIdentity, processRef: AtomicReference<Process?>) {
        val helper = File(helperEnv)
        assertTrue("$HELPER_ENV must be an absolute path when set", helper.isAbsolute)
        assertTrue("$HELPER_ENV must point to a file", helper.isFile)
        assertTrue("$HELPER_ENV must be executable", helper.canExecute())
        System.getenv(HELPER_SHA256_ENV).orEmpty().takeIf { it.isNotBlank() }?.let { expectedHash ->
            assertEquals(expectedHash, helper.sha256Hex())
        }

        val process = ProcessBuilder(helper.absolutePath).start()
        processRef.set(process)
        if (Thread.currentThread().isInterrupted) {
            process.destroyForcibly()
            error("socketless rustls interop interrupted after helper start")
        }
        val lineExecutor = Executors.newSingleThreadExecutor()
        var verifiedChannel: SslEngineUsbTlsEstablishedChannel? = null
        try {
            val stdout = process.inputStream
            val version = readAsciiLineBounded(lineExecutor, stdout)
            val qrLine = readAsciiLineBounded(lineExecutor, stdout)

            assertEquals("CHINCHILLACAM-USB-INTEROP:v1", version)
            assertTrue("helper must emit qr= public metadata", qrLine.startsWith("qr="))

            val now = epochSeconds()
            val qr = PairingQrPayloadCodec.decode(qrLine.removePrefix("qr="), nowEpochSeconds = now).getOrThrow()
            val identity = DesktopTlsIdentityMaterial.validate(qr.trustMaterial).getOrThrow()
            assertEquals(PairingTrustFingerprint.fromTrustMaterial(identity.subjectPublicKeyInfoDer), identity.fingerprint)
            assertTrue("helper QR must still be live", qr.expiresAtEpochSeconds > now)

            val challengeNonce = ByteArray(CHALLENGE_NONCE_BYTES) { index -> (index + 1).toByte() }
            val challenge = PairingProofChallenge(
                desktopId = qr.desktopId,
                trustMaterialFingerprint = identity.fingerprint,
                desktopSubjectPublicKeyInfoDer = identity.subjectPublicKeyInfoDer,
                qrNonce = qr.nonce,
                challengeNonce = challengeNonce,
                sessionId = "socketless-stdio-session",
                qrExpiresAtEpochSeconds = qr.expiresAtEpochSeconds,
                challengeExpiresAtEpochSeconds = minOf(qr.expiresAtEpochSeconds, now + CHALLENGE_TTL_SECONDS),
            )
            val session = AccessoryIoSession(
                input = stdout,
                output = process.outputStream,
                closeable = Closeable { process.destroy() },
            )

            val verifier = UsbTlsPairingProofVerifier(
                epochSecondsSource = EpochSecondsSource { epochSeconds() },
                tlsChannel = SslEngineUsbTlsChannel(phoneTlsIdentity = phoneIdentity),
            )
            val result = verifier.verify(challenge, session)
            assertTrue("expected status0 verified proof, got $result", result is UsbTlsPairingProofVerificationResult.Verified)
            val verified = result as UsbTlsPairingProofVerificationResult.Verified
            verifiedChannel = verified.channel
            assertEquals(qr.desktopId, verified.proof.desktopId)
            assertEquals(identity.fingerprint, verified.proof.trustMaterialFingerprint)
            assertArrayEquals(qr.nonce, verified.proof.qrNonce)
            assertArrayEquals(challengeNonce, verified.proof.challengeNonce)
            assertEquals(challenge.sessionId, verified.proof.sessionId)
            assertEquals(challenge.challengeExpiresAtEpochSeconds, verified.proof.expiresAtEpochSeconds)
            assertTrue(verified.proof.verifiedAtEpochSeconds in now..epochSeconds())

            assertTrue("helper did not exit after CCP1 status0", process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS))
            val exitCode = process.exitValue()
            if (exitCode != 0) fail(process.stderrDiagnostic())
            assertEquals(0, exitCode)

            val stderrContent = readAllStderrBounded(lineExecutor, process)
            val normalizedStderr = stderrContent.removeSuffix("\n")
            assertFalse("helper stderr must not be empty after a successful proof", normalizedStderr.isEmpty())
            assertFalse("helper stderr must be exactly one line, got: $stderrContent", normalizedStderr.contains("\n"))
            val phoneIdMatch = PHONE_ID_LINE_REGEX.matchEntire(normalizedStderr)
            assertTrue("expected exactly one \"phone_id=<hex>\" line, got: $stderrContent", phoneIdMatch != null)
            val reportedPhoneId = phoneIdMatch!!.groupValues[1]
            val expectedPhoneId = phoneIdentity.subjectPublicKeyInfoDer.sha256Hex()
            assertEquals(
                "helper-reported phone_id must equal SHA-256(SPKI) of the presented phone identity",
                expectedPhoneId,
                reportedPhoneId,
            )
            assertEquals(
                "helper-reported phone_id must equal the phone's PairingTrustFingerprint",
                PairingTrustFingerprint.fromTrustMaterial(phoneIdentity.subjectPublicKeyInfoDer).hex,
                reportedPhoneId,
            )

            verified.channel.close()
        } finally {
            runCatching { verifiedChannel?.close() }
            lineExecutor.shutdownNow()
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun readAsciiLineBounded(
        executor: ExecutorService,
        input: InputStream,
    ): String = executor.submit(Callable<String> {
        val line = StringBuilder()
        var completeLine: String? = null
        while (completeLine == null) {
            val next = input.read()
            require(next != -1) { "helper stdout ended before complete metadata line" }
            when (next) {
                '\n'.code -> completeLine = line.toString()
                else -> {
                    require(next in 0x20..0x7e) { "helper metadata must be printable ASCII before binary stream" }
                    require(line.length < MAX_METADATA_LINE_BYTES) { "helper metadata line exceeds $MAX_METADATA_LINE_BYTES bytes" }
                    line.append(next.toChar())
                }
            }
        }
        completeLine
    }).get(LINE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Builds a JVM-testable phone TLS identity: an in-memory PKCS12 keystore holding an EC P-256
     * key generated with the same `keytool` recipe as `KeyStorePhoneTlsIdentityTest`, wrapped by
     * the production, alias-pinned [KeyStorePhoneTlsIdentity] so this interop test exercises the
     * real key-manager seam end to end instead of a hand-rolled test double.
     */
    private fun createPhoneTlsIdentity(): PhoneTlsIdentity {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        return KeyStorePhoneTlsIdentity(
            keyStore = keyStore,
            alias = PHONE_KEY_ALIAS,
            generateKeyPair = {
                keyStore.setEntry(
                    PHONE_KEY_ALIAS,
                    generatePhoneEcPrivateKeyEntry(),
                    KeyStore.PasswordProtection(PHONE_KEYSTORE_PASSWORD.toCharArray()),
                )
            },
            keyProtection = KeyStore.PasswordProtection(PHONE_KEYSTORE_PASSWORD.toCharArray()),
        )
    }

    /** Same keytool recipe as `KeyStorePhoneTlsIdentityTest.generateEcPrivateKeyEntry`. */
    private fun generatePhoneEcPrivateKeyEntry(): KeyStore.PrivateKeyEntry {
        val temp = createTempDir(prefix = "cc-phone-tls-interop")
        try {
            val store = File(temp, "$PHONE_KEY_ALIAS.p12")
            val keytool = File(
                File(System.getProperty("java.home"), "bin"),
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool",
            ).absolutePath
            val command = listOf(
                keytool, "-genkeypair", "-alias", PHONE_KEY_ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=$PHONE_KEY_ALIAS", "-keystore", store.absolutePath, "-storepass", PHONE_KEYSTORE_PASSWORD,
                "-keypass", PHONE_KEYSTORE_PASSWORD, "-storetype", "PKCS12", "-startdate", "2026/01/01 00:00:00", "-validity", "36500",
            )
            val exit = ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
            require(exit == 0) { "keytool failed to generate phone TLS identity" }
            val loaded = KeyStore.getInstance("PKCS12")
            store.inputStream().use { loaded.load(it, PHONE_KEYSTORE_PASSWORD.toCharArray()) }
            return loaded.getEntry(PHONE_KEY_ALIAS, KeyStore.PasswordProtection(PHONE_KEYSTORE_PASSWORD.toCharArray())) as KeyStore.PrivateKeyEntry
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun Process.stderrDiagnostic(): String {
        val bytes = ByteArray(MAX_STDERR_DIAGNOSTIC_BYTES)
        val count = runCatching { errorStream.read(bytes) }.getOrDefault(-1)
        return if (count <= 0) {
            "helper stderr was empty"
        } else {
            "helper stderr: " + bytes.copyOf(count).toString(Charsets.UTF_8)
        }
    }

    /** Reads the helper's complete stderr after it has exited, bounded in both size and time. */
    private fun readAllStderrBounded(executor: ExecutorService, process: Process): String =
        executor.submit(Callable<String> {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            process.errorStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    require(out.size() <= MAX_STDERR_DIAGNOSTIC_BYTES) {
                        "helper stderr exceeded $MAX_STDERR_DIAGNOSTIC_BYTES bytes"
                    }
                }
            }
            out.toString(Charsets.UTF_8.name())
        }).get(STDERR_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1_000L

    private companion object {
        const val HELPER_ENV = "CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER"
        const val HELPER_SHA256_ENV = "CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER_SHA256"
        const val CHALLENGE_NONCE_BYTES = 32
        const val CHALLENGE_TTL_SECONDS = 30L
        const val LINE_TIMEOUT_SECONDS = 5L
        const val PROCESS_TIMEOUT_SECONDS = 10L
        const val EXIT_WAIT_SECONDS = 2L
        const val DESTROY_WAIT_SECONDS = 1L
        const val MAX_METADATA_LINE_BYTES = 2_048
        const val MAX_STDERR_DIAGNOSTIC_BYTES = 4_096
        const val STDERR_READ_TIMEOUT_SECONDS = 5L
        const val PHONE_KEY_ALIAS = "chinchillacam-phone-tls-interop"
        const val PHONE_KEYSTORE_PASSWORD = "changeit"
        val PHONE_ID_LINE_REGEX = Regex("^phone_id=([0-9a-f]{64})$")
    }
}
