package dev.chinchillacam.usbprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Process-level owner of the production [PhoneConnectionController] (task c4b,
 * `android-production-connection.md` §4.3). Built once, lazily, with the application context.
 * Construction touches the Android keystore (possibly generating the phone key), so callers should
 * call [get] off the main thread the first time. Nothing here opens a network listener: pairing,
 * reconnection and sessions only run over the USB accessory.
 */
object PhoneConnectionRuntime {
    private class Components(
        val controller: PhoneConnectionController,
        val identity: AndroidKeyStorePhoneTlsIdentity,
        val accessorySource: UsbManagerAccessoryTransportSource<UsbAccessory>,
        val qualityApplier: CameraQualityApplier,
    )

    private var components: Components? = null

    fun get(context: Context): PhoneConnectionController = components(context).controller

    /** False if the session is no longer connected; service-start exceptions are left to the UI to report. */
    fun applyCameraQuality(context: Context, cameraId: String?): Boolean = components(context).qualityApplier.apply(cameraId)

    /** `true` when the phone key had to be regenerated: every desktop must be paired again (c6 shows it). */
    fun identityRegenerated(context: Context): Boolean = components(context).identity.regenerated

    /** Matching accessory the UI (c6) must request the USB permission for, if any. */
    fun accessoryAwaitingPermission(context: Context): UsbAccessory? = components(context).accessorySource.accessoryAwaitingPermission()

    @Synchronized
    private fun components(context: Context): Components = components ?: build(context.applicationContext ?: context).also { components = it }

    private fun build(context: Context): Components {
        val clock = EpochSecondsSource { System.currentTimeMillis() / 1000 }
        val identity = AndroidKeyStorePhoneTlsIdentity()
        val tlsChannel = SslEngineUsbTlsChannel(phoneTlsIdentity = identity)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessorySource = UsbManagerAccessoryTransportSource(AndroidAttachedAccessoryPort(usbManager))
        val cameraIdResolver = SessionCameraIdResolver(
            snapshotProvider = { cameraCatalogSnapshot(context) },
            preference = CameraSelectionPreference(
                SharedPreferencesStringStore(context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE), CAMERA_SELECTION_KEY),
            ),
        )
        val cameraService = AndroidCameraServiceControl(context)
        val sessionLauncher = ServiceSessionLauncher(
            cameraService = cameraService,
            cameraIdProvider = cameraIdResolver::resolve,
            endExecutor = singleThread("session-end"),
            // Documented ignore until M7/T26 (camera controls): the only intentionally unhandled callback.
            onCameraControlCommand = { },
        )
        val coordinator = PendingPairingCoordinator(clock, SecureRandomChallengeNonceSource(clock), UnusedByteProofVerifier)
        val controller = PhoneConnectionController(
            accessorySource = accessorySource,
            pairingFlow = UsbPairingFlow(coordinator, UsbTlsPairingProofVerifier(clock, tlsChannel)),
            pairedSessionStarter = PairedSessionStarter(identity),
            reconnect = UsbTrustedReconnect(clock, identity, tlsChannel = tlsChannel),
            store = AndroidTrustedDesktopStores.trustedDesktopStore(context),
            authority = ActiveDesktopAuthority(),
            phoneSpki = identity.subjectPublicKeyInfoDer,
            sessionLauncher = sessionLauncher,
            worker = singleThread("phone-connection"),
            epochSecondsSource = clock,
        )
        registerAccessoryDetached(context, controller)
        return Components(controller, identity, accessorySource, CameraQualityApplier(controller::snapshot, cameraService))
    }

    /** Registered once with the application context, so a cable pull ends the session even with no activity alive. */
    private fun registerAccessoryDetached(context: Context, controller: PhoneConnectionController) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) controller.accessoryDetached()
            }
        }
        registerNotExportedReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED))
    }

    /** Same catalog adapter as [VisibleCameraForegroundService] and [UsbProbeActivity]. */
    private fun cameraCatalogSnapshot(context: Context): CameraCatalogSnapshot {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return CameraCapabilityCatalog(AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(cameraManager))).snapshot()
    }

    private fun singleThread(name: String): Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    /**
     * [UsbPairingFlow] verifies the proof over the USB transport, so the coordinator's byte-based
     * proof path is never reached in production; it fails closed if it ever is.
     */
    private object UnusedByteProofVerifier : PairingProofVerifier {
        override fun verify(challenge: PairingProofChallenge, proofBytes: ByteArray): PairingProofVerificationResult =
            PairingProofVerificationResult.Rejected("byte-based pairing proof is not used over USB")
    }

    /** Same preference file and key as [UsbProbeActivity]'s camera selection (kept in sync by hand). */
    private const val CAMERA_PREFERENCES = "dev.chinchillacam.usbprobe.camera"
    private const val CAMERA_SELECTION_KEY = "selected_direct_camera_id"
}

/** Sends a reconfiguration only for a live session; injectable state and service keep this contract testable. */
class CameraQualityApplier(
    private val state: () -> PhoneConnectionState,
    private val cameraService: CameraServiceControl,
) {
    fun apply(cameraId: String?): Boolean {
        if (state() !is PhoneConnectionState.Connected) return false
        cameraService.reconfigure(cameraId)
        return true
    }
}

/**
 * Context-registered receiver reachable only by the system and this app (the explicit flag is
 * required from API 33; earlier context receivers have no export flag).
 */
internal fun registerNotExportedReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }
}
