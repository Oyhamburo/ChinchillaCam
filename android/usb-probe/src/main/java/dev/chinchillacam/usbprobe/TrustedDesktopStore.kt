package dev.chinchillacam.usbprobe

import android.content.Context
import android.content.SharedPreferences

private const val TRUSTED_DESKTOP_STORAGE_VERSION = "trusted-desktops-v1"
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

enum class TrustedDesktopStorageStatus {
    Available,
    Unavailable,
}

class TrustedDesktopStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class InMemoryTrustedDesktopStore : TrustedDesktopStore {
    private val recordsByDesktopId = linkedMapOf<String, TrustedDesktopRecord>()

    override fun save(record: TrustedDesktopRecord) {
        saveTrustedDesktopRecord(recordsByDesktopId, record)
    }

    override fun lookup(desktopId: String): TrustedDesktopRecord? = lookupTrustedDesktopRecord(recordsByDesktopId, desktopId)

    override fun list(): List<TrustedDesktopRecord> = listTrustedDesktopRecords(recordsByDesktopId)

    override fun revoke(desktopId: String, revokedAtEpochSeconds: Long): Boolean = revokeTrustedDesktopRecord(
        recordsByDesktopId,
        desktopId,
        revokedAtEpochSeconds,
    )

    override fun forget(desktopId: String): Boolean {
        validateDesktopId(desktopId)
        return recordsByDesktopId.remove(desktopId) != null
    }

    override fun evaluate(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
    ): TrustedDesktopAuthResult = evaluateTrustedDesktopRecord(
        recordsByDesktopId,
        desktopId,
        presentedTrustMaterialFingerprint,
        nowEpochSeconds,
    )
}

interface SerializedTrustedDesktopStorage {
    fun read(): String?
    fun write(serialized: String)
    fun clear()
}

class SharedPreferencesTrustedDesktopStorage(
    private val sharedPreferences: SharedPreferences,
    private val key: String = TRUSTED_DESKTOPS_SHARED_PREFERENCES_KEY,
) : SerializedTrustedDesktopStorage {
    override fun read(): String? = sharedPreferences.getString(key, null)

    override fun write(serialized: String) {
        val committed = sharedPreferences.edit().putString(key, serialized).commit()
        if (!committed) {
            sharedPreferences.edit().remove(key).commit()
            throw TrustedDesktopStorageException("Trusted desktop storage commit failed")
        }
    }

    override fun clear() {
        if (!sharedPreferences.edit().remove(key).commit()) {
            throw TrustedDesktopStorageException("Trusted desktop storage clear failed")
        }
    }
}

class SingletonTrustedDesktopStoreProvider(
    private val storageFactory: () -> SerializedTrustedDesktopStorage,
) {
    @Volatile private var store: TrustedDesktopStore? = null

    fun get(): TrustedDesktopStore {
        val existing = store
        if (existing != null) return existing
        return synchronized(this) {
            store ?: LocalPersistentTrustedDesktopStore(storageFactory()).also { store = it }
        }
    }
}

object AndroidTrustedDesktopStores {
    @Volatile private var provider: SingletonTrustedDesktopStoreProvider? = null

    fun trustedDesktopStore(context: Context): TrustedDesktopStore {
        val appContext = context.applicationContext ?: context
        val existing = provider
        if (existing != null) return existing.get()
        return synchronized(this) {
            val current = provider ?: SingletonTrustedDesktopStoreProvider {
                SharedPreferencesTrustedDesktopStorage(
                    appContext.getSharedPreferences(TRUSTED_DESKTOPS_SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE),
                )
            }.also { provider = it }
            current.get()
        }
    }
}

private const val TRUSTED_DESKTOPS_SHARED_PREFERENCES_NAME = "trusted_desktops"
private const val TRUSTED_DESKTOPS_SHARED_PREFERENCES_KEY = "records"

