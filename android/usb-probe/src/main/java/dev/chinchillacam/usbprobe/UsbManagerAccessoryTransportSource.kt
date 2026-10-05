package dev.chinchillacam.usbprobe

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/** Plain seam over the attached USB accessories, so selection and mapping stay JVM-testable. */
interface AttachedAccessoryPort<A> {
    fun attachedAccessories(): List<A>
    fun fingerprint(accessory: A): AccessoryFingerprint
    fun hasPermission(accessory: A): Boolean
    fun open(accessory: A): AccessoryOpenResult<A>
}

/** [AttachedAccessoryPort] over [UsbManager]; opening goes through [AndroidUsbAccessoryBoundary]. */
class AndroidAttachedAccessoryPort(private val usbManager: UsbManager) : AttachedAccessoryPort<UsbAccessory> {
    private val boundary = AndroidUsbAccessoryBoundary(usbManager)

    override fun attachedAccessories(): List<UsbAccessory> = usbManager.accessoryList?.toList().orEmpty()
    override fun fingerprint(accessory: UsbAccessory): AccessoryFingerprint = AccessoryFingerprint.fromUsbAccessory(accessory)
    override fun hasPermission(accessory: UsbAccessory): Boolean = usbManager.hasPermission(accessory)
    override fun open(accessory: UsbAccessory): AccessoryOpenResult<UsbAccessory> = boundary.open(accessory)
}

/**
 * Production [AccessoryTransportSource] (task c4b): opens the first attached accessory whose
 * identity matches the app's accessory filter (`res/xml/accessory_filter.xml`) as a USB ciphertext
 * transport. Foreign accessories are never opened. Requesting the USB permission is the UI's job
 * (c6): it uses [accessoryAwaitingPermission] and then calls `accessoryAttached()` again.
 */
class UsbManagerAccessoryTransportSource<A>(private val port: AttachedAccessoryPort<A>) : AccessoryTransportSource {
    override fun open(): AccessoryTransportOpenResult {
        val accessory = matchingAccessory() ?: return AccessoryTransportOpenResult.NotAttached
        if (!port.hasPermission(accessory)) return AccessoryTransportOpenResult.PermissionDenied
        val opened = try {
            port.open(accessory)
        } catch (error: Exception) {
            return AccessoryTransportOpenResult.Failed(error.toString())
        }
        return when (opened) {
            is AccessoryOpenResult.Opened -> AccessoryTransportOpenResult.Opened(UsbAccessoryTlsCiphertextTransport(opened.session))
            is AccessoryOpenResult.PermissionDenied -> AccessoryTransportOpenResult.PermissionDenied
            is AccessoryOpenResult.OpenFailed -> AccessoryTransportOpenResult.Failed("the accessory could not be opened")
        }
    }

    /** The matching accessory the user still has to grant permission for, if any. */
    fun accessoryAwaitingPermission(): A? = matchingAccessory()?.takeUnless(port::hasPermission)

    fun hasAttachedAccessoryWithoutPermission(): Boolean = accessoryAwaitingPermission() != null

    private fun matchingAccessory(): A? = port.attachedAccessories().firstOrNull { accessory ->
        val fingerprint = port.fingerprint(accessory)
        fingerprint.manufacturer == ACCESSORY_MANUFACTURER && fingerprint.model == ACCESSORY_MODEL
    }

    companion object {
        /** Must match `res/xml/accessory_filter.xml` (renamed together in T29). */
        const val ACCESSORY_MANUFACTURER = "ChinchillaCam"
        const val ACCESSORY_MODEL = "USB Probe"
    }
}
