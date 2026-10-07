package dev.chinchillacam.usbprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VisibleCameraForegroundService : Service() {
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val cameraCallbackThread: HandlerThread by lazy {
        HandlerThread(VisibleCameraForegroundServiceCallbackThreadSpec.serviceDefault().threadName).also { it.start() }
    }
    private val cameraCallbackHandler: Handler by lazy { Handler(cameraCallbackThread.looper) }
    private val startExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "visible-camera-service-start") } }
    // Main-thread confined. [sessionToken] is set while the pipeline runs for an ActiveSessionRegistry entry.
    private var pipelineOwner: VisibleCameraForegroundServicePipelineOwner? = null
    private var sessionToken: Long? = null

    private fun newPipelineOwner(sinkFactory: (() -> EncodedVideoEgressSink)?) = VisibleCameraForegroundServicePipelineOwner(
        pipeline = ControllerVisibleCameraServicePipeline(
            controller = VisibleCameraPipelineController(
                launcher = AndroidVisibleCameraPipelineLauncher(
                    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                    handler = cameraCallbackHandler,
                ),
                encoderConfig = CameraQualityPlanner.plan(null, QualityPreference.Automatic).encoderConfig,
                encodedVideoSinkFactory = sinkFactory,
            ),
        ),
        drainLoop = ThreadedVisibleCameraServiceDrainLoop(),
        qualityPlanResolver = { cameraId ->
            val preferences = getSharedPreferences("dev.chinchillacam.usbprobe.camera", Context.MODE_PRIVATE)
            val preference = QualityPreferenceStore(SharedPreferencesStringStore(preferences, "quality_preference")).load()
            val entry = currentCameraCatalogSnapshot().entries.firstOrNull { it.id == cameraId }
            CameraQualityPlanner.plan(entry, preference)
        },
        onPipelineFailureStop = { mainHandler.post { stopForegroundAndSelfPreservingStatus() } },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_START_SESSION -> {
                val sessionMode = intent.action == ACTION_START_SESSION
                when (val resolution = ServicePipelineSinkResolver.resolve(sessionMode, ActiveSessionRegistry)) {
                    is ServicePipelineSinkResolution.NoSession -> {
                        VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, message = resolution.message))
                        stopForegroundAndSelfPreservingStatus()
                    }
                    ServicePipelineSinkResolution.Diagnostic -> startPipeline(intent, ownerFor(null), VisibleCameraForegroundServiceNotificationSpec.default())
                    is ServicePipelineSinkResolution.Session -> startPipeline(intent, ownerFor(resolution), VisibleCameraForegroundServiceNotificationSpec.session())
                }
            }
            ACTION_RECONFIGURE -> {
                val owner = pipelineOwner
                if (owner == null) {
                    // A cold start by this intent must never open a camera or become a foreground service.
                    stopSelf(startId)
                } else if (sessionToken != null) {
                    commandRunner(owner, VisibleCameraForegroundServiceNotificationSpec.session()).handleReconfigure(
                        intent.getStringExtra(EXTRA_SELECTED_CAMERA_ID),
                        currentCameraPermissionGranted(),
                        snapshotProvider = { currentCameraCatalogSnapshot() },
                    )
                }
            }
            ACTION_STOP -> {
                VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopping, message = "Deteniendo servicio visible de cámara local."))
                pipelineOwner?.handleStopCommand()
                VisibleCameraServiceStatusStore.clearStopped()
                stopForegroundAndSelf()
            }
            else -> {
                VisibleCameraServiceStatusStore.clearStopped()
                stopSelf(startId)
            }
        }
        return restartMode()
    }

    override fun onDestroy() {
        val errorMessage = currentErrorMessage()
        pipelineOwner?.handleDestroy()
        endSessionMode(errorMessage)
        VisibleCameraServiceStatusStore.clearStopped()
        startExecutor.shutdownNow()
        cameraCallbackThread.quitSafely()
        super.onDestroy()
    }

    private fun startPipeline(intent: Intent, owner: VisibleCameraForegroundServicePipelineOwner, notification: VisibleCameraForegroundServiceNotificationSpec) {
        commandRunner(owner, notification).handleStart(
            request = VisibleCameraServiceStartRequest.fromIntent(intent),
            cameraPermissionGranted = currentCameraPermissionGranted(),
            snapshotProvider = { currentCameraCatalogSnapshot() },
        )
    }

    private fun commandRunner(owner: VisibleCameraForegroundServicePipelineOwner, notification: VisibleCameraForegroundServiceNotificationSpec) = VisibleCameraForegroundServiceCommandRunner(
            owner = owner,
            scheduler = startExecutor,
            foreground = object : VisibleCameraForegroundStarter {
                override fun startForegroundForVisibleCamera() {
                    startForeground(NOTIFICATION_ID, buildNotification(notification))
                }
            },
            stopService = {
                mainHandler.post {
                    endSessionMode(currentErrorMessage())
                    stopForegroundAndSelf()
                }
            },
        )

    /** Diagnostic starts keep reusing their owner; any switch of mode or session replaces it with one wired to the new sink. */
    private fun ownerFor(session: ServicePipelineSinkResolution.Session?): VisibleCameraForegroundServicePipelineOwner {
        val previous = pipelineOwner
        if (previous != null && session?.token == sessionToken) return previous
        previous?.handleStopCommand()
        endSessionMode(null)
        sessionToken = session?.token
        return newPipelineOwner(session?.sinkFactory).also { pipelineOwner = it }
    }

    /**
     * Ends the session this service runs for, once. After the launcher's own close the registry no
     * longer holds the token, so the notice is dropped there.
     */
    private fun endSessionMode(message: String?) {
        val token = sessionToken ?: return
        sessionToken = null
        ActiveSessionRegistry.notifyServiceStopped(token, message)
    }

    private fun currentErrorMessage(): String? = VisibleCameraServiceStatusStore.snapshot()
        .takeIf { it.state == VisibleCameraServiceState.Error }?.message

    private fun currentCameraPermissionGranted(): Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true
    } else {
        checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentCameraCatalogSnapshot(): CameraCatalogSnapshot {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return CameraCapabilityCatalog(AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(cameraManager))).snapshot()
    }

    private fun stopForegroundAndSelfPreservingStatus() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun stopForegroundAndSelf() {
        VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopping, message = "Deteniendo servicio visible de cámara local."))
        pipelineOwner?.handleStopCommand()
        VisibleCameraServiceStatusStore.clearStopped()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(spec: VisibleCameraForegroundServiceNotificationSpec): Notification {
        ensureNotificationChannel()
        val stopIntent = Intent(this, VisibleCameraForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(Notification.BigTextStyle().bigText(spec.text))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, spec.stopActionTitle, stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "dev.chinchillacam.usbprobe.action.START_VISIBLE_CAMERA"
        const val ACTION_STOP = "dev.chinchillacam.usbprobe.action.STOP_VISIBLE_CAMERA"
        const val ACTION_RECONFIGURE = "dev.chinchillacam.usbprobe.action.RECONFIGURE_VISIBLE_CAMERA"

        /** Session mode: the pipeline sends video through the [ActiveSessionRegistry] entry. */
        const val ACTION_START_SESSION = "dev.chinchillacam.usbprobe.action.START_SESSION_CAMERA"
        private const val CHANNEL_ID = "visible_camera_local_test"
        private const val CHANNEL_NAME = "Prueba local visible de cámara"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_SELECTED_CAMERA_ID = "dev.chinchillacam.usbprobe.extra.SELECTED_CAMERA_ID"
        const val EXTRA_VISIBLE_START_REQUESTED = "dev.chinchillacam.usbprobe.extra.VISIBLE_START_REQUESTED"

        fun serviceIntent(context: Context): Intent = Intent(context, VisibleCameraForegroundService::class.java)
        fun startIntent(context: Context, selectedCameraId: String): Intent = serviceIntent(context).apply {
            val spec = VisibleCameraForegroundServiceStartIntentSpec.forVisibleStart(selectedCameraId)
            action = spec.action
            putExtra(EXTRA_SELECTED_CAMERA_ID, spec.selectedCameraId)
            putExtra(EXTRA_VISIBLE_START_REQUESTED, spec.visibleStartRequested)
        }
        fun startIntent(selectedCameraId: String): Intent = Intent().apply {
            val spec = VisibleCameraForegroundServiceStartIntentSpec.forVisibleStart(selectedCameraId)
            action = spec.action
            putExtra(EXTRA_SELECTED_CAMERA_ID, spec.selectedCameraId)
            putExtra(EXTRA_VISIBLE_START_REQUESTED, spec.visibleStartRequested)
        }
        fun sessionStartIntent(context: Context, cameraId: String): Intent = startIntent(context, cameraId).apply { action = ACTION_START_SESSION }
        fun stopIntent(context: Context): Intent = serviceIntent(context).apply { action = ACTION_STOP }
        fun reconfigureIntent(context: Context, cameraId: String?): Intent = serviceIntent(context).apply {
            action = ACTION_RECONFIGURE
            cameraId?.let { putExtra(EXTRA_SELECTED_CAMERA_ID, it) }
        }
        fun restartMode(): Int = START_NOT_STICKY
    }
}

