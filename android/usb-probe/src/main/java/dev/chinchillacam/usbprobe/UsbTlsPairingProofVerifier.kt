package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class UsbTlsPairingProofVerifier(
    private val epochSecondsSource: EpochSecondsSource,
    private val tlsChannel: SslEngineUsbTlsChannel = SslEngineUsbTlsChannel(),
) {
    /**
     * Verifies the pairing proof over an [AccessoryIoSession] (USB). Wraps it in a
     * [UsbAccessoryTlsCiphertextTransport] and delegates to the transport-neutral overload, so
     * behaviour is identical to running directly over the USB transport. Matches
     * [ChannelPairingProofVerifier] and can still be passed as a bound method reference.
     */
    fun verify(
        challenge: PairingProofChallenge,
        session: AccessoryIoSession,
    ): UsbTlsPairingProofVerificationResult = verify(challenge, tlsChannel.usbTransport(session))

    /**
     * Transport-neutral counterpart (contract `wifi-loopback-transport` §4.3): runs the same
     * challenge validation, pinned mTLS handshake, and CCP1 proof exchange over any
     * [TlsCiphertextTransport]. An invalid challenge still closes [transport] before any TLS I/O.
     * Matches [TransportPairingProofVerifier] (task g1) and can be passed as a bound method reference.
     */
    fun verify(
        challenge: PairingProofChallenge,
        transport: TlsCiphertextTransport,
    ): UsbTlsPairingProofVerificationResult {
        validateChallenge(challenge)?.let {
            try {
                transport.close()
            } catch (_: Exception) {
                // Best-effort close: preserve the typed rejection reason.
            }
            return UsbTlsPairingProofVerificationResult.Rejected(it)
        }

        val handshake = tlsChannel.handshake(transport, challenge.desktopSubjectPublicKeyInfoDer)
        val channel = when (handshake) {
            is SslEngineUsbTlsHandshakeResult.Authenticated -> handshake.channel
            is SslEngineUsbTlsHandshakeResult.Rejected -> return UsbTlsPairingProofVerificationResult.Rejected(handshake.reason)
        }

        return try {
            channel.writeApplicationData(PairingProofProtocol.encodeRequest(requestFrom(challenge)))
            val response = PairingProofProtocol.parseResponse(readFrame(channel))
            when {
                response.status != 0 -> reject(channel, "desktop rejected proof")
                !matches(challenge, response) -> reject(channel, "proof response mismatch")
                else -> {
                    val verifiedAt = epochSecondsSource.nowEpochSeconds()
                    when {
                        challenge.qrExpiresAtEpochSeconds <= verifiedAt -> reject(channel, "qr expired")
                        challenge.challengeExpiresAtEpochSeconds <= verifiedAt -> reject(channel, "challenge expired")
                        else -> UsbTlsPairingProofVerificationResult.Verified(
                            proof = PairingProofVerificationResult.Verified(
                                desktopId = challenge.desktopId,
                                trustMaterialFingerprint = challenge.trustMaterialFingerprint,
                                qrNonce = challenge.qrNonce,
                                challengeNonce = challenge.challengeNonce,
                                sessionId = challenge.sessionId,
                                verifiedAtEpochSeconds = verifiedAt,
                                expiresAtEpochSeconds = challenge.challengeExpiresAtEpochSeconds,
                            ),
                            channel = channel,
                        )
                    }
                }
            }
        } catch (_: PairingProofProtocol.ProtocolError) {
            reject(channel, "tls proof failed")
        } catch (_: Exception) {
            reject(channel, "tls proof failed")
        }
    }

    private fun validateChallenge(challenge: PairingProofChallenge): String? {
        val now = epochSecondsSource.nowEpochSeconds()
        if (challenge.qrExpiresAtEpochSeconds <= now) return "qr expired"
        if (challenge.challengeExpiresAtEpochSeconds <= now) return "challenge expired"
        if (challenge.qrNonce.size < MIN_QR_NONCE_BYTES) return "qr nonce too short"
        if (challenge.challengeNonce.size < MIN_CHALLENGE_NONCE_BYTES) return "challenge nonce too short"
        val identity = DesktopTlsIdentityMaterial.validate(challenge.desktopSubjectPublicKeyInfoDer).getOrElse {
            return "invalid challenge identity"
        }
        if (identity.fingerprint != challenge.trustMaterialFingerprint) return "invalid challenge identity"
        return null
    }

    private fun readFrame(channel: SslEngineUsbTlsEstablishedChannel): ByteArray {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(APPLICATION_READ_TIMEOUT_MILLIS)
        val header = readExactly(channel, FRAME_HEADER_BYTES, deadlineNanos)
        val length = ((header[6].toInt() and 0xff) shl 24) or
            ((header[7].toInt() and 0xff) shl 16) or
            ((header[8].toInt() and 0xff) shl 8) or
            (header[9].toInt() and 0xff)
        if (length < 0 || length > MAX_FRAME_PAYLOAD_BYTES) throw IllegalStateException("frame payload too large")
        return header + readExactly(channel, length, deadlineNanos)
    }

    private fun readExactly(channel: SslEngineUsbTlsEstablishedChannel, bytes: Int, deadlineNanos: Long): ByteArray {
        val out = ByteArrayOutputStream(bytes)
        while (out.size() < bytes) {
            out.write(channel.readApplicationData(bytes - out.size(), deadlineNanos))
        }
        return out.toByteArray()
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

    private fun reject(channel: SslEngineUsbTlsEstablishedChannel, reason: String): UsbTlsPairingProofVerificationResult.Rejected {
        try {
            channel.close()
        } catch (_: Exception) {
            // Best-effort close: preserve the typed rejection reason.
        }
        return UsbTlsPairingProofVerificationResult.Rejected(reason)
    }

    private companion object {
        const val MIN_QR_NONCE_BYTES = 16
        const val MIN_CHALLENGE_NONCE_BYTES = 32
        const val FRAME_HEADER_BYTES = 10
        const val MAX_FRAME_PAYLOAD_BYTES = 1024
        const val APPLICATION_READ_TIMEOUT_MILLIS: Long = 5_000
    }
}

sealed class UsbTlsPairingProofVerificationResult {
    data class Verified(
        val proof: PairingProofVerificationResult.Verified,
        val channel: SslEngineUsbTlsEstablishedChannel,
    ) : UsbTlsPairingProofVerificationResult()

    data class Rejected(val reason: String) : UsbTlsPairingProofVerificationResult()
}
