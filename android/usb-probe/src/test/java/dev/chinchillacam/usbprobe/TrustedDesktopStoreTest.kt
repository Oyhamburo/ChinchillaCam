package dev.chinchillacam.usbprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrustedDesktopStoreTest {
    private val baseRecord = TrustedDesktopRecord(
        desktopId = "desktop-01",
        desktopName = "Studio Desktop",
        trustMaterialFingerprint = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        createdAtEpochSeconds = 100L,
        lastSeenAtEpochSeconds = 120L,
        expiresAtEpochSeconds = 1_000L,
        revokedAtEpochSeconds = null,
    )

    @Test
    fun saveAndEvaluateTrustedWithInjectedClock() {
        val store = InMemoryTrustedDesktopStore()

        store.save(baseRecord)

        assertEquals(TrustedDesktopAuthResult.Trusted, store.evaluate("desktop-01", byteArrayOf(0x01, 0x02, 0x03, 0x04), nowEpochSeconds = 500L))
        assertEquals(baseRecord, store.lookup("desktop-01"))
        assertEquals(listOf(baseRecord), store.list())
    }

    @Test
    fun returnsUnknownForMissingDesktop() {
        val store = InMemoryTrustedDesktopStore()

        assertEquals(TrustedDesktopAuthResult.Unknown, store.evaluate("missing", byteArrayOf(0x01), nowEpochSeconds = 1L))
        assertEquals(null, store.lookup("missing"))
    }

    @Test
    fun returnsFingerprintMismatchWhenPresentedFingerprintDiffers() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)

        assertEquals(TrustedDesktopAuthResult.FingerprintMismatch, store.evaluate("desktop-01", byteArrayOf(0x09), nowEpochSeconds = 500L))
    }

    @Test
    fun returnsExpiredWhenTrustIsExpiredAtInjectedClock() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)

        assertEquals(TrustedDesktopAuthResult.Expired, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 1_001L))
    }

    @Test
    fun returnsRevokedAndKeepsRecordAfterRevoke() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)

        assertEquals(true, store.revoke("desktop-01", revokedAtEpochSeconds = 300L))

        val revoked = store.lookup("desktop-01")
        assertEquals(300L, revoked?.revokedAtEpochSeconds)
        assertEquals(TrustedDesktopAuthResult.Revoked, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 400L))
    }

    @Test
    fun revokeReturnsFalseForUnknownAndForgetRemovesTrust() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)

        assertEquals(false, store.revoke("missing", revokedAtEpochSeconds = 300L))
        assertEquals(true, store.forget("desktop-01"))
        assertEquals(false, store.forget("desktop-01"))
        assertEquals(null, store.lookup("desktop-01"))
        assertEquals(TrustedDesktopAuthResult.Unknown, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 400L))
    }

    @Test
    fun upsertReplacesExistingRecord() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)
        val replacement = baseRecord.copy(
            desktopName = "Renamed Desktop",
            trustMaterialFingerprint = byteArrayOf(0x05, 0x06),
            createdAtEpochSeconds = 200L,
            lastSeenAtEpochSeconds = 210L,
            expiresAtEpochSeconds = null,
        )

        store.save(replacement)

        assertEquals(replacement, store.lookup("desktop-01"))
        assertEquals(TrustedDesktopAuthResult.FingerprintMismatch, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 220L))
        assertEquals(TrustedDesktopAuthResult.Trusted, store.evaluate("desktop-01", byteArrayOf(0x05, 0x06), nowEpochSeconds = 220L))
    }

    @Test
    fun returnsDefensiveCopiesForSavedLookupAndListRecords() {
        val store = InMemoryTrustedDesktopStore()
        val sourceFingerprint = byteArrayOf(0x01, 0x02)
        store.save(baseRecord.copy(trustMaterialFingerprint = sourceFingerprint))
        sourceFingerprint[0] = 0x7F

        assertEquals(TrustedDesktopAuthResult.Trusted, store.evaluate("desktop-01", byteArrayOf(0x01, 0x02), nowEpochSeconds = 500L))

        val lookedUp = store.lookup("desktop-01")!!
        lookedUp.trustMaterialFingerprint[0] = 0x7E
        val listed = store.list().single()
        listed.trustMaterialFingerprint[1] = 0x7D

        val freshLookup = store.lookup("desktop-01")!!
        assertNotSame(lookedUp.trustMaterialFingerprint, freshLookup.trustMaterialFingerprint)
        assertArrayEquals(byteArrayOf(0x01, 0x02), freshLookup.trustMaterialFingerprint)
        assertEquals(TrustedDesktopAuthResult.Trusted, store.evaluate("desktop-01", byteArrayOf(0x01, 0x02), nowEpochSeconds = 500L))
    }

    @Test
    fun rejectsInvalidRecordsWithIllegalArgumentErrors() {
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(desktopId = "")) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(desktopId = "bad id")) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(desktopName = " ")) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(desktopName = "bad\nname")) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(trustMaterialFingerprint = byteArrayOf())) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(lastSeenAtEpochSeconds = 99L)) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(expiresAtEpochSeconds = 99L)) }
        assertIllegalArgument { InMemoryTrustedDesktopStore().save(baseRecord.copy(revokedAtEpochSeconds = 99L)) }
    }

    @Test
    fun rejectsInvalidOperationInputs() {
        val store = InMemoryTrustedDesktopStore()
        store.save(baseRecord)

        assertIllegalArgument { store.lookup("") }
        assertIllegalArgument { store.evaluate("desktop-01", byteArrayOf(), nowEpochSeconds = 1L) }
        assertIllegalArgument { store.revoke("desktop-01", revokedAtEpochSeconds = 99L) }
        assertTrue(store.list().isNotEmpty())
    }

    @Test
    fun persistentStoreReloadsSavedRevokedForgottenAndMismatchedRecords() {
        val storage = MutableSerializedTrustedDesktopStorage()
        val store = LocalPersistentTrustedDesktopStore(storage)
        store.save(baseRecord)

        assertEquals(baseRecord, LocalPersistentTrustedDesktopStore(storage).lookup("desktop-01"))
        assertEquals(true, LocalPersistentTrustedDesktopStore(storage).revoke("desktop-01", revokedAtEpochSeconds = 300L))
        assertEquals(TrustedDesktopAuthResult.Revoked, LocalPersistentTrustedDesktopStore(storage).evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
        assertEquals(true, LocalPersistentTrustedDesktopStore(storage).forget("desktop-01"))
        assertEquals(TrustedDesktopAuthResult.Unknown, LocalPersistentTrustedDesktopStore(storage).evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))

        LocalPersistentTrustedDesktopStore(storage).save(baseRecord)
        assertEquals(TrustedDesktopAuthResult.FingerprintMismatch, LocalPersistentTrustedDesktopStore(storage).evaluate("desktop-01", byteArrayOf(0x09), nowEpochSeconds = 500L))
    }

    @Test
    fun persistentStoreFailsClosedForCorruptUnknownVersionAndWriteFailures() {
        val corruptStore = LocalPersistentTrustedDesktopStore(MutableSerializedTrustedDesktopStorage("not trusted data"))
        val unknownVersionStore = LocalPersistentTrustedDesktopStore(MutableSerializedTrustedDesktopStorage("trusted-desktops-v999\n"))
        assertEquals(TrustedDesktopStorageStatus.Unavailable, corruptStore.storageStatus)
        assertEquals(TrustedDesktopStorageStatus.Unavailable, unknownVersionStore.storageStatus)
        assertEquals(TrustedDesktopAuthResult.Unknown, corruptStore.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
        assertEquals(emptyList<TrustedDesktopRecord>(), unknownVersionStore.list())

        val storage = FailingSerializedTrustedDesktopStorage()
        val store = LocalPersistentTrustedDesktopStore(storage)
        assertStorageFailure { store.save(baseRecord) }
        assertEquals(TrustedDesktopStorageStatus.Unavailable, store.storageStatus)
        assertEquals(null, store.lookup("desktop-01"))
        assertEquals(TrustedDesktopAuthResult.Unknown, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
    }

    @Test
    fun persistentStoreSerializesConcurrentSaveAndRevokeWithoutResurrectingTrust() {
        val storage = BlockingSerializedTrustedDesktopStorage()
        val store = LocalPersistentTrustedDesktopStore(storage)
        store.save(baseRecord)
        storage.blockUnrevokedReplacement = true

        val staleSave = Thread {
            store.save(baseRecord.copy(lastSeenAtEpochSeconds = 400L))
        }
        staleSave.start()
        assertTrue(storage.replacementWriteStarted.await(1, TimeUnit.SECONDS))
        val revokeResult = arrayOfNulls<Boolean>(1)
        val revoke = Thread {
            revokeResult[0] = store.revoke("desktop-01", revokedAtEpochSeconds = 300L)
        }
        revoke.start()

        storage.releaseReplacementWrite.countDown()
        staleSave.join(1_000)
        revoke.join(1_000)

        assertEquals(true, revokeResult[0])
        assertEquals(TrustedDesktopAuthResult.Revoked, store.evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
        assertEquals(TrustedDesktopAuthResult.Revoked, LocalPersistentTrustedDesktopStore(storage).evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
    }

    @Test
    fun persistentStoreDoesNotUndoRevocationOnUpsert() {
        val storage = MutableSerializedTrustedDesktopStorage()
        val store = LocalPersistentTrustedDesktopStore(storage)
        store.save(baseRecord)
        store.revoke("desktop-01", revokedAtEpochSeconds = 300L)

        LocalPersistentTrustedDesktopStore(storage).save(baseRecord.copy(lastSeenAtEpochSeconds = 400L))

        assertEquals(TrustedDesktopAuthResult.Revoked, LocalPersistentTrustedDesktopStore(storage).evaluate("desktop-01", baseRecord.trustMaterialFingerprint, nowEpochSeconds = 500L))
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    private fun assertStorageFailure(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is TrustedDesktopStorageException)
    }

    private class MutableSerializedTrustedDesktopStorage(initialValue: String? = null) : SerializedTrustedDesktopStorage {
        private var value = initialValue
        override fun read(): String? = value
        override fun write(serialized: String) { value = serialized }
        override fun clear() { value = null }
    }

    private class FailingSerializedTrustedDesktopStorage : SerializedTrustedDesktopStorage {
        override fun read(): String? = null
        override fun write(serialized: String) { throw Exception("write failed") }
        override fun clear() { throw Exception("clear failed") }
    }

    private class BlockingSerializedTrustedDesktopStorage : SerializedTrustedDesktopStorage {
        @Volatile var blockUnrevokedReplacement = false
        val replacementWriteStarted = CountDownLatch(1)
        val releaseReplacementWrite = CountDownLatch(1)
        @Volatile private var value: String? = null

        override fun read(): String? = value

        override fun write(serialized: String) {
            if (blockUnrevokedReplacement && serialized.contains("\t400\t") && serialized.endsWith("\n")) {
                replacementWriteStarted.countDown()
                assertTrue(releaseReplacementWrite.await(1, TimeUnit.SECONDS))
            }
            value = serialized
        }

        override fun clear() {
            value = null
        }
    }
}
