package dev.chinchillacam.usbprobe

private val TRUSTED_DESKTOP_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

data class TrustedDesktopRecord(
    val desktopId: String,
    val desktopName: String,
    val trustMaterialFingerprint: ByteArray,
    val createdAtEpochSeconds: Long,
    val lastSeenAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long? = null,
    val revokedAtEpochSeconds: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedDesktopRecord) return false
        return desktopId == other.desktopId &&
            desktopName == other.desktopName &&
            trustMaterialFingerprint.contentEquals(other.trustMaterialFingerprint) &&
            createdAtEpochSeconds == other.createdAtEpochSeconds &&
            lastSeenAtEpochSeconds == other.lastSeenAtEpochSeconds &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            revokedAtEpochSeconds == other.revokedAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = desktopId.hashCode()
        result = 31 * result + desktopName.hashCode()
        result = 31 * result + trustMaterialFingerprint.contentHashCode()
        result = 31 * result + createdAtEpochSeconds.hashCode()
        result = 31 * result + lastSeenAtEpochSeconds.hashCode()
        result = 31 * result + (expiresAtEpochSeconds?.hashCode() ?: 0)
        result = 31 * result + (revokedAtEpochSeconds?.hashCode() ?: 0)
        return result
    }
}

interface TrustedDesktopStore {
    fun save(record: TrustedDesktopRecord)
    fun lookup(desktopId: String): TrustedDesktopRecord?
    fun list(): List<TrustedDesktopRecord>
    fun revoke(desktopId: String, revokedAtEpochSeconds: Long): Boolean
    fun forget(desktopId: String): Boolean
    fun evaluate(desktopId: String, presentedTrustMaterialFingerprint: ByteArray, nowEpochSeconds: Long): TrustedDesktopAuthResult
}

enum class TrustedDesktopAuthResult {
    Trusted,
    Unknown,
    Revoked,
    Expired,
    FingerprintMismatch,
}

class InMemoryTrustedDesktopStore : TrustedDesktopStore {
    private val recordsByDesktopId = linkedMapOf<String, TrustedDesktopRecord>()

    override fun save(record: TrustedDesktopRecord) {
        record.validate()
        recordsByDesktopId[record.desktopId] = record.defensiveCopy()
    }

    override fun lookup(desktopId: String): TrustedDesktopRecord? {
        validateDesktopId(desktopId)
        return recordsByDesktopId[desktopId]?.defensiveCopy()
    }

    override fun list(): List<TrustedDesktopRecord> = recordsByDesktopId.values.map { it.defensiveCopy() }

    override fun revoke(desktopId: String, revokedAtEpochSeconds: Long): Boolean {
        validateDesktopId(desktopId)
        val current = recordsByDesktopId[desktopId] ?: return false
        val revoked = current.copy(revokedAtEpochSeconds = revokedAtEpochSeconds)
        revoked.validate()
        recordsByDesktopId[desktopId] = revoked.defensiveCopy()
        return true
    }

    override fun forget(desktopId: String): Boolean {
        validateDesktopId(desktopId)
        return recordsByDesktopId.remove(desktopId) != null
    }

    override fun evaluate(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
    ): TrustedDesktopAuthResult {
        validateDesktopId(desktopId)
        validateFingerprint(presentedTrustMaterialFingerprint)
        require(nowEpochSeconds >= 0) { "nowEpochSeconds must be non-negative" }

        val record = recordsByDesktopId[desktopId] ?: return TrustedDesktopAuthResult.Unknown
        return when {
            record.revokedAtEpochSeconds != null -> TrustedDesktopAuthResult.Revoked
            record.expiresAtEpochSeconds != null && record.expiresAtEpochSeconds < nowEpochSeconds -> TrustedDesktopAuthResult.Expired
            !record.trustMaterialFingerprint.contentEquals(presentedTrustMaterialFingerprint) -> TrustedDesktopAuthResult.FingerprintMismatch
            else -> TrustedDesktopAuthResult.Trusted
        }
    }
}

private fun TrustedDesktopRecord.defensiveCopy(): TrustedDesktopRecord = copy(
    trustMaterialFingerprint = trustMaterialFingerprint.copyOf(),
)

private fun TrustedDesktopRecord.validate() {
    validateDesktopId(desktopId)
    validateDesktopName(desktopName)
    validateFingerprint(trustMaterialFingerprint)
    require(createdAtEpochSeconds >= 0) { "createdAtEpochSeconds must be non-negative" }
    require(lastSeenAtEpochSeconds >= createdAtEpochSeconds) { "lastSeenAtEpochSeconds must not be before createdAtEpochSeconds" }
    expiresAtEpochSeconds?.let {
        require(it >= createdAtEpochSeconds) { "expiresAtEpochSeconds must not be before createdAtEpochSeconds" }
    }
    revokedAtEpochSeconds?.let {
        require(it >= createdAtEpochSeconds) { "revokedAtEpochSeconds must not be before createdAtEpochSeconds" }
    }
}

private fun validateDesktopId(value: String) {
    require(value.isNotEmpty()) { "desktopId must not be empty" }
    require(TRUSTED_DESKTOP_ID_PATTERN.matches(value)) { "desktopId contains invalid characters" }
}

private fun validateDesktopName(value: String) {
    require(value.isNotBlank()) { "desktopName must not be blank" }
    require(value.none { it.isISOControl() }) { "desktopName contains control characters" }
}

private fun validateFingerprint(value: ByteArray) {
    require(value.isNotEmpty()) { "trustMaterialFingerprint must not be empty" }
}
