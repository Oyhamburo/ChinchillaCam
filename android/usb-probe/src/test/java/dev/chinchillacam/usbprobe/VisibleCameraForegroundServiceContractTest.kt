package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisibleCameraForegroundServiceContractTest {
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
