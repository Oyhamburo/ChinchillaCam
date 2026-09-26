package dev.chinchillacam.usbprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class VisibleCameraForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground(NOTIFICATION_ID, buildNotification())
            ACTION_STOP -> stopForegroundAndSelf()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundAndSelf() {
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

        fun serviceIntent(context: Context): Intent = Intent(context, VisibleCameraForegroundService::class.java)
        fun startIntent(context: Context): Intent = serviceIntent(context).apply { action = ACTION_START }
        fun stopIntent(context: Context): Intent = serviceIntent(context).apply { action = ACTION_STOP }
    }
}

sealed class VisibleCameraForegroundServiceStartPlan {
    data class Allowed(val cameraId: String) : VisibleCameraForegroundServiceStartPlan()
    data class Blocked(val message: String) : VisibleCameraForegroundServiceStartPlan()
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
