package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisibleCameraForegroundServiceContractTest {
    @Test
    fun serviceStopsItselfWhenThePipelineFails() {
        val source = File("src/main/java/dev/chinchillacam/usbprobe/VisibleCameraForegroundService.kt").readText()
        assertTrue(
            "the production pipeline owner must wire onPipelineFailureStop to stop the service on the main thread",
            Regex("""onPipelineFailureStop\s*=\s*\{\s*mainHandler\.post\s*\{\s*stopForegroundAndSelfPreservingStatus\(\)""").containsMatchIn(source),
        )
    }

    @Test
    fun failureAlertSurvivesForegroundRemovalAndOpensConnectionScreen() {
        val source = File("src/main/java/dev/chinchillacam/usbprobe/VisibleCameraForegroundService.kt").readText()
        val stop = source.substringAfter("private fun stopForegroundAndSelfPreservingStatus()").substringBefore("private fun stopForegroundAndSelf()")
        val notification = source.substringAfter("private fun postFailureNotification(").substringBefore("private fun cancelFailureNotification()")
        assertTrue(stop.contains("VisibleCameraFailureStop("))
        assertTrue(stop.contains("errorNotifier = ::postFailureNotification"))
        assertTrue(notification.contains("Intent(this, ConnectionActivity::class.java)"))
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"))
        assertTrue(notification.contains(".setOngoing(false).setAutoCancel(true)"))
        assertTrue(notification.contains("notify(ERROR_NOTIFICATION_ID, notification)"))
        assertTrue(notification.contains("runCatching {"))
        assertTrue(source.contains("NotificationManager.IMPORTANCE_DEFAULT"))
        assertTrue(source.contains("cancel(ERROR_NOTIFICATION_ID)"))
        assertTrue("external session failures must alert in onDestroy", source.substringAfter("override fun onDestroy()")
            .substringBefore("private fun startPipeline(").contains("errorNotifier = ::postFailureNotification"))
    }

    @Test
    fun sessionModeWiresRegistrySinkAndNotifiesWhenTheServiceStops() {
        val source = File("src/main/java/dev/chinchillacam/usbprobe/VisibleCameraForegroundService.kt").readText()
        assertTrue(
            "session mode must resolve the sink factory from ActiveSessionRegistry",
            Regex("""ServicePipelineSinkResolver\.resolve\([^)]*ActiveSessionRegistry""").containsMatchIn(source),
        )
        assertTrue(
            "the session sink factory must reach the pipeline controller",
            Regex("""encodedVideoSinkFactory\s*=\s*sinkFactory""").containsMatchIn(source),
        )
        assertTrue(
            "a stopping session-mode service must notify the registry",
            Regex("""ActiveSessionRegistry\.notifyServiceStopped\(""").containsMatchIn(source),
        )
        val onDestroy = source.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue("onDestroy must end the session mode", onDestroy.contains("endSessionMode("))
    }

    @Test
    fun reconfigureIntentUsesExistingServiceAndNeverStartsForegroundOnColdIntent() {
        val source = File("src/main/java/dev/chinchillacam/usbprobe/VisibleCameraForegroundService.kt").readText()
        val launcher = File("src/main/java/dev/chinchillacam/usbprobe/ServiceSessionLauncher.kt").readText()
        assertTrue(source.contains("ACTION_RECONFIGURE ->"))
        assertTrue(source.contains("if (owner == null)"))
        assertTrue(source.contains("stopSelf(startId)"))
        assertTrue(source.contains("commandRunner(owner, VisibleCameraForegroundServiceNotificationSpec.session()).handleReconfigure("))
        assertTrue(launcher.contains("context.startService(VisibleCameraForegroundService.reconfigureIntent(context, cameraId))"))
    }

    @Test
    fun manifestDeclaresCameraForegroundServiceContract() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
        assertTrue(manifest.contains("android:name=\".VisibleCameraForegroundService\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"camera\""))
    }

    @Test
    fun startPlannerAllowsOnlyVisiblePermissionGrantedDirectSelection() {
        val plan = VisibleCameraForegroundServicePlanner.planStart(
            activityVisible = true,
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
            selectedCameraId = "camera-1",
        )

        assertEquals(VisibleCameraForegroundServiceStartPlan.Allowed("camera-1"), plan)
    }

    @Test
    fun startPlannerBlocksWhenActivityIsNotVisibleInSpanish() {
        val plan = VisibleCameraForegroundServicePlanner.planStart(
            activityVisible = false,
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
            selectedCameraId = "camera-1",
        )

        assertEquals(VisibleCameraForegroundServiceStartPlan.Blocked("La app debe estar visible para iniciar la prueba local de cámara."), plan)
    }

    @Test
    fun startPlannerBlocksWithoutRuntimeCameraPermissionInSpanish() {
        val plan = VisibleCameraForegroundServicePlanner.planStart(
            activityVisible = true,
            cameraPermissionGranted = false,
            snapshot = sampleSnapshot(),
            selectedCameraId = "camera-1",
        )

        assertEquals(VisibleCameraForegroundServiceStartPlan.Blocked("Permiso de cámara requerido antes de iniciar."), plan)
    }

    @Test
    fun startPlannerBlocksPhysicalOnlyOrStaleSelectionInSpanish() {
        val physical = VisibleCameraForegroundServicePlanner.planStart(
            activityVisible = true,
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
            selectedCameraId = "physical-1",
        )
        val stale = VisibleCameraForegroundServicePlanner.planStart(
            activityVisible = true,
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
            selectedCameraId = "stale",
        )

        assertEquals(VisibleCameraForegroundServiceStartPlan.Blocked("Selecciona una cámara directa actual antes de iniciar."), physical)
        assertEquals(VisibleCameraForegroundServiceStartPlan.Blocked("Selecciona una cámara directa actual antes de iniciar."), stale)
    }

    @Test
    fun notificationSpecIsHonestAboutLocalVisibleDiscardOnly() {
        val spec = VisibleCameraForegroundServiceNotificationSpec.default()

        assertEquals("Prueba local visible de cámara", spec.title)
        assertTrue(spec.text.contains("video codificado se descarta en memoria"))
        assertTrue(spec.text.contains("no transmite"))
        assertTrue(spec.text.contains("no graba"))
        assertFalse(spec.text.contains("pantalla bloqueada", ignoreCase = true))
        assertFalse(spec.text.contains("USB", ignoreCase = true))
        assertFalse(spec.text.contains("Wi-Fi", ignoreCase = true))
        assertFalse(spec.text.contains("red", ignoreCase = true))
    }

    @Test
    fun actionsAreExplicitStartAndStop() {
        assertEquals("dev.chinchillacam.usbprobe.action.START_VISIBLE_CAMERA", VisibleCameraForegroundService.ACTION_START)
        assertEquals("dev.chinchillacam.usbprobe.action.STOP_VISIBLE_CAMERA", VisibleCameraForegroundService.ACTION_STOP)
    }

    @Test
    fun startIntentCarriesSelectedCameraIdAndVisibleStartMarker() {
        val spec = VisibleCameraForegroundServiceStartIntentSpec.forVisibleStart("camera-1")

        assertEquals(VisibleCameraForegroundService.ACTION_START, spec.action)
        assertEquals("camera-1", spec.selectedCameraId)
        assertTrue(spec.visibleStartRequested)
    }

    @Test
    fun commandPolicyRejectsNullEmptyStaleAndPhysicalSelections() {
        val policy = VisibleCameraForegroundServiceCommandPolicy()

        assertEquals(
            VisibleCameraServiceStartDecision.Blocked("Selecciona una cámara directa actual antes de iniciar."),
            policy.planStartCommand(visibleStartRequested = true, cameraPermissionGranted = true, snapshot = sampleSnapshot(), selectedCameraId = null),
        )
        assertEquals(
            VisibleCameraServiceStartDecision.Blocked("Selecciona una cámara directa actual antes de iniciar."),
            policy.planStartCommand(visibleStartRequested = true, cameraPermissionGranted = true, snapshot = sampleSnapshot(), selectedCameraId = ""),
        )
        assertEquals(
            VisibleCameraServiceStartDecision.Blocked("Selecciona una cámara directa actual antes de iniciar."),
            policy.planStartCommand(visibleStartRequested = true, cameraPermissionGranted = true, snapshot = sampleSnapshot(), selectedCameraId = "stale"),
        )
        assertEquals(
            VisibleCameraServiceStartDecision.Blocked("Selecciona una cámara directa actual antes de iniciar."),
            policy.planStartCommand(visibleStartRequested = true, cameraPermissionGranted = true, snapshot = sampleSnapshot(), selectedCameraId = "physical-1"),
        )
    }

    @Test
    fun startNotStickyAndNullIntentDoesNotAutoRestartCamera() {
        val owner = VisibleCameraForegroundServicePipelineOwner(RecordingServicePipeline(), VisibleCameraServiceDrainLoop.Noop)

        assertEquals(VisibleCameraServiceCommandOutcome.IgnoredColdRestart, owner.handleStartCommand(null, cameraPermissionGranted = true, snapshot = sampleSnapshot()))
        assertEquals(android.app.Service.START_NOT_STICKY, VisibleCameraForegroundService.restartMode())
    }

    private class RecordingServicePipeline : VisibleCameraServicePipeline {
        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Running
        override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Running
        override fun stop(): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Stopped
    }

    private fun sampleSnapshot(): CameraCatalogSnapshot = CameraCatalogSnapshot(
        entries = listOf(
            CameraCatalogEntry(
                id = "camera-1",
                role = CameraIdRole.DirectOpenCandidate,
                facing = CapabilityState.Known(CameraFacing.Back),
                outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
                controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)),
            ),
            CameraCatalogEntry(
                id = "physical-1",
                role = CameraIdRole.PhysicalOnlyChild("camera-1"),
                facing = CapabilityState.Known(CameraFacing.Back),
                outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
                controls = CapabilityState.Known(CameraControlAvailability(autoFocus = true, exposureCompensation = true, zoomRatio = true)),
            ),
        ),
    )
}
