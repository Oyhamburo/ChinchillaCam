package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSelectionPreferenceTest {
    @Test
    fun restoresOnlyPersistedDirectOpenCandidate() {
        val store = InMemoryStringPreferenceStore("1")
        val snapshot = snapshotWithDirectAndPhysical()

        val selected = CameraSelectionPreference(store).restoreSelection(snapshot)

        assertEquals("1", selected)
    }

    @Test
    fun ignoresPersistedPhysicalOnlyChildAndClearsPreference() {
        val store = InMemoryStringPreferenceStore("0-wide")
        val snapshot = snapshotWithDirectAndPhysical()

        val selected = CameraSelectionPreference(store).restoreSelection(snapshot)

        assertNull(selected)
        assertNull(store.value)
    }


    @Test
    fun ignoresStalePersistedCameraIdAndClearsPreference() {
        val store = InMemoryStringPreferenceStore("removed")
        val snapshot = snapshotWithDirectAndPhysical()

        val selected = CameraSelectionPreference(store).restoreSelection(snapshot)

        assertNull(selected)
        assertNull(store.value)
    }

    @Test
    fun savesOnlyDirectOpenCandidateSelections() {
        val store = InMemoryStringPreferenceStore(null)
        val preference = CameraSelectionPreference(store)
        val snapshot = snapshotWithDirectAndPhysical()

        preference.saveSelection(snapshot, "0-wide")
        assertNull(store.value)

        preference.saveSelection(snapshot, "1")
        assertEquals("1", store.value)
    }

    private fun snapshotWithDirectAndPhysical(): CameraCatalogSnapshot = CameraCatalogSnapshot(
        entries = listOf(
            entry("0", CameraIdRole.DirectOpenCandidate),
            entry("1", CameraIdRole.DirectOpenCandidate),
            entry("0-wide", CameraIdRole.PhysicalOnlyChild(parentId = "0")),
        ),
    )

    private fun entry(id: String, role: CameraIdRole): CameraCatalogEntry = CameraCatalogEntry(
        id = id,
        role = role,
        facing = CapabilityState.Unknown("not relevant"),
        outputSizes = CapabilityState.Unknown("not relevant"),
        fpsRanges = CapabilityState.Unknown("not relevant"),
        controls = CapabilityState.Unknown("not relevant"),
    )
}

private class InMemoryStringPreferenceStore(
    var value: String?,
) : StringPreferenceStore {
    override fun get(): String? = value
    override fun put(value: String) {
        this.value = value
    }
    override fun clear() {
        value = null
    }
}
