package dev.chinchillacam.usbprobe

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

    private fun safeCharacteristics(cameraId: String): CameraCapabilityCharacteristics? =
        runCatching { gateway.getCharacteristics(cameraId) }.getOrNull()

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