data class VisibleCameraForegroundServiceStartIntentSpec(
    val action: String,
    val selectedCameraId: String,
    val visibleStartRequested: Boolean,
) {
    companion object {
        fun forVisibleStart(selectedCameraId: String): VisibleCameraForegroundServiceStartIntentSpec =
            VisibleCameraForegroundServiceStartIntentSpec(
                action = VisibleCameraForegroundService.ACTION_START,
                selectedCameraId = selectedCameraId,
                visibleStartRequested = true,
            )
    }
}

sealed class VisibleCameraForegroundServiceStartPlan {
    data class Allowed(val cameraId: String) : VisibleCameraForegroundServiceStartPlan()
    data class Blocked(val message: String) : VisibleCameraForegroundServiceStartPlan()
}

sealed class VisibleCameraServiceStartDecision {
    data class Allowed(val cameraId: String) : VisibleCameraServiceStartDecision()
    data class Blocked(val message: String) : VisibleCameraServiceStartDecision()
}

data class VisibleCameraServiceStartRequest(
    val selectedCameraId: String?,
    val visibleStartRequested: Boolean,
) {
    companion object {
        fun fromIntent(intent: Intent?): VisibleCameraServiceStartRequest? {
            val action = intent?.action
            if (action != VisibleCameraForegroundService.ACTION_START && action != VisibleCameraForegroundService.ACTION_START_SESSION) return null
            return VisibleCameraServiceStartRequest(
                selectedCameraId = intent.getStringExtra(VisibleCameraForegroundService.EXTRA_SELECTED_CAMERA_ID),
                visibleStartRequested = intent.getBooleanExtra(VisibleCameraForegroundService.EXTRA_VISIBLE_START_REQUESTED, false),
            )
        }
    }
}

