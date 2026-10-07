package dev.chinchillacam.usbprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text contract for the product screen (task c6b, `android-production-connection.md` §3.5,
 * §4.6): manifest entry points and the wiring of [ConnectionActivity] to the controller, the
 * scanner and the USB/camera permissions. The planner itself is covered by `ConnectionScreenPlannerTest`.
 */
class ConnectionActivityContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val source = File("src/main/java/dev/chinchillacam/usbprobe/ConnectionActivity.kt").readText()

    @Test
    fun connectionActivityIsTheLauncherAndTheAccessoryEntryPoint() {
        val activity = activityBlock(".ConnectionActivity")
        assertTrue("must be exported", activity.contains("android:exported=\"true\""))
        assertTrue("must be singleTop", activity.contains("android:launchMode=\"singleTop\""))
        assertTrue("must be labelled ChinchillaCam", activity.contains("android:label=\"ChinchillaCam\""))
        assertTrue("must be the launcher", activity.contains("android.intent.action.MAIN") && activity.contains("android.intent.category.LAUNCHER"))
        assertTrue("must receive the accessory attach", activity.contains("<action android:name=\"android.hardware.usb.action.USB_ACCESSORY_ATTACHED\" />"))
        assertTrue("must keep the accessory filter", activity.contains("android:resource=\"@xml/accessory_filter\""))
    }

    @Test
    fun usbProbeActivityIsUnexportedDiagnosticsOnly() {
        val activity = activityBlock(".UsbProbeActivity")
        assertTrue("must not be exported", activity.contains("android:exported=\"false\""))
        assertFalse("must not be a launcher", activity.contains("LAUNCHER"))
        assertFalse("must not receive the accessory attach", activity.contains("USB_ACCESSORY_ATTACHED"))
        assertTrue("diagnostics must be opened from the product screen", Regex("""Intent\(this, UsbProbeActivity::class\.java\)""").containsMatchIn(source))
    }

    @Test
    fun runtimeIsObtainedOffTheMainThreadAndTheListenerIsReleased() {
        assertContains("the runtime must be built on a background thread", """Thread\(\{[^}]{0,120}PhoneConnectionRuntime\.get\(""")
        assertContains("the listener must be registered", """\.addListener\(""")
        assertTrue("onDestroy must remove the listener", body("onDestroy").contains("removeListener("))
        assertContains("listener updates must render on the main thread", """mainHandler\.post \{""")
    }

    @Test
    fun accessoryAttachIntentResumesTheController() {
        val handler = body("handleAccessoryIntent")
        assertTrue(handler.contains("UsbManager.ACTION_USB_ACCESSORY_ATTACHED") && handler.contains(".accessoryAttached()"))
        assertTrue("onNewIntent must handle the attach", body("onNewIntent").contains("handleAccessoryIntent("))
    }

    @Test
    fun everyActionAndControllerOperationIsWired() {
        val onAction = body("onAction")
        assertFalse("the action mapping must be exhaustive without else", onAction.contains("else ->"))
        ConnectionAction.values().forEach { action ->
            assertTrue("missing mapping for $action", onAction.contains("ConnectionAction.${action.name} ->"))
        }
        listOf("qrScanned(", "cancel()", "confirmPairing()", "rejectPairing()", "disconnect()", "connect(", "forget(", "accessoryAttached()")
            .forEach { operation -> assertTrue("missing controller op $operation", source.contains(".$operation")) }
    }

    @Test
    fun scannerUsesCamera2AndClosesWhenLeavingTheScreen() {
        assertContains("the scanner must be the Camera2 QR scanner", """Camera2QrScanner\(""")
        assertTrue("onPause must close the scanner", body("onPause").contains("closeScanner()"))
        assertTrue("closeScanner must close it", body("closeScanner").contains("?.close()"))
        assertContains("the camera permission must be requested", """requestPermissions\(arrayOf\(Manifest\.permission\.CAMERA\)""")
    }

    @Test
    fun usbPermissionIsRequestedThroughANotExportedReceiver() {
        assertContains("the accessory awaiting permission must come from the runtime", """PhoneConnectionRuntime\.accessoryAwaitingPermission\(""")
        assertContains("the USB permission must be requested", """usbManager\.requestPermission\(""")
        assertContains("the permission result receiver must not be exported", """registerNotExportedReceiver\(""")
        assertContains("the pending intent must target this package", """setPackage\(packageName\)""")
        assertTrue("onDestroy must unregister the receiver", body("onDestroy").contains("unregisterReceiver("))
    }

    @Test
    fun qualityControlsPersistAndRefreshOffMainThreadAndReportReconfigureFailures() {
        assertTrue("catalog must refresh on resume", body("onResume").contains("refreshCameraCatalog()"))
        assertTrue("catalog snapshot must run in a worker", body("refreshCameraCatalog").contains("Thread({"))
        assertTrue("the quality section must be rendered", body("render").contains("renderQuality(it)"))
        assertTrue("selection must save a validated camera", body("selectCamera").contains("saveSelection(snapshot, id)"))
        assertTrue("quality preference must persist", body("selectQuality").contains(".save(preference)"))
        assertTrue("runtime must receive live changes", body("applyQuality").contains("PhoneConnectionRuntime.applyCameraQuality("))
        assertTrue("service-start errors must appear as notices", body("applyQuality").contains("onFailure { localNotice ="))
    }

    private fun activityBlock(name: String): String {
        val match = Regex("""<activity\s+android:name="${Regex.escape(name)}"[^>]*?(/>|>[\s\S]*?</activity>)""").find(manifest)
        assertTrue("manifest must declare $name", match != null)
        return match!!.value
    }

    private fun body(function: String): String {
        assertTrue("missing fun $function", source.contains("fun $function("))
        return source.substringAfter("fun $function(").substringBefore("\n    }\n")
    }

    private fun assertContains(message: String, pattern: String) {
        assertTrue(message, Regex(pattern).containsMatchIn(source))
    }
}
