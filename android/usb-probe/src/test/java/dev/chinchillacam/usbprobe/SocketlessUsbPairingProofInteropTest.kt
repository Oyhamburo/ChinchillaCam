package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
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

        val processRef = AtomicReference<Process?>()
        val watchdog = Executors.newSingleThreadExecutor()
        try {
            watchdog.submit(Callable { runInterop(helperEnv, processRef) })
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

    private fun runInterop(helperEnv: String, processRef: AtomicReference<Process?>) {
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

            val result = UsbTlsPairingProofVerifier(EpochSecondsSource { epochSeconds() }).verify(challenge, session)
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

    private fun Process.stderrDiagnostic(): String {
        val bytes = ByteArray(MAX_STDERR_DIAGNOSTIC_BYTES)
        val count = runCatching { errorStream.read(bytes) }.getOrDefault(-1)
        return if (count <= 0) {
            "helper stderr was empty"
        } else {
            "helper stderr: " + bytes.copyOf(count).toString(Charsets.UTF_8)
        }
    }

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
    }
}
