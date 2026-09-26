package dev.chinchillacam.usbprobe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class H264EncoderBoundary(
    private val gateway: H264EncoderGateway,
    private val maxPendingChunks: Int = 8,
) {
    fun start(config: H264EncoderConfig): H264EncoderStartResult = when (val outcome = gateway.start(config)) {
        is H264EncoderStartOutcome.Started -> H264EncoderStartResult.Started(
            inputSurface = outcome.inputSurface,
            session = H264EncoderSession(outcome.inputSurface, outcome.codecSession, maxPendingChunks),
        )
        is H264EncoderStartOutcome.Failed -> H264EncoderStartResult.Failed(outcome.reason)
    }
}

data class H264EncoderConfig(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameIntervalSeconds: Int,
)

interface EncoderInputSurface : CaptureTargetSurface

interface H264EncoderGateway {
    fun start(config: H264EncoderConfig): H264EncoderStartOutcome
}

sealed class H264EncoderStartOutcome {
    data class Started(
        val inputSurface: EncoderInputSurface,
        val codecSession: CloseableH264CodecSession,
    ) : H264EncoderStartOutcome()

    data class Failed(val reason: String) : H264EncoderStartOutcome()
}

sealed class H264EncoderStartResult {
    data class Started(
        val inputSurface: EncoderInputSurface,
        val session: H264EncoderSession,
    ) : H264EncoderStartResult()

    data class Failed(val reason: String) : H264EncoderStartResult()
}

data class EncodedVideoChunk(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val isCodecConfig: Boolean,
    val isKeyFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedVideoChunk) return false
        return bytes.contentEquals(other.bytes) &&
            presentationTimeUs == other.presentationTimeUs &&
            isCodecConfig == other.isCodecConfig &&
            isKeyFrame == other.isKeyFrame
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + isCodecConfig.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        return result
    }
}

object H264BufferFlags {
    const val KEY_FRAME: Int = 1
    const val CODEC_CONFIG: Int = 2
}

data class H264BufferInfo(
    val offset: Int,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int,
)

sealed class H264CodecOutput {
    object TryAgainLater : H264CodecOutput()
    data class FormatChanged(val description: String) : H264CodecOutput()
    data class Buffer(
        val index: Int,
        val buffer: ByteBuffer?,
        val info: H264BufferInfo,
    ) : H264CodecOutput()
}

interface CloseableH264CodecSession {
    val inputSurface: EncoderInputSurface
    fun dequeueOutput(): H264CodecOutput
    fun releaseOutputBuffer(index: Int)
    fun stop(): H264CodecCloseOutcome
    fun release(): H264CodecCloseOutcome
}

sealed class H264CodecCloseOutcome {
    object Closed : H264CodecCloseOutcome()
    data class Failed(val reason: String) : H264CodecCloseOutcome()
}

sealed class H264DrainResult {
    data class Chunks(val chunks: List<EncodedVideoChunk>) : H264DrainResult()
    object TryAgainLater : H264DrainResult()
    object Stopped : H264DrainResult()
    data class BackpressureExceeded(val maxPendingChunks: Int) : H264DrainResult()
    data class Failed(val reason: String) : H264DrainResult()
}

sealed class H264EncoderStopResult {
    object Stopped : H264EncoderStopResult()
    object AlreadyStopped : H264EncoderStopResult()
    data class Failed(val reasons: List<String>) : H264EncoderStopResult()
}

