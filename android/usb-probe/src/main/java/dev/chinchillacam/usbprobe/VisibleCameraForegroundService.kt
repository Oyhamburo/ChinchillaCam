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
    private val pipelineOwner: VisibleCameraForegroundServicePipelineOwner by lazy {
        VisibleCameraForegroundServicePipelineOwner(
            pipeline = ControllerVisibleCameraServicePipeline(
                controller = VisibleCameraPipelineController(
                    launcher = AndroidVisibleCameraPipelineLauncher(
                        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                        handler = cameraCallbackHandler,
                    ),
                    encoderConfig = H264EncoderConfig(width = 1280, height = 720, bitrate = 2_000_000, frameRate = 30, iFrameIntervalSeconds = 2),
                ),
            ),
            drainLoop = ThreadedVisibleCameraServiceDrainLoop(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = VisibleCameraServiceStartRequest.fromIntent(intent)
                VisibleCameraForegroundServiceCommandRunner(
                    owner = pipelineOwner,
                    scheduler = startExecutor,
                    foreground = object : VisibleCameraForegroundStarter {
                        override fun startForegroundForVisibleCamera() {
                            startForeground(NOTIFICATION_ID, buildNotification())
                        }
                    },
                    stopService = { mainHandler.post { stopForegroundAndSelf() } },
                ).handleStart(
                    request = request,
                    cameraPermissionGranted = currentCameraPermissionGranted(),
                    snapshotProvider = { currentCameraCatalogSnapshot() },
                )
            }
            ACTION_STOP -> {
                pipelineOwner.handleStopCommand()
                stopForegroundAndSelf()
            }
            else -> stopSelf(startId)
        }
        return restartMode()
    }

    override fun onDestroy() {
        pipelineOwner.handleDestroy()
        startExecutor.shutdownNow()
        cameraCallbackThread.quitSafely()
        super.onDestroy()
    }

    private fun currentCameraPermissionGranted(): Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        true
    } else {
        checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentCameraCatalogSnapshot(): CameraCatalogSnapshot {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return CameraCapabilityCatalog(AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(cameraManager))).snapshot()
    }

    private fun stopForegroundAndSelf() {
        pipelineOwner.handleStopCommand()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val spec = VisibleCameraForegroundServiceNotificationSpec.default()
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
        fun stopIntent(context: Context): Intent = serviceIntent(context).apply { action = ACTION_STOP }
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
            if (intent?.action != VisibleCameraForegroundService.ACTION_START) return null
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
    Blocked,
    Stopped,
    IgnoredColdRestart,
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
        if (request == null) return VisibleCameraServiceCommandOutcome.IgnoredColdRestart
        if (!request.visibleStartRequested || request.selectedCameraId.isNullOrBlank()) {
            stopService()
            return VisibleCameraServiceCommandOutcome.Blocked
        }
        try {
            foreground.startForegroundForVisibleCamera()
        } catch (exception: RuntimeException) {
            if (!exception.isForegroundStartFailure()) throw exception
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

    private fun RuntimeException.isForegroundStartFailure(): Boolean = this is SecurityException ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this is android.app.ForegroundServiceStartNotAllowedException)
}

interface VisibleCameraServicePipeline {
    fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus
    fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus
    fun stop(): VisibleCameraPipelineStatus
}

interface VisibleCameraServiceDrainLoop {
    fun start(pipeline: VisibleCameraServicePipeline)
    fun stop()

    object Noop : VisibleCameraServiceDrainLoop {
        override fun start(pipeline: VisibleCameraServicePipeline) = Unit
        override fun stop() = Unit
    }
}

class VisibleCameraForegroundServicePipelineOwner(
    private val pipeline: VisibleCameraServicePipeline,
    private val drainLoop: VisibleCameraServiceDrainLoop,
    private val policy: VisibleCameraForegroundServiceCommandPolicy = VisibleCameraForegroundServiceCommandPolicy(),
) {
    val requiresActivityReference: Boolean = false
    private var active: Boolean = false
    private var generation: Int = 0

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
        val snapshot = snapshotProvider()
        if (synchronized(this) { token != generation }) return VisibleCameraServiceCommandOutcome.Stopped
        val decision = policy.planStartCommand(startRequest.visibleStartRequested, cameraPermissionGranted, snapshot, startRequest.selectedCameraId)
        if (decision is VisibleCameraServiceStartDecision.Blocked) {
            synchronized(this) {
                if (token == generation) stopActiveLocked()
            }
            return VisibleCameraServiceCommandOutcome.Blocked
        }

        decision as VisibleCameraServiceStartDecision.Allowed
        val status = pipeline.start(snapshot, decision.cameraId, cameraPermissionGranted)
        val shouldStopLate = synchronized(this) {
            if (token != generation) {
                true
            } else {
                active = status == VisibleCameraPipelineStatus.Running || status == VisibleCameraPipelineStatus.Starting
                if (active) drainLoop.start(pipeline)
                false
            }
        }
        if (shouldStopLate) {
            pipeline.stop()
            return VisibleCameraServiceCommandOutcome.Stopped
        }
        return if (status == VisibleCameraPipelineStatus.Running || status == VisibleCameraPipelineStatus.Starting) {
            VisibleCameraServiceCommandOutcome.Started
        } else {
            VisibleCameraServiceCommandOutcome.Blocked
        }
    }

    fun handleStopCommand(): VisibleCameraServiceCommandOutcome {
        synchronized(this) {
            generation += 1
            stopActiveLocked()
        }
        return VisibleCameraServiceCommandOutcome.Stopped
    }

    fun handleDestroy() {
        synchronized(this) {
            generation += 1
            stopActiveLocked()
        }
    }

    private fun stopActiveLocked() {
        drainLoop.stop()
        pipeline.stop()
        active = false
    }
}

class ControllerVisibleCameraServicePipeline(
    private val controller: VisibleCameraPipelineController,
) : VisibleCameraServicePipeline {
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
        controller.start(snapshot, selectedCameraId, cameraPermissionGranted).status

    override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = controller.drainOnce(maxOutputs).status

    override fun stop(): VisibleCameraPipelineStatus = controller.stopFromUser().status
}

class ThreadedVisibleCameraServiceDrainLoop(
    private val maxOutputs: Int = 4,
    private val intervalMillis: Long = 33L,
) : VisibleCameraServiceDrainLoop {
    @Volatile private var active: Boolean = false

    override fun start(pipeline: VisibleCameraServicePipeline) {
        if (active) return
        active = true
        Thread {
            while (active) {
                val status = pipeline.drainOnce(maxOutputs)
                if (status != VisibleCameraPipelineStatus.Running) active = false
                Thread.sleep(intervalMillis)
            }
        }.start()
    }

    override fun stop() {
        active = false
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
    }
}
