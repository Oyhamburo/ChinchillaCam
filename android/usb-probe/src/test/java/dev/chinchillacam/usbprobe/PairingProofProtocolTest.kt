package dev.chinchillacam.usbprobe

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
    fun endpointAcceptsIpv6LoopbackLiteral() {
        val endpoint = PairingProofProtocol.Endpoint("::1", 443, 250)

        assertEquals(endpoint, PairingProofProtocol.parseEndpoint(PairingProofProtocol.encodeEndpoint(endpoint)))
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
        assertProtocolError("endpoint port length invalid") {
            PairingProofProtocol.parseEndpoint(hex("43435042010100093132372e302e302e3102000103030004000005dc"))
        }
    }

    private fun assertProtocolError(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("expected PairingProofProtocol.ProtocolError")
        } catch (error: PairingProofProtocol.ProtocolError) {
            assertEquals(expectedMessage, error.message)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val ENDPOINT_GOLDEN = "43435042010100093132372e302e302e31020002962b030004000005dc"
    }
}
