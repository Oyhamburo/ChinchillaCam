package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Task c4a (`android-production-connection.md` §4.4): process registry the session-mode service reads. */
class ActiveSessionRegistryTest {
    private val sinkFactory: () -> EncodedVideoEgressSink = { error("not invoked by these tests") }

    @Test
    fun serviceUsesSessionSinkFactoryWhenSessionActive() {
        val registry = ActiveSessionSlot()

        assertEquals(
            ServicePipelineSinkResolution.NoSession("No hay una sesión activa con la computadora."),
            ServicePipelineSinkResolver.resolve(sessionMode = true, registry = registry),
        )

        val token = registry.register(ActiveSessionEntry(sinkFactory) {})
        val session = ServicePipelineSinkResolver.resolve(sessionMode = true, registry = registry)
        session as ServicePipelineSinkResolution.Session
        assertSame(sinkFactory, session.sinkFactory)
        assertEquals(token, session.token)

        assertEquals(ServicePipelineSinkResolution.Diagnostic, ServicePipelineSinkResolver.resolve(sessionMode = false, registry = registry))
    }

    @Test
    fun staleTokenClearIsIgnored() {
        val registry = ActiveSessionSlot()
        val first = registry.register(ActiveSessionEntry(sinkFactory) {})
        registry.clear(first)
        val second = registry.register(ActiveSessionEntry(sinkFactory) {})

        registry.clear(first)

        assertEquals(second, registry.current()?.token)
        registry.clear(second)
        assertNull(registry.current())
    }

    @Test
    fun notifyServiceStoppedFiresOnceForTheCurrentToken() {
        val registry = ActiveSessionSlot()
        val notices = mutableListOf<String?>()
        val stale = registry.register(ActiveSessionEntry(sinkFactory) { notices += "stale:$it" })
        registry.clear(stale)
        val token = registry.register(ActiveSessionEntry(sinkFactory) { notices += it })

        registry.notifyServiceStopped(stale, "ignored")
        registry.notifyServiceStopped(token, "Se detuvo la cámara.")
        registry.notifyServiceStopped(token, "again")

        assertEquals(listOf<String?>("Se detuvo la cámara."), notices)
        assertNull(registry.current())
    }
}
