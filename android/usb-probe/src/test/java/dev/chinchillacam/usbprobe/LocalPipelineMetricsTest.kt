package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPipelineMetricsTest {
    @Test
    fun reportsUnknownFpsBeforeAnySample() {
        val clock = FakeMetricsClock(nowUs = 1_000_000L)
        val metrics = LocalPipelineMetricsTracker(clock)

        val snapshot = metrics.snapshot()

        assertEquals(MetricValue.Unknown, snapshot.encodedFps)
        assertEquals("FPS: sin muestras aún", LocalPipelineMetricsFormatter.format(snapshot).lines().first())
    }

    @Test
    fun computesFpsFromRealDrainedChunksAndInjectedClock() {
        val clock = FakeMetricsClock(nowUs = 0L)
        val metrics = LocalPipelineMetricsTracker(clock)

        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(100_000L), chunk(200_000L))))
        clock.nowUs = 1_000_000L
        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(900_000L))))

        val snapshot = metrics.snapshot()

        assertEquals(MetricValue.Known(3.0), snapshot.encodedFps)
        assertEquals(3, snapshot.encodedChunksDiscarded)
        assertEquals(12, snapshot.encodedBytesDiscarded)
    }

    @Test
    fun reportsZeroFpsOnlyAfterWindowWithSamplesAgesOut() {
        val clock = FakeMetricsClock(nowUs = 0L)
        val metrics = LocalPipelineMetricsTracker(clock, fpsWindowUs = 1_000_000L)
        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(0L))))

        clock.nowUs = 2_000_000L
        metrics.recordDrain(H264DrainResult.TryAgainLater)

        assertEquals(MetricValue.Known(0.0), metrics.snapshot().encodedFps)
    }

    @Test
    fun countsBackpressureDropsFromBoundedDiscardPath() {
        val metrics = LocalPipelineMetricsTracker(FakeMetricsClock(0L))

        metrics.recordDrain(H264DrainResult.BackpressureExceeded(maxPendingChunks = 2))
        metrics.recordDrain(H264DrainResult.BackpressureExceeded(maxPendingChunks = 2))

        assertEquals(2, metrics.snapshot().backpressureDrops)
    }

    @Test
    fun latencyRemainsUnknownWithoutProvenPresentationClockDomain() {
        val clock = FakeMetricsClock(nowUs = 1_000_000L)
        val metrics = LocalPipelineMetricsTracker(clock)

        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(900_000L))))
        val seeminglyAligned = metrics.snapshot().estimatedEncodeLatencyUs
        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(2_000_000L))))
        val futureTimestamp = metrics.snapshot().estimatedEncodeLatencyUs

        assertEquals(MetricEstimate.Unknown, seeminglyAligned)
        assertEquals(MetricEstimate.Unknown, futureTimestamp)
    }


    @Test
    fun encodedFpsCountsOnlyVideoFrameChunksNotCodecConfigBuffers() {
        val clock = FakeMetricsClock(nowUs = 0L)
        val metrics = LocalPipelineMetricsTracker(clock)

        metrics.recordDrain(H264DrainResult.Chunks(listOf(
            codecConfigChunk(0L),
            chunk(100_000L),
            chunk(200_000L),
        )))

        val snapshot = metrics.snapshot()

        assertEquals(MetricValue.Known(2.0), snapshot.encodedFps)
        assertEquals(3, snapshot.encodedChunksDiscarded)
        assertEquals(12, snapshot.encodedBytesDiscarded)
    }

    @Test
    fun presentationTimestampDoesNotProduceLatencyWithoutProvenClockDomain() {
        val clock = FakeMetricsClock(nowUs = 1_000_000L)
        val metrics = LocalPipelineMetricsTracker(clock)

        metrics.recordDrain(H264DrainResult.Chunks(listOf(chunk(900_000L))))

        assertEquals(MetricEstimate.Unknown, metrics.snapshot().estimatedEncodeLatencyUs)
    }

    @Test
    fun stopClearsMetricsSoStaleSamplesDoNotRemainCurrent() {
        val clock = FakeMetricsClock(1_000_000L)
        val handle = MetricsVisibleHandle(H264DrainResult.Chunks(listOf(chunk(900_000L), chunk(950_000L))))
        val controller = VisibleCameraPipelineController(
            launcher = OneShotMetricsLauncher(handle),
            encoderConfig = H264EncoderConfig(1280, 720, 2_000_000, 30, 2),
            metrics = LocalPipelineMetricsTracker(clock),
        )
        controller.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true)
        controller.drainOnce(maxOutputs = 4)

        val stopped = controller.stopFromUser()

        assertTrue(stopped.metricsText.contains("FPS: sin muestras aún"))
        assertTrue(stopped.metricsText.contains("Chunks descartados: 0"))
    }

    @Test
    fun controllerAppendsSpanishMetricsFromActualDrain() {
        val clock = FakeMetricsClock(1_000_000L)
        val handle = MetricsVisibleHandle(H264DrainResult.Chunks(listOf(chunk(900_000L), chunk(950_000L))))
        val controller = VisibleCameraPipelineController(
            launcher = OneShotMetricsLauncher(handle),
            encoderConfig = H264EncoderConfig(1280, 720, 2_000_000, 30, 2),
            metrics = LocalPipelineMetricsTracker(clock),
        )
        controller.start(sampleSnapshot(), "camera-1", cameraPermissionGranted = true)

        val state = controller.drainOnce(maxOutputs = 4)

        assertTrue(state.metricsText.contains("FPS: 2.0"))
        assertTrue(state.metricsText.contains("Chunks descartados: 2"))
        assertTrue(state.metricsText.contains("Latencia encode estimada: sin estimación"))
        assertEquals(listOf(2), handle.consumed)
    }

    private fun chunk(presentationTimeUs: Long): EncodedVideoChunk = EncodedVideoChunk(
        bytes = byteArrayOf(1, 2, 3, 4),
        presentationTimeUs = presentationTimeUs,
        isCodecConfig = false,
        isKeyFrame = false,
    )

    private fun codecConfigChunk(presentationTimeUs: Long): EncodedVideoChunk = EncodedVideoChunk(
        bytes = byteArrayOf(9, 9, 9, 9),
        presentationTimeUs = presentationTimeUs,
        isCodecConfig = true,
        isKeyFrame = false,
    )

    private fun sampleSnapshot(): CameraCatalogSnapshot = CameraCatalogSnapshot(
        entries = listOf(
            CameraCatalogEntry(
                id = "camera-1",
                role = CameraIdRole.DirectOpenCandidate,
                facing = CapabilityState.Known(CameraFacing.Back),
                outputSizes = CapabilityState.Known(listOf(CameraOutputSize(1280, 720))),
                fpsRanges = CapabilityState.Known(listOf(CameraFpsRange(30, 30))),
                controls = CapabilityState.Known(CameraControlAvailability(true, true, true)),
            ),
        ),
    )
}

private class FakeMetricsClock(
    var nowUs: Long,
) : PipelineMetricsClock {
    override fun nowUs(): Long = nowUs
}

private class OneShotMetricsLauncher(
    private val handle: VisibleCameraPipelineHandle,
) : VisibleCameraPipelineLauncher {
    override fun start(snapshot: CameraCatalogSnapshot, selectedCameraId: String?, cameraPermissionGranted: Boolean, encoderConfig: H264EncoderConfig): VisibleCameraPipelineLaunchResult =
        VisibleCameraPipelineLaunchResult.Running(handle)
}

private class MetricsVisibleHandle(
    private val drain: H264DrainResult,
) : VisibleCameraPipelineHandle {
    val consumed = mutableListOf<Int>()
    override fun drainEncoded(maxOutputs: Int): H264DrainResult = drain
    override fun consumeEncoded(count: Int) { consumed += count }
    override fun stop(): CameraEncoderPipelineStopResult = CameraEncoderPipelineStopResult.Stopped
}
