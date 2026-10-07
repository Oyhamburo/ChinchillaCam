package dev.chinchillacam.usbprobe

import java.util.concurrent.Executor

/** Serial worker owns subscription and preference writes; the session reader only enqueues work. */
class QualityControlHandler(
    private val snapshotProvider: () -> CameraCatalogSnapshot,
    private val cameraSelection: StringPreferenceStore,
    private val qualityStore: QualityPreferenceStore,
    private val apply: (cameraId: String?) -> Unit,
    private val send: (command: String, args: Map<String, String>) -> Boolean,
    private val executor: Executor,
) {
    private var subscribed = false
    private var pendingReply: Pair<Long?, String?>? = null

    fun onCommand(command: String, args: Map<String, String>) {
        if (command != QUALITY_SUBSCRIBE && command != SET_QUALITY) return
        executor.execute {
            val copied = args.toMap()
            // Protocol §4.4 drops over-limit frames without responding or ending the session.
            if (!withinQualityControlLimits(copied)) return@execute
            when (command) {
                QUALITY_SUBSCRIBE -> if (isSubscribe(command, copied)) {
                    subscribed = true
                    state()
                }
                SET_QUALITY -> setQuality(copied)
            }
        }
    }

    /** Called on session transitions; queued with commands to keep subscription worker-confined. */
    fun reset() = executor.execute { subscribed = false; pendingReply = null }

    /** A subscribe may arrive before the launcher publishes the active handle. Retry once it is ready. */
    fun onSessionReady() = executor.execute { pendingReply?.let { state(it.first, it.second) } }

    fun onLocalChange() = executor.execute { if (subscribed) state() }

    private fun setQuality(args: Map<String, String>) {
        val request = parseSetQuality(args)
        if (request is SetQualityRequest.Invalid) return state(request.req, "invalid")
        request as SetQualityRequest.Valid
        val snapshot = snapshotProvider()
        val camera = when (request.camera) {
            null -> cameraSelection.get()
            "auto" -> null
            else -> request.camera
        }
        if (camera != null && snapshot.entries.none { it.id == camera && it.role is CameraIdRole.DirectOpenCandidate }) {
            return state(request.req, "unavailable", snapshot)
        }
        val plan = QualityControlsPlanner.plan(snapshot, camera, qualityStore.load())
        val manual = request.preference as? QualityPreference.Manual
        if (manual != null && (plan.resolutions.none { it.value == Resolution(manual.width, manual.height) && it.enabled } ||
                    plan.frameRates.none { it.value == manual.fps && it.enabled })) {
            return state(request.req, "unsupported", snapshot)
        }
        if (request.camera != null) {
            if (camera == null) cameraSelection.clear()
            else CameraSelectionPreference(cameraSelection).saveSelection(snapshot, camera)
        }
        qualityStore.save(request.preference)
        apply(QualityControlsPlanner.plan(snapshot, camera, request.preference).effectiveCameraId)
        state(request.req, snapshot = snapshot)
    }

    private fun state(req: Long? = null, error: String? = null, snapshot: CameraCatalogSnapshot? = null) {
        if (!subscribed) return
        val current = snapshot ?: snapshotProvider()
        val plan = QualityControlsPlanner.plan(current, cameraSelection.get(), qualityStore.load())
        pendingReply = if (send(QUALITY_STATE, encodeQualityState(plan, req, error))) null else req to error
    }
}
