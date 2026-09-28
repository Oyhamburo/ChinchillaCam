package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingProofProtocolTest {
    @Test
    fun endpointProofBytesRoundTripMatchesFrozenGolden() {
        val endpoint = PairingProofProtocol.Endpoint("127.0.0.1", 38443, 1500)

        val encoded = PairingProofProtocol.encodeEndpoint(endpoint)

        assertEquals(ENDPOINT_GOLDEN, encoded.hex())
        assertEquals(endpoint, PairingProofProtocol.parseEndpoint(encoded))
    }

    @Test
    fun endpointRejectsNonLoopbackInvalidPortsTimeoutsUnknownDuplicateAndTrailingBytes() {
        assertProtocolError("endpoint host must be loopback literal") {
            PairingProofProtocol.encodeEndpoint(PairingProofProtocol.Endpoint("localhost", 38443, 1500))
        }
        assertProtocolError("endpoint port out of range") {
            PairingProofProtocol.encodeEndpoint(PairingProofProtocol.Endpoint("127.0.0.1", 0, 1500))
        }
        assertProtocolError("endpoint timeout out of range") {
            PairingProofProtocol.encodeEndpoint(PairingProofProtocol.Endpoint("127.0.0.1", 38443, 249))
        }
        assertProtocolError("unknown endpoint TLV") {
            PairingProofProtocol.parseEndpoint(hex("43435042010100093132372e302e302e31020002962b04000100"))
        }
        assertProtocolError("duplicate endpoint TLV") {
            PairingProofProtocol.parseEndpoint(hex("43435042010100093132372e302e302e310100093132372e302e302e31020002962b030004000005dc"))
        }
        assertProtocolError("endpoint TLVs out of order") {
            PairingProofProtocol.parseEndpoint(hex("4343504201020002962b0100093132372e302e302e31030004000005dc"))
        }
        assertProtocolError("endpoint length mismatch") {
            PairingProofProtocol.parseEndpoint(hex(ENDPOINT_GOLDEN) + byteArrayOf(0x00))
        }
    }

    @Test
    fun requestFrameRoundTripMatchesFrozenGolden() {
        val request = request()

        val encoded = PairingProofProtocol.encodeRequest(request)

        assertEquals(REQUEST_GOLDEN, encoded.hex())
        assertEquals(request, PairingProofProtocol.parseRequest(encoded))
    }

    @Test
    fun responseFrameRoundTripMatchesFrozenGoldenWithStatusTlvFirst() {
        val response = response()

        val encoded = PairingProofProtocol.encodeResponse(response)

        assertEquals(RESPONSE_GOLDEN, encoded.hex())
        assertEquals(response, PairingProofProtocol.parseResponse(encoded))
    }

    @Test
    fun responseCodecPreservesNonzeroStatusAsExplicitRejectionForVerifier() {
        val response = response().copyForTest(status = 1)

        val decoded = PairingProofProtocol.parseResponse(PairingProofProtocol.encodeResponse(response))

        assertEquals(1, decoded.status)
    }

    @Test
    fun framesRejectBadMagicVersionTypeLengthUnknownDuplicateMissingAndOutOfOrderTlvs() {
        assertProtocolError("invalid frame magic") {
            PairingProofProtocol.parseRequest(hex(REQUEST_GOLDEN).also { it[0] = 0x00 })
        }
        assertProtocolError("unsupported frame version") {
            PairingProofProtocol.parseRequest(hex(REQUEST_GOLDEN).also { it[4] = 0x02 })
        }
        assertProtocolError("unexpected frame type") {
            PairingProofProtocol.parseRequest(hex(REQUEST_GOLDEN).also { it[5] = 0x02 })
        }
        assertProtocolError("frame length mismatch") {
            PairingProofProtocol.parseRequest(hex(REQUEST_GOLDEN) + byteArrayOf(0x00))
        }
        assertProtocolError("frame payload too large") {
            PairingProofProtocol.parseRequest(byteArrayOf(0x43, 0x43, 0x50, 0x31, 0x01, 0x01, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        assertProtocolError("unknown request TLV") {
            PairingProofProtocol.parseRequest(frame(type = 0x01, payloadHex = "01000470632d3102000201020300041011121304000973657373696f6e2d3105000100"))
        }
        assertProtocolError("duplicate request TLV") {
            PairingProofProtocol.parseRequest(frame(type = 0x01, payloadHex = "01000470632d3101000470632d3102000201020300041011121304000973657373696f6e2d31"))
        }
        assertProtocolError("request TLVs out of order") {
            PairingProofProtocol.parseRequest(frame(type = 0x01, payloadHex = "020002010201000470632d310300041011121304000973657373696f6e2d31"))
        }
        assertProtocolError("missing request TLV") {
            PairingProofProtocol.parseRequest(frame(type = 0x01, payloadHex = "01000470632d31020002010203000410111213"))
        }
    }

    @Test
    fun framesRejectInvalidUtf8OversizedPayloadAndInvalidFieldLengths() {
        assertProtocolError("invalid UTF-8") {
            PairingProofProtocol.parseRequest(frame(type = 0x01, payloadHex = "010001ff02000201020300041011121304000973657373696f6e2d31"))
        }
        assertProtocolError("frame payload too large") {
            PairingProofProtocol.parseRequest(byteArrayOf(0x43, 0x43, 0x50, 0x31, 0x01, 0x01, 0x00, 0x00, 0x04, 0x01) + ByteArray(1025))
        }
        assertProtocolError("response status length invalid") {
            PairingProofProtocol.parseResponse(frame(type = 0x02, payloadHex = "000002000001000470632d3102000201020300041011121304000973657373696f6e2d31"))
        }
        assertProtocolError("endpoint port length invalid") {
            PairingProofProtocol.parseEndpoint(hex("43435042010100093132372e302e302e3102000103030004000005dc"))
        }
    }

    @Test
    fun requestAndResponseFieldCapsRejectInvalidOrOversizedValues() {
        assertProtocolError("desktopId invalid") {
            PairingProofProtocol.encodeRequest(request().copyForTest(desktopId = "bad id"))
        }
        assertProtocolError("desktopId invalid") {
            PairingProofProtocol.encodeRequest(request().copyForTest(desktopId = "a".repeat(65)))
        }
        assertProtocolError("sessionId invalid") {
            PairingProofProtocol.encodeRequest(request().copyForTest(sessionId = ""))
        }
        assertProtocolError("sessionId invalid") {
            PairingProofProtocol.encodeRequest(request().copyForTest(sessionId = "s".repeat(65)))
        }
        assertProtocolError("qrNonce invalid") {
            PairingProofProtocol.encodeRequest(request().copyForTest(qrNonce = byteArrayOf()))
        }
        assertProtocolError("challengeNonce invalid") {
            PairingProofProtocol.encodeResponse(response().copyForTest(challengeNonce = ByteArray(65) { 0x01 }))
        }
    }

    @Test
    fun codecDefensivelyCopiesNonceBytes() {
        val qrNonce = byteArrayOf(0x01, 0x02)
        val challengeNonce = byteArrayOf(0x10, 0x11, 0x12, 0x13)
        val request = PairingProofProtocol.ProofRequest("pc-1", qrNonce, challengeNonce, "session-1")
        qrNonce[0] = 0x7f
        challengeNonce[0] = 0x7e

        assertArrayEquals(byteArrayOf(0x01, 0x02), request.qrNonce)
        assertArrayEquals(byteArrayOf(0x10, 0x11, 0x12, 0x13), request.challengeNonce)
        val read = request.qrNonce
        read[0] = 0x55
        assertArrayEquals(byteArrayOf(0x01, 0x02), request.qrNonce)
    }

    private fun request() = PairingProofProtocol.ProofRequest(
        desktopId = "pc-1",
        qrNonce = byteArrayOf(0x01, 0x02),
        challengeNonce = byteArrayOf(0x10, 0x11, 0x12, 0x13),
        sessionId = "session-1",
    )

    private fun response() = PairingProofProtocol.ProofResponse(
        status = 0,
        desktopId = "pc-1",
        qrNonce = byteArrayOf(0x01, 0x02),
        challengeNonce = byteArrayOf(0x10, 0x11, 0x12, 0x13),
        sessionId = "session-1",
    )

    private fun PairingProofProtocol.ProofRequest.copyForTest(
        desktopId: String = this.desktopId,
        qrNonce: ByteArray = this.qrNonce,
        challengeNonce: ByteArray = this.challengeNonce,
        sessionId: String = this.sessionId,
    ) = PairingProofProtocol.ProofRequest(desktopId, qrNonce, challengeNonce, sessionId)

    private fun PairingProofProtocol.ProofResponse.copyForTest(
        status: Int = this.status,
        desktopId: String = this.desktopId,
        qrNonce: ByteArray = this.qrNonce,
        challengeNonce: ByteArray = this.challengeNonce,
        sessionId: String = this.sessionId,
    ) = PairingProofProtocol.ProofResponse(status, desktopId, qrNonce, challengeNonce, sessionId)

    private fun assertProtocolError(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("expected PairingProofProtocol.ProtocolError")
        } catch (error: PairingProofProtocol.ProtocolError) {
            assertEquals(expectedMessage, error.message)
        }
    }

    private fun frame(type: Int, payloadHex: String): ByteArray {
        val payload = hex(payloadHex)
        return byteArrayOf(0x43, 0x43, 0x50, 0x31, 0x01, type.toByte()) + payload.size.toBytes4() + payload
    }

    private fun Int.toBytes4(): ByteArray = byteArrayOf(
        ((this ushr 24) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        (this and 0xff).toByte(),
    )

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val REQUEST_GOLDEN = "4343503101010000001f01000470632d3102000201020300041011121304000973657373696f6e2d31"
        const val RESPONSE_GOLDEN = "434350310102000000230000010001000470632d3102000201020300041011121304000973657373696f6e2d31"
        const val ENDPOINT_GOLDEN = "43435042010100093132372e302e302e31020002962b030004000005dc"
    }
}
