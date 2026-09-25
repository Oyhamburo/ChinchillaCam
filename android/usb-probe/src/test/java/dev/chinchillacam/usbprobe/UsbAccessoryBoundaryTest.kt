package dev.chinchillacam.usbprobe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessoryBoundaryTest {
    @Test
    fun parsesLittleEndianAoaProtocolVersion() {
        val parsed = AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x02, 0x00))

        assertEquals(AoaProtocolVersion(2), parsed)
    }

    @Test
    fun rejectsZeroProtocolVersion() {
        val result = runCatching { AoaProtocolVersion.fromLittleEndian(byteArrayOf(0x00, 0x00)) }

        assertTrue(result.isFailure)
    }

    @Test
    fun validatesAccessoryIdentityRequiredFields() {
        val identity = AccessoryIdentity(
            manufacturer = "ChinchillaCam",
            model = "USB Probe",
            description = "AOA feasibility boundary",
            version = "0.1.0",
            uri = "https://example.invalid/chinchillacam",
            serial = "prototype",
        )

        assertTrue(identity.isValidForAoaHandshake())
        assertFalse(identity.copy(manufacturer = "").isValidForAoaHandshake())
    }

    @Test
    fun formatsDeviceSummaryWithoutHardwareClaims() {
        val summary = UsbDeviceSummary(
            vendorId = 0x18D1,
            productId = 0x2D00,
            protocolVersion = AoaProtocolVersion(2),
        )

        assertEquals(
            "USB device 18d1:2d00 reports AOA protocol v2; hardware transport remains unvalidated",
            summary.toString(),
        )
    }

    @Test
    fun permissionDeniedDoesNotOpenAccessory() {
        val gateway = RecordingAccessoryGateway()
        val accessory = TestAccessoryHandle("phone")

        val result = UsbAccessoryBoundary(gateway).open(accessory, hasPermission = false)

        assertEquals(AccessoryOpenResult.PermissionDenied(accessory), result)
        assertEquals(0, gateway.openAttempts)
    }

    @Test
    fun permissionGrantedOpensAccessoryAndExposesSessionStreams() {
        val input = ByteArrayInputStream(byteArrayOf(0x11, 0x22))
        val output = FlushTrackingOutputStream()
        val gatewaySession = RecordingCloseable()
        val gateway = RecordingAccessoryGateway(AccessoryStreams(input, output, gatewaySession))
        val accessory = TestAccessoryHandle("phone")

        val result = UsbAccessoryBoundary(gateway).open(accessory, hasPermission = true)

        assertTrue(result is AccessoryOpenResult.Opened)
        val session = (result as AccessoryOpenResult.Opened).session
        assertArrayEquals(byteArrayOf(0x11, 0x22), session.readExactly(2).getOrThrow())
        session.write(byteArrayOf(0x33, 0x44))
        assertArrayEquals(byteArrayOf(0x33, 0x44), output.toByteArray())
        assertEquals(1, output.flushCount)
        assertEquals(1, gateway.openAttempts)
        assertSame(accessory, gateway.openedAccessories.single())
    }

    @Test
    fun shortReadIsExplicitAndDoesNotPretendFrameIsComplete() {
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf(0x01, 0x02)),
            output = ByteArrayOutputStream(),
            closeable = RecordingCloseable(),
        )

        val result = session.readExactly(4)

        assertEquals(AccessoryReadResult.ShortRead(expectedBytes = 4, actualBytes = 2), result)
    }

    @Test
    fun eofReadIsExplicit() {
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            closeable = RecordingCloseable(),
        )

        val result = session.readExactly(1)

        assertEquals(AccessoryReadResult.Eof, result)
    }

    @Test
    fun closeIsIdempotentAndClosesStreamsAndGatewaySessionOnce() {
        val input = CloseTrackingInputStream(byteArrayOf(0x01))
        val output = CloseTrackingOutputStream()
        val closeable = RecordingCloseable()
        val session = AccessoryIoSession(input, output, closeable)

        session.close()
        session.close()

        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)
        assertEquals(1, closeable.closeCount)
    }

    @Test
    fun writeFlushesExpectedBytes() {
        val output = FlushTrackingOutputStream()
        val session = AccessoryIoSession(
            input = ByteArrayInputStream(byteArrayOf()),
            output = output,
            closeable = RecordingCloseable(),
        )

        session.write(byteArrayOf(0x55, 0x66))

        assertArrayEquals(byteArrayOf(0x55, 0x66), output.toByteArray())
        assertEquals(1, output.flushCount)
    }
}

private data class TestAccessoryHandle(val name: String)

private class RecordingAccessoryGateway(
    private val streams: AccessoryStreams? = null,
) : UsbAccessoryGateway<TestAccessoryHandle> {
    var openAttempts = 0
    val openedAccessories = mutableListOf<TestAccessoryHandle>()

    override fun openAccessory(accessory: TestAccessoryHandle): AccessoryStreams? {
        openAttempts += 1
        openedAccessories += accessory
        return streams
    }
}

private class RecordingCloseable : Closeable {
    var closeCount = 0
        private set

    override fun close() {
        closeCount += 1
    }
}

private class FlushTrackingOutputStream : ByteArrayOutputStream() {
    var flushCount = 0
        private set

    override fun flush() {
        flushCount += 1
        super.flush()
    }
}

private class CloseTrackingOutputStream : OutputStream() {
    var closeCount = 0
        private set

    override fun write(b: Int) = Unit

    override fun close() {
        closeCount += 1
    }
}

private class CloseTrackingInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var closeCount = 0
        private set

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun close() {
        closeCount += 1
    }
}
