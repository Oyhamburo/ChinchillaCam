package dev.chinchillacam.usbprobe

@JvmInline
value class AoaProtocolVersion(val value: Int) {
    init {
        require(value > 0) { "AOA protocol version must be positive" }
    }

    companion object {
        fun fromLittleEndian(bytes: ByteArray): AoaProtocolVersion {
            require(bytes.size == 2) { "AOA protocol version response must be exactly two bytes" }

            val parsed = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            return AoaProtocolVersion(parsed)
        }
    }
}

data class AccessoryIdentity(
    val manufacturer: String,
    val model: String,
    val description: String,
    val version: String,
    val uri: String,
    val serial: String,
) {
    fun isValidForAoaHandshake(): Boolean =
        manufacturer.isNotBlank() &&
            model.isNotBlank() &&
            description.isNotBlank() &&
            version.isNotBlank()
}

data class UsbDeviceSummary(
    val vendorId: Int,
    val productId: Int,
    val protocolVersion: AoaProtocolVersion,
) {
    override fun toString(): String =
        "USB device ${vendorId.toFourDigitHex()}:${productId.toFourDigitHex()} reports " +
            "AOA protocol v${protocolVersion.value}; hardware transport remains unvalidated"
}

private fun Int.toFourDigitHex(): String = toString(16).padStart(4, '0')
