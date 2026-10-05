package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable

class UsbManagerAccessoryTransportSourceTest {
    @Test
    fun noAccessoryIsNotAttached() {
        val source = UsbManagerAccessoryTransportSource(FakePort(attached = emptyList()))

        assertSame(AccessoryTransportOpenResult.NotAttached, source.open())
        assertFalse(source.hasAttachedAccessoryWithoutPermission())
    }

    @Test
    fun foreignAccessoryIsNotAttached() {
        val port = FakePort(attached = listOf(FOREIGN))

        assertSame(AccessoryTransportOpenResult.NotAttached, UsbManagerAccessoryTransportSource(port).open())
        assertTrue("a foreign accessory must never be opened", port.opened.isEmpty())
    }

    @Test
    fun matchingAccessoryWithoutPermissionIsPermissionDenied() {
        val port = FakePort(attached = listOf(FOREIGN, OURS), permitted = emptySet())
        val source = UsbManagerAccessoryTransportSource(port)

        assertSame(AccessoryTransportOpenResult.PermissionDenied, source.open())
        assertTrue(port.opened.isEmpty())
        assertTrue(source.hasAttachedAccessoryWithoutPermission())
        assertEquals(OURS, source.accessoryAwaitingPermission())
    }

    @Test
    fun openFailureIsFailed() {
        val port = FakePort(attached = listOf(OURS), permitted = setOf(OURS), openResult = AccessoryOpenResult.OpenFailed(OURS))

        val result = UsbManagerAccessoryTransportSource(port).open()

        assertTrue("expected Failed, got $result", result is AccessoryTransportOpenResult.Failed)
        assertEquals(listOf(OURS), port.opened)
    }

    @Test
    fun throwingOpenIsFailed() {
        val port = FakePort(attached = listOf(OURS), permitted = setOf(OURS), openError = SecurityException("revoked"))

        val result = UsbManagerAccessoryTransportSource(port).open()

        assertTrue("expected Failed, got $result", result is AccessoryTransportOpenResult.Failed)
    }

    @Test
    fun permittedMatchingAccessoryOpensUsbCiphertextTransport() {
        var closed = false
        val session = AccessoryIoSession(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), Closeable { closed = true })
        val port = FakePort(attached = listOf(FOREIGN, OURS), permitted = setOf(FOREIGN, OURS), openResult = AccessoryOpenResult.Opened(session))
        val source = UsbManagerAccessoryTransportSource(port)

        val result = source.open()

        assertTrue("expected Opened, got $result", result is AccessoryTransportOpenResult.Opened)
        val transport = (result as AccessoryTransportOpenResult.Opened).transport
        assertTrue(transport is UsbAccessoryTlsCiphertextTransport)
        assertEquals(listOf(OURS), port.opened)
        assertFalse(source.hasAttachedAccessoryWithoutPermission())
        assertNull(source.accessoryAwaitingPermission())
        transport.close()
        assertTrue("closing the transport must close the accessory session", closed)
    }

    private class FakePort(
        private val attached: List<String>,
        private val permitted: Set<String> = emptySet(),
        private val openResult: AccessoryOpenResult<String>? = null,
        private val openError: Exception? = null,
    ) : AttachedAccessoryPort<String> {
        val opened = mutableListOf<String>()

        override fun attachedAccessories(): List<String> = attached
        override fun fingerprint(accessory: String): AccessoryFingerprint = FINGERPRINTS.getValue(accessory)
        override fun hasPermission(accessory: String): Boolean = accessory in permitted
        override fun open(accessory: String): AccessoryOpenResult<String> {
            opened += accessory
            openError?.let { throw it }
            return checkNotNull(openResult)
        }
    }

    private companion object {
        const val OURS = "chinchillacam"
        const val FOREIGN = "foreign"
        val FINGERPRINTS = mapOf(
            OURS to AccessoryFingerprint.fromFields("ChinchillaCam", "USB Probe", "desc", "0.1.0", null, "serial"),
            FOREIGN to AccessoryFingerprint.fromFields("Acme", "Dock", "desc", "1.0", null, null),
        )
    }
}