class VisibleCameraForegroundServiceCommandPolicy {
    fun planStartCommand(
        visibleStartRequested: Boolean,
        cameraPermissionGranted: Boolean,
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
    ): VisibleCameraServiceStartDecision = when (val plan = VisibleCameraForegroundServicePlanner.planStart(
        activityVisible = visibleStartRequested,
        cameraPermissionGranted = cameraPermissionGranted,
        snapshot = snapshot,
        selectedCameraId = selectedCameraId?.takeIf { it.isNotBlank() },
    )) {
        is VisibleCameraForegroundServiceStartPlan.Allowed -> VisibleCameraServiceStartDecision.Allowed(plan.cameraId)
        is VisibleCameraForegroundServiceStartPlan.Blocked -> VisibleCameraServiceStartDecision.Blocked(plan.message)
    }
}

enum class VisibleCameraServiceCommandOutcome {
    Started,
    Reconfigured,
    IgnoredNoActivePipeline,
    Blocked,
    Stopped,
    IgnoredColdRestart,
}

enum class VisibleCameraServiceState {
    Idle,
    Starting,
    Running,
    Stopping,
    Stopped,
    Error,
}

data class VisibleCameraServiceStatus(
    val state: VisibleCameraServiceState,
    val selectedCameraId: String? = null,
    val message: String = "",
    val cause: FailureCause? = null,
) {
    val isActive: Boolean = state == VisibleCameraServiceState.Starting || state == VisibleCameraServiceState.Running
}

object VisibleCameraServiceStatusStore {
    val exposesActivityReference: Boolean = false
    private val lock = Any()
    private var current: VisibleCameraServiceStatus = VisibleCameraServiceStatus(
        state = VisibleCameraServiceState.Idle,
        message = "Servicio visible inactivo.",
    )

    fun snapshot(): VisibleCameraServiceStatus = synchronized(lock) { current }

    fun publish(status: VisibleCameraServiceStatus) {
        synchronized(lock) { current = status }
    }

    fun clearStopped(message: String = "Servicio visible detenido.") {
        publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopped, message = message))
    }
}

data class VisibleCameraServiceActivityUiState(
    val title: String,
    val detail: String,
    val primaryAction: String,
    val primaryActionEnabled: Boolean,
)

interface VisibleCameraServiceActivityStarter {
    fun startVisibleCameraService(selectedCameraId: String)
    fun stopVisibleCameraService()
}

