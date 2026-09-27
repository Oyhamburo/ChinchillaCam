package dev.chinchillacam.usbprobe

class VisibleCameraPipelineController(
    private val launcher: VisibleCameraPipelineLauncher,
    private val encoderConfig: H264EncoderConfig,
    private val metrics: LocalPipelineMetricsTracker = LocalPipelineMetricsTracker(SystemPipelineMetricsClock()),
    private val encodedVideoSinkFactory: (() -> EncodedVideoSessionFrameSink)? = null,
) {
    private var state: VisibleCameraPipelineUiState = VisibleCameraPipelineUiState(
        status = VisibleCameraPipelineStatus.Idle,
        title = "Cámara local",
        detail = "Lista para iniciar una prueba local visible.",
        primaryAction = "Iniciar cámara local",
        primaryActionEnabled = true,
        metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    )
    private var handle: VisibleCameraPipelineHandle? = null
    private var activeEncodedVideoSink: EncodedVideoSessionFrameSink? = null
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
            metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
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

    fun completeStart(
        token: Int,
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
    ): VisibleCameraPipelineUiState {
        synchronized(this) {
            if (token != startGeneration || state.status != VisibleCameraPipelineStatus.Starting) return state
            metrics.reset()
            if (!cameraPermissionGranted) return setError("Permiso de cámara requerido antes de iniciar.")
            if (selectedCameraId == null || !snapshot.isDirectCandidate(selectedCameraId)) {
                return setError("Selecciona una cámara directa antes de iniciar.")
            }
        }

        val result = launcher.start(snapshot, selectedCameraId, cameraPermissionGranted, encoderConfig)

        return synchronized(this) {
            if (token != startGeneration || state.status != VisibleCameraPipelineStatus.Starting) {
                if (result is VisibleCameraPipelineLaunchResult.Running) {
                    result.handle.stop()
                }
                state
            } else {
                when (result) {
                    is VisibleCameraPipelineLaunchResult.Running -> {
                        val sink = try {
                            encodedVideoSinkFactory?.invoke()
                        } catch (error: RuntimeException) {
                            result.handle.stop()
                            return@synchronized setError("Egreso fake no pudo iniciar: ${error.message ?: error::class.java.simpleName}")
                        }
                        activeEncodedVideoSink = sink
                        handle = result.handle
                        setRunning("Cámara local activa. Video codificado se descarta en memoria; no se transmite ni se graba.")
                    }
                    is VisibleCameraPipelineLaunchResult.Failed -> {
                        handle = null
                        setError(result.reason)
                    }
                }
            }
        }
    }

    @Synchronized
    fun drainOnce(maxOutputs: Int): VisibleCameraPipelineUiState {
        val running = handle ?: return state
        val drained = running.drainEncoded(maxOutputs)
        if (drained !is H264DrainResult.Chunks || activeEncodedVideoSink == null) {
            metrics.recordDrain(drained)
        }
        return when (drained) {
            is H264DrainResult.Chunks -> drainChunks(running, drained.chunks)
            H264DrainResult.TryAgainLater -> setRunning("Cámara local activa. Esperando salida codificada.")
            H264DrainResult.Stopped -> stopWithMessage("La cámara local ya se detuvo.")
            is H264DrainResult.BackpressureExceeded -> failAndStop("Backpressure local excedido; salida codificada detenida.")
            is H264DrainResult.Failed -> failAndStop("Error al drenar encoder: ${drained.reason}")
        }
    }

    @Synchronized
    fun stopFromUser(): VisibleCameraPipelineUiState = stopWithMessage("Cámara local detenida por el usuario.")

    @Synchronized
    fun stopForLifecycle(): VisibleCameraPipelineUiState = stopWithMessage("Cámara local detenida al ocultar la app.")

    private fun drainChunks(running: VisibleCameraPipelineHandle, chunks: List<EncodedVideoChunk>): VisibleCameraPipelineUiState {
        val sink = activeEncodedVideoSink ?: run {
            running.consumeEncoded(chunks.size)
            return setRunning("Cámara local activa. ${chunks.size} chunks codificados descartados en memoria.")
        }
        for (chunk in chunks) {
            when (val result = sink.write(chunk)) {
                is EncodedVideoSessionFrameSinkResult.Accepted -> Unit
                EncodedVideoSessionFrameSinkResult.BackpressureExceeded -> return failAndStop("Egreso fake detenido por backpressure; cámara local detenida.")
                EncodedVideoSessionFrameSinkResult.Closed -> return failAndStop("Egreso fake cerrado; cámara local detenida.")
                EncodedVideoSessionFrameSinkResult.Oversized -> return failAndStop("Egreso fake rechazó chunk H.264 oversized; cámara local detenida.")
                is EncodedVideoSessionFrameSinkResult.InvalidPayload -> return failAndStop("Egreso fake rechazó payload inválido: ${result.reason}; cámara local detenida.")
                is EncodedVideoSessionFrameSinkResult.Failed -> return failAndStop("Egreso fake falló: ${result.reason}")
            }
        }
        running.consumeEncoded(chunks.size)
        val stats = sink.stats()
        return setRunning(
            detail = "Cámara local activa. ${stats.accepted} chunks codificados enviados al egreso fake; ${stats.dropped} descartados.",
            metricsText = fakeEgressMetricsText(stats),
        )
    }

    private fun failAndStop(detail: String): VisibleCameraPipelineUiState {
        val stoppedHandle = handle
        handle = null
        stoppedHandle?.stop()
        closeActiveSink()
        return setError(detail)
    }

    private fun stopWithMessage(successMessage: String): VisibleCameraPipelineUiState {
        startGeneration += 1
        val stoppedHandle = handle
        handle = null
        metrics.reset()
        closeActiveSink()
        if (stoppedHandle == null) {
            state = VisibleCameraPipelineUiState(
                status = VisibleCameraPipelineStatus.Stopped,
                title = "Cámara local",
                detail = successMessage,
                primaryAction = "Iniciar cámara local",
                primaryActionEnabled = true,
                metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
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
                metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
            )
            is CameraEncoderPipelineStopResult.Failed -> VisibleCameraPipelineUiState(
                status = VisibleCameraPipelineStatus.Error,
                title = "Cámara local",
                detail = "La cámara se detuvo con errores: ${stopped.reasons.joinToString()}",
                primaryAction = "Iniciar cámara local",
                primaryActionEnabled = true,
                metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
            )
        }
        return state
    }

    private fun setRunning(
        detail: String,
        metricsText: String = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    ): VisibleCameraPipelineUiState {
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Running,
            title = "Cámara local",
            detail = detail,
            primaryAction = "Detener cámara local",
            primaryActionEnabled = true,
            metricsText = metricsText,
        )
        return state
    }

    private fun closeActiveSink() {
        activeEncodedVideoSink?.close()
        activeEncodedVideoSink = null
    }

    private fun fakeEgressMetricsText(stats: EncodedVideoSessionFrameSinkStats): String = listOf(
        "FPS: no disponible para egreso fake en T15d2",
        "Chunks aceptados: ${stats.accepted}",
        "Chunks descartados: ${stats.dropped}",
    ).joinToString("\n")

    private fun setError(detail: String): VisibleCameraPipelineUiState {
        handle = null
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Error,
            title = "Cámara local",
            detail = detail,
            primaryAction = "Iniciar cámara local",
            primaryActionEnabled = true,
            metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
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
    val metricsText: String = "",
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
