package dev.chinchillacam.usbprobe

class VisibleCameraPipelineController(
    private val launcher: VisibleCameraPipelineLauncher,
    private val encoderConfig: H264EncoderConfig,
    private val metrics: LocalPipelineMetricsTracker = LocalPipelineMetricsTracker(SystemPipelineMetricsClock()),
    private val encodedVideoSinkFactory: (() -> EncodedVideoEgressSink)? = null,
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
    private var activeEncodedVideoSink: EncodedVideoEgressSink? = null
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
                            return@synchronized setError("No se pudo iniciar el envío de video: ${error.message ?: error::class.java.simpleName}")
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
        val acceptedChunks = mutableListOf<EncodedVideoChunk>()
        for (chunk in chunks) {
            when (val result = sink.write(chunk)) {
                EncodedVideoEgressSinkResult.Accepted -> acceptedChunks += chunk
                EncodedVideoEgressSinkResult.BackpressureExceeded -> return failAfterPartialFakeEgressDelivery(
                    acceptedChunks,
                    sink.stats(),
                    "El envío de video se detuvo por saturación; cámara local detenida.",
                )
                EncodedVideoEgressSinkResult.Closed -> return failAfterPartialFakeEgressDelivery(
                    acceptedChunks,
                    sink.stats(),
                    "El envío de video se detuvo porque el canal se cerró; cámara local detenida.",
                )
                EncodedVideoEgressSinkResult.Oversized -> return failAfterPartialFakeEgressDelivery(
                    acceptedChunks,
                    sink.stats(),
                    "El envío de video se detuvo: un fragmento de video superó el tamaño permitido; cámara local detenida.",
                )
                is EncodedVideoEgressSinkResult.InvalidPayload -> return failAfterPartialFakeEgressDelivery(
                    acceptedChunks,
                    sink.stats(),
                    "El envío de video se detuvo: fragmento de video inválido: ${result.reason}; cámara local detenida.",
                )
                is EncodedVideoEgressSinkResult.Failed -> return failAfterPartialFakeEgressDelivery(
                    acceptedChunks,
                    sink.stats(),
                    "El envío de video se detuvo: ${result.reason}",
                )
            }
        }
        metrics.recordDeliveredChunks(acceptedChunks)
        running.consumeEncoded(chunks.size)
        val stats = sink.stats()
        return setRunning(
            detail = "Cámara local activa. ${stats.accepted} fragmentos de video entregados al canal de salida; ${stats.dropped} descartados.",
            metricsText = fakeEgressMetricsText(stats),
        )
    }

    private fun failAfterPartialFakeEgressDelivery(
        acceptedChunks: List<EncodedVideoChunk>,
        stats: EncodedVideoEgressSinkStats,
        detail: String,
    ): VisibleCameraPipelineUiState {
        metrics.recordDeliveredChunks(acceptedChunks)
        return failAndStop(detail, metricsText = fakeEgressMetricsText(stats))
    }

    private fun failAndStop(
        detail: String,
        metricsText: String = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    ): VisibleCameraPipelineUiState {
        val stoppedHandle = handle
        handle = null
        stoppedHandle?.stop()
        val closeError = closeActiveSink()
        val errorDetail = if (closeError == null) detail else "$detail El canal de salida se cerró con errores: $closeError"
        return setError(errorDetail, metricsText)
    }

    private fun stopWithMessage(successMessage: String): VisibleCameraPipelineUiState {
        startGeneration += 1
        val stoppedHandle = handle
        handle = null
        metrics.reset()
        val closeError = closeActiveSink()
        if (stoppedHandle == null) {
            state = if (closeError == null) stoppedState(successMessage) else stopErrorState(listOf("falló el cierre del canal de salida: $closeError"))
            return state
        }
        state = when (val stopped = stoppedHandle.stop()) {
            CameraEncoderPipelineStopResult.Stopped,
            CameraEncoderPipelineStopResult.AlreadyStopped -> {
                if (closeError == null) stoppedState(successMessage) else stopErrorState(listOf("falló el cierre del canal de salida: $closeError"))
            }
            is CameraEncoderPipelineStopResult.Failed -> {
                val reasons = stopped.reasons + listOfNotNull(closeError?.let { "falló el cierre del canal de salida: $it" })
                stopErrorState(reasons)
            }
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

    private fun closeActiveSink(): String? {
        val sink = activeEncodedVideoSink ?: return null
        activeEncodedVideoSink = null
        return try {
            sink.close()
            null
        } catch (error: RuntimeException) {
            error.message ?: error::class.java.simpleName
        }
    }

    private fun stoppedState(detail: String): VisibleCameraPipelineUiState = VisibleCameraPipelineUiState(
        status = VisibleCameraPipelineStatus.Stopped,
        title = "Cámara local",
        detail = detail,
        primaryAction = "Iniciar cámara local",
        primaryActionEnabled = true,
        metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    )

    private fun stopErrorState(reasons: List<String>): VisibleCameraPipelineUiState = VisibleCameraPipelineUiState(
        status = VisibleCameraPipelineStatus.Error,
        title = "Cámara local",
        detail = "La cámara se detuvo con errores: ${reasons.joinToString()}",
        primaryAction = "Iniciar cámara local",
        primaryActionEnabled = true,
        metricsText = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    )

    private fun fakeEgressMetricsText(stats: EncodedVideoEgressSinkStats): String = listOf(
        "FPS: ${formatFakeEgressMetricValue(metrics.snapshot().encodedFps)}",
        "Chunks aceptados: ${stats.accepted}",
        "Chunks descartados: ${stats.dropped}",
    ).joinToString("\n")

    private fun formatFakeEgressMetricValue(value: MetricValue): String = when (value) {
        MetricValue.Unknown -> "sin muestras aún"
        is MetricValue.Known -> String.format(java.util.Locale.US, "%.1f", value.value)
    }

    private fun setError(
        detail: String,
        metricsText: String = LocalPipelineMetricsFormatter.format(metrics.snapshot()),
    ): VisibleCameraPipelineUiState {
        handle = null
        state = VisibleCameraPipelineUiState(
            status = VisibleCameraPipelineStatus.Error,
            title = "Cámara local",
            detail = detail,
            primaryAction = "Iniciar cámara local",
            primaryActionEnabled = true,
            metricsText = metricsText,
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