class H264EncoderSession(
    val inputSurface: EncoderInputSurface,
    private val codecSession: CloseableH264CodecSession,
    private val maxPendingChunks: Int,
) {
    val outputFormats = mutableListOf<String>()
    private var pendingChunks: Int = 0
    private var stopped: Boolean = false

    @Synchronized
    fun drain(maxOutputs: Int): H264DrainResult {
        if (stopped) return H264DrainResult.Stopped
        if (pendingChunks >= maxPendingChunks) return H264DrainResult.BackpressureExceeded(maxPendingChunks)

        val chunks = mutableListOf<EncodedVideoChunk>()
        repeat(maxOutputs.coerceAtLeast(0)) {
            if (pendingChunks >= maxPendingChunks) return if (chunks.isEmpty()) {
                H264DrainResult.BackpressureExceeded(maxPendingChunks)
            } else {
                H264DrainResult.Chunks(chunks)
            }
            when (val output = codecSession.dequeueOutput()) {
                H264CodecOutput.TryAgainLater -> return if (chunks.isEmpty()) {
                    H264DrainResult.TryAgainLater
                } else {
                    H264DrainResult.Chunks(chunks)
                }
                is H264CodecOutput.FormatChanged -> outputFormats += output.description
                is H264CodecOutput.Buffer -> {
                    val chunk = try {
                        copyChunk(output.buffer, output.info)
                    } catch (_: IllegalArgumentException) {
                        return releaseAndFail(output.index, "output buffer bounds invalid")
                    } catch (_: IllegalStateException) {
                        return releaseAndFail(output.index, "output buffer unavailable")
                    } catch (_: RuntimeException) {
                        return releaseAndFail(output.index, "output buffer copy failed")
                    }
                    codecSession.releaseOutputBuffer(output.index)
                    pendingChunks += 1
                    chunks += chunk
                }
            }
        }
        return if (chunks.isEmpty()) H264DrainResult.TryAgainLater else H264DrainResult.Chunks(chunks)
    }

    @Synchronized
    fun consumePending(count: Int) {
        pendingChunks = (pendingChunks - count.coerceAtLeast(0)).coerceAtLeast(0)
    }

    @Synchronized
    fun stop(): H264EncoderStopResult {
        if (stopped) return H264EncoderStopResult.AlreadyStopped
        stopped = true
        val failures = mutableListOf<String>()
        when (val outcome = codecSession.stop()) {
            H264CodecCloseOutcome.Closed -> Unit
            is H264CodecCloseOutcome.Failed -> failures += outcome.reason
        }
        when (val outcome = codecSession.release()) {
            H264CodecCloseOutcome.Closed -> Unit
            is H264CodecCloseOutcome.Failed -> failures += outcome.reason
        }
        return if (failures.isEmpty()) H264EncoderStopResult.Stopped else H264EncoderStopResult.Failed(failures)
    }

    @Synchronized
    fun cancel(): H264EncoderStopResult {
        if (stopped) return H264EncoderStopResult.AlreadyStopped
        stopped = true
        return when (val outcome = codecSession.release()) {
            H264CodecCloseOutcome.Closed -> H264EncoderStopResult.Stopped
            is H264CodecCloseOutcome.Failed -> H264EncoderStopResult.Failed(listOf(outcome.reason))
        }
    }

    private fun releaseAndFail(index: Int, reason: String): H264DrainResult {
        try {
            codecSession.releaseOutputBuffer(index)
        } catch (_: RuntimeException) {
            // The original failure is more actionable; the release attempt still happened.
        }
        return H264DrainResult.Failed(reason)
    }

    private fun copyChunk(buffer: ByteBuffer?, info: H264BufferInfo): EncodedVideoChunk {
        val source = buffer ?: throw IllegalStateException("missing output buffer")
        if (info.offset < 0 || info.size < 0 || info.offset + info.size > source.limit()) {
            throw IllegalArgumentException("invalid output bounds")
        }
        val duplicate = source.duplicate()
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        duplicate.get(bytes)
        return EncodedVideoChunk(
            bytes = bytes,
            presentationTimeUs = info.presentationTimeUs,
            isCodecConfig = (info.flags and H264BufferFlags.CODEC_CONFIG) != 0,
            isKeyFrame = (info.flags and H264BufferFlags.KEY_FRAME) != 0,
        )
    }
}

class AndroidEncoderInputSurface(
    val surface: Surface,
    override val label: String,
) : EncoderInputSurface

class AndroidH264EncoderGateway(
    private val dequeueTimeoutUs: Long = 0L,
) : H264EncoderGateway {
    override fun start(config: H264EncoderConfig): H264EncoderStartOutcome {
        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = AndroidEncoderInputSurface(codec.createInputSurface(), "h264-encoder-input")
            codec.start()
            H264EncoderStartOutcome.Started(
                inputSurface = inputSurface,
                codecSession = AndroidH264CodecSession(inputSurface, codec, dequeueTimeoutUs),
            )
        } catch (_: IllegalArgumentException) {
            H264EncoderStartOutcome.Failed("encoder configuration rejected")
        } catch (_: IllegalStateException) {
            H264EncoderStartOutcome.Failed("encoder state rejected")
        } catch (_: RuntimeException) {
            H264EncoderStartOutcome.Failed("encoder unavailable")
        }
    }
}

private class AndroidH264CodecSession(
    override val inputSurface: EncoderInputSurface,
    private val codec: MediaCodec,
    private val dequeueTimeoutUs: Long,
) : CloseableH264CodecSession {
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun dequeueOutput(): H264CodecOutput {
        return when (val index = codec.dequeueOutputBuffer(bufferInfo, dequeueTimeoutUs)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> H264CodecOutput.TryAgainLater
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> H264CodecOutput.FormatChanged(codec.outputFormat.toString())
            else -> if (index >= 0) {
                H264CodecOutput.Buffer(
                    index = index,
                    buffer = codec.getOutputBuffer(index),
                    info = H264BufferInfo(
                        offset = bufferInfo.offset,
                        size = bufferInfo.size,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        flags = bufferInfo.flags,
                    ),
                )
            } else {
                H264CodecOutput.TryAgainLater
            }
        }
    }

    override fun releaseOutputBuffer(index: Int) {
        codec.releaseOutputBuffer(index, false)
    }

    override fun stop(): H264CodecCloseOutcome = try {
        codec.stop()
        H264CodecCloseOutcome.Closed
    } catch (_: IllegalStateException) {
        H264CodecCloseOutcome.Failed("encoder stop failed")
    }

    override fun release(): H264CodecCloseOutcome = try {
        codec.release()
        H264CodecCloseOutcome.Closed
    } catch (_: IllegalStateException) {
        H264CodecCloseOutcome.Failed("encoder release failed")
    }
}
