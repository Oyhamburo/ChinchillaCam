package dev.chinchillacam.usbprobe

import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build

class CameraCapabilityCatalog(
    private val gateway: CameraCapabilityGateway,
) {
    fun snapshot(): CameraCatalogSnapshot {
        val directCandidateIds = gateway.getOpenableCameraIds().distinct().sorted()
        val directCandidateSet = directCandidateIds.toSet()
        val entries = mutableListOf<CameraCatalogEntry>()
        val physicalOnlyChildren = sortedMapOf<String, String>()

        for (cameraId in directCandidateIds) {
            val characteristics = safeCharacteristics(cameraId)
            entries += entryFor(
                cameraId = cameraId,
                role = CameraIdRole.DirectOpenCandidate,
                characteristics = characteristics,
            )
            characteristics?.physicalCameraIds.orEmpty()
                .sorted()
                .filterNot { it in directCandidateSet }
                .forEach { childId -> physicalOnlyChildren.putIfAbsent(childId, cameraId) }
        }

        for ((childId, parentId) in physicalOnlyChildren) {
            entries += entryFor(
                cameraId = childId,
                role = CameraIdRole.PhysicalOnlyChild(parentId),
                characteristics = safeCharacteristics(childId),
            )
        }

        return CameraCatalogSnapshot(entries)
    }

    private fun safeCharacteristics(cameraId: String): CameraCapabilityCharacteristics? = try {
        gateway.getCharacteristics(cameraId)
    } catch (_: SecurityException) {
        null
    } catch (_: CameraAccessException) {
        null
    } catch (_: AndroidCameraAccessFailure) {
        null
    }

    private fun entryFor(
        cameraId: String,
        role: CameraIdRole,
        characteristics: CameraCapabilityCharacteristics?,
    ): CameraCatalogEntry {
        val unavailableReason = "characteristics unavailable for $cameraId"
        return CameraCatalogEntry(
            id = cameraId,
            role = role,
            facing = characteristics?.facing ?: CapabilityState.Unknown(unavailableReason),
            outputSizes = characteristics?.outputSizes ?: CapabilityState.Unknown(unavailableReason),
            fpsRanges = characteristics?.fpsRanges ?: CapabilityState.Unknown(unavailableReason),
            controls = characteristics?.controls ?: CapabilityState.Unknown(unavailableReason),
        )
    }
}

interface CameraCapabilityGateway {
    fun getOpenableCameraIds(): List<String>
    fun getCharacteristics(cameraId: String): CameraCapabilityCharacteristics?
}

data class CameraCapabilityCharacteristics(
    val facing: CapabilityState<CameraFacing>,
    val physicalCameraIds: Set<String> = emptySet(),
    val outputSizes: CapabilityState<List<CameraOutputSize>>,
    val fpsRanges: CapabilityState<List<CameraFpsRange>>,
    val controls: CapabilityState<CameraControlAvailability>,
)

data class CameraCatalogSnapshot(
    val entries: List<CameraCatalogEntry>,
)

data class CameraCatalogEntry(
    val id: String,
    val role: CameraIdRole,
    val facing: CapabilityState<CameraFacing>,
    val outputSizes: CapabilityState<List<CameraOutputSize>>,
    val fpsRanges: CapabilityState<List<CameraFpsRange>>,
    val controls: CapabilityState<CameraControlAvailability>,
)

sealed class CameraIdRole {
    /**
     * True when the ID is listed by CameraManager as directly addressable. This is only an
     * open attempt candidate: openCamera can still fail because of permissions, disconnects,
     * concurrent camera use, or other runtime conditions.
     */
    abstract val canAttemptOpenDirectly: Boolean

    object DirectOpenCandidate : CameraIdRole() {
        override val canAttemptOpenDirectly: Boolean = true
    }

    data class PhysicalOnlyChild(val parentId: String) : CameraIdRole() {
        override val canAttemptOpenDirectly: Boolean = false
    }
}

sealed class CapabilityState<out T> {
    data class Known<T>(val value: T) : CapabilityState<T>()
    data class Unknown(val reason: String) : CapabilityState<Nothing>()
    data class Unavailable(val reason: String) : CapabilityState<Nothing>()
}

enum class CameraFacing {
    Front,
    Back,
    External,
}

data class CameraOutputSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }
}

data class CameraFpsRange(
    val min: Int,
    val max: Int,
) {
    init {
        require(min >= 0) { "min must be non-negative" }
        require(max >= min) { "max must be greater than or equal to min" }
    }
}

data class CameraControlAvailability(
    val autoFocus: Boolean,
    val exposureCompensation: Boolean,
    val zoomRatio: Boolean,
)

class AndroidCameraAccessFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)


enum class AndroidCameraCharacteristicField {
    Facing,
    PhysicalCameraIds,
    OutputSizes,
    FpsRanges,
    Controls,
}

interface AndroidCameraManagerFacade {
    val sdkInt: Int
    fun getCameraIdList(): List<String>
    fun getFacing(cameraId: String): CameraFacing?
    fun getPhysicalCameraIds(cameraId: String): Set<String>
    fun getOutputSizes(cameraId: String): List<CameraOutputSize>?
    fun getFpsRanges(cameraId: String): List<CameraFpsRange>?
    fun getControls(cameraId: String): CameraControlAvailability?
}

class AndroidCameraManagerGateway(
    private val facade: AndroidCameraManagerFacade,
) : CameraCapabilityGateway {
    private var directCandidateIds: Set<String> = emptySet()

    override fun getOpenableCameraIds(): List<String> = try {
        facade.getCameraIdList()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: CameraAccessException) {
        emptyList()
    } catch (_: AndroidCameraAccessFailure) {
        emptyList()
    }.also { ids ->
        directCandidateIds = ids.toSet()
    }

    override fun getCharacteristics(cameraId: String): CameraCapabilityCharacteristics {
        if (cameraId !in directCandidateIds && facade.sdkInt < 29) {
            return unknownCharacteristics("physical camera characteristics require API 29 for $cameraId")
        }

        return CameraCapabilityCharacteristics(
            facing = knownOrUnknown(cameraId, "facing") { facade.getFacing(cameraId) },
            physicalCameraIds = safePhysicalCameraIds(cameraId),
            outputSizes = sizesFor(cameraId),
            fpsRanges = knownOrUnknown(cameraId, "fps ranges") { facade.getFpsRanges(cameraId) },
            controls = knownOrUnknown(cameraId, "controls") { facade.getControls(cameraId) },
        )
    }

    private fun unknownCharacteristics(reason: String): CameraCapabilityCharacteristics = CameraCapabilityCharacteristics(
        facing = CapabilityState.Unknown(reason),
        physicalCameraIds = emptySet(),
        outputSizes = CapabilityState.Unknown(reason),
        fpsRanges = CapabilityState.Unknown(reason),
        controls = CapabilityState.Unknown(reason),
    )

    private fun safePhysicalCameraIds(cameraId: String): Set<String> = try {
        facade.getPhysicalCameraIds(cameraId)
    } catch (_: SecurityException) {
        emptySet()
    } catch (_: CameraAccessException) {
        emptySet()
    } catch (_: AndroidCameraAccessFailure) {
        emptySet()
    }

    private fun sizesFor(cameraId: String): CapabilityState<List<CameraOutputSize>> = try {
        val sizes = facade.getOutputSizes(cameraId)
        when {
            sizes == null -> CapabilityState.Unavailable("output sizes not advertised for $cameraId")
            else -> CapabilityState.Known(sizes)
        }
    } catch (_: SecurityException) {
        CapabilityState.Unknown("output sizes unavailable for $cameraId")
    } catch (_: CameraAccessException) {
        CapabilityState.Unknown("output sizes unavailable for $cameraId")
    } catch (_: AndroidCameraAccessFailure) {
        CapabilityState.Unknown("output sizes unavailable for $cameraId")
    }

    private fun <T> knownOrUnknown(
        cameraId: String,
        label: String,
        read: () -> T?,
    ): CapabilityState<T> = try {
        val value = read()
        if (value == null) {
            CapabilityState.Unknown("$label unavailable for $cameraId")
        } else {
            CapabilityState.Known(value)
        }
    } catch (_: SecurityException) {
        CapabilityState.Unknown("$label unavailable for $cameraId")
    } catch (_: CameraAccessException) {
        CapabilityState.Unknown("$label unavailable for $cameraId")
    } catch (_: AndroidCameraAccessFailure) {
        CapabilityState.Unknown("$label unavailable for $cameraId")
    }
}

