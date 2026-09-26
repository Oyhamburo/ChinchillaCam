package dev.chinchillacam.usbprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class UsbProbeActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var safety: TextView
    private lateinit var action: Button
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionState: AccessoryPermissionUiModel = AccessoryPermissionUiModel.Idle
    private var permissionGate = AccessoryPermissionRequestGate(PERMISSION_CALLBACK_TIMEOUT_MILLIS)
    private var busy: Boolean = false
    private var lastResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        action.setOnClickListener { requestPermissionFromUserAction() }
        handlePermissionCallbackIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePermissionCallbackIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUi() {
        title = TextView(this).apply { textSize = 22f }
        status = TextView(this).apply { textSize = 16f }
        safety = TextView(this).apply { textSize = 14f }
        action = Button(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
            addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(action, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(safety, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(layout)
    }

    private fun render() {
        val ui = UsbProbeScreenPlanner.plan(
            accessoryAvailable = currentAccessory() != null,
            permissionState = permissionState,
            busy = busy,
            lastResult = lastResult,
        )
        title.text = ui.title
        status.text = ui.statusText
        safety.text = ui.safetyNotice
        action.text = ui.primaryActionLabel
        action.isEnabled = ui.primaryActionEnabled
    }

    private fun requestPermissionFromUserAction() {
        val accessory = currentAccessory()
        if (accessory == null) {
            lastResult = "No hay accesorio USB disponible."
            render()
            return
        }

        if (usbManager.hasPermission(accessory)) {
            runApprovedAccessory(accessory)
            return
        }

        val plan = AccessoryPermissionPlanner.plan(
            packageName = packageName,
            receiverClassName = UsbAccessoryPermissionReceiver::class.java.name,
        )
        val request = permissionGate.beginRequest(
            fingerprint = AccessoryFingerprint.fromUsbAccessory(accessory),
            nowMillis = SystemClock.elapsedRealtime(),
        )
        val intent = Intent(plan.action).apply {
            setClassName(plan.packageName, plan.receiverClassName)
            putExtra(UsbAccessoryPermissionReceiver.EXTRA_PACKAGE_NAME, packageName)
            putExtra(UsbAccessoryPermissionReceiver.EXTRA_PERMISSION_TOKEN, request.token.value)
            putExtra(UsbAccessoryPermissionReceiver.EXTRA_ACCESSORY_FINGERPRINT, request.fingerprint.stableString())
        }
        val pendingIntent = PendingIntent.getBroadcast(this, request.token.value.hashCode(), intent, plan.pendingIntentFlags)
        permissionState = AccessoryPermissionLifecycleReducer.reduce(permissionState, AccessoryPermissionEvent.Requested)
        lastResult = null
        render()
        usbManager.requestPermission(accessory, pendingIntent)
        schedulePermissionCallbackTimeout()
    }

    private fun runApprovedAccessory(accessory: UsbAccessory) {
        busy = true
        lastResult = "Permiso aprobado. Ejecutando prueba USB fuera del hilo principal."
        render()
        Thread {
            val message = runApprovedAccessoryOffMainThread(accessory)
            runOnUiThread {
                lastResult = message
                busy = false
                permissionState = AccessoryPermissionUiModel.Idle
                render()
            }
        }.start()
    }

    private fun runApprovedAccessoryOffMainThread(accessory: UsbAccessory): String = when (val opened = AndroidUsbAccessoryBoundary(usbManager).open(accessory)) {
        is AccessoryOpenResult.Opened -> opened.session.use { session ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                when (val bounded = BoundedAccessorySmokeSession(
                    timeoutMillis = AccessoryExecutionPlanner.DEFAULT_SMOKE_TIMEOUT_MILLIS,
                    executor = executor,
                ).run(session) { AccessorySmokeRunner(maxPayloadBytes = MAX_PAYLOAD_BYTES).runApprovedSession(it) }) {
                    is BoundedAccessorySmokeResult.Completed -> bounded.result.toUiMessage()
                    is BoundedAccessorySmokeResult.TimedOut -> "La prueba USB agotó el tiempo y se cerró la sesión; Android app-level timeout aplicado, sin garantizar que el SO interrumpa toda lectura bloqueada."
                }
            } finally {
                executor.shutdownNow()
            }
        }
        is AccessoryOpenResult.OpenFailed -> "Android aprobó el permiso, pero no se pudo abrir el accesorio."
        is AccessoryOpenResult.PermissionDenied -> "Android no aprobó el permiso del accesorio."
    }

    private fun handlePermissionCallbackIntent(intent: Intent?) {
        val callbackIntent = intent ?: return
        val callback = permissionCallbackFromExtra(callbackIntent.getStringExtra(UsbAccessoryPermissionReceiver.EXTRA_PERMISSION_CALLBACK)) ?: return
        val decision = permissionGate.classifyCallback(
            token = callbackIntent.getStringExtra(UsbAccessoryPermissionReceiver.EXTRA_PERMISSION_TOKEN)?.let(::PermissionRequestToken),
            fingerprint = AccessoryFingerprint.fromStableString(callbackIntent.getStringExtra(UsbAccessoryPermissionReceiver.EXTRA_ACCESSORY_FINGERPRINT)),
            callback = callback,
            nowMillis = SystemClock.elapsedRealtime(),
        )
        permissionState = AccessoryPermissionLifecycleReducer.reduce(permissionState, AccessoryPermissionEvent.CallbackDecision(decision))
        lastResult = null
        if (decision == PermissionCallbackDecision.Valid(AccessoryPermissionCallback.Granted)) {
            callbackAccessory(callbackIntent)?.let { runApprovedAccessory(it) } ?: run {
                permissionState = AccessoryPermissionUiModel.MissingAccessory
            }
        }
    }

    private fun schedulePermissionCallbackTimeout() {
        mainHandler.postDelayed({
            val next = AccessoryPermissionLifecycleReducer.reduce(permissionState, AccessoryPermissionEvent.CallbackTimedOut)
            if (next != permissionState) {
                permissionGate.retireExpired(SystemClock.elapsedRealtime())
                permissionState = next
                lastResult = null
                render()
            }
        }, PERMISSION_CALLBACK_TIMEOUT_MILLIS)
    }

    private fun AccessorySmokeResult.toUiMessage(): String = when (this) {
        is AccessorySmokeResult.AckWritten -> "ACK enviado para stream $streamId ($payloadBytes bytes)."
        is AccessorySmokeResult.DecodeFailed -> "Frame USB inválido: ${reason.javaClass.simpleName}."
        is AccessorySmokeResult.ReadFailed -> "Lectura USB incompleta: ${reason.javaClass.simpleName}."
    }

    private fun currentAccessory(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()

    @Suppress("DEPRECATION")
    private fun callbackAccessory(intent: Intent): UsbAccessory? = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val PERMISSION_CALLBACK_TIMEOUT_MILLIS = 10_000L
    }
}