class LocalPersistentTrustedDesktopStore(
    private val storage: SerializedTrustedDesktopStorage,
) : TrustedDesktopStore {
    private val recordsByDesktopId: LinkedHashMap<String, TrustedDesktopRecord>
    @get:Synchronized
    var storageStatus: TrustedDesktopStorageStatus = TrustedDesktopStorageStatus.Available
        private set

    init {
        val loaded = runCatching { loadTrustedDesktopRecords(storage.read()) }.getOrElse {
            failClosed()
            null
        }
        if (loaded == null || !loaded.succeeded) {
            failClosed()
            recordsByDesktopId = linkedMapOf()
        } else {
            recordsByDesktopId = loaded.records
        }
    }

    @Synchronized
    override fun save(record: TrustedDesktopRecord) {
        ensureAvailableForMutation()
        val candidate = linkedMapCopyOf(recordsByDesktopId)
        saveTrustedDesktopRecord(candidate, record)
        persist(candidate)
        publish(candidate)
    }

    @Synchronized
    override fun lookup(desktopId: String): TrustedDesktopRecord? {
        validateDesktopId(desktopId)
        if (storageStatus == TrustedDesktopStorageStatus.Unavailable) return null
        return lookupTrustedDesktopRecord(recordsByDesktopId, desktopId)
    }

    @Synchronized
    override fun list(): List<TrustedDesktopRecord> = if (storageStatus == TrustedDesktopStorageStatus.Unavailable) {
        emptyList()
    } else {
        listTrustedDesktopRecords(recordsByDesktopId)
    }

    @Synchronized
    override fun revoke(desktopId: String, revokedAtEpochSeconds: Long): Boolean {
        ensureAvailableForMutation()
        val candidate = linkedMapCopyOf(recordsByDesktopId)
        val changed = revokeTrustedDesktopRecord(candidate, desktopId, revokedAtEpochSeconds)
        if (changed) {
            persist(candidate)
            publish(candidate)
        }
        return changed
    }

    @Synchronized
    override fun forget(desktopId: String): Boolean {
        ensureAvailableForMutation()
        validateDesktopId(desktopId)
        val candidate = linkedMapCopyOf(recordsByDesktopId)
        val changed = candidate.remove(desktopId) != null
        if (changed) {
            persist(candidate)
            publish(candidate)
        }
        return changed
    }

    @Synchronized
    override fun evaluate(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
    ): TrustedDesktopAuthResult {
        validateDesktopId(desktopId)
        validateFingerprint(presentedTrustMaterialFingerprint)
        require(nowEpochSeconds >= 0) { "nowEpochSeconds must be non-negative" }
        if (storageStatus == TrustedDesktopStorageStatus.Unavailable) return TrustedDesktopAuthResult.Unknown
        return evaluateTrustedDesktopRecord(recordsByDesktopId, desktopId, presentedTrustMaterialFingerprint, nowEpochSeconds)
    }

    private fun ensureAvailableForMutation() {
        if (storageStatus == TrustedDesktopStorageStatus.Unavailable) {
            throw TrustedDesktopStorageException("Trusted desktop storage is unavailable")
        }
    }

    private fun persist(recordsToPersist: Map<String, TrustedDesktopRecord>) {
        try {
            if (recordsToPersist.isEmpty()) storage.clear() else storage.write(serializeTrustedDesktopRecords(recordsToPersist.values))
        } catch (exception: Exception) {
            failClosed()
            throw TrustedDesktopStorageException("Trusted desktop storage commit failed", exception)
        }
    }

    private fun publish(candidate: LinkedHashMap<String, TrustedDesktopRecord>) {
        recordsByDesktopId.clear()
        recordsByDesktopId.putAll(candidate)
    }

    private fun failClosed() {
        storageStatus = TrustedDesktopStorageStatus.Unavailable
    }
}

private fun linkedMapCopyOf(recordsByDesktopId: Map<String, TrustedDesktopRecord>): LinkedHashMap<String, TrustedDesktopRecord> =
    recordsByDesktopId.entries.associateTo(linkedMapOf()) { (desktopId, record) -> desktopId to record.defensiveCopy() }

private fun saveTrustedDesktopRecord(recordsByDesktopId: MutableMap<String, TrustedDesktopRecord>, record: TrustedDesktopRecord) {
    record.validate()
    val existing = recordsByDesktopId[record.desktopId]
    val recordToSave = if (existing?.revokedAtEpochSeconds != null && record.revokedAtEpochSeconds == null) {
        record.copy(revokedAtEpochSeconds = existing.revokedAtEpochSeconds)
    } else {
        record
    }
    recordsByDesktopId[record.desktopId] = recordToSave.defensiveCopy()
}

private fun lookupTrustedDesktopRecord(recordsByDesktopId: Map<String, TrustedDesktopRecord>, desktopId: String): TrustedDesktopRecord? {
    validateDesktopId(desktopId)
    return recordsByDesktopId[desktopId]?.defensiveCopy()
}

