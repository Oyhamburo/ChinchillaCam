package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VisibleCameraForegroundServiceDrainLoopTest {
    @Test
    fun stopThenFastRestartDoesNotAllowOldGenerationToDrainRestartedSession() {
        val pipeline = GenerationTrackingPipeline()
        val drainLoop = ThreadedVisibleCameraServiceDrainLoop(intervalMillis = 150L)

        drainLoop.start(pipeline)
        assertTrue(pipeline.awaitDrainCount(generation = 1, expected = 1))

        drainLoop.stop()
        pipeline.advanceGeneration(2)
        drainLoop.start(pipeline)
        assertTrue(pipeline.awaitDrainCount(generation = 2, expected = 1))

        assertTrue("old generation must have had time to wake if it was not interrupted", pipeline.awaitWakeWindow())

        assertEquals(
            "only the restarted generation's own loop may drain generation 2",
            1,
            pipeline.distinctThreadsForGeneration(2),
        )

        drainLoop.stop()
    }

    @Test
    fun stopInterruptsSleepingGenerationSoItExitsPromptly() {
        val pipeline = GenerationTrackingPipeline()
        val drainLoop = ThreadedVisibleCameraServiceDrainLoop(intervalMillis = 10_000L)

        drainLoop.start(pipeline)
        assertTrue(pipeline.awaitDrainCount(generation = 1, expected = 1))
        val firstLoopThread = pipeline.firstDrainThread()

        drainLoop.stop()

        firstLoopThread.join(500)
        assertFalse("stop must interrupt/request the sleeping drain generation to exit", firstLoopThread.isAlive)
    }

    @Test
    fun runtimeExceptionTerminatesOnlyThatGenerationAndAllowsLaterStart() {
        val pipeline = FailingOncePipeline()
        val drainLoop = ThreadedVisibleCameraServiceDrainLoop(intervalMillis = 10L)

        drainLoop.start(pipeline)
        assertTrue(pipeline.awaitFirstDrain())
        pipeline.firstDrainThread().join(500)
        assertFalse("failed drain generation must not leak a live thread", pipeline.firstDrainThread().isAlive)

        pipeline.failNextDrain.set(false)
        drainLoop.start(pipeline)

        assertTrue("a later start must create a new generation after drain failure", pipeline.awaitSuccessfulDrain())
        drainLoop.stop()
    }

    @Test
    fun repeatedStartWhileRunningDoesNotCreateTwoActiveLoops() {
        val pipeline = BlockingDrainPipeline()
        val drainLoop = ThreadedVisibleCameraServiceDrainLoop(intervalMillis = 10L)

        drainLoop.start(pipeline)
        assertTrue(pipeline.awaitFirstDrainEntered())

        drainLoop.start(pipeline)

        assertEquals("second start while a generation is active must not start another loop", 1, pipeline.concurrentDrainAttempts.get())
        pipeline.releaseFirstDrain()
        drainLoop.stop()
    }

    private class GenerationTrackingPipeline : VisibleCameraServicePipeline {
        private val generation = AtomicInteger(1)
        private val firstDrain = CountDownLatch(1)
        private val secondGenerationDrain = CountDownLatch(1)
        private val threadsByGeneration = Collections.synchronizedMap(mutableMapOf<Int, MutableSet<Thread>>())
        private lateinit var firstThread: Thread

        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
            VisibleCameraPipelineStatus.Running

        override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus {
            val currentGeneration = generation.get()
            threadsByGeneration.getOrPut(currentGeneration) { mutableSetOf() }.add(Thread.currentThread())
            if (currentGeneration == 1) {
                firstThread = Thread.currentThread()
                firstDrain.countDown()
            }
            if (currentGeneration == 2) secondGenerationDrain.countDown()
            return VisibleCameraPipelineStatus.Running
        }

        override fun stop(): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Stopped

        fun advanceGeneration(nextGeneration: Int) {
            generation.set(nextGeneration)
        }

        fun awaitDrainCount(generation: Int, expected: Int): Boolean {
            val latch = if (generation == 1) firstDrain else secondGenerationDrain
            return latch.await(1, TimeUnit.SECONDS) && distinctThreadsForGeneration(generation) >= expected
        }

        fun awaitWakeWindow(): Boolean {
            Thread.sleep(300)
            return true
        }

        fun distinctThreadsForGeneration(generation: Int): Int = synchronized(threadsByGeneration) {
            threadsByGeneration[generation]?.size ?: 0
        }

        fun firstDrainThread(): Thread = firstThread
    }

    private class FailingOncePipeline : VisibleCameraServicePipeline {
        val failNextDrain = AtomicBoolean(true)
        private val firstDrain = CountDownLatch(1)
        private val successfulDrain = CountDownLatch(1)
        private lateinit var firstThread: Thread

        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
            VisibleCameraPipelineStatus.Running

        override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus {
            if (failNextDrain.get()) {
                firstThread = Thread.currentThread()
                firstDrain.countDown()
                throw IllegalStateException("boom")
            }
            successfulDrain.countDown()
            return VisibleCameraPipelineStatus.Running
        }

        override fun stop(): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Stopped

        fun awaitFirstDrain(): Boolean = firstDrain.await(1, TimeUnit.SECONDS)
        fun awaitSuccessfulDrain(): Boolean = successfulDrain.await(1, TimeUnit.SECONDS)
        fun firstDrainThread(): Thread = firstThread
    }

    private class BlockingDrainPipeline : VisibleCameraServicePipeline {
        val concurrentDrainAttempts = AtomicInteger(0)
        private val firstDrainEntered = CountDownLatch(1)
        private val releaseFirstDrain = CountDownLatch(1)

        override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String, cameraPermissionGranted: Boolean): VisibleCameraPipelineStatus =
            VisibleCameraPipelineStatus.Running

        override fun drainOnce(maxOutputs: Int): VisibleCameraPipelineStatus {
            concurrentDrainAttempts.incrementAndGet()
            firstDrainEntered.countDown()
            assertTrue(releaseFirstDrain.await(2, TimeUnit.SECONDS))
            return VisibleCameraPipelineStatus.Running
        }

        override fun stop(): VisibleCameraPipelineStatus = VisibleCameraPipelineStatus.Stopped

        fun awaitFirstDrainEntered(): Boolean = firstDrainEntered.await(1, TimeUnit.SECONDS)
        fun releaseFirstDrain() {
            releaseFirstDrain.countDown()
        }
    }
}