class AndroidCameraManagerFacadeImpl(
    private val cameraManager: CameraManager,
    override val sdkInt: Int = Build.VERSION.SDK_INT,
) : AndroidCameraManagerFacade {
    override fun getCameraIdList(): List<String> = cameraManager.cameraIdList.toList()

    override fun getFacing(cameraId: String): CameraFacing? = when (characteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.Front
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.Back
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.External
        else -> null
    }

    override fun getPhysicalCameraIds(cameraId: String): Set<String> = if (sdkInt >= Build.VERSION_CODES.P) {
        characteristics(cameraId).physicalCameraIds
    } else {
        emptySet()
    }

    override fun getOutputSizes(cameraId: String): List<CameraOutputSize>? = streamConfiguration(cameraId)
        ?.getOutputSizes(ImageFormat.YUV_420_888)
        ?.map { CameraOutputSize(width = it.width, height = it.height) }
        ?.sortedWith(compareBy<CameraOutputSize> { it.width }.thenBy { it.height })

    override fun getFpsRanges(cameraId: String): List<CameraFpsRange>? = characteristics(cameraId)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.map { range -> CameraFpsRange(min = range.lower, max = range.upper) }
        ?.sortedWith(compareBy<CameraFpsRange> { it.min }.thenBy { it.max })

    override fun getControls(cameraId: String): CameraControlAvailability? {
        val characteristics = characteristics(cameraId)
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return null
        val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
        val zoomAvailable = if (sdkInt >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.upper > 1.0f } ?: return null
        } else {
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { it > 1.0f } ?: return null
        }
        return CameraControlAvailability(
            autoFocus = afModes.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF },
            exposureCompensation = exposureRange.lower != 0 || exposureRange.upper != 0,
            zoomRatio = zoomAvailable,
        )
    }

    private fun characteristics(cameraId: String): CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

    private fun streamConfiguration(cameraId: String): StreamConfigurationMap? = characteristics(cameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
}

data class CameraCatalogUiState(
    val title: String,
    val summary: String,
    val selectedCameraId: String?,
    val rows: List<CameraCatalogUiRow>,
) {
    fun toDisplayText(): String = buildString {
        appendLine(title)
        appendLine(summary)
        rows.forEach { row ->
            appendLine("- ${row.title}")
            appendLine("  ${row.status}")
        }
    }.trimEnd()
}

data class CameraCatalogUiRow(
    val cameraId: String,
    val title: String,
    val status: String,
    val selectable: Boolean,
    val selected: Boolean,
)

object CameraCatalogUiPlanner {
    fun plan(
        snapshot: CameraCatalogSnapshot,
        requestedSelectionId: String?,
    ): CameraCatalogUiState {
        val directRows = snapshot.entries.filter { it.role is CameraIdRole.DirectOpenCandidate }
        val selectedId = requestedSelectionId
            ?.takeIf { requested -> directRows.any { it.id == requested } }
            ?: directRows.firstOrNull()?.id
        val physicalOnlyCount = snapshot.entries.count { it.role is CameraIdRole.PhysicalOnlyChild }
        val summary = when {
            snapshot.entries.isEmpty() -> "No hay cámaras direccionables para listar todavía."
            directRows.isEmpty() -> "No hay cámaras direccionables; ${physicalOnlyCount} físico no abrible directamente."
            else -> "${directRows.size} ${cameraWord(directRows.size)} seleccionable; $physicalOnlyCount ${physicalWord(physicalOnlyCount)} no abrible directamente."
        }
        return CameraCatalogUiState(
            title = "Cámaras del teléfono",
            summary = summary,
            selectedCameraId = selectedId,
            rows = snapshot.entries.map { entry -> rowFor(entry, selectedId) },
        )
    }

    private fun rowFor(entry: CameraCatalogEntry, selectedId: String?): CameraCatalogUiRow {
        val selectable = entry.role.canAttemptOpenDirectly
        val selected = selectable && entry.id == selectedId
        val titlePrefix = when (val role = entry.role) {
            CameraIdRole.DirectOpenCandidate -> "Cámara ${entry.id}"
            is CameraIdRole.PhysicalOnlyChild -> "Físico ${entry.id} de ${role.parentId}"
        }
        val status = when {
            selected -> "Seleccionada · direccionable por Android; la apertura real se validará después."
            selectable -> "Disponible para seleccionar; la apertura real se validará después."
            else -> "No abrible directamente; se muestra solo como información del grupo lógico."
        }
        return CameraCatalogUiRow(
            cameraId = entry.id,
            title = "$titlePrefix · ${entry.facing.toSpanishLabel()}",
            status = status,
            selectable = selectable,
            selected = selected,
        )
    }

    private fun CapabilityState<CameraFacing>.toSpanishLabel(): String = when (this) {
        is CapabilityState.Known -> when (value) {
            CameraFacing.Front -> "frontal"
            CameraFacing.Back -> "trasera"
            CameraFacing.External -> "externa"
        }
        is CapabilityState.Unknown -> "orientación desconocida"
        is CapabilityState.Unavailable -> "orientación no disponible"
    }

    private fun cameraWord(count: Int): String = if (count == 1) "cámara" else "cámaras"

    private fun physicalWord(count: Int): String = if (count == 1) "físico" else "físicos"
}
