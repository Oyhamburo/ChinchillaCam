package dev.chinchillacam.usbprobe

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side wiring between a reconnected, authenticated session and the encoded-video egress path
 * (contract `session-pipeline-wiring.md` §4.1, decision 1). It starts a [SessionRuntime] from a
 * [UsbTrustedReconnectResult.Reconnected] (or its explicit parts), exposes a trivial factory that
 * builds the fragmenting video sink over [SessionRuntimeVideoTransport], and turns a [SessionEnd]
 * into a stop-and-notify signal:
 *
 * - [SessionEnd.LocalClose] is a local stop: nothing user-visible is reported.
 * - Any other cause hands [onSessionEndedWithError] a neutral Spanish message describing the cause.
 *
 * The runtime invokes its `onEnd` on its own writer/reader thread. This binding never acts on that
 * thread: it hands the notification to [endExecutor] (contract §4.1, risk §5), so a handler that
 * calls [close] -- which joins the runtime threads with a bounded timeout -- cannot deadlock the
 * runtime thread against itself.
 *
 * [close] is idempotent. It ends the runtime with [SessionEnd.LocalClose] and joins the worker
 * threads. Because the join is bounded and skips the current thread, [close] is safe to call from
 * the [endExecutor] thread and from a test thread. In production it must NOT be called on the main
 * thread, since the bounded join would still block the UI thread.
 */
class SessionEgressBinding private constructor(
    private val runtime: SessionRuntime,
) {
    private val closed = AtomicBoolean(false)

    /** The session identity stamped on every outbound frame. */
    val sessionId: String get() = runtime.sessionId

    /**
     * Trivial, no-I/O factory producing the fragmenting encoded-video sink over the running runtime.
     * Invoking it only wires objects; the TLS write happens on the runtime writer thread.
     */
    val videoSinkFactory: () -> EncodedVideoEgressSink = {
        FragmentingEncodedVideoEgressSink(
            EncodedVideoFragmentingSessionFrameSink(SessionRuntimeVideoTransport(runtime)),
        )
    }

    /** Ends the runtime with [SessionEnd.LocalClose] and joins its threads; idempotent (see class doc). */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtime.close()
    }

    companion object {
        /** Neutral Spanish, cause-typed copy that never claims a successful transmission (contract §4.1, §4.2). */
        internal fun messageForEnd(cause: SessionEnd): String = when (cause) {
            SessionEnd.PeerDead -> "Se perdió la conexión con la computadora."
            SessionEnd.Backpressure -> "El envío de video se saturó y la sesión se detuvo."
            is SessionEnd.WriteFailed -> "No se pudo enviar datos a la computadora; la sesión se detuvo."
            is SessionEnd.ReadFailed -> "No se pudo recibir datos de la computadora; la sesión se detuvo."
            is SessionEnd.ProtocolViolation -> "La computadora envió datos inesperados; la sesión se detuvo."
            SessionEnd.LocalClose -> "La sesión se cerró."
        }

        /** Starts a binding from the explicit parts a [UsbTrustedReconnectResult.Reconnected] carries. */
        fun start(
            channel: SslEngineUsbTlsEstablishedChannel,
            frameAdapter: TlsSessionFrameIoAdapter,
            sessionId: String,
            nextOutboundSequence: Int,
            nextInboundSequence: Int,
            onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
            onSessionEndedWithError: (SessionEnd, String) -> Unit,
            endExecutor: Executor,
            config: SessionRuntimeConfig = SessionRuntimeConfig(),
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ): SessionEgressBinding {
            val runtime = SessionRuntime.start(
                channel = channel,
                frameAdapter = frameAdapter,
                sessionId = sessionId,
                nextOutboundSequence = nextOutboundSequence,
                nextInboundSequence = nextInboundSequence,
                config = config,
                clock = clock,
                onCameraControlCommand = onCameraControlCommand,
                onEnd = { cause ->
                    // Hand off the runtime thread so the stop-and-notify path (which may join the
                    // runtime threads via close) never runs on the runtime writer/reader thread.
                    endExecutor.execute {
                        if (cause != SessionEnd.LocalClose) {
                            onSessionEndedWithError(cause, messageForEnd(cause))
                        }
                    }
                },
            )
            return SessionEgressBinding(runtime)
        }

        /** Starts a binding directly from a [UsbTrustedReconnectResult.Reconnected]. */
        fun start(
            reconnected: UsbTrustedReconnectResult.Reconnected,
            onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
            onSessionEndedWithError: (SessionEnd, String) -> Unit,
            endExecutor: Executor,
            config: SessionRuntimeConfig = SessionRuntimeConfig(),
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ): SessionEgressBinding = start(
            channel = reconnected.channel,
            frameAdapter = reconnected.frameAdapter,
            sessionId = reconnected.sessionId,
            nextOutboundSequence = reconnected.nextOutboundSequence,
            nextInboundSequence = reconnected.nextInboundSequence,
            onCameraControlCommand = onCameraControlCommand,
            onSessionEndedWithError = onSessionEndedWithError,
            endExecutor = endExecutor,
            config = config,
            clock = clock,
        )
    }
}

