package dev.chinchillacam.usbprobe

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

private const val PAIRING_QR_PREFIX = "CHINCHILLACAM-PAIR"
private const val SUPPORTED_PAIRING_QR_VERSION = 1
private const val DEFAULT_MAX_PAIRING_QR_SIZE = 1024
private const val DEFAULT_MAX_DESKTOP_ID_BYTES = 64
private const val DEFAULT_MAX_DESKTOP_NAME_BYTES = 64
private const val DEFAULT_MAX_TRUST_MATERIAL_BYTES = 512
private const val DEFAULT_MAX_NONCE_BYTES = 64
private val DESKTOP_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

data class PairingQrPayload(
    val desktopId: String,
    val desktopName: String,
    val trustMaterial: ByteArray,
    val expiresAtEpochSeconds: Long,
    val nonce: ByteArray,
    val version: Int = SUPPORTED_PAIRING_QR_VERSION,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingQrPayload) return false
        return desktopId == other.desktopId &&
            desktopName == other.desktopName &&
            trustMaterial.contentEquals(other.trustMaterial) &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            nonce.contentEquals(other.nonce) &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = desktopId.hashCode()
        result = 31 * result + desktopName.hashCode()
        result = 31 * result + trustMaterial.contentHashCode()
        result = 31 * result + expiresAtEpochSeconds.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + version
        return result
    }
}

sealed class PairingQrPayloadDecodeError(message: String) : Exception(message) {
    object InvalidFormatOrPrefix : PairingQrPayloadDecodeError("invalid pairing QR format or prefix")
    data class UnsupportedVersion(val actualVersion: Int) : PairingQrPayloadDecodeError("unsupported pairing QR version: $actualVersion")
    data class PayloadTooLarge(val actualSize: Int, val maxSize: Int) : PairingQrPayloadDecodeError("pairing QR payload size $actualSize exceeds max $maxSize")
    data class MissingField(val field: String) : PairingQrPayloadDecodeError("missing pairing QR field: $field")
    data class InvalidField(val field: String) : PairingQrPayloadDecodeError("invalid pairing QR field: $field")
    data class Expired(val expiresAtEpochSeconds: Long, val nowEpochSeconds: Long) : PairingQrPayloadDecodeError("pairing QR payload expired")
    object ChecksumMismatch : PairingQrPayloadDecodeError("pairing QR checksum mismatch")
}

object PairingQrPayloadCodec {
    fun encode(
        payload: PairingQrPayload,
        maxEncodedSize: Int = DEFAULT_MAX_PAIRING_QR_SIZE,
        maxDesktopIdBytes: Int = DEFAULT_MAX_DESKTOP_ID_BYTES,
        maxDesktopNameBytes: Int = DEFAULT_MAX_DESKTOP_NAME_BYTES,
        maxTrustMaterialBytes: Int = DEFAULT_MAX_TRUST_MATERIAL_BYTES,
        maxNonceBytes: Int = DEFAULT_MAX_NONCE_BYTES,
    ): String {
        require(payload.version == SUPPORTED_PAIRING_QR_VERSION) { "unsupported pairing QR version: ${payload.version}" }
        validateDesktopIdForEncode(payload.desktopId, maxDesktopIdBytes)
        validateDesktopNameForEncode(payload.desktopName, maxDesktopNameBytes)
        validateTrustMaterialForEncode(payload.trustMaterial, maxTrustMaterialBytes)
        validateNonceForEncode(payload.nonce, maxNonceBytes)
        require(payload.expiresAtEpochSeconds > 0) { "expiresAtEpochSeconds must be positive" }

        val canonicalBody = canonicalBody(
            desktopId = payload.desktopId,
            desktopName = payload.desktopName,
            expiresAtEpochSeconds = payload.expiresAtEpochSeconds.toString(),
            nonce = payload.nonce.toBase64Url(),
            trustMaterial = payload.trustMaterial.toBase64Url(),
        )
        val encoded = "$PAIRING_QR_PREFIX:v${payload.version}:$canonicalBody&checksum=${checksum(canonicalBody)}"
        require(encoded.length <= maxEncodedSize) { "pairing QR payload is too large" }
        return encoded
    }

