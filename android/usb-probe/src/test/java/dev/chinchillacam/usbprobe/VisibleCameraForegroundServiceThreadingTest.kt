package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VisibleCameraForegroundServiceThreadingTest {
    @Test
    fun startCommandDispatchesPipelineStartOffCallerThreadAndReturnsBeforeBlockedStartCompletes() {
        val pipeline = BlockingServicePipeline()
        val scheduler = CapturingScheduler()
        val runner = VisibleCameraForegroundServiceCommandRunner(
            owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop),
            scheduler = scheduler,
            foreground = RecordingForegroundStarter(),
            stopService = {},
        )

        val outcome = runner.handleStart(
            request = VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
        )

        assertEquals(VisibleCameraServiceCommandOutcome.Started, outcome)
        assertEquals(1, scheduler.scheduledCount)
        assertFalse("pipeline start must not run synchronously on the caller/main thread", pipeline.startEntered.get())

        val worker = Thread { scheduler.runOnlyScheduled() }
        worker.start()
        assertTrue(pipeline.startEnteredLatch.await(1, TimeUnit.SECONDS))
        assertTrue("background start is intentionally still blocked", worker.isAlive)

        pipeline.releaseStart()
        worker.join(1_000)
        assertFalse(worker.isAlive)
    }

    @Test
    fun reconfigureCommandDispatchesOnStartExecutorAndDoesNotRestartForeground() {
        val pipeline = BlockingServicePipeline()
        pipeline.releaseStart()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop)
        owner.handleStartCommand(VisibleCameraServiceStartRequest("camera-1", true), true, sampleSnapshot())
        val scheduler = CapturingScheduler()
        var foregroundCalls = 0
        val runner = VisibleCameraForegroundServiceCommandRunner(owner, scheduler, object : VisibleCameraForegroundStarter {
            override fun startForegroundForVisibleCamera() { foregroundCalls++ }
        }, stopService = { throw AssertionError("reconfigure must not stop the service") })

        runner.handleReconfigure(null, true) { sampleSnapshot() }

        assertEquals(1, scheduler.scheduledCount)
        assertEquals(0, foregroundCalls)
        assertEquals(0, pipeline.stopCalls.get())
        val worker = Thread { scheduler.runOnlyScheduled() }
        worker.start()
        worker.join(1_000)
        assertFalse(worker.isAlive)
        assertEquals(1, pipeline.stopCalls.get())
        assertEquals(VisibleCameraServiceState.Running, VisibleCameraServiceStatusStore.snapshot().state)
    }

    @Test
    fun stopWhileBackgroundStartIsBlockedReturnsPromptlyAndStopsLateRunningGeneration() {
        val pipeline = BlockingServicePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop)
        val startThread = Thread {
            owner.handleStartCommand(
                request = VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
                cameraPermissionGranted = true,
                snapshot = sampleSnapshot(),
            )
        }

        startThread.start()
        assertTrue(pipeline.startEnteredLatch.await(1, TimeUnit.SECONDS))

        val stopThread = Thread { owner.handleStopCommand() }
        stopThread.start()
        stopThread.join(250)
        assertFalse("STOP must not wait for a blocked start to finish", stopThread.isAlive)
        assertTrue(pipeline.stopCalls.get() >= 1)

        pipeline.releaseStart()
        startThread.join(1_000)
        assertFalse(startThread.isAlive)
        assertTrue("a late Running result from a cancelled generation must be stopped", pipeline.stopCalls.get() >= 2)
    }


    @Test
    fun stopBeforeSnapshotCompletesCancelsStartBeforeCameraOpen() {
        val pipeline = BlockingServicePipeline()
        val owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop)
        val snapshotEntered = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val startThread = Thread {
            owner.handleStartCommand(
                request = VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
                cameraPermissionGranted = true,
                snapshotProvider = {
                    snapshotEntered.countDown()
                    assertTrue(releaseSnapshot.await(2, TimeUnit.SECONDS))
                    sampleSnapshot()
                },
            )
        }

        startThread.start()
        assertTrue(snapshotEntered.await(1, TimeUnit.SECONDS))
        val stopThread = Thread { owner.handleStopCommand() }
        stopThread.start()
        stopThread.join(250)
        assertFalse("STOP must not wait for snapshot acquisition", stopThread.isAlive)

        releaseSnapshot.countDown()
        startThread.join(1_000)

        assertFalse(startThread.isAlive)
        assertFalse("cancelled pre-snapshot generation must not open camera", pipeline.startEntered.get())
    }

    @Test
    fun serviceCameraCallbackHandlerSpecRequiresDedicatedNonMainThread() {
        val mainSpec = VisibleCameraForegroundServiceCallbackThreadSpec(threadName = "main", callbackLooperIsMain = true)
        val serviceSpec = VisibleCameraForegroundServiceCallbackThreadSpec.serviceDefault()

        assertFalse(mainSpec.isValidForCameraCallbacks)
        assertTrue(serviceSpec.isValidForCameraCallbacks)
        assertFalse(serviceSpec.callbackLooperIsMain)
        assertNotEquals("main", serviceSpec.threadName)
    }

    @Test
    fun foregroundExceptionPreventsSchedulingPipelineOpenAndStopsService() {
        val pipeline = BlockingServicePipeline()
        val scheduler = CapturingScheduler()
        val stopped = AtomicBoolean(false)
        val runner = VisibleCameraForegroundServiceCommandRunner(
            owner = VisibleCameraForegroundServicePipelineOwner(pipeline, VisibleCameraServiceDrainLoop.Noop),
            scheduler = scheduler,
            foreground = ThrowingForegroundStarter(SecurityException("stale visible token")),
            stopService = { stopped.set(true) },
        )

        val outcome = runner.handleStart(
            request = VisibleCameraServiceStartRequest(selectedCameraId = "camera-1", visibleStartRequested = true),
            cameraPermissionGranted = true,
            snapshot = sampleSnapshot(),
        )

        assertEquals(VisibleCameraServiceCommandOutcome.Blocked, outcome)
        assertEquals(0, scheduler.scheduledCount)
        assertFalse(pipeline.startEntered.get())
        assertTrue(stopped.get())
    }

    private class CapturingScheduler : Executor {
        private val tasks = mutableListOf<Runnable>()
        val scheduledCount: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runOnlyScheduled() {
            tasks.single().run()
        }
    }

    private class RecordingForegroundStarter : VisibleCameraForegroundStarter {
        override fun startForegroundForVisibleCamera() = Unit
    }

    private class ThrowingForegroundStarter(private val throwable: RuntimeException) : VisibleCameraForegroundStarter {
        override fun startForegroundForVisibleCamera() {
            throw throwable
        }
    }

    private class BlockingServicePipeline : VisibleCameraServicePipeline {
        val startEntered = AtomicBoolean(false)
        val startEnteredLatch = CountDownLatch(1)
        val releaseStartLatch = CountDownLatch(1)
        val stopCalls = AtomicInteger(0)

        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus {
            startEntered.set(true)
            startEnteredLatch.countDown()
            assertTrue(releaseStartLatch.await(2, TimeUnit.SECONDS))
            return VisibleCameraPipelineStatus.Running
        }

        override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Running

        override fun stop(): VisibleCameraPipelineStatus {
            stopCalls.incrementAndGet()
            return VisibleCameraPipelineStatus.Stopped
        }

        fun releaseStart() {
            releaseStartLatch.countDown()
        }
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
        ),
    )
}
