package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

class VisibleCameraServiceReconfigureTest {
    @Test
    fun reconfigure_restarts_pipeline_without_ending_session() {
        val pipeline = RecordingReconfigurePipeline()
        val loop = RecordingReconfigureLoop(pipeline.events)
        var ended = false
        val old = plan(1280)
        val updated = plan(1920)
        var selected = old
        val owner = VisibleCameraForegroundServicePipelineOwner(
            pipeline, loop,
            onPipelineFailureStop = { ended = true },
            qualityPlanResolver = { selected },
        )
        assertEquals(VisibleCameraServiceCommandOutcome.Started, owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot()))

        selected = updated
        assertEquals(VisibleCameraServiceCommandOutcome.Reconfigured, owner.handleReconfigureCommand(null, true) { snapshot() })

        assertEquals(listOf("start:camera-1:1280", "loop:start", "loop:stop", "stop", "start:camera-1:1920", "loop:start"), pipeline.events)
        assertEquals(VisibleCameraServiceState.Running, VisibleCameraServiceStatusStore.snapshot().state)
        assertFalse("a successful restart must not end the session", ended)
    }

    @Test
    fun reconfigure_without_active_pipeline_is_ignored() {
        val pipeline = RecordingReconfigurePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, RecordingReconfigureLoop(pipeline.events))
        assertEquals(VisibleCameraServiceCommandOutcome.IgnoredNoActivePipeline, owner.handleReconfigureCommand(null, true) { snapshot() })
        assertTrue(pipeline.events.isEmpty())
    }

    @Test
    fun failed_restart_reverts_to_previous_camera_and_plan_without_ending_session() {
        val pipeline = RecordingReconfigurePipeline()
        val loop = RecordingReconfigureLoop(pipeline.events)
        var ended = false
        var selected = plan(1280)
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, loop, onPipelineFailureStop = { ended = true }, qualityPlanResolver = { selected })
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot())
        selected = plan(1920)
        pipeline.failWidths += 1920

        assertEquals(VisibleCameraServiceCommandOutcome.Reconfigured, owner.handleReconfigureCommand(null, true) { snapshot() })
        assertEquals(listOf("start:camera-1:1280", "loop:start", "loop:stop", "stop", "start:camera-1:1920", "stop", "start:camera-1:1280", "loop:start"), pipeline.events)
        assertEquals("camera-1", VisibleCameraServiceStatusStore.snapshot().selectedCameraId)
        assertFalse(ended)
    }

    @Test
    fun failed_revert_ends_session_through_existing_failure_callback() {
        val pipeline = RecordingReconfigurePipeline()
        var ended = 0
        var selected = plan(1280)
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, RecordingReconfigureLoop(pipeline.events), onPipelineFailureStop = { ended++ }, qualityPlanResolver = { selected })
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot())
        selected = plan(1920)
        pipeline.failWidths += listOf(1280, 1920)

        assertEquals(VisibleCameraServiceCommandOutcome.Blocked, owner.handleReconfigureCommand(null, true) { snapshot() })
        assertEquals(1, ended)
        assertEquals(VisibleCameraServiceState.Error, VisibleCameraServiceStatusStore.snapshot().state)
        assertEquals(VisibleCameraServiceCommandOutcome.IgnoredNoActivePipeline, owner.handleReconfigureCommand(null, true) { snapshot() })
    }

    @Test
    fun camera_switch_passes_new_id_and_reuses_it_for_next_quality_change() {
        val pipeline = RecordingReconfigurePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, RecordingReconfigureLoop(pipeline.events))
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot())

        assertEquals(VisibleCameraServiceCommandOutcome.Reconfigured, owner.handleReconfigureCommand("camera-2", true) { snapshot() })
        assertEquals(VisibleCameraServiceCommandOutcome.Reconfigured, owner.handleReconfigureCommand(null, true) { snapshot() })
        assertEquals(listOf("start:camera-1:1280", "start:camera-2:1280", "start:camera-2:1280"), pipeline.events.filter { it.startsWith("start:") })
    }

    @Test
    fun invalid_camera_switch_keeps_old_pipeline_running_and_stop_after_reconfigure_stays_stopped() {
        val pipeline = RecordingReconfigurePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, RecordingReconfigureLoop(pipeline.events))
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot())
        assertEquals(VisibleCameraServiceCommandOutcome.Blocked, owner.handleReconfigureCommand("missing", true) { snapshot() })
        assertEquals(listOf("start:camera-1:1280", "loop:start"), pipeline.events)
        assertEquals(VisibleCameraServiceCommandOutcome.Reconfigured, owner.handleReconfigureCommand(null, true) { snapshot() })

        owner.handleStopCommand()
        assertEquals(VisibleCameraServiceState.Stopped, VisibleCameraServiceStatusStore.snapshot().state)
        assertEquals(VisibleCameraServiceCommandOutcome.IgnoredNoActivePipeline, owner.handleReconfigureCommand(null, true) { snapshot() })
    }

    @Test
    fun stop_during_reconfigure_start_cannot_resurrect_pipeline() {
        val pipeline = RecordingReconfigurePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, RecordingReconfigureLoop(pipeline.events))
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, snapshot())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        pipeline.beforeStart = {
            if (pipeline.events.count { it.startsWith("start:") } == 2) {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        val worker = thread { owner.handleReconfigureCommand(null, true) { snapshot() } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        owner.handleStopCommand()
        release.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(VisibleCameraServiceState.Stopped, VisibleCameraServiceStatusStore.snapshot().state)
        assertEquals(VisibleCameraServiceCommandOutcome.IgnoredNoActivePipeline, owner.handleReconfigureCommand(null, true) { snapshot() })
        assertFalse("late start must not resume draining", pipeline.events.last() == "loop:start")
    }

    private fun plan(width: Int): QualityPlan = CameraQualityPlanner.plan(null, QualityPreference.Automatic).copy(
        encoderConfig = H264EncoderConfig(width, 720, 2_000_000, 30, 2),
    )

    private fun snapshot() = CameraCatalogSnapshot(listOf("camera-1", "camera-2").map { id -> CameraCatalogEntry(
        id = id,
        role = CameraIdRole.DirectOpenCandidate,
        facing = CapabilityState.Known(CameraFacing.Back),
        outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
        fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
        controls = CapabilityState.Known(CameraControlAvailability(true, true, true)),
    ) })

    private class RecordingReconfigurePipeline : VisibleCameraServicePipeline {
        val events = mutableListOf<String>()
        val failWidths = mutableListOf<Int>()
        var beforeStart: () -> Unit = {}
        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean) = VisibleCameraPipelineStatus.Running
        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean, plan: QualityPlan): VisibleCameraPipelineStatus {
            events += "start:$selectedCameraId:${plan.encoderConfig.width}"
            beforeStart()
            return if (plan.encoderConfig.width in failWidths) VisibleCameraPipelineStatus.Error else VisibleCameraPipelineStatus.Running
        }
        override fun drainOnce(maxOutputs: Int) = VisibleCameraPipelineStatus.Running
        override fun stop(): VisibleCameraPipelineStatus {
            events += "stop"
            return VisibleCameraPipelineStatus.Stopped
        }
    }

    private class RecordingReconfigureLoop(private val events: MutableList<String>) : VisibleCameraServiceDrainLoop {
        override fun start(pipeline: VisibleCameraServicePipeline, onPipelineStopped: () -> Unit) { events += "loop:start" }
        override fun stop() { events += "loop:stop" }
    }
}