    fun decode(
        encoded: String,
        nowEpochSeconds: Long,
        maxEncodedSize: Int = DEFAULT_MAX_PAIRING_QR_SIZE,
        maxDesktopIdBytes: Int = DEFAULT_MAX_DESKTOP_ID_BYTES,
        maxDesktopNameBytes: Int = DEFAULT_MAX_DESKTOP_NAME_BYTES,
        maxTrustMaterialBytes: Int = DEFAULT_MAX_TRUST_MATERIAL_BYTES,
        maxNonceBytes: Int = DEFAULT_MAX_NONCE_BYTES,
    ): Result<PairingQrPayload> {
        if (encoded.length > maxEncodedSize) {
            return Result.failure(PairingQrPayloadDecodeError.PayloadTooLarge(encoded.length, maxEncodedSize))
        }
        if (!encoded.startsWith("$PAIRING_QR_PREFIX:v")) {
            return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
        }

        return try {
            val afterPrefix = encoded.removePrefix("$PAIRING_QR_PREFIX:v")
            val versionText = afterPrefix.substringBefore(':', missingDelimiterValue = "")
            if (versionText.isEmpty() || !versionText.all { it.isDigit() }) {
                return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
            }
            val version = versionText.toInt()
            if (version != SUPPORTED_PAIRING_QR_VERSION) {
                return Result.failure(PairingQrPayloadDecodeError.UnsupportedVersion(version))
            }
            val bodyWithChecksum = afterPrefix.substringAfter(':', missingDelimiterValue = "")
            if (bodyWithChecksum.isEmpty()) {
                return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
            }
            val fields = parseFields(bodyWithChecksum).getOrElse { return Result.failure(it) }
            val canonicalBody = canonicalBodyFromRequiredFields(fields).getOrElse { return Result.failure(it) }
            val expectedChecksum = fields["checksum"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("checksum"))
            if (expectedChecksum != checksum(canonicalBody)) {
                return Result.failure(PairingQrPayloadDecodeError.ChecksumMismatch)
            }

            val desktopId = fields.getValue("desktopId")
            val desktopName = fields.getValue("desktopName")
            val expiresAt = fields.getValue("expiresAt").toLongOrNull()
                ?: return Result.failure(PairingQrPayloadDecodeError.InvalidField("expiresAt"))
            val nonce = decodeBase64UrlField(fields.getValue("nonce"), "nonce").getOrElse { return Result.failure(it) }
            val trustMaterial = decodeBase64UrlField(fields.getValue("trustMaterial"), "trustMaterial").getOrElse { return Result.failure(it) }

            validateDesktopIdForDecode(desktopId, maxDesktopIdBytes)?.let { return Result.failure(it) }
            validateDesktopNameForDecode(desktopName, maxDesktopNameBytes)?.let { return Result.failure(it) }
            validateByteFieldForDecode(trustMaterial, "trustMaterial", maxTrustMaterialBytes)?.let { return Result.failure(it) }
            validateByteFieldForDecode(nonce, "nonce", maxNonceBytes)?.let { return Result.failure(it) }
            if (expiresAt <= 0) return Result.failure(PairingQrPayloadDecodeError.InvalidField("expiresAt"))
            if (expiresAt <= nowEpochSeconds) {
                return Result.failure(PairingQrPayloadDecodeError.Expired(expiresAt, nowEpochSeconds))
            }

            Result.success(PairingQrPayload(desktopId, desktopName, trustMaterial, expiresAt, nonce, version))
        } catch (error: IllegalArgumentException) {
            Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
        }
    }
}

private fun canonicalBodyFromRequiredFields(fields: Map<String, String>): Result<String> {
    val desktopId = fields["desktopId"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("desktopId"))
    val desktopName = fields["desktopName"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("desktopName"))
    val expiresAt = fields["expiresAt"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("expiresAt"))
    val nonce = fields["nonce"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("nonce"))
    val trustMaterial = fields["trustMaterial"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("trustMaterial"))
    if (expiresAt.toLongOrNull() == null) return Result.failure(PairingQrPayloadDecodeError.InvalidField("expiresAt"))
    decodeBase64UrlField(nonce, "nonce").getOrElse { return Result.failure(it) }
    decodeBase64UrlField(trustMaterial, "trustMaterial").getOrElse { return Result.failure(it) }
    return Result.success(canonicalBody(desktopId, desktopName, expiresAt, nonce, trustMaterial))
}

