package dev.chinchillacam.usbprobe

/**
 * Camera used by a connected session (`android-production-connection.md` §3.6): the saved
 * selection if it is still a direct-open candidate in the current catalog, else the first
 * back-facing direct candidate, else the first direct candidate, else `null` (the launcher then
 * fails the session start visibly). A stale saved id is cleared by [CameraSelectionPreference].
 */
class SessionCameraIdResolver(
    private val snapshotProvider: () -> CameraCatalogSnapshot,
    private val preference: CameraSelectionPreference,
) {
    fun resolve(): String? {
        val snapshot = snapshotProvider()
        preference.restoreSelection(snapshot)?.let { return it }
        val direct = snapshot.entries.filter { it.role is CameraIdRole.DirectOpenCandidate }
        val back = direct.firstOrNull { it.facing == CapabilityState.Known(CameraFacing.Back) }
        return (back ?: direct.firstOrNull())?.id
    }
}
