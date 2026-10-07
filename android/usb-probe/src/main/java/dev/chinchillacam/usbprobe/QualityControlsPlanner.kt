package dev.chinchillacam.usbprobe

/** A null camera id means Android's automatic (back-facing first) direct-camera choice. */
data class CameraChoice(val id: String?, val label: String, val selected: Boolean)
data class QualityChoice<T>(val value: T, val label: String, val enabled: Boolean, val disabledReason: String?)
data class QualityControlsPlan(
    val effectiveCameraId: String?,
    val cameras: List<CameraChoice>,
    val resolutions: List<QualityChoice<Resolution>>,
    val frameRates: List<QualityChoice<Int>>,
    val appliedResolution: Resolution,
    val appliedFps: Int,
    val appliedPreference: QualityPreference,
    val summary: String,
)

object QualityControlsPlanner {
    fun plan(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, preference: QualityPreference): QualityControlsPlan {
        val direct = snapshot.entries.filter { it.role.canAttemptOpenDirectly }
        val requested = selectedCameraId?.takeIf { id -> direct.any { it.id == id } }
        val entry = direct.firstOrNull { it.id == requested } ?: direct.firstOrNull { it.facing == CapabilityState.Known(CameraFacing.Back) }
            ?: direct.firstOrNull()
        val quality = CameraQualityPlanner.plan(entry, preference)
        val incomplete = entry == null || entry.outputSizes !is CapabilityState.Known || entry.fpsRanges !is CapabilityState.Known
        val blockedReason = quality.fallbackReason?.takeIf { incomplete }
        val counts = mutableMapOf<String, Int>()
        val cameras = listOf(CameraChoice(null, "Automático", requested == null)) + direct.map { camera ->
            val label = when (camera.facing) {
                CapabilityState.Known(CameraFacing.Back) -> "Trasera"
                CapabilityState.Known(CameraFacing.Front) -> "Frontal"
                CapabilityState.Known(CameraFacing.External) -> "Externa"
                else -> "Cámara"
            }
            val index = (counts[label] ?: 0) + 1
            counts[label] = index
            CameraChoice(camera.id, "$label $index", requested == camera.id)
        }
        val resolutions = quality.resolutionOptions.map { option ->
            QualityChoice(option.value, "${option.value.width} × ${option.value.height}", option.enabled && blockedReason == null,
                blockedReason ?: option.disabledReason)
        }
        val rates = quality.fpsOptions.map { option ->
            QualityChoice(option.value, "${option.value} FPS", option.enabled && blockedReason == null,
                blockedReason ?: option.disabledReason)
        }
        val config = quality.encoderConfig
        val label = if (quality.appliedPreference == QualityPreference.Automatic) "Automático" else "Manual"
        val summary = "Calidad: $label (${config.width} × ${config.height}, ${config.frameRate} FPS)" +
            (quality.fallbackReason?.let { " · $it" } ?: "")
        return QualityControlsPlan(entry?.id, cameras, resolutions, rates, Resolution(config.width, config.height), config.frameRate,
            quality.appliedPreference, summary)
    }

    /** Choose a supported dimension; preserve the other applied dimension when possible. */
    fun selectResolution(plan: QualityControlsPlan, resolution: Resolution): QualityPreference.Manual? {
        if (plan.resolutions.none { it.value == resolution && it.enabled }) return null
        val fps = plan.appliedFps.takeIf { current -> plan.frameRates.any { it.value == current && it.enabled } }
            ?: plan.frameRates.filter { it.enabled }.maxOfOrNull { it.value } ?: return null
        return QualityPreference.Manual(resolution.width, resolution.height, fps)
    }

    fun selectFps(plan: QualityControlsPlan, fps: Int): QualityPreference.Manual? {
        if (plan.frameRates.none { it.value == fps && it.enabled }) return null
        val resolution = plan.appliedResolution.takeIf { current -> plan.resolutions.any { it.value == current && it.enabled } }
            ?: plan.resolutions.filter { it.enabled }.maxByOrNull { it.value.width.toLong() * it.value.height }?.value ?: return null
        return QualityPreference.Manual(resolution.width, resolution.height, fps)
    }
}