private fun canonicalBody(
    desktopId: String,
    desktopName: String,
    expiresAtEpochSeconds: String,
    nonce: String,
    trustMaterial: String,
): String = listOf(
    "desktopId=${desktopId.percentEncode()}",
    "desktopName=${desktopName.percentEncode()}",
    "expiresAt=${expiresAtEpochSeconds.percentEncode()}",
    "nonce=${nonce.percentEncode()}",
    "trustMaterial=${trustMaterial.percentEncode()}",
).joinToString("&")

private fun parseFields(body: String): Result<Map<String, String>> {
    val fields = linkedMapOf<String, String>()
    val allowedFields = setOf("desktopId", "desktopName", "expiresAt", "nonce", "trustMaterial", "checksum")
    body.split('&').forEach { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
        val key = part.substring(0, separator)
        if (key !in allowedFields) return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
        val value = part.substring(separator + 1).percentDecodeStrict().getOrElse { return Result.failure(it) }
        if (fields.put(key, value) != null) return Result.failure(PairingQrPayloadDecodeError.InvalidFormatOrPrefix)
    }
    return Result.success(fields)
}

private fun validateDesktopIdForEncode(value: String, maxBytes: Int) {
    require(value.isNotEmpty()) { "desktop id must not be empty" }
    require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "desktop id is too long" }
    require(DESKTOP_ID_PATTERN.matches(value)) { "desktop id contains invalid characters" }
}

private fun validateDesktopNameForEncode(value: String, maxBytes: Int) {
    require(value.isNotBlank()) { "desktop name must not be blank" }
    require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "desktop name is too long" }
    require(value.none { it.isISOControl() }) { "desktop name contains control characters" }
}

private fun validateTrustMaterialForEncode(value: ByteArray, maxBytes: Int) = validateBytesForEncode(value, maxBytes, "trust material")
private fun validateNonceForEncode(value: ByteArray, maxBytes: Int) = validateBytesForEncode(value, maxBytes, "nonce")

private fun validateBytesForEncode(value: ByteArray, maxBytes: Int, field: String) {
    require(value.isNotEmpty()) { "$field must not be empty" }
    require(value.size <= maxBytes) { "$field is too large" }
}

private fun validateDesktopIdForDecode(value: String, maxBytes: Int): PairingQrPayloadDecodeError? = when {
    value.isEmpty() -> PairingQrPayloadDecodeError.InvalidField("desktopId")
    value.toByteArray(Charsets.UTF_8).size > maxBytes -> PairingQrPayloadDecodeError.InvalidField("desktopId")
    !DESKTOP_ID_PATTERN.matches(value) -> PairingQrPayloadDecodeError.InvalidField("desktopId")
    else -> null
}

private fun validateDesktopNameForDecode(value: String, maxBytes: Int): PairingQrPayloadDecodeError? = when {
    value.isBlank() -> PairingQrPayloadDecodeError.InvalidField("desktopName")
    value.toByteArray(Charsets.UTF_8).size > maxBytes -> PairingQrPayloadDecodeError.InvalidField("desktopName")
    value.any { it.isISOControl() } -> PairingQrPayloadDecodeError.InvalidField("desktopName")
    else -> null
}

private fun validateByteFieldForDecode(value: ByteArray, field: String, maxBytes: Int): PairingQrPayloadDecodeError? = when {
    value.isEmpty() -> PairingQrPayloadDecodeError.InvalidField(field)
    value.size > maxBytes -> PairingQrPayloadDecodeError.InvalidField(field)
    else -> null
}

private fun decodeBase64UrlField(value: String, field: String): Result<ByteArray> =
    Base64UrlNoPadding.decode(value).fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(PairingQrPayloadDecodeError.InvalidField(field)) },
    )

private fun ByteArray.toBase64Url(): String = Base64UrlNoPadding.encode(this)