sealed class VisibleCameraServiceActivityAction {
    abstract fun apply(starter: VisibleCameraServiceActivityStarter, selectedCameraId: String?)

    object Start : VisibleCameraServiceActivityAction() {
        override fun apply(starter: VisibleCameraServiceActivityStarter, selectedCameraId: String?) {
            selectedCameraId?.let { starter.startVisibleCameraService(it) }
        }
    }

    object Stop : VisibleCameraServiceActivityAction() {
        override fun apply(starter: VisibleCameraServiceActivityStarter, selectedCameraId: String?) {
            starter.stopVisibleCameraService()
        }
    }
}

object VisibleCameraServiceActivityBindingPolicy {
    fun shouldRenderServiceStatus(status: VisibleCameraServiceStatus): Boolean = when (status.state) {
        VisibleCameraServiceState.Starting,
        VisibleCameraServiceState.Running,
        VisibleCameraServiceState.Stopping,
        VisibleCameraServiceState.Error -> true
        VisibleCameraServiceState.Idle,
        VisibleCameraServiceState.Stopped -> false
    }

    fun serviceOwnershipRequested(status: VisibleCameraServiceStatus): Boolean = when (status.state) {
        VisibleCameraServiceState.Starting,
        VisibleCameraServiceState.Running,
        VisibleCameraServiceState.Stopping -> true
        VisibleCameraServiceState.Idle,
        VisibleCameraServiceState.Stopped,
        VisibleCameraServiceState.Error -> false
    }

    fun render(status: VisibleCameraServiceStatus): VisibleCameraServiceActivityUiState = when (status.state) {
        VisibleCameraServiceState.Starting -> VisibleCameraServiceActivityUiState(
            title = "Cámara local",
            detail = status.message.ifBlank { "Iniciando prueba local desde el servicio visible." },
            primaryAction = "Detener cámara local",
            primaryActionEnabled = true,
        )
        VisibleCameraServiceState.Running -> VisibleCameraServiceActivityUiState(
            title = "Cámara local",
            detail = status.message.ifBlank { "Prueba local activa desde el servicio visible." },
            primaryAction = "Detener cámara local",
            primaryActionEnabled = true,
        )
        VisibleCameraServiceState.Stopping -> VisibleCameraServiceActivityUiState(
            title = "Cámara local",
            detail = status.message.ifBlank { "Deteniendo servicio visible de cámara local." },
            primaryAction = "Detener cámara local",
            primaryActionEnabled = false,
        )
        VisibleCameraServiceState.Error -> VisibleCameraServiceActivityUiState(
            title = "Cámara local",
            detail = status.message.ifBlank { "El servicio visible de cámara local informó un error." },
            primaryAction = "Iniciar cámara local",
            primaryActionEnabled = true,
        )
        VisibleCameraServiceState.Idle,
        VisibleCameraServiceState.Stopped -> VisibleCameraServiceActivityUiState(
            title = "Cámara local",
            detail = status.message.ifBlank { "Servicio visible detenido." },
            primaryAction = "Iniciar cámara local",
            primaryActionEnabled = true,
        )
    }

    fun actionForPrimaryClick(status: VisibleCameraServiceStatus): VisibleCameraServiceActivityAction = when (status.state) {
        VisibleCameraServiceState.Starting,
        VisibleCameraServiceState.Running,
        VisibleCameraServiceState.Stopping -> VisibleCameraServiceActivityAction.Stop
        VisibleCameraServiceState.Idle,
        VisibleCameraServiceState.Stopped,
        VisibleCameraServiceState.Error -> VisibleCameraServiceActivityAction.Start
    }
}

object VisibleCameraServiceActivityStopPolicy {
    fun statusAfterStopRequest(stopRequestAccepted: Boolean): VisibleCameraServiceStatus = if (stopRequestAccepted) {
        VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopping, message = "Deteniendo servicio visible de cámara local.")
    } else {
        VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopped, message = "Servicio visible no estaba activo.")
    }
}

interface VisibleCameraForegroundStarter {
    fun startForegroundForVisibleCamera()
}

data class VisibleCameraForegroundServiceCallbackThreadSpec(
    val threadName: String,
    val callbackLooperIsMain: Boolean,
) {
    val isValidForCameraCallbacks: Boolean = !callbackLooperIsMain && threadName != "main"

    companion object {
        fun serviceDefault(): VisibleCameraForegroundServiceCallbackThreadSpec = VisibleCameraForegroundServiceCallbackThreadSpec(
            threadName = "visible-camera-service-camera-callbacks",
            callbackLooperIsMain = false,
        )
    }
}

