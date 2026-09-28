package dev.chinchillacam.usbprobe

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object PairingProofProtocol {
    private val ENDPOINT_MAGIC = byteArrayOf(0x43, 0x43, 0x50, 0x42)
    private const val VERSION = 0x01
    private const val MAX_ENDPOINT_BYTES = 256

    data class Endpoint(val host: String, val port: Int, val timeoutMillis: Int)

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
        val tlvs = parseEndpointTlvs(bytes, 5)
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

    private fun parseEndpointTlvs(bytes: ByteArray, offset: Int): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var cursor = offset
        var previous = -1
        while (cursor < bytes.size) {
            if (cursor + 3 > bytes.size) throw ProtocolError("endpoint length mismatch")
            val type = bytes[cursor].toInt() and 0xff
            val length = ((bytes[cursor + 1].toInt() and 0xff) shl 8) or (bytes[cursor + 2].toInt() and 0xff)
            val valueStart = cursor + 3
            val valueEnd = valueStart + length
            if (valueEnd < valueStart || valueEnd > bytes.size) throw ProtocolError("endpoint length mismatch")
            if (type !in 0x01..0x03) throw ProtocolError("unknown endpoint TLV")
            if (type <= previous) {
                if (result.containsKey(type)) throw ProtocolError("duplicate endpoint TLV")
                throw ProtocolError("endpoint TLVs out of order")
            }
            result[type] = bytes.copyOfRange(valueStart, valueEnd)
            previous = type
            cursor = valueEnd
        }
        return result
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray = byteArrayOf(type.toByte()) + value.size.toBytes2() + value

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
