package dev.chinchillacam.usbprobe

sealed interface QualityPreference {
    object Automatic : QualityPreference
    data class Manual(val width: Int, val height: Int, val fps: Int) : QualityPreference
}

class QualityPreferenceStore(private val store: StringPreferenceStore) {
    fun load(): QualityPreference {
        val raw = store.get() ?: return QualityPreference.Automatic
        if (raw == "auto") return QualityPreference.Automatic
        val match = MANUAL_FORMAT.matchEntire(raw) ?: return QualityPreference.Automatic
        val width = match.groupValues[1].toIntOrNull() ?: return QualityPreference.Automatic
        val height = match.groupValues[2].toIntOrNull() ?: return QualityPreference.Automatic
        val fps = match.groupValues[3].toIntOrNull() ?: return QualityPreference.Automatic
        return QualityPreference.Manual(width, height, fps)
    }

    fun save(preference: QualityPreference) {
        store.put(when (preference) {
            QualityPreference.Automatic -> "auto"
            is QualityPreference.Manual -> {
                require(preference.width > 0 && preference.height > 0 && preference.fps > 0)
                "manual:${preference.width}x${preference.height}@${preference.fps}"
            }
        })
    }

    fun clear() = store.clear()

    private companion object {
        val MANUAL_FORMAT = Regex("manual:([1-9][0-9]*)x([1-9][0-9]*)@([1-9][0-9]*)")
    }
}

data class Resolution(val width: Int, val height: Int)

data class QualityOption<T>(val value: T, val enabled: Boolean, val disabledReason: String?)

data class QualityPlan(
    val encoderConfig: H264EncoderConfig,
    val fpsRange: CameraFpsRange?,
    val resolutionOptions: List<QualityOption<Resolution>>,
    val fpsOptions: List<QualityOption<Int>>,
    val appliedPreference: QualityPreference,
    val fallbackReason: String?,
)

object CameraQualityPlanner {
    private val resolutions = listOf(Resolution(1920, 1080), Resolution(1280, 720), Resolution(960, 540), Resolution(640, 480))
    private val frameRates = listOf(30, 24, 15)
    private val defaultResolution = Resolution(1280, 720)
    private const val defaultFps = 30

    fun plan(entry: CameraCatalogEntry?, preference: QualityPreference): QualityPlan {
        val resolutionOptions = resolutions.map { resolution ->
            option(resolution, entry?.outputSizes, "resoluciones") { sizes ->
                sizes.any { it.width == resolution.width && it.height == resolution.height }
            }
        }
        val fpsOptions = frameRates.map { fps ->
            option(fps, entry?.fpsRanges, "FPS") { ranges ->
                ranges.any { it.min <= fps && fps <= it.max }
            }
        }
        val supportedSizes = resolutionOptions.filter { it.enabled }.map { it.value }
        val supportedFps = fpsOptions.filter { it.enabled }.map { it.value }
        val capabilitiesIncomplete = entry == null || entry.outputSizes !is CapabilityState.Known || entry.fpsRanges !is CapabilityState.Known
        val cannotSelect = capabilitiesIncomplete || supportedSizes.isEmpty() || supportedFps.isEmpty()
        val manual = preference as? QualityPreference.Manual
        val manualSupported = manual != null && !cannotSelect &&
            Resolution(manual.width, manual.height) in supportedSizes && manual.fps in supportedFps
        val applied = if (manualSupported) preference else QualityPreference.Automatic
        val resolution = when {
            cannotSelect -> defaultResolution
            manualSupported -> Resolution(manual!!.width, manual.height)
            defaultResolution in supportedSizes && defaultFps in supportedFps -> defaultResolution
            else -> supportedSizes.filter { it.width <= defaultResolution.width && it.height <= defaultResolution.height }
                .maxByOrNull { it.width.toLong() * it.height } ?: supportedSizes.minByOrNull { it.width.toLong() * it.height }!!
        }
        val fps = when {
            cannotSelect -> defaultFps
            manualSupported -> manual!!.fps
            else -> supportedFps.maxOrNull()!!
        }
        val range = if (cannotSelect) null else (entry!!.fpsRanges as CapabilityState.Known).value
            .filter { it.min <= fps && fps <= it.max }
            .minWithOrNull(compareBy<CameraFpsRange> { it.max - it.min }.thenBy { it.min })
        val fallbackReason = when {
            capabilitiesIncomplete -> "No se pudieron confirmar las capacidades de calidad de la cámara."
            cannotSelect -> "La cámara no admite las opciones de calidad disponibles."
            manual != null && !manualSupported -> "La cámara no admite la calidad guardada; se usó el modo automático."
            else -> null
        }
        val bitrate = (2_000_000L * resolution.width * resolution.height * fps / (1280L * 720 * 30))
            .coerceIn(500_000L, 8_000_000L).toInt()
        return QualityPlan(
            encoderConfig = H264EncoderConfig(resolution.width, resolution.height, bitrate, fps, 2),
            fpsRange = range,
            resolutionOptions = resolutionOptions,
            fpsOptions = fpsOptions,
            appliedPreference = applied,
            fallbackReason = fallbackReason,
        )
    }

    private fun <T, V> option(
        value: T,
        state: CapabilityState<V>?,
        label: String,
        supported: (V) -> Boolean,
    ): QualityOption<T> {
        val reason = when (state) {
            is CapabilityState.Known -> if (supported(state.value)) null else "La cámara no admite ${if (label == "FPS") "estos FPS" else "esta resolución"}."
            is CapabilityState.Unavailable -> if (label == "FPS") "La cámara no informó los FPS disponibles." else "La cámara no informó las resoluciones disponibles."
            is CapabilityState.Unknown, null -> if (label == "FPS") "No se pudo leer qué FPS admite la cámara." else "No se pudo leer qué resoluciones admite la cámara."
        }
        return QualityOption(value, reason == null, reason)
    }
}
