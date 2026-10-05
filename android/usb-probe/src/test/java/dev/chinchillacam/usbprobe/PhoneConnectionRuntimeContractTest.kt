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
        assertContains("sessions must launch through the camera service", """ServiceSessionLauncher\(\s*cameraService = AndroidCameraServiceControl\(""")
        assertContains("the camera id must come from the session resolver", """cameraIdProvider = cameraIdResolver::resolve""")
        assertContains("the accessory source must be the UsbManager adapter", """accessorySource = accessorySource""")
        assertContains("the accessory source must be built over UsbManager", """UsbManagerAccessoryTransportSource\(AndroidAttachedAccessoryPort\(""")
        assertContains("trust must persist in the Android store", """AndroidTrustedDesktopStores\.trustedDesktopStore\(""")
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

    private fun assertContains(message: String, pattern: String) {
        assertTrue(message, Regex(pattern).containsMatchIn(source))
    }
}
