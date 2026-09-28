package dev.chinchillacam.usbprobe

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TlsPairingProofVerifier(
    private val epochSecondsSource: EpochSecondsSource,
) : PairingProofVerifier {
    override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult {
        val now = epochSecondsSource.nowEpochSeconds()
        if (challenge.qrExpiresAtEpochSeconds <= now) return PairingProofVerificationResult.Rejected("qr expired")
        if (challenge.challengeExpiresAtEpochSeconds <= now) return PairingProofVerificationResult.Rejected("challenge expired")
        if (challenge.qrNonce.size < MIN_QR_NONCE_BYTES) return PairingProofVerificationResult.Rejected("qr nonce too short")
        if (challenge.challengeNonce.size < MIN_CHALLENGE_NONCE_BYTES) return PairingProofVerificationResult.Rejected("challenge nonce too short")
        val identity = DesktopTlsIdentityMaterial.validate(challenge.desktopSubjectPublicKeyInfoDer).getOrElse {
            return PairingProofVerificationResult.Rejected("invalid challenge identity")
        }
        if (identity.fingerprint != challenge.trustMaterialFingerprint) {
            return PairingProofVerificationResult.Rejected("invalid challenge identity")
        }
        val endpoint = try {
            PairingProofProtocol.parseEndpoint(proofBytes)
        } catch (_: PairingProofProtocol.ProtocolError) {
            return PairingProofVerificationResult.Rejected("invalid proof endpoint")
        }

        return try {
            connectAndVerify(endpoint, identity, challenge)
        } catch (_: SocketTimeoutException) {
            PairingProofVerificationResult.Rejected("tls proof timed out")
        } catch (_: IOException) {
            PairingProofVerificationResult.Rejected("tls proof failed")
        } catch (_: PairingProofProtocol.ProtocolError) {
            PairingProofVerificationResult.Rejected("tls proof failed")
        }
    }

    private fun connectAndVerify(
        endpoint: PairingProofProtocol.Endpoint,
        identity: DesktopTlsIdentityMaterial,
        challenge: PairingProofChallenge,
    ): PairingProofVerificationResult {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedDesktopTlsTrustManager(identity)), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = endpoint.timeoutMillis
            it.enabledProtocols = it.supportedProtocols.filter { protocol -> protocol == "TLSv1.2" || protocol == "TLSv1.3" }.toTypedArray()
            it.connect(InetSocketAddress(endpoint.host, endpoint.port), endpoint.timeoutMillis)
            it.startHandshake()
            it.outputStream.write(PairingProofProtocol.encodeRequest(requestFrom(challenge)))
            it.outputStream.flush()
            val response = PairingProofProtocol.parseResponse(readFrame(it.inputStream))
            if (response.status != 0) return PairingProofVerificationResult.Rejected("desktop rejected proof")
            if (!matches(challenge, response)) return PairingProofVerificationResult.Rejected("proof response mismatch")
            val verifiedAt = epochSecondsSource.nowEpochSeconds()
            if (challenge.qrExpiresAtEpochSeconds <= verifiedAt) return PairingProofVerificationResult.Rejected("qr expired")
            if (challenge.challengeExpiresAtEpochSeconds <= verifiedAt) return PairingProofVerificationResult.Rejected("challenge expired")
            return PairingProofVerificationResult.Verified(
                desktopId = challenge.desktopId,
                trustMaterialFingerprint = challenge.trustMaterialFingerprint,
                qrNonce = challenge.qrNonce,
                challengeNonce = challenge.challengeNonce,
                sessionId = challenge.sessionId,
                verifiedAtEpochSeconds = verifiedAt,
                expiresAtEpochSeconds = challenge.challengeExpiresAtEpochSeconds,
            )
        }
    }

    private fun requestFrom(challenge: PairingProofChallenge) = PairingProofProtocol.ProofRequest(
        desktopId = challenge.desktopId,
        qrNonce = challenge.qrNonce,
        challengeNonce = challenge.challengeNonce,
        sessionId = challenge.sessionId,
    )

    private fun matches(challenge: PairingProofChallenge, response: PairingProofProtocol.ProofResponse): Boolean =
        response.desktopId == challenge.desktopId &&
            response.qrNonce.contentEquals(challenge.qrNonce) &&
            response.challengeNonce.contentEquals(challenge.challengeNonce) &&
            response.sessionId == challenge.sessionId

    private fun readFrame(input: InputStream): ByteArray {
        val header = input.readExactly(FRAME_HEADER_BYTES)
        val length = ((header[6].toInt() and 0xff) shl 24) or
            ((header[7].toInt() and 0xff) shl 16) or
            ((header[8].toInt() and 0xff) shl 8) or
            (header[9].toInt() and 0xff)
        if (length < 0 || length > MAX_FRAME_PAYLOAD_BYTES) throw IOException("frame payload too large")
        return header + input.readExactly(length)
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("unexpected EOF")
            offset += read
        }
        return bytes
    }

    private companion object {
        const val MIN_QR_NONCE_BYTES = 16
        const val MIN_CHALLENGE_NONCE_BYTES = 32
        const val FRAME_HEADER_BYTES = 10
        const val MAX_FRAME_PAYLOAD_BYTES = 1024
    }
}
