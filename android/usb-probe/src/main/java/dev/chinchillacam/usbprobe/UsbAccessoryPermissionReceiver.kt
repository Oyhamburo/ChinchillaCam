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
        val plan = AccessoryPermissionCallbackPlanner.plan(
            actionMatches = intent.action == expectedAction,
            hasGrantExtra = intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED),
            permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
            hasAccessory = intent.hasExtra(UsbManager.EXTRA_ACCESSORY),
        )
        if (plan !is AccessoryPermissionReceiverPlan.RecordAndLaunchActivity) return

        val activityIntent = Intent(context, UsbProbeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_PERMISSION_CALLBACK, plan.callback.toExtraValue())
            putExtra(EXTRA_PERMISSION_TOKEN, intent.getStringExtra(EXTRA_PERMISSION_TOKEN))
            putExtra(EXTRA_ACCESSORY_FINGERPRINT, callbackAccessoryFingerprint(intent)?.stableString())
            intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)?.let {
                putExtra(UsbManager.EXTRA_ACCESSORY, it)
            }
        }
        context.startActivity(activityIntent)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "dev.chinchillacam.usbprobe.extra.PACKAGE_NAME"
        const val EXTRA_PERMISSION_CALLBACK = "dev.chinchillacam.usbprobe.extra.PERMISSION_CALLBACK"
        const val EXTRA_PERMISSION_TOKEN = "dev.chinchillacam.usbprobe.extra.PERMISSION_TOKEN"
        const val EXTRA_ACCESSORY_FINGERPRINT = "dev.chinchillacam.usbprobe.extra.ACCESSORY_FINGERPRINT"
        const val CALLBACK_GRANTED = "granted"
        const val CALLBACK_DENIED = "denied"
        const val CALLBACK_MISSING_ACCESSORY = "missing_accessory"
        const val CALLBACK_MISSING_PERMISSION_RESULT = "missing_permission_result"
    }
}

@Suppress("DEPRECATION")
private fun callbackAccessoryFingerprint(intent: Intent): AccessoryFingerprint? {
    val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return null
    return AccessoryFingerprint.fromUsbAccessory(accessory)
}

fun AccessoryPermissionCallback.toExtraValue(): String = when (this) {
    AccessoryPermissionCallback.Granted -> UsbAccessoryPermissionReceiver.CALLBACK_GRANTED
    AccessoryPermissionCallback.Denied -> UsbAccessoryPermissionReceiver.CALLBACK_DENIED
    AccessoryPermissionCallback.MissingAccessory -> UsbAccessoryPermissionReceiver.CALLBACK_MISSING_ACCESSORY
    AccessoryPermissionCallback.MissingPermissionResult -> UsbAccessoryPermissionReceiver.CALLBACK_MISSING_PERMISSION_RESULT
}

fun permissionCallbackFromExtra(value: String?): AccessoryPermissionCallback? = when (value) {
    UsbAccessoryPermissionReceiver.CALLBACK_GRANTED -> AccessoryPermissionCallback.Granted
    UsbAccessoryPermissionReceiver.CALLBACK_DENIED -> AccessoryPermissionCallback.Denied
    UsbAccessoryPermissionReceiver.CALLBACK_MISSING_ACCESSORY -> AccessoryPermissionCallback.MissingAccessory
    UsbAccessoryPermissionReceiver.CALLBACK_MISSING_PERMISSION_RESULT -> AccessoryPermissionCallback.MissingPermissionResult
    else -> null
}
