package dev.chinchillacam.usbprobe

import android.content.Context
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Port over the camera foreground service started in session mode. */
interface CameraServiceControl {
    fun start(cameraId: String)
    fun reconfigure(cameraId: String?)
    fun stop()
}

/** Starts and stops [VisibleCameraForegroundService] in session mode. */
class AndroidCameraServiceControl(context: Context) : CameraServiceControl {
    private val context: Context = context.applicationContext

    override fun start(cameraId: String) {
        val intent = VisibleCameraForegroundService.sessionStartIntent(context, cameraId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    override fun reconfigure(cameraId: String?) {
        context.startService(VisibleCameraForegroundService.reconfigureIntent(context, cameraId))
    }

    override fun stop() {
        context.stopService(VisibleCameraForegroundService.serviceIntent(context))
    }
}

/**
 * Production [SessionLauncher] (`android-production-connection.md` §4.3, §4.4): starts the session
 * composition over the authenticated channel, publishes its sink factory in [registry] for the
 * session-mode service and starts the camera. The session ends on its own when the composition
 * reports a session error (camera stopped, same message it published) or when the service stops
 * without [ActiveSessionHandle.close] (camera error, stop from the notification).
 */
class ServiceSessionLauncher(
    private val cameraService: CameraServiceControl,
    private val cameraIdProvider: () -> String?,
    private val endExecutor: Executor,
    private val onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
    private val registry: ActiveSessionSlot = ActiveSessionRegistry,
    private val config: SessionRuntimeConfig = SessionRuntimeConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : SessionLauncher {
    override fun launch(reconnected: UsbTrustedReconnectResult.Reconnected, onEnded: (SessionEndNotice) -> Unit): ActiveSessionHandle {
        val cameraId = checkNotNull(cameraIdProvider()) { "no camera available for the session" }
        val session = ServiceSession(cameraService, registry)
        val composition = SessionEgressServicePipelineComposition.start(
            reconnected = reconnected,
            requestPipelineFailureStop = { message, cause ->
                session.stopCamera()
                onEnded(SessionEndNotice(message, cause))
            },
            endExecutor = endExecutor,
            onCameraControlCommand = onCameraControlCommand,
            config = config,
            clock = clock,
        )
        session.composition = composition
        try {
            session.token = registry.register(
                ActiveSessionEntry(composition.encodedVideoSinkFactory) { message ->
                    val status = VisibleCameraServiceStatusStore.snapshot()
                    onEnded(SessionEndNotice(message ?: SERVICE_STOPPED_MESSAGE,
                        status.cause.takeIf { message != null && status.state == VisibleCameraServiceState.Error && status.message == message }))
                },
            )
            cameraService.start(cameraId)
        } catch (error: Exception) {
            session.close()
            throw error
        }
        return session
    }

    /** Owns one launched session; [close] is idempotent and joins the runtime threads (never on main). */
    private class ServiceSession(
        private val cameraService: CameraServiceControl,
        private val registry: ActiveSessionSlot,
    ) : ActiveSessionHandle {
        private val closed = AtomicBoolean(false)
        @Volatile var composition: SessionEgressServicePipelineComposition? = null
        @Volatile var token: Long? = null

        override fun sendControl(command: String, arguments: Map<String, String>): Boolean =
            !closed.get() && (composition?.sendControl(command, arguments) ?: false)

        /** Clears the entry first, so the service stopping because of this call does not report it back. */
        fun stopCamera() {
            token?.let(registry::clear)
            cameraService.stop()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                stopCamera()
            } finally {
                composition?.close()
            }
        }
    }

    companion object {
        const val SERVICE_STOPPED_MESSAGE = "Se detuvo la cámara."
    }
}
