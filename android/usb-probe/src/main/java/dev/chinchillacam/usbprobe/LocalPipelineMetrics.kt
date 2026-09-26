package dev.chinchillacam.usbprobe

interface PipelineMetricsClock {
    fun nowUs(): Long
}

class SystemPipelineMetricsClock : PipelineMetricsClock {
    override fun nowUs(): Long = System.nanoTime() / 1_000L
}

sealed class MetricValue {
    object Unknown : MetricValue()
    data class Known(val value: Double) : MetricValue()
}

sealed class MetricEstimate {
    object Unknown : MetricEstimate()
    data class Estimated(val valueUs: Long) : MetricEstimate()
}

data class LocalPipelineMetricsSnapshot(
    val encodedFps: MetricValue,
    val encodedChunksDiscarded: Int,
    val encodedBytesDiscarded: Long,
    val backpressureDrops: Int,
    val estimatedEncodeLatencyUs: MetricEstimate,
)

class LocalPipelineMetricsTracker(
    private val clock: PipelineMetricsClock,
    private val fpsWindowUs: Long = 1_000_000L,
) {
    private data class Sample(val atUs: Long, val count: Int)

    private val samples = mutableListOf<Sample>()
    private var hasObservedDrainWindow = false
    private var chunksDiscarded = 0
    private var bytesDiscarded = 0L
    private var backpressureDrops = 0
    private var latestLatency: MetricEstimate = MetricEstimate.Unknown

    @Synchronized
    fun reset() {
        samples.clear()
        hasObservedDrainWindow = false
        chunksDiscarded = 0
        bytesDiscarded = 0L
        backpressureDrops = 0
        latestLatency = MetricEstimate.Unknown
    }

    @Synchronized
    fun recordDrain(result: H264DrainResult) {
        val nowUs = clock.nowUs()
        hasObservedDrainWindow = true
        prune(nowUs)
        when (result) {
            is H264DrainResult.Chunks -> {
                val count = result.chunks.size
                samples += Sample(nowUs, count)
                chunksDiscarded += count
                bytesDiscarded += result.chunks.sumOf { it.bytes.size.toLong() }
                latestLatency = estimateLatency(nowUs, result.chunks.lastOrNull()?.presentationTimeUs)
            }
            is H264DrainResult.BackpressureExceeded -> backpressureDrops += 1
            is H264DrainResult.Failed,
            H264DrainResult.Stopped,
            H264DrainResult.TryAgainLater -> Unit
        }
        prune(nowUs)
    }

    @Synchronized
    fun snapshot(): LocalPipelineMetricsSnapshot {
        val nowUs = clock.nowUs()
        prune(nowUs)
        val fps = when {
            !hasObservedDrainWindow -> MetricValue.Unknown
            samples.isEmpty() -> MetricValue.Known(0.0)
            else -> MetricValue.Known(samples.sumOf { it.count }.toDouble() * 1_000_000.0 / fpsWindowUs.toDouble())
        }
        return LocalPipelineMetricsSnapshot(
            encodedFps = fps,
            encodedChunksDiscarded = chunksDiscarded,
            encodedBytesDiscarded = bytesDiscarded,
            backpressureDrops = backpressureDrops,
            estimatedEncodeLatencyUs = latestLatency,
        )
    }

    private fun prune(nowUs: Long) {
        samples.removeAll { nowUs - it.atUs > fpsWindowUs }
    }

    private fun estimateLatency(nowUs: Long, presentationTimeUs: Long?): MetricEstimate {
        val pts = presentationTimeUs ?: return MetricEstimate.Unknown
        if (pts < 0L || pts > nowUs) return MetricEstimate.Unknown
        return MetricEstimate.Estimated(nowUs - pts)
    }
}

object LocalPipelineMetricsFormatter {
    fun format(snapshot: LocalPipelineMetricsSnapshot): String = listOf(
        "FPS: ${formatMetricValue(snapshot.encodedFps)}",
        "Chunks descartados: ${snapshot.encodedChunksDiscarded}",
        "Bytes descartados: ${snapshot.encodedBytesDiscarded}",
        "Drops por backpressure: ${snapshot.backpressureDrops}",
        "Latencia encode estimada: ${formatEstimate(snapshot.estimatedEncodeLatencyUs)}",
    ).joinToString("\n")

    private fun formatMetricValue(value: MetricValue): String = when (value) {
        MetricValue.Unknown -> "sin muestras aún"
        is MetricValue.Known -> String.format(java.util.Locale.US, "%.1f", value.value)
    }

    private fun formatEstimate(estimate: MetricEstimate): String = when (estimate) {
        MetricEstimate.Unknown -> "sin estimación"
        is MetricEstimate.Estimated -> "${estimate.valueUs / 1_000L} ms"
    }
}
