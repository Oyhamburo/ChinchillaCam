package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCameraIdResolverTest {
    @Test
    fun cameraIdPrefersSavedSelectionThenFirstBackCandidate() {
        val catalog = CameraCatalogSnapshot(
            listOf(
                entry("0", CameraFacing.Front),
                entry("1", CameraFacing.Back),
                entry("2", CameraFacing.Back),
            ),
        )
        val saved = MemoryStringStore("2")

        assertEquals("2", resolver(catalog, saved).resolve())

        saved.clear()
        assertEquals("1", resolver(catalog, saved).resolve())
    }

    @Test
    fun staleSavedIdIsIgnored() {
        val catalog = CameraCatalogSnapshot(
            listOf(
                entry("0", CameraFacing.Front),
                entry("1", CameraFacing.Back),
                entry("4", CameraFacing.Back, CameraIdRole.PhysicalOnlyChild("1")),
            ),
        )

        assertEquals("1", resolver(catalog, MemoryStringStore("9")).resolve())
        assertEquals("1", resolver(catalog, MemoryStringStore("4")).resolve())
    }

    @Test
    fun withoutBackCandidateFallsBackToFirstDirectCandidate() {
        val catalog = CameraCatalogSnapshot(
            listOf(
                entry("0", CameraFacing.Front),
                entry("3", null),
                entry("5", CameraFacing.Back, CameraIdRole.PhysicalOnlyChild("0")),
            ),
        )

        assertEquals("0", resolver(catalog, MemoryStringStore(null)).resolve())
    }

    @Test
    fun noDirectCandidateResolvesNull() {
        val physicalOnly = CameraCatalogSnapshot(listOf(entry("5", CameraFacing.Back, CameraIdRole.PhysicalOnlyChild("0"))))

        assertNull(resolver(physicalOnly, MemoryStringStore("5")).resolve())
        assertNull(resolver(CameraCatalogSnapshot(emptyList()), MemoryStringStore(null)).resolve())
    }

    private fun resolver(catalog: CameraCatalogSnapshot, store: StringPreferenceStore) =
        SessionCameraIdResolver(snapshotProvider = { catalog }, preference = CameraSelectionPreference(store))

    private fun entry(id: String, facing: CameraFacing?, role: CameraIdRole = CameraIdRole.DirectOpenCandidate) = CameraCatalogEntry(
        id = id,
        role = role,
        facing = facing?.let { CapabilityState.Known(it) } ?: CapabilityState.Unknown("no facing"),
        outputSizes = CapabilityState.Unknown("n/a"),
        fpsRanges = CapabilityState.Unknown("n/a"),
        controls = CapabilityState.Unknown("n/a"),
    )

    private class MemoryStringStore(private var value: String?) : StringPreferenceStore {
        override fun get(): String? = value
        override fun put(value: String) {
            this.value = value
        }
        override fun clear() {
            value = null
        }
    }
}
