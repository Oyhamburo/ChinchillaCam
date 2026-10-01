package dev.chinchillacam.usbprobe

/**
 * [TlsCiphertextTransport] backed by the existing USB accessory stack (contract
 * `wifi-loopback-transport` §4.3).
 *
 * Delegates to [UsbTlsCiphertextIoAdapter] over an [AccessoryIoSession], preserving the AccessoryFrame
 * chunking, stream id, 64 KiB write cap, and fail-closed semantics exactly as before the seam was
 * introduced. The adapter already closes the session on any failure, so this wrapper only translates
 * the USB-specific result types into the transport-neutral [TlsCiphertextWriteResult] /
 * [TlsCiphertextReadResult].
 */
class UsbAccessoryTlsCiphertextTransport(
    private val session: AccessoryIoSession,
    private val adapter: UsbTlsCiphertextIoAdapter = UsbTlsCiphertextIoAdapter(),
) : TlsCiphertextTransport {
    override val maxWriteBytes: Int = USB_TLS_CIPHERTEXT_WRITE_MAX_BYTES

    override fun writeCiphertext(ciphertext: ByteArray): TlsCiphertextWriteResult =
        when (val result = adapter.write(session, ciphertext)) {
            UsbTlsCiphertextWriteResult.Sent -> TlsCiphertextWriteResult.Sent
            else -> TlsCiphertextWriteResult.Failed(result.toString())
        }

    override fun readCiphertext(): TlsCiphertextReadResult =
        when (val result = adapter.read(session)) {
            is UsbTlsCiphertextReadResult.Received -> TlsCiphertextReadResult.Received(result.ciphertext)
            UsbTlsCiphertextReadResult.EofEmpty -> TlsCiphertextReadResult.Eof(result.toString())
            else -> TlsCiphertextReadResult.Failed(result.toString())
        }

    override fun close() = session.close()
}
