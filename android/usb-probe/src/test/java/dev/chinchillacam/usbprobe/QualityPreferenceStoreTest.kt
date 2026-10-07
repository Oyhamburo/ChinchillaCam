package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityPreferenceStoreTest {
    @Test
    fun round_trip_and_clear() {
        val memory = MemoryQualityStringStore()
        val store = QualityPreferenceStore(memory)
        assertEquals(QualityPreference.Automatic, store.load())

        val manual = QualityPreference.Manual(1280, 720, 30)
        store.save(manual)
        assertEquals("manual:1280x720@30", memory.value)
        assertEquals(manual, store.load())

        store.save(QualityPreference.Automatic)
        assertEquals("auto", memory.value)
        assertEquals(QualityPreference.Automatic, store.load())
        store.clear()
        assertNull(memory.value)
        assertEquals(QualityPreference.Automatic, store.load())
    }

    @Test
    fun corrupt_or_nonpositive_values_load_as_automatic() {
        val memory = MemoryQualityStringStore()
        val store = QualityPreferenceStore(memory)
        for (raw in listOf(
            "", "automatic", "AUTO", " auto", "manual", "manual:1280x720", "manual:1280x720@30junk",
            "manual:-1280x720@30", "manual:1280x-720@30", "manual:1280x720@-30",
            "manual:0x720@30", "manual:1280x0@30", "manual:1280x720@0",
            "manual:1280x720@abc", "manual:999999999999x720@30", "manual:1280x720@999999999999",
        )) {
            memory.value = raw
            assertEquals(raw, QualityPreference.Automatic, store.load())
        }
    }
}

private class MemoryQualityStringStore(var value: String? = null) : StringPreferenceStore {
    override fun get(): String? = value
    override fun put(value: String) { this.value = value }
    override fun clear() { value = null }
}
