package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RustlsPairingProofInteropTest {
    @Test
    fun rustlsHelperCompletesPinnedTlsProofOverLoopback() {
        val helper = System.getenv(HELPER_ENV).orEmpty()
        assumeTrue("$HELPER_ENV not set; skipping cross-worktree rustls interop", helper.isNotBlank())

        val process = ProcessBuilder(helper).start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stdout = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.US_ASCII))
            val version = readLineBounded(executor, stdout)
            val qrLine = readLineBounded(executor, stdout)
            val proofLine = readLineBounded(executor, stdout)

            assertEquals("CHINCHILLACAM-INTEROP:v1", version)
            assertTrue(qrLine.startsWith("qr="))
            assertTrue(proofLine.startsWith("proof="))

            val now = epochSeconds()
            val qr = PairingQrPayloadCodec.decode(qrLine.removePrefix("qr="), nowEpochSeconds = now).getOrThrow()
            val identity = DesktopTlsIdentityMaterial.validate(qr.trustMaterial).getOrThrow()
            assertTrue(qr.desktopId.isNotBlank())
            assertEquals(PairingTrustFingerprint.fromTrustMaterial(identity.subjectPublicKeyInfoDer), identity.fingerprint)

            val proofBytes = proofLine.removePrefix("proof=").decodeLowercaseHex()
            val clock = EpochSecondsSource { epochSeconds() }
            val coordinator = PendingPairingCoordinator(
                epochSecondsSource = clock,
                challengeNonceSource = SecureRandomChallengeNonceSource(clock),
                proofVerifier = TlsPairingProofVerifier(clock),
            )

            val result = coordinator.start(qr, proofBytes)

            assertTrue(result is PendingPairingStartResult.PendingConfirmation)
            val pending = (result as PendingPairingStartResult.PendingConfirmation).summary
            assertEquals(qr.desktopId, pending.desktopId)
            assertEquals(identity.fingerprint, pending.trustMaterialFingerprint)
            assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(-1, stdout.read())
        } finally {
            executor.shutdownNow()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readLineBounded(
        executor: java.util.concurrent.ExecutorService,
        reader: BufferedReader,
    ): String = executor.submit(Callable {
        val line = StringBuilder()
        var completeLine: String? = null
        while (completeLine == null) {
            val next = reader.read()
            require(next != -1) { "helper stdout ended before complete contract" }
            if (next == '\n'.code) {
                completeLine = line.toString()
            } else {
                require(next in 0x20..0x7e) { "helper stdout must be printable ASCII" }
                require(line.length < MAX_STDOUT_LINE_BYTES) { "helper stdout line exceeds limit" }
                line.append(next.toChar())
            }
        }
        completeLine
    }).get(LINE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun String.decodeLowercaseHex(): ByteArray {
        require(length % 2 == 0 && length <= MAX_PROOF_HEX_BYTES && all { it in '0'..'9' || it in 'a'..'f' }) {
            "invalid proof hex"
        }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1_000L

    private companion object {
        const val HELPER_ENV = "CHINCHILLA_PAIRING_PROOF_HELPER"
        const val LINE_TIMEOUT_SECONDS = 5L
        const val PROCESS_TIMEOUT_SECONDS = 10L
        const val MAX_STDOUT_LINE_BYTES = 2_048
        const val MAX_PROOF_HEX_BYTES = 512
    }
}
