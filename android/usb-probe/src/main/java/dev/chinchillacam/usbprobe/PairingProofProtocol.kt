package dev.chinchillacam.usbprobe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object PairingProofProtocol {
    private val ENDPOINT_MAGIC = byteArrayOf(0x43, 0x43, 0x50, 0x42)
    private val FRAME_MAGIC = byteArrayOf(0x43, 0x43, 0x50, 0x31)
    private const val VERSION = 0x01
    private const val REQUEST_TYPE = 0x01
    private const val RESPONSE_TYPE = 0x02
    private const val MAX_ENDPOINT_BYTES = 256
    private const val MAX_FRAME_PAYLOAD_BYTES = 1024

    data class Endpoint(val host: String, val port: Int, val timeoutMillis: Int)

    class ProofRequest(
        val desktopId: String,
        qrNonce: ByteArray,
        challengeNonce: ByteArray,
        val sessionId: String,
    ) {
        private val qrNonceBytes = qrNonce.copyOf()
        private val challengeNonceBytes = challengeNonce.copyOf()
        val qrNonce: ByteArray get() = qrNonceBytes.copyOf()
        val challengeNonce: ByteArray get() = challengeNonceBytes.copyOf()

        override fun equals(other: Any?): Boolean = other is ProofRequest &&
            desktopId == other.desktopId &&
            qrNonceBytes.contentEquals(other.qrNonceBytes) &&
            challengeNonceBytes.contentEquals(other.challengeNonceBytes) &&
            sessionId == other.sessionId

        override fun hashCode(): Int {
            var result = desktopId.hashCode()
            result = 31 * result + qrNonceBytes.contentHashCode()
            result = 31 * result + challengeNonceBytes.contentHashCode()
            result = 31 * result + sessionId.hashCode()
            return result
        }
    }

    class ProofResponse(
        val status: Int,
        val desktopId: String,
        qrNonce: ByteArray,
        challengeNonce: ByteArray,
        val sessionId: String,
    ) {
        private val qrNonceBytes = qrNonce.copyOf()
        private val challengeNonceBytes = challengeNonce.copyOf()
        val qrNonce: ByteArray get() = qrNonceBytes.copyOf()
        val challengeNonce: ByteArray get() = challengeNonceBytes.copyOf()

        override fun equals(other: Any?): Boolean = other is ProofResponse &&
            status == other.status &&
            desktopId == other.desktopId &&
            qrNonceBytes.contentEquals(other.qrNonceBytes) &&
            challengeNonceBytes.contentEquals(other.challengeNonceBytes) &&
            sessionId == other.sessionId

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + desktopId.hashCode()
            result = 31 * result + qrNonceBytes.contentHashCode()
            result = 31 * result + challengeNonceBytes.contentHashCode()
            result = 31 * result + sessionId.hashCode()
            return result
        }
    }

    class ProtocolError(message: String) : Exception(message)

    fun encodeEndpoint(endpoint: Endpoint): ByteArray {
        if (!isLoopbackLiteral(endpoint.host)) throw ProtocolError("endpoint host must be loopback literal")
        if (endpoint.port !in 1..65535) throw ProtocolError("endpoint port out of range")
        if (endpoint.timeoutMillis !in 250..5000) throw ProtocolError("endpoint timeout out of range")
        val body = tlv(0x01, endpoint.host.toByteArray(StandardCharsets.UTF_8)) +
            tlv(0x02, endpoint.port.toBytes2()) +
            tlv(0x03, endpoint.timeoutMillis.toBytes4())
        val encoded = ENDPOINT_MAGIC + byteArrayOf(VERSION.toByte()) + body
        if (encoded.size > MAX_ENDPOINT_BYTES) throw ProtocolError("endpoint length mismatch")
        return encoded
    }

    fun parseEndpoint(bytes: ByteArray): Endpoint {
        if (bytes.size > MAX_ENDPOINT_BYTES) throw ProtocolError("endpoint length mismatch")
        if (bytes.size < 5 || !bytes.copyOfRange(0, 4).contentEquals(ENDPOINT_MAGIC)) throw ProtocolError("invalid endpoint magic")
        if ((bytes[4].toInt() and 0xff) != VERSION) throw ProtocolError("unsupported endpoint version")
        val tlvs = parseTlvs(bytes, 5, mapOf(0x01 to "endpoint", 0x02 to "endpoint", 0x03 to "endpoint"), "endpoint")
        val host = decodeUtf8(tlvs[0x01] ?: throw ProtocolError("missing endpoint TLV"))
        val portBytes = tlvs[0x02] ?: throw ProtocolError("missing endpoint TLV")
        if (portBytes.size != 2) throw ProtocolError("endpoint port length invalid")
        val timeoutBytes = tlvs[0x03] ?: throw ProtocolError("missing endpoint TLV")
        if (timeoutBytes.size != 4) throw ProtocolError("endpoint timeout length invalid")
        val endpoint = Endpoint(host, portBytes.toInt2(), timeoutBytes.toInt4())
        if (!isLoopbackLiteral(endpoint.host)) throw ProtocolError("endpoint host must be loopback literal")
        if (endpoint.port !in 1..65535) throw ProtocolError("endpoint port out of range")
        if (endpoint.timeoutMillis !in 250..5000) throw ProtocolError("endpoint timeout out of range")
        return endpoint
    }

    fun encodeRequest(request: ProofRequest): ByteArray {
        val desktopId = encodeDesktopId(request.desktopId)
        val qrNonce = validateNonce("qrNonce", request.qrNonce)
        val challengeNonce = validateNonce("challengeNonce", request.challengeNonce)
        val sessionId = encodeSessionId(request.sessionId)
        return encodeFrame(
            REQUEST_TYPE,
            tlv(0x01, desktopId) +
                tlv(0x02, qrNonce) +
                tlv(0x03, challengeNonce) +
                tlv(0x04, sessionId),
        )
    }

    fun parseRequest(bytes: ByteArray): ProofRequest {
        val payload = parseFrame(bytes, REQUEST_TYPE)
        val tlvs = parseTlvs(payload, 0, mapOf(0x01 to "request", 0x02 to "request", 0x03 to "request", 0x04 to "request"), "request")
        return ProofRequest(
            desktopId = decodeDesktopId(tlvs[0x01] ?: throw ProtocolError("missing request TLV")),
            qrNonce = validateNonce("qrNonce", tlvs[0x02] ?: throw ProtocolError("missing request TLV")),
            challengeNonce = validateNonce("challengeNonce", tlvs[0x03] ?: throw ProtocolError("missing request TLV")),
            sessionId = decodeSessionId(tlvs[0x04] ?: throw ProtocolError("missing request TLV")),
        )
    }

    fun encodeResponse(response: ProofResponse): ByteArray {
        if (response.status !in 0..255) throw ProtocolError("response status length invalid")
        val desktopId = encodeDesktopId(response.desktopId)
        val qrNonce = validateNonce("qrNonce", response.qrNonce)
        val challengeNonce = validateNonce("challengeNonce", response.challengeNonce)
        val sessionId = encodeSessionId(response.sessionId)
        return encodeFrame(
            RESPONSE_TYPE,
            tlv(0x00, byteArrayOf(response.status.toByte())) +
                tlv(0x01, desktopId) +
                tlv(0x02, qrNonce) +
                tlv(0x03, challengeNonce) +
                tlv(0x04, sessionId),
        )
    }

    fun parseResponse(bytes: ByteArray): ProofResponse {
        val payload = parseFrame(bytes, RESPONSE_TYPE)
        val tlvs = parseTlvs(payload, 0, mapOf(0x00 to "response", 0x01 to "response", 0x02 to "response", 0x03 to "response", 0x04 to "response"), "response")
        val status = tlvs[0x00] ?: throw ProtocolError("missing response TLV")
        if (status.size != 1) throw ProtocolError("response status length invalid")
        return ProofResponse(
            status = status[0].toInt() and 0xff,
            desktopId = decodeDesktopId(tlvs[0x01] ?: throw ProtocolError("missing response TLV")),
            qrNonce = validateNonce("qrNonce", tlvs[0x02] ?: throw ProtocolError("missing response TLV")),
            challengeNonce = validateNonce("challengeNonce", tlvs[0x03] ?: throw ProtocolError("missing response TLV")),
            sessionId = decodeSessionId(tlvs[0x04] ?: throw ProtocolError("missing response TLV")),
        )
    }

    private fun encodeFrame(type: Int, payload: ByteArray): ByteArray {
        if (payload.size > MAX_FRAME_PAYLOAD_BYTES) throw ProtocolError("frame payload too large")
        return FRAME_MAGIC + byteArrayOf(VERSION.toByte(), type.toByte()) + payload.size.toBytes4() + payload
    }

    private fun parseFrame(bytes: ByteArray, expectedType: Int): ByteArray {
        if (bytes.size < 10 || !bytes.copyOfRange(0, 4).contentEquals(FRAME_MAGIC)) throw ProtocolError("invalid frame magic")
        if ((bytes[4].toInt() and 0xff) != VERSION) throw ProtocolError("unsupported frame version")
        if ((bytes[5].toInt() and 0xff) != expectedType) throw ProtocolError("unexpected frame type")
        val payloadLength = bytes.copyOfRange(6, 10).toInt4()
        if (payloadLength < 0 || payloadLength > MAX_FRAME_PAYLOAD_BYTES) throw ProtocolError("frame payload too large")
        if (bytes.size != 10 + payloadLength) throw ProtocolError("frame length mismatch")
        return bytes.copyOfRange(10, bytes.size)
    }

    private fun parseTlvs(bytes: ByteArray, offset: Int, allowedTypes: Map<Int, String>, label: String): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var cursor = offset
        var previous = -1
        while (cursor < bytes.size) {
            if (cursor + 3 > bytes.size) throw ProtocolError("$label length mismatch")
            val type = bytes[cursor].toInt() and 0xff
            val length = ((bytes[cursor + 1].toInt() and 0xff) shl 8) or (bytes[cursor + 2].toInt() and 0xff)
            val valueStart = cursor + 3
            val valueEnd = valueStart + length
            if (valueEnd < valueStart || valueEnd > bytes.size) throw ProtocolError("$label length mismatch")
            if (!allowedTypes.containsKey(type)) throw ProtocolError("unknown $label TLV")
            if (type <= previous) {
                if (result.containsKey(type)) throw ProtocolError("duplicate $label TLV")
                throw ProtocolError("$label TLVs out of order")
            }
            result[type] = bytes.copyOfRange(valueStart, valueEnd)
            previous = type
            cursor = valueEnd
        }
        return result
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        if (value.size > 65535) throw ProtocolError("frame payload too large")
        return byteArrayOf(type.toByte()) + value.size.toBytes2() + value
    }

    private fun encodeDesktopId(value: String): ByteArray = validateDesktopIdBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeDesktopId(bytes: ByteArray): String = decodeUtf8(validateDesktopIdBytes(bytes))

    private fun validateDesktopIdBytes(bytes: ByteArray): ByteArray {
        val value = decodeUtf8(bytes)
        if (bytes.isEmpty() || bytes.size > 64 || !DESKTOP_ID_PATTERN.matches(value)) throw ProtocolError("desktopId invalid")
        return bytes.copyOf()
    }

    private fun encodeSessionId(value: String): ByteArray = validateSessionIdBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeSessionId(bytes: ByteArray): String = decodeUtf8(validateSessionIdBytes(bytes))

    private fun validateSessionIdBytes(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size > 64) throw ProtocolError("sessionId invalid")
        decodeUtf8(bytes)
        return bytes.copyOf()
    }

    private fun validateNonce(field: String, bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size > 64) throw ProtocolError("$field invalid")
        return bytes.copyOf()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw ProtocolError("invalid UTF-8")
    }

    private fun isLoopbackLiteral(host: String): Boolean = host == "127.0.0.1" || host == "::1"

    private val DESKTOP_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

    private fun Int.toBytes2(): ByteArray = byteArrayOf(((this ushr 8) and 0xff).toByte(), (this and 0xff).toByte())

    private fun Int.toBytes4(): ByteArray = byteArrayOf(
        ((this ushr 24) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        (this and 0xff).toByte(),
    )

    private fun ByteArray.toInt2(): Int = ((this[0].toInt() and 0xff) shl 8) or (this[1].toInt() and 0xff)

    private fun ByteArray.toInt4(): Int =
        ((this[0].toInt() and 0xff) shl 24) or
            ((this[1].toInt() and 0xff) shl 16) or
            ((this[2].toInt() and 0xff) shl 8) or
            (this[3].toInt() and 0xff)
}