class VisibleCameraForegroundServiceCommandRunner(
    private val owner: VisibleCameraForegroundServicePipelineOwner,
    private val scheduler: Executor,
    private val foreground: VisibleCameraForegroundStarter,
    private val stopService: () -> Unit,
) {
    fun handleStart(
        request: VisibleCameraServiceStartRequest?,
        cameraPermissionGranted: Boolean,
        snapshot: CameraCatalogSnapshot,
    ): VisibleCameraServiceCommandOutcome = handleStart(request, cameraPermissionGranted) { snapshot }

    fun handleStart(
        request: VisibleCameraServiceStartRequest?,
        cameraPermissionGranted: Boolean,
        snapshotProvider: () -> CameraCatalogSnapshot,
    ): VisibleCameraServiceCommandOutcome {
        if (request == null) {
            VisibleCameraServiceStatusStore.clearStopped("Servicio visible no reinicia cámara sin una acción explícita.")
            return VisibleCameraServiceCommandOutcome.IgnoredColdRestart
        }
        if (!request.visibleStartRequested || request.selectedCameraId.isNullOrBlank()) {
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, message = "La app debe estar visible para iniciar la prueba local de cámara."))
            stopService()
            return VisibleCameraServiceCommandOutcome.Blocked
        }
        try {
            foreground.startForegroundForVisibleCamera()
        } catch (exception: RuntimeException) {
            if (!exception.isForegroundStartFailure()) throw exception
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, selectedCameraId = request.selectedCameraId, message = "Android bloqueó el inicio del servicio visible de cámara."))
            stopService()
            return VisibleCameraServiceCommandOutcome.Blocked
        }
        scheduler.execute {
            val outcome = owner.handleStartCommand(
                request = request,
                cameraPermissionGranted = cameraPermissionGranted,
                snapshotProvider = snapshotProvider,
            )
            if (outcome != VisibleCameraServiceCommandOutcome.Started) stopService()
        }
        return VisibleCameraServiceCommandOutcome.Started
    }

    fun handleReconfigure(
        cameraId: String?,
        cameraPermissionGranted: Boolean,
        snapshotProvider: () -> CameraCatalogSnapshot,
    ) {
        scheduler.execute { owner.handleReconfigureCommand(cameraId, cameraPermissionGranted, snapshotProvider) }
    }

    private fun RuntimeException.isForegroundStartFailure(): Boolean = this is SecurityException ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this is android.app.ForegroundServiceStartNotAllowedException)
}

interface VisibleCameraServicePipeline {
    fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus
    fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean, plan: QualityPlan): VisibleCameraPipelineStatus =
        start(snapshot, selectedCameraId, cameraPermissionGranted)
    fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus
    fun stop(): VisibleCameraPipelineStatus
    fun stopForReconfigure(): VisibleCameraPipelineStatus = stop()
    fun currentDetail(): String = ""
    fun currentCause(): FailureCause? = null
}

interface VisibleCameraServiceDrainLoop {
    fun start(pipeline: VisibleCameraServicePipeline, onPipelineStopped: () -> Unit = {})
    fun stop()

    object Noop : VisibleCameraServiceDrainLoop {
        override fun start(pipeline: VisibleCameraServicePipeline, onPipelineStopped: () -> Unit) = Unit
        override fun stop() = Unit
    }
}

