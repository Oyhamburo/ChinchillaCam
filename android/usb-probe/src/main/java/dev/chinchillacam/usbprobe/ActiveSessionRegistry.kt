package dev.chinchillacam.usbprobe

/**
 * What a launched session hands the session-mode camera service (`android-production-connection.md`
 * §4.4): the egress sink factory and the callback the service invokes when it stops on its own.
 */
class ActiveSessionEntry(
    val encodedVideoSinkFactory: () -> EncodedVideoEgressSink,
    val onServiceStopped: (message: String?) -> Unit,
)

data class ActiveSessionRegistration(val token: Long, val entry: ActiveSessionEntry)

/**
 * Thread-safe slot holding at most one active session. Every operation that ends an entry takes the
 * token it was registered with, so a late call for an older session never touches a newer one.
 */
open class ActiveSessionSlot {
    private val lock = Any()
    private var nextToken = 0L
    private var current: ActiveSessionRegistration? = null

    /** Replaces any previous entry and returns the token that identifies this one. */
    fun register(entry: ActiveSessionEntry): Long = synchronized(lock) {
        nextToken += 1
        current = ActiveSessionRegistration(nextToken, entry)
        nextToken
    }

    fun current(): ActiveSessionRegistration? = synchronized(lock) { current }

    /** Clears the entry only if [token] is still the current one. */
    fun clear(token: Long) {
        take(token)
    }

    /** Invokes the [token] entry's [ActiveSessionEntry.onServiceStopped] once (outside the lock) and clears it. */
    fun notifyServiceStopped(token: Long, message: String?) {
        take(token)?.entry?.onServiceStopped?.invoke(message)
    }

    private fun take(token: Long): ActiveSessionRegistration? = synchronized(lock) {
        current?.takeIf { it.token == token }?.also { current = null }
    }
}

/** Process-level registry shared by the session launcher and the camera service. */
object ActiveSessionRegistry : ActiveSessionSlot()

sealed class ServicePipelineSinkResolution {
    /** Diagnostic start: no egress sink, encoded video is discarded in memory. */
    object Diagnostic : ServicePipelineSinkResolution()
    data class Session(val token: Long, val sinkFactory: () -> EncodedVideoEgressSink) : ServicePipelineSinkResolution()
    data class NoSession(val message: String) : ServicePipelineSinkResolution()
}

/** Decides which egress sink the camera service pipeline uses for a start command. */
object ServicePipelineSinkResolver {
    fun resolve(sessionMode: Boolean, registry: ActiveSessionSlot): ServicePipelineSinkResolution {
        if (!sessionMode) return ServicePipelineSinkResolution.Diagnostic
        val active = registry.current() ?: return ServicePipelineSinkResolution.NoSession("No hay una sesión activa con la computadora.")
        return ServicePipelineSinkResolution.Session(active.token, active.entry.encodedVideoSinkFactory)
    }
}