/**
 * Composes a [SessionEgressBinding] with the visible-camera service layer WITHOUT a production
 * session source (contract `session-pipeline-wiring.md` §4.6, decision 4: USB production source and
 * pairing UI are out of scope). It is built in ONE step by [start], which starts its own binding with
 * the composition's own end handler, so neither side needs a holder for the other
 * (`android-followups.md` §4.3). [ServiceSessionLauncher] is its production caller. It only exposes
 * the seams the service uses:
 *
 * - [encodedVideoSinkFactory] is handed to [VisibleCameraPipelineController] as its egress factory.
 * - On any session end other than [SessionEnd.LocalClose] (already handed off the runtime thread by
 *   the binding), it publishes `Error` to [VisibleCameraServiceStatusStore] with the typed Spanish
 *   message and asks the pipeline to stop through the same failure-stop path p1 uses
 *   (`requestPipelineFailureStop`, which mirrors `onPipelineFailureStop`).
 * - [close] ends the owned binding; idempotent, with the same threading rules as
 *   [SessionEgressBinding.close].
 */
class SessionEgressServicePipelineComposition private constructor(
    private val binding: SessionEgressBinding,
) {
    val encodedVideoSinkFactory: () -> EncodedVideoEgressSink = binding.videoSinkFactory

    /** Ends the owned binding (see [SessionEgressBinding.close]); idempotent. */
    fun close() = binding.close()

    companion object {
        /**
         * Starts the binding from [reconnected] with this composition's end handler and returns the
         * composition that owns it. [requestPipelineFailureStop] receives the Spanish message just
         * published as `Error`; it runs on the [endExecutor] thread, never on the runtime thread.
         */
        fun start(
            reconnected: UsbTrustedReconnectResult.Reconnected,
            requestPipelineFailureStop: (message: String) -> Unit,
            endExecutor: Executor,
            onCameraControlCommand: (SessionPayload.CameraControlCommand) -> Unit,
            config: SessionRuntimeConfig = SessionRuntimeConfig(),
            clock: () -> Long = { System.nanoTime() / 1_000_000 },
        ): SessionEgressServicePipelineComposition {
            val binding = SessionEgressBinding.start(
                reconnected = reconnected,
                onCameraControlCommand = onCameraControlCommand,
                onSessionEndedWithError = { _, message ->
                    publishErrorAndRequestStop(message, requestPipelineFailureStop)
                },
                endExecutor = endExecutor,
                config = config,
                clock = clock,
            )
            return SessionEgressServicePipelineComposition(binding)
        }

        /** Publishes the visible error and requests the same failure stop p1 wires through the owner. */
        private fun publishErrorAndRequestStop(message: String, requestPipelineFailureStop: (String) -> Unit) {
            VisibleCameraServiceStatusStore.publish(
                VisibleCameraServiceStatus(state = VisibleCameraServiceState.Error, message = message),
            )
            requestPipelineFailureStop(message)
        }
    }
}
