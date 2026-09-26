package dev.chinchillacam.usbprobe

class VisibleCameraPipelineController(
    private val launcher: VisibleCameraPipelineLauncher,
    private val encoderConfig: H264EncoderConfig,
) {
    private var state: VisibleCameraPipelineUiState = VisibleCameraPipelineUiState(
        status = VisibleCameraPipelineStatus.Idle,
        title = "Cámara local",
        detail = "Lista para iniciar una prueba local visible.",
        primaryAction = "Iniciar cámara local",
        primaryActionEnabled = true,
    )
    private var handle: VisibleCameraPipelineHandle? = null
    private var startGeneration: Int = 0

    @Synchronized
    fun currentState(): VisibleCameraPipelineUiState = state

    @Synchronized
    fun prepareStart(): Int? {
        if (state.status == VisibleCameraPipelineStatus.Starting || handle != null) return null
        startGeneration += 1
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Starting,
            title = "Cámara local",
            detail = "Iniciando cámara local visible…",
            primaryAction = "Detener cámara local",
            primaryActionEnabled = true,
        )
        return startGeneration
    }

    fun start(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
    ): VisibleCameraPipelineUiState {
        val token = prepareStart() ?: return currentState()
        return completeStart(token, snapshot, selectedCameraId, cameraPermissionGranted)
    }

    @Synchronized
    fun completeStart(
        token: Int,
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
    ): VisibleCameraPipelineUiState {
        if (token != startGeneration || state.status != VisibleCameraPipelineStatus.Starting) return state
        if (!cameraPermissionGranted) return setError("Permiso de cámara requerido antes de iniciar.")
        if (selectedCameraId == null || !snapshot.isDirectCandidate(selectedCameraId)) {
            return setError("Selecciona una cámara directa antes de iniciar.")
        }
        return when (val result = launcher.start(snapshot, selectedCameraId, cameraPermissionGranted, encoderConfig)) {
            is VisibleCameraPipelineLaunchResult.Running -> {
                if (token != startGeneration || state.status != VisibleCameraPipelineStatus.Starting) {
                    result.handle.stop()
                    state
                } else {
                    handle = result.handle
                    setRunning("Cámara local activa. Video codificado se descarta en memoria; no se transmite ni se graba.")
                }
            }
            is VisibleCameraPipelineLaunchResult.Failed -> {
                handle = null
                setError(result.reason)
            }
        }
    }

    @Synchronized
    fun drainOnce(maxOutputs: Int): VisibleCameraPipelineUiState {
        val running = handle ?: return state
        return when (val drained = running.drainEncoded(maxOutputs)) {
            is H264DrainResult.Chunks -> {
                running.consumeEncoded(drained.chunks.size)
                setRunning("Cámara local activa. ${drained.chunks.size} chunks codificados descartados en memoria.")
            }
            H264DrainResult.TryAgainLater -> setRunning("Cámara local activa. Esperando salida codificada.")
            H264DrainResult.Stopped -> stopWithMessage("La cámara local ya se detuvo.")
            is H264DrainResult.BackpressureExceeded -> failAndStop("Backpressure local excedido; salida codificada detenida.")
            is H264DrainResult.Failed -> failAndStop("Error al drenar encoder: ${drained.reason}")
        }
    }

    @Synchronized
    fun stopFromUser(): VisibleCameraPipelineUiState = stopWithMessage("Cámara local detenida por el usuario.")

    @Synchronized
    fun stopForLifecycle(): VisibleCameraPipelineUiState = stopWithMessage("Cámara local detenida al ocultar la app; no continúa con pantalla bloqueada.")

    private fun failAndStop(detail: String): VisibleCameraPipelineUiState {
        val stoppedHandle = handle
        handle = null
        stoppedHandle?.stop()
        return setError(detail)
    }

    private fun stopWithMessage(successMessage: String): VisibleCameraPipelineUiState {
        startGeneration += 1
        val stoppedHandle = handle
        handle = null
        if (stoppedHandle == null) {
            state = VisibleCameraPipelineUiState(
                status = VisibleCameraPipelineStatus.Stopped,
                title = "Cámara local",
                detail = successMessage,
                primaryAction = "Iniciar cámara local",
                primaryActionEnabled = true,
            )
            return state
        }
        state = when (val stopped = stoppedHandle.stop()) {
            CameraEncoderPipelineStopResult.Stopped,
            CameraEncoderPipelineStopResult.AlreadyStopped -> VisibleCameraPipelineUiState(
                status = VisibleCameraPipelineStatus.Stopped,
                title = "Cámara local",
                detail = successMessage,
                primaryAction = "Iniciar cámara local",
                primaryActionEnabled = true,
            )
            is CameraEncoderPipelineStopResult.Failed -> VisibleCameraPipelineUiState(
                status = VisibleCameraPipelineStatus.Error,
                title = "Cámara local",
                detail = "La cámara se detuvo con errores: ${stopped.reasons.joinToString()}",
                primaryAction = "Iniciar cámara local",
                primaryActionEnabled = true,
            )
        }
        return state
    }

    private fun setRunning(detail: String): VisibleCameraPipelineUiState {
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Running,
            title = "Cámara local",
            detail = detail,
            primaryAction = "Detener cámara local",
            primaryActionEnabled = true,
        )
        return state
    }

    private fun setError(detail: String): VisibleCameraPipelineUiState {
        handle = null
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Error,
            title = "Cámara local",
            detail = detail,
            primaryAction = "Iniciar cámara local",
            primaryActionEnabled = true,
        )
        return state
    }

    private fun CameraCatalogSnapshot.isDirectCandidate(cameraId: String): Boolean = entries.any {
        it.id == cameraId && it.role is CameraIdRole.DirectOpenCandidate
    }
}

data class VisibleCameraPipelineUiState(
    val status: VisibleCameraPipelineStatus,
    val title: String,
    val detail: String,
    val primaryAction: String,
    val primaryActionEnabled: Boolean,
)

enum class VisibleCameraPipelineStatus {
    Idle,
    Starting,
    Running,
    Stopped,
    Error,
}

interface VisibleCameraPipelineLauncher {
    fun start(
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
        encoderConfig: H264EncoderConfig,
    ): VisibleCameraPipelineLaunchResult
}

sealed class VisibleCameraPipelineLaunchResult {
    data class Running(val handle: VisibleCameraPipelineHandle) : VisibleCameraPipelineLaunchResult()
    data class Failed(val reason: String) : VisibleCameraPipelineLaunchResult()
}

interface VisibleCameraPipelineHandle {
    fun drainEncoded(maxOutputs: Int): H264DrainResult
    fun consumeEncoded(count: Int)
    fun stop(): CameraEncoderPipelineStopResult
}

class CameraEncoderPipelineVisibleHandle(
    private val session: CameraEncoderPipelineSession,
) : VisibleCameraPipelineHandle {
    override fun drainEncoded(maxOutputs: Int): H264DrainResult = session.drainEncoded(maxOutputs)
    override fun consumeEncoded(count: Int) = session.consumeEncoded(count)
    override fun stop(): CameraEncoderPipelineStopResult = session.stop()
}
