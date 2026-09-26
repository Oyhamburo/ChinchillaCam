package dev.chinchillacam.usbprobe

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

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
    object TamperedChecksum : PairingQrPayloadDecodeError("pairing QR checksum mismatch")
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
            val fields = parseFields(bodyWithChecksum)
            val canonicalBody = canonicalBodyFromRequiredFields(fields).getOrElse { return Result.failure(it) }
            val expectedChecksum = fields["checksum"] ?: return Result.failure(PairingQrPayloadDecodeError.MissingField("checksum"))
            if (expectedChecksum != checksum(canonicalBody)) {
                return Result.failure(PairingQrPayloadDecodeError.TamperedChecksum)
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
            if (expiresAt < nowEpochSeconds) {
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

private fun parseFields(body: String): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    body.split('&').forEach { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) throw IllegalArgumentException("invalid field")
        val key = part.substring(0, separator)
        val value = part.substring(separator + 1).percentDecode()
        if (fields.put(key, value) != null) throw IllegalArgumentException("duplicate field")
    }
    return fields
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

private fun decodeBase64UrlField(value: String, field: String): Result<ByteArray> = try {
    if (value.isEmpty()) Result.failure(PairingQrPayloadDecodeError.InvalidField(field))
    else Result.success(Base64.getUrlDecoder().decode(value))
} catch (_: IllegalArgumentException) {
    Result.failure(PairingQrPayloadDecodeError.InvalidField(field))
}

private fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.percentEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
private fun String.percentDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

private fun checksum(canonicalBody: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBody.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
}
