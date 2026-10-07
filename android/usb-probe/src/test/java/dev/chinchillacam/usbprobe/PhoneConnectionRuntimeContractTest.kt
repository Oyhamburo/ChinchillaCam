package dev.chinchillacam.usbprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-text contract for the production composition of [PhoneConnectionController] (task c4b). */
class PhoneConnectionRuntimeContractTest {
    private val source = File("src/main/java/dev/chinchillacam/usbprobe/PhoneConnectionRuntime.kt").readText()

    @Test
    fun phoneIdentityReachesTheTlsChannelPairingVerifierAndReconnect() {
        assertContains("the identity must come from the Android keystore", """val identity = AndroidKeyStorePhoneTlsIdentity\(\)""")
        assertContains("the TLS channel must present the phone identity", """val tlsChannel = SslEngineUsbTlsChannel\(phoneTlsIdentity = identity\)""")
        assertContains("pairing must verify the proof over that channel", """UsbTlsPairingProofVerifier\(clock, tlsChannel\)""")
        assertContains("reconnect must use the identity and that channel", """UsbTrustedReconnect\(clock, identity, tlsChannel = tlsChannel\)""")
        assertContains("the first session must start with the identity", """PairedSessionStarter\(identity\)""")
        assertContains("the controller must derive the SAS from the identity SPKI", """phoneSpki = identity\.subjectPublicKeyInfoDer""")
    }

    @Test
    fun sessionsLaunchTheCameraServiceAndUseTheUsbAccessorySource() {
        assertContains("camera service must use the Android adapter", """val cameraService = AndroidCameraServiceControl\(context\)""")
        assertContains("sessions must launch through that camera service", """ServiceSessionLauncher\(\s*cameraService = cameraService""")
        assertContains("quality updates must use the same service", """CameraQualityApplier\(controller::snapshot, cameraService\)""")
        assertContains("the camera id must come from the session resolver", """cameraIdProvider = cameraIdResolver::resolve""")
        assertContains("the accessory source must be the UsbManager adapter", """accessorySource = accessorySource""")
        assertContains("the accessory source must be built over UsbManager", """UsbManagerAccessoryTransportSource\(AndroidAttachedAccessoryPort\(""")
        assertContains("trust must persist in the Android store", """AndroidTrustedDesktopStores\.trustedDesktopStore\(""")
    }

    @Test
    fun remoteQualityCommandsAndLocalChangesReachTheSameSharedPreferences() {
        assertContains("reader callback forwards commands", """onCameraControlCommand = \{ qualityHandler\.onCommand\(it\.command, it\.arguments\) \}""")
        assertContains("quality worker is dedicated", """executor = singleThread\("quality-control"\)""")
        assertContains("quality egress uses active controller", """send = controller::sendControl""")
        assertContains("quality updates use the live applier", """apply = \{ qualityApplier\.apply\(it\) \}""")
        assertContains("session transition resets subscription", """qualityHandler\.reset\(\)""")
        assertContains("active handle retries early subscription", """qualityHandler\.onSessionReady\(\)""")
        assertContains("selection uses UI key", """SharedPreferencesStringStore\(preferences, CAMERA_SELECTION_KEY\)""")
        assertContains("quality uses UI key", """SharedPreferencesStringStore\(preferences, QUALITY_KEY\)""")
        val activity = File("src/main/java/dev/chinchillacam/usbprobe/ConnectionActivity.kt").readText()
        assertTrue(Regex("""selectedCameraId = id\s+applyQuality\(id\)\s+PhoneConnectionRuntime\.notifyLocalQualityChange\(this\)""").containsMatchIn(activity))
        assertTrue(Regex("""qualityPreference = preference\s+applyQuality\(selectedCameraId\)\s+PhoneConnectionRuntime\.notifyLocalQualityChange\(this\)""").containsMatchIn(activity))
        val diagnosticActivity = File("src/main/java/dev/chinchillacam/usbprobe/UsbProbeActivity.kt").readText()
        assertTrue(
            "diagnostic camera selection must apply live and notify the subscribed desktop even if applying fails",
            Regex("""cameraSelectionPreference\.saveSelection\(snapshot, row\.cameraId\)\s+selectedCameraId = row\.cameraId\s+runCatching \{ PhoneConnectionRuntime\.applyCameraQuality\(this@UsbProbeActivity, row\.cameraId\) \}\s+runCatching \{ PhoneConnectionRuntime\.notifyLocalQualityChange\(this@UsbProbeActivity\) \}""")
                .containsMatchIn(diagnosticActivity),
        )
    }

    @Test
    fun workerIsSingleThreadedAndNothingListensOnTheNetwork() {
        assertContains("the controller worker must be a single thread", """worker = singleThread\("phone-connection"\)""")
        assertContains("singleThread must build a single-thread executor", """Executors\.newSingleThreadExecutor""")
        assertFalse(
            "the runtime must not use the socket-based pairing verifier",
            Regex("""(?<!Usb)TlsPairingProofVerifier\(""").containsMatchIn(source),
        )
        assertFalse("the runtime must not open sockets", Regex("""Socket""").containsMatchIn(source))
    }

    @Test
    fun cableDetachReachesTheControllerWithoutAnyActivity() {
        assertContains("the detach receiver must be registered once, from build, with the application context", """registerAccessoryDetached\(context, controller\)""")
        assertContains("build must receive the application context", """build\(context\.applicationContext \?: context\)""")
        assertContains("the receiver must listen for accessory detach", """IntentFilter\(UsbManager\.ACTION_USB_ACCESSORY_DETACHED\)""")
        assertContains("a detach must end the session in the controller", """controller\.accessoryDetached\(\)""")
        assertContains("API 33+ registration must declare the receiver not exported", """registerReceiver\(receiver, filter, Context\.RECEIVER_NOT_EXPORTED\)""")
    }

    private fun assertContains(message: String, pattern: String) {
        assertTrue(message, Regex(pattern).containsMatchIn(source))
    }
}
