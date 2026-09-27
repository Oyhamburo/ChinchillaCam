package dev.chinchillacam.usbprobe

interface EncodedVideoEgressSink {
    fun write(chunk: EncodedVideoChunk): EncodedVideoEgressSinkResult
    fun stats(): EncodedVideoEgressSinkStats
    fun close()
}

sealed class EncodedVideoEgressSinkResult {
    object Accepted : EncodedVideoEgressSinkResult()
    object BackpressureExceeded : EncodedVideoEgressSinkResult()
    object Closed : EncodedVideoEgressSinkResult()
    object Oversized : EncodedVideoEgressSinkResult()
    data class InvalidPayload(val reason: String) : EncodedVideoEgressSinkResult()
    data class Failed(val reason: String) : EncodedVideoEgressSinkResult()
}

data class EncodedVideoEgressSinkStats(
    val accepted: Int,
    val dropped: Int,
)

class LegacyEncodedVideoEgressSink(
    private val sink: EncodedVideoSessionFrameSink,
) : EncodedVideoEgressSink {
    override fun write(chunk: EncodedVideoChunk): EncodedVideoEgressSinkResult = when (val result = sink.write(chunk)) {
        is EncodedVideoSessionFrameSinkResult.Accepted -> EncodedVideoEgressSinkResult.Accepted
        EncodedVideoSessionFrameSinkResult.BackpressureExceeded -> EncodedVideoEgressSinkResult.BackpressureExceeded
        EncodedVideoSessionFrameSinkResult.Closed -> EncodedVideoEgressSinkResult.Closed
        EncodedVideoSessionFrameSinkResult.Oversized -> EncodedVideoEgressSinkResult.Oversized
        is EncodedVideoSessionFrameSinkResult.InvalidPayload -> EncodedVideoEgressSinkResult.InvalidPayload(result.reason)
        is EncodedVideoSessionFrameSinkResult.Failed -> EncodedVideoEgressSinkResult.Failed(result.reason)
    }

    override fun stats(): EncodedVideoEgressSinkStats = sink.stats().let {
        EncodedVideoEgressSinkStats(accepted = it.accepted, dropped = it.dropped)
    }

    override fun close() = sink.close()
}

class FragmentingEncodedVideoEgressSink(
    private val sink: EncodedVideoFragmentingSessionFrameSink,
) : EncodedVideoEgressSink {
    override fun write(chunk: EncodedVideoChunk): EncodedVideoEgressSinkResult = when (val result = sink.write(chunk)) {
        is EncodedVideoFragmentingSessionFrameSinkResult.Accepted -> EncodedVideoEgressSinkResult.Accepted
        EncodedVideoFragmentingSessionFrameSinkResult.BackpressureExceeded -> EncodedVideoEgressSinkResult.BackpressureExceeded
        EncodedVideoFragmentingSessionFrameSinkResult.Closed -> EncodedVideoEgressSinkResult.Closed
        EncodedVideoFragmentingSessionFrameSinkResult.Oversized -> EncodedVideoEgressSinkResult.Oversized
        is EncodedVideoFragmentingSessionFrameSinkResult.InvalidPayload -> EncodedVideoEgressSinkResult.InvalidPayload(result.reason)
        is EncodedVideoFragmentingSessionFrameSinkResult.Failed -> EncodedVideoEgressSinkResult.Failed(result.reason)
    }

    override fun stats(): EncodedVideoEgressSinkStats = sink.stats().let {
        EncodedVideoEgressSinkStats(accepted = it.accepted, dropped = it.dropped)
    }

    override fun close() = sink.close()
}