private fun listTrustedDesktopRecords(recordsByDesktopId: Map<String, TrustedDesktopRecord>): List<TrustedDesktopRecord> =
    recordsByDesktopId.values.map { it.defensiveCopy() }

private fun revokeTrustedDesktopRecord(
    recordsByDesktopId: MutableMap<String, TrustedDesktopRecord>,
    desktopId: String,
    revokedAtEpochSeconds: Long,
): Boolean {
    validateDesktopId(desktopId)
    val current = recordsByDesktopId[desktopId] ?: return false
    val revoked = current.copy(revokedAtEpochSeconds = revokedAtEpochSeconds)
    revoked.validate()
    recordsByDesktopId[desktopId] = revoked.defensiveCopy()
    return true
}

private fun evaluateTrustedDesktopRecord(
    recordsByDesktopId: Map<String, TrustedDesktopRecord>,
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

private data class TrustedDesktopLoadResult(
    val succeeded: Boolean,
    val records: LinkedHashMap<String, TrustedDesktopRecord>,
)

private fun loadTrustedDesktopRecords(serialized: String?): TrustedDesktopLoadResult {
    if (serialized.isNullOrEmpty()) return TrustedDesktopLoadResult(succeeded = true, records = linkedMapOf())
    return runCatching {
        val lines = serialized.lines()
        require(lines.firstOrNull() == TRUSTED_DESKTOP_STORAGE_VERSION)
        val loaded = linkedMapOf<String, TrustedDesktopRecord>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val fields = line.split('\t')
            require(fields.size == 7)
            val record = TrustedDesktopRecord(
                desktopId = decodeTrustedDesktopText(fields[0]),
                desktopName = decodeTrustedDesktopText(fields[1]),
                trustMaterialFingerprint = decodeTrustedDesktopHex(fields[2]),
                createdAtEpochSeconds = fields[3].toLong(),
                lastSeenAtEpochSeconds = fields[4].toLong(),
                expiresAtEpochSeconds = fields[5].toOptionalLong(),
                revokedAtEpochSeconds = fields[6].toOptionalLong(),
            )
            record.validate()
            require(!loaded.containsKey(record.desktopId))
            loaded[record.desktopId] = record.defensiveCopy()
        }
        TrustedDesktopLoadResult(succeeded = true, records = loaded)
    }.getOrDefault(TrustedDesktopLoadResult(succeeded = false, records = linkedMapOf()))
}

private fun serializeTrustedDesktopRecords(records: Collection<TrustedDesktopRecord>): String = buildString {
    append(TRUSTED_DESKTOP_STORAGE_VERSION).append('\n')
    records.forEach { record ->
        record.validate()
        append(encodeTrustedDesktopText(record.desktopId)).append('\t')
        append(encodeTrustedDesktopText(record.desktopName)).append('\t')
        append(encodeTrustedDesktopHex(record.trustMaterialFingerprint)).append('\t')
        append(record.createdAtEpochSeconds).append('\t')
        append(record.lastSeenAtEpochSeconds).append('\t')
        append(record.expiresAtEpochSeconds?.toString().orEmpty()).append('\t')
        append(record.revokedAtEpochSeconds?.toString().orEmpty()).append('\n')
    }
}

private fun String.toOptionalLong(): Long? = if (isEmpty()) null else toLong()

private fun encodeTrustedDesktopHex(value: ByteArray): String = buildString(value.size * 2) {
    value.forEach { byte ->
        append(((byte.toInt() ushr 4) and 0x0F).toString(16).uppercase())
        append((byte.toInt() and 0x0F).toString(16).uppercase())
    }
}

private fun decodeTrustedDesktopHex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

private fun encodeTrustedDesktopText(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xFF
        val safe = unsigned in 'A'.code..'Z'.code ||
            unsigned in 'a'.code..'z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '_'.code || unsigned == '.'.code
        if (safe) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

private fun decodeTrustedDesktopText(value: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current == '%') {
            require(index + 2 < value.length)
            bytes += value.substring(index + 1, index + 3).toInt(16).toByte()
            index += 3
        } else {
            require(current.code <= 0x7F)
            bytes += current.code.toByte()
            index += 1
        }
    }
    return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
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
    expiresAtEpochSeconds?.let { require(it >= createdAtEpochSeconds) { "expiresAtEpochSeconds must not be before createdAtEpochSeconds" } }
    revokedAtEpochSeconds?.let { require(it >= createdAtEpochSeconds) { "revokedAtEpochSeconds must not be before createdAtEpochSeconds" } }
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