class VisibleCameraForegroundServicePipelineOwner(
    private val pipeline: VisibleCameraServicePipeline,
    private val drainLoop: VisibleCameraServiceDrainLoop,
    private val policy: VisibleCameraForegroundServiceCommandPolicy = VisibleCameraForegroundServiceCommandPolicy(),
    private val onPipelineFailureStop: () -> Unit = {},
    private val qualityPlanResolver: (String) -> QualityPlan = { CameraQualityPlanner.plan(null, QualityPreference.Automatic) },
) {
    val requiresActivityReference: Boolean = false
    private var active: Boolean = false
    private var generation: Int = 0
    private var currentCameraId: String? = null
    private var currentPlan: QualityPlan? = null

    fun handleStartCommand(
        request: VisibleCameraServiceStartRequest?,
        cameraPermissionGranted: Boolean,
        snapshot: CameraCatalogSnapshot,
    ): VisibleCameraServiceCommandOutcome = handleStartCommand(request, cameraPermissionGranted) { snapshot }

    fun handleStartCommand(
        request: VisibleCameraServiceStartRequest?,
        cameraPermissionGranted: Boolean,
        snapshotProvider: () -> CameraCatalogSnapshot,
    ): VisibleCameraServiceCommandOutcome {
        val startRequest = request ?: return VisibleCameraServiceCommandOutcome.IgnoredColdRestart
        val token = synchronized(this) {
            generation += 1
            active = false
            generation
        }
        VisibleCameraServiceStatusStore.publish(
            VisibleCameraServiceStatus(
                state = VisibleCameraServiceState.Starting,
                selectedCameraId = startRequest.selectedCameraId,
                message = "Iniciando prueba local desde el servicio visible.",
            ),
        )
        val snapshot = snapshotProvider()
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        val decision = policy.planStartCommand(startRequest.visibleStartRequested, cameraPermissionGranted, snapshot, startRequest.selectedCameraId)
        if (decision is VisibleCameraServiceStartDecision.Blocked) {
            val cause = when {
                !cameraPermissionGranted -> FailureCause.CameraPermissionDenied
                !startRequest.visibleStartRequested -> null
                else -> FailureCause.CameraUnavailable
            }
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error,
                message = cause?.let(UserFailureCatalog::messageFor) ?: decision.message, cause = cause))
            synchronized(this) {
                if (token == generation) stopActiveLocked()
            }
            return VisibleCameraServiceCommandOutcome.Blocked
        }

        decision as VisibleCameraServiceStartDecision.Allowed
        val plan = try {
            qualityPlanResolver(decision.cameraId)
        } catch (_: RuntimeException) {
            CameraQualityPlanner.plan(null, QualityPreference.Automatic)
        }
        val status = pipeline.start(snapshot, decision.cameraId, cameraPermissionGranted, plan)
        val shouldStopLate = synchronized(this) {
            if (token != generation) {
                true
            } else {
                active = status == VisibleCameraPipelineStatus.Running || status == VisibleCameraPipelineStatus.Starting
                if (active) {
                    currentCameraId = decision.cameraId
                    currentPlan = plan
                    drainLoop.start(pipeline) { handlePipelineStoppedByFailure(token) }
                }
                false
            }
        }
        if (shouldStopLate) {
            pipeline.stop()
            return VisibleCameraServiceCommandOutcome.Stopped
        }
        return if (status == VisibleCameraPipelineStatus.Running || status == VisibleCameraPipelineStatus.Starting) {
            VisibleCameraServiceStatusStore.publish(
                VisibleCameraServiceStatus(
                    state = if (status == VisibleCameraPipelineStatus.Running) VisibleCameraServiceState.Running else VisibleCameraServiceState.Starting,
                    selectedCameraId = decision.cameraId,
                    message = if (status == VisibleCameraPipelineStatus.Running) {
                        "Prueba local activa desde el servicio visible."
                    } else {
                        "Iniciando prueba local desde el servicio visible."
                    },
                ),
            )
            VisibleCameraServiceCommandOutcome.Started
        } else {
            val cause = pipeline.currentCause() ?: FailureCause.CameraOpenFailed
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, selectedCameraId = decision.cameraId, message = UserFailureCatalog.messageFor(cause), cause = cause))
            VisibleCameraServiceCommandOutcome.Blocked
        }
    }

    fun handleReconfigureCommand(
        selectedCameraId: String?,
        cameraPermissionGranted: Boolean,
        snapshotProvider: () -> CameraCatalogSnapshot,
    ): VisibleCameraServiceCommandOutcome {
        val previous = synchronized(this) {
            if (!active || currentCameraId == null || currentPlan == null) return VisibleCameraServiceCommandOutcome.IgnoredNoActivePipeline
            generation += 1
            Triple(generation, currentCameraId!!, currentPlan!!)
        }
        val (token, previousCamera, previousPlan) = previous
        val cameraId = selectedCameraId?.takeIf { it.isNotBlank() } ?: previousCamera
        synchronized(this) {
            if (token != generation) return VisibleCameraServiceCommandOutcome.Stopped
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(VisibleCameraServiceState.Starting, cameraId, "Aplicando la nueva calidad…"))
        }
        val snapshot = try { snapshotProvider() } catch (_: RuntimeException) { null }
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        // A stale/physical selection or revoked permission must not tear down the working stream.
        if (snapshot == null || policy.planStartCommand(true, cameraPermissionGranted, snapshot, cameraId) !is VisibleCameraServiceStartDecision.Allowed) {
            synchronized(this) {
                if (token == generation) VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(VisibleCameraServiceState.Running, previousCamera, "Prueba local activa desde el servicio visible."))
            }
            return VisibleCameraServiceCommandOutcome.Blocked
        }
        val plan = try { qualityPlanResolver(cameraId) } catch (_: RuntimeException) {
            CameraQualityPlanner.plan(null, QualityPreference.Automatic)
        }
        synchronized(this) {
            if (token != generation) return VisibleCameraServiceCommandOutcome.Stopped
            drainLoop.stop()
            // Controller drainOnce and stopForReconfigure are @Synchronized: stop waits for any
            // in-flight chunk to finish. No new drain generation starts until start completes.
            pipeline.stopForReconfigure()
            active = false
        }
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        val started = try { pipeline.start(snapshot, cameraId, cameraPermissionGranted, plan) }
        catch (_: RuntimeException) { VisibleCameraPipelineStatus.Error }
        if (finishReconfigureStart(token, cameraId, plan, started)) return VisibleCameraServiceCommandOutcome.Reconfigured
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        // A failed start may leave a partial handle; clear it before trying the old configuration.
        pipeline.stopForReconfigure()
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        val reverted = try { pipeline.start(snapshot, previousCamera, cameraPermissionGranted, previousPlan) }
        catch (_: RuntimeException) { VisibleCameraPipelineStatus.Error }
        if (finishReconfigureStart(token, previousCamera, previousPlan, reverted)) return VisibleCameraServiceCommandOutcome.Reconfigured
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        handlePipelineStoppedByFailure(token)
        return VisibleCameraServiceCommandOutcome.Blocked
    }

    private fun finishReconfigureStart(token: Int, cameraId: String, plan: QualityPlan, status: VisibleCameraPipelineStatus): Boolean = synchronized(this) {
        if (token != generation) {
            pipeline.stop()
            return@synchronized false
        }
        if (status != VisibleCameraPipelineStatus.Running && status != VisibleCameraPipelineStatus.Starting) return@synchronized false
        active = true
        currentCameraId = cameraId
        currentPlan = plan
        drainLoop.start(pipeline) { handlePipelineStoppedByFailure(token) }
        VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(
            if (status == VisibleCameraPipelineStatus.Running) VisibleCameraServiceState.Running else VisibleCameraServiceState.Starting,
            cameraId,
            "Prueba local activa desde el servicio visible.",
        ))
        true
    }

    fun handleStopCommand(): VisibleCameraServiceCommandOutcome {
        VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopping, message = "Deteniendo servicio visible de cámara local."))
        synchronized(this) {
            generation += 1
            stopActiveLocked()
        }
        VisibleCameraServiceStatusStore.clearStopped()
        return VisibleCameraServiceCommandOutcome.Stopped
    }

    fun handleDestroy() {
        val preserveError = VisibleCameraServiceStatusStore.snapshot().state == VisibleCameraServiceState.Error
        if (!preserveError) {
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Stopping, message = "Deteniendo servicio visible de cámara local."))
        }
        synchronized(this) {
            generation += 1
            stopActiveLocked()
        }
        if (!preserveError) VisibleCameraServiceStatusStore.clearStopped()
    }

    private fun handlePipelineStoppedByFailure(token: Int) {
        synchronized(this) {
            if (token != generation) return
            val cause = pipeline.currentCause()
            val detail = cause?.let(UserFailureCatalog::messageFor)
                ?: pipeline.currentDetail().ifBlank { UserFailureCatalog.messageFor(FailureCause.CaptureFailed) }
            generation += 1
            stopActiveLocked()
            VisibleCameraServiceStatusStore.publish(VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, message = detail, cause = cause))
        }
        onPipelineFailureStop()
    }

    private fun stopActiveLocked() {
        drainLoop.stop()
        pipeline.stop()
        active = false
        currentCameraId = null
        currentPlan = null
    }
}