private object Base64UrlNoPadding {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index + 3 <= bytes.size) {
            appendTriple(output, bytes[index].toInt() and 0xff, bytes[index + 1].toInt() and 0xff, bytes[index + 2].toInt() and 0xff)
            index += 3
        }
        val remaining = bytes.size - index
        if (remaining == 1) {
            val first = bytes[index].toInt() and 0xff
            output.append(ALPHABET[first ushr 2])
            output.append(ALPHABET[(first and 0x03) shl 4])
        } else if (remaining == 2) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes[index + 1].toInt() and 0xff
            output.append(ALPHABET[first ushr 2])
            output.append(ALPHABET[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(ALPHABET[(second and 0x0f) shl 2])
        }
        return output.toString()
    }

    fun decode(value: String): Result<ByteArray> {
        if (value.isEmpty()) return Result.failure(IllegalArgumentException("empty base64url"))
        val firstPadding = value.indexOf('=')
        val dataLength = if (firstPadding >= 0) firstPadding else value.length
        if (firstPadding >= 0 && value.drop(firstPadding).any { it != '=' }) return Result.failure(IllegalArgumentException("bad padding"))
        val padding = value.length - dataLength
        if (padding > 2) return Result.failure(IllegalArgumentException("bad padding"))
        val remainder = dataLength % 4
        if (remainder == 1) return Result.failure(IllegalArgumentException("bad length"))
        val expectedPadding = when (remainder) {
            0 -> 0
            2 -> 2
            3 -> 1
            else -> 0
        }
        if (padding != 0 && padding != expectedPadding) return Result.failure(IllegalArgumentException("bad padding"))
        val output = ArrayList<Byte>((dataLength * 3) / 4)
        var index = 0
        while (index + 4 <= dataLength) {
            val a = decodeChar(value[index]) ?: return Result.failure(IllegalArgumentException("bad char"))
            val b = decodeChar(value[index + 1]) ?: return Result.failure(IllegalArgumentException("bad char"))
            val c = decodeChar(value[index + 2]) ?: return Result.failure(IllegalArgumentException("bad char"))
            val d = decodeChar(value[index + 3]) ?: return Result.failure(IllegalArgumentException("bad char"))
            output += ((a shl 2) or (b ushr 4)).toByte()
            output += (((b and 0x0f) shl 4) or (c ushr 2)).toByte()
            output += (((c and 0x03) shl 6) or d).toByte()
            index += 4
        }
        when (dataLength - index) {
            0 -> Unit
            2 -> {
                val a = decodeChar(value[index]) ?: return Result.failure(IllegalArgumentException("bad char"))
                val b = decodeChar(value[index + 1]) ?: return Result.failure(IllegalArgumentException("bad char"))
                output += ((a shl 2) or (b ushr 4)).toByte()
            }
            3 -> {
                val a = decodeChar(value[index]) ?: return Result.failure(IllegalArgumentException("bad char"))
                val b = decodeChar(value[index + 1]) ?: return Result.failure(IllegalArgumentException("bad char"))
                val c = decodeChar(value[index + 2]) ?: return Result.failure(IllegalArgumentException("bad char"))
                output += ((a shl 2) or (b ushr 4)).toByte()
                output += (((b and 0x0f) shl 4) or (c ushr 2)).toByte()
            }
            else -> return Result.failure(IllegalArgumentException("bad length"))
        }
        return Result.success(output.toByteArray())
    }

    private fun appendTriple(output: StringBuilder, first: Int, second: Int, third: Int) {
        output.append(ALPHABET[first ushr 2])
        output.append(ALPHABET[((first and 0x03) shl 4) or (second ushr 4)])
        output.append(ALPHABET[((second and 0x0f) shl 2) or (third ushr 6)])
        output.append(ALPHABET[third and 0x3f])
    }

    private fun decodeChar(char: Char): Int? = if (char.code < DECODE.size) DECODE[char.code].takeIf { it >= 0 } else null
}

private fun String.percentEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

private fun String.percentDecodeStrict(): Result<String> {
    val bytes = ByteArray(length * 4)
    var byteCount = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%') {
            if (index + 2 >= length) return Result.failure(PairingQrPayloadDecodeError.InvalidField("percentEncoding"))
            val high = this[index + 1].hexDigitToIntOrNull()
            val low = this[index + 2].hexDigitToIntOrNull()
            if (high == null || low == null) return Result.failure(PairingQrPayloadDecodeError.InvalidField("percentEncoding"))
            bytes[byteCount++] = ((high shl 4) or low).toByte()
            index += 3
        } else {
            val encodedChar = char.toString().toByteArray(Charsets.UTF_8)
            encodedChar.copyInto(bytes, destinationOffset = byteCount)
            byteCount += encodedChar.size
            index += 1
        }
    }

    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        Result.success(decoder.decode(ByteBuffer.wrap(bytes, 0, byteCount)).toString())
    } catch (_: CharacterCodingException) {
        Result.failure(PairingQrPayloadDecodeError.InvalidField("percentEncoding"))
    }
}

private fun Char.hexDigitToIntOrNull(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}

private fun checksum(canonicalBody: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBody.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
}
