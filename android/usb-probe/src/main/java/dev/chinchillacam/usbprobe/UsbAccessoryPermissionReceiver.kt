package dev.chinchillacam.usbprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

class UsbAccessoryPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: context.packageName
        val expectedAction = AccessoryPermissionPlanner.plan(
            packageName = packageName,
            receiverClassName = javaClass.name,
        ).action
        if (intent.action != expectedAction) return
        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return

        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val opened = AndroidUsbAccessoryBoundary(usbManager).open(accessory)
        if (opened is AccessoryOpenResult.Opened) {
            opened.session.use { session ->
                AccessorySmokeRunner(maxPayloadBytes = MAX_PAYLOAD_BYTES).runApprovedSession(session)
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "dev.chinchillacam.usbprobe.extra.PACKAGE_NAME"
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