class ControllerVisibleCameraServicePipeline(
    private val controller: VisibleCameraPipelineController,
) : VisibleCameraServicePipeline {
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
        controller.start(snapshot, selectedCameraId, cameraPermissionGranted).status

    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean, plan: QualityPlan): VisibleCameraPipelineStatus =
        controller.start(snapshot, selectedCameraId, cameraPermissionGranted, CameraStreamConfig(plan.encoderConfig, plan.fpsRange)).status

    override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = controller.drainOnce(maxOutputs).status

    override fun stop(): VisibleCameraPipelineStatus = controller.stopFromUser().status

    override fun stopForReconfigure(): VisibleCameraPipelineStatus = controller.stopForReconfigure().status

    override fun currentDetail(): String = controller.currentState().detail

    override fun currentCause(): FailureCause? = controller.currentState().cause
}

class ThreadedVisibleCameraServiceDrainLoop(
    private val maxOutputs: Int = 4,
    private val intervalMillis: Long = 33L,
) : VisibleCameraServiceDrainLoop {
    private val lock = Any()
    private var nextGeneration: Long = 0L
    private var currentJob: DrainJob? = null

    override fun start(pipeline: VisibleCameraServicePipeline, onPipelineStopped: () -> Unit) {
        val job = synchronized(lock) {
            val activeJob = currentJob
            if (activeJob != null && !activeJob.stopRequested) return
            nextGeneration += 1
            DrainJob(generation = nextGeneration, pipeline = pipeline, onPipelineStopped = onPipelineStopped).also { newJob ->
                newJob.thread = Thread({ runDrainLoop(newJob) }, "visible-camera-service-drain-${newJob.generation}")
                currentJob = newJob
            }
        }
        job.thread.start()
    }

    override fun stop() {
        val jobToStop = synchronized(lock) {
            currentJob.also { job ->
                currentJob = null
                job?.stopRequested = true
            }
        }
        jobToStop?.thread?.interrupt()
    }

    private fun runDrainLoop(job: DrainJob) {
        var stoppedByFailure = false
        try {
            while (isCurrent(job)) {
                val status = try {
                    job.pipeline.drainOnce(maxOutputs)
                } catch (exception: RuntimeException) {
                    stoppedByFailure = isCurrent(job)
                    return
                }
                if (status != VisibleCameraPipelineStatus.Running) {
                    stoppedByFailure = isCurrent(job)
                    return
                }
                if (!isCurrent(job)) return
                try {
                    Thread.sleep(intervalMillis)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (Thread.currentThread().isInterrupted || !isCurrent(job)) return
            }
        } finally {
            finish(job)
            if (stoppedByFailure) job.onPipelineStopped()
        }
    }

    private fun isCurrent(job: DrainJob): Boolean = synchronized(lock) {
        currentJob === job && !job.stopRequested
    }

    private fun finish(job: DrainJob) {
        synchronized(lock) {
            if (currentJob === job) currentJob = null
            job.stopRequested = true
        }
    }

    private class DrainJob(
        val generation: Long,
        val pipeline: VisibleCameraServicePipeline,
        val onPipelineStopped: () -> Unit,
    ) {
        @Volatile var stopRequested: Boolean = false
        lateinit var thread: Thread
    }
}

object VisibleCameraForegroundServicePlanner {
    fun planStart(
        activityVisible: Boolean,
        cameraPermissionGranted: Boolean,
        snapshot: CameraCatalogSnapshot,
        selectedCameraId: String?,
    ): VisibleCameraForegroundServiceStartPlan = when {
        !activityVisible -> VisibleCameraForegroundServiceStartPlan.Blocked("La app debe estar visible para iniciar la prueba local de cámara.")
        !cameraPermissionGranted -> VisibleCameraForegroundServiceStartPlan.Blocked("Permiso de cámara requerido antes de iniciar.")
        selectedCameraId == null || !snapshot.isDirectOpenCandidate(selectedCameraId) -> {
            VisibleCameraForegroundServiceStartPlan.Blocked("Selecciona una cámara directa actual antes de iniciar.")
        }
        else -> VisibleCameraForegroundServiceStartPlan.Allowed(selectedCameraId)
    }

    private fun CameraCatalogSnapshot.isDirectOpenCandidate(cameraId: String): Boolean = entries.any { entry ->
        entry.id == cameraId && entry.role is CameraIdRole.DirectOpenCandidate
    }
}

data class VisibleCameraForegroundServiceNotificationSpec(
    val title: String,
    val text: String,
    val stopActionTitle: String,
) {
    companion object {
        fun default(): VisibleCameraForegroundServiceNotificationSpec = VisibleCameraForegroundServiceNotificationSpec(
            title = "Prueba local visible de cámara",
            text = "Prueba local visible: el video codificado se descarta en memoria; no transmite y no graba.",
            stopActionTitle = "Detener",
        )

        fun session(): VisibleCameraForegroundServiceNotificationSpec = VisibleCameraForegroundServiceNotificationSpec(
            title = "Cámara conectada a la computadora",
            text = "La cámara se envía a la computadora por USB.",
            stopActionTitle = "Detener",
        )
    }
}
