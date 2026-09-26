package dev.chinchillacam.usbprobe

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
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
    private lateinit var cameraPermissionStatus: TextView
    private lateinit var cameraPermissionAction: Button
    private lateinit var cameraCatalog: TextView
    private lateinit var cameraRows: LinearLayout
    private lateinit var action: Button
    private var selectedCameraId: String? = null
    private var cameraPermissionState: CameraPermissionUiModel = CameraPermissionUiModel.NotRequested
    private val cameraSelectionPreference: CameraSelectionPreference by lazy {
        CameraSelectionPreference(SharedPreferencesStringStore(getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE), CAMERA_SELECTION_KEY))
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionState: AccessoryPermissionUiModel = AccessoryPermissionUiModel.Idle
    private var permissionGate = AccessoryPermissionRequestGate(PERMISSION_CALLBACK_TIMEOUT_MILLIS)
    private var busy: Boolean = false
    private var lastResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        cameraPermissionState = currentCameraPermissionState()
        restorePermissionRequest(savedInstanceState)
        buildUi()
        action.setOnClickListener { requestPermissionFromUserAction() }
        cameraPermissionAction.setOnClickListener { requestCameraPermissionFromUserAction() }
        if (permissionState == AccessoryPermissionUiModel.WaitingForCallback) {
            schedulePermissionCallbackTimeout()
        }
        handlePermissionCallbackIntent(intent)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PERMISSION_UI, permissionState.toSavedName())
        when (val snapshot = permissionGate.snapshotPendingRequest(SystemClock.elapsedRealtime())) {
            is PendingPermissionSnapshotResult.Restored -> {
                outState.putString(STATE_PERMISSION_TOKEN, snapshot.request.token.value)
                outState.putString(STATE_ACCESSORY_FINGERPRINT, snapshot.request.fingerprint.stableString())
                outState.putLong(STATE_PERMISSION_EXPIRES_AT, snapshot.request.expiresAtMillis)
                outState.putBoolean(STATE_PERMISSION_CONSUMED, snapshot.request.consumed)
            }
            PendingPermissionSnapshotResult.Consumed,
            PendingPermissionSnapshotResult.ExpiredOrMissing -> Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePermissionCallbackIntent(intent)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        cameraPermissionState = when {
            permissions.singleOrNull() != Manifest.permission.CAMERA -> CameraPermissionUiModel.UnknownResult
            grantResults.singleOrNull() == PackageManager.PERMISSION_GRANTED -> CameraPermissionUiModel.Granted
            grantResults.singleOrNull() == PackageManager.PERMISSION_DENIED -> CameraPermissionUiModel.Denied
            else -> CameraPermissionUiModel.UnknownResult
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        cameraPermissionState = currentCameraPermissionState()
        render()
    }

    private fun buildUi() {
        title = TextView(this).apply { textSize = 22f }
        status = TextView(this).apply { textSize = 16f }
        safety = TextView(this).apply { textSize = 14f }
        cameraPermissionStatus = TextView(this).apply { textSize = 14f }
        cameraPermissionAction = Button(this)
        cameraCatalog = TextView(this).apply { textSize = 14f }
        cameraRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        action = Button(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
            addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(action, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(safety, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(cameraPermissionStatus, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(cameraPermissionAction, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(cameraCatalog, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(cameraRows, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        renderCameraPermission()
        renderCameraCatalog()
    }

    private fun renderCameraPermission() {
        val ui = CameraPermissionUiPlanner.plan(cameraPermissionState)
        cameraPermissionStatus.text = "${ui.title}\n${ui.status}"
        cameraPermissionAction.text = ui.primaryActionLabel
        cameraPermissionAction.isEnabled = ui.primaryActionEnabled
    }

    private fun requestCameraPermissionFromUserAction() {
        if (currentCameraPermissionState() == CameraPermissionUiModel.Granted) {
            cameraPermissionState = CameraPermissionUiModel.Granted
            render()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    private fun currentCameraPermissionState(): CameraPermissionUiModel = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        CameraPermissionUiModel.Granted
    } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        CameraPermissionUiModel.Granted
    } else {
        CameraPermissionUiModel.NotRequested
    }

    private fun renderCameraCatalog(snapshot: CameraCatalogSnapshot = currentCameraCatalogSnapshot()) {
        val restoredSelection = selectedCameraId ?: cameraSelectionPreference.restoreSelection(snapshot)
        val ui = CameraCatalogUiPlanner.plan(snapshot = snapshot, requestedSelectionId = restoredSelection)
        selectedCameraId = ui.selectedCameraId
        cameraCatalog.text = "${ui.title}\n${ui.summary}"
        cameraRows.removeAllViews()
        ui.rows.forEach { row ->
            cameraRows.addView(cameraRowView(row), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun cameraRowView(row: CameraCatalogUiRow): TextView = if (row.selectable) {
        Button(this).apply {
            text = "${row.title}\n${row.status}"
            isAllCaps = false
            isEnabled = true
            setOnClickListener {
                val snapshot = currentCameraCatalogSnapshot()
                cameraSelectionPreference.saveSelection(snapshot, row.cameraId)
                selectedCameraId = row.cameraId
                renderCameraCatalog(snapshot)
            }
        }
    } else {
        TextView(this).apply {
            text = "${row.title}\n${row.status}"
            textSize = 14f
            isEnabled = false
        }
    }

    private fun currentCameraCatalogSnapshot(): CameraCatalogSnapshot {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return CameraCapabilityCatalog(
            AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(cameraManager)),
        ).snapshot()
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
        val decision = permissionGate.classifyCallbackTokenValue(
            tokenValue = callbackIntent.getStringExtra(UsbAccessoryPermissionReceiver.EXTRA_PERMISSION_TOKEN),
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

    private fun restorePermissionRequest(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        permissionState = permissionStateFromSavedName(state.getString(STATE_PERMISSION_UI))
        val token = PermissionRequestToken.fromCallbackExtra(state.getString(STATE_PERMISSION_TOKEN)) ?: return
        val fingerprint = AccessoryFingerprint.fromStableString(state.getString(STATE_ACCESSORY_FINGERPRINT)) ?: return
        val expiresAt = state.getLong(STATE_PERMISSION_EXPIRES_AT, Long.MIN_VALUE)
        if (expiresAt == Long.MIN_VALUE) return
        val request = PendingAccessoryPermissionRequest(
            token = token,
            fingerprint = fingerprint,
            expiresAtMillis = expiresAt,
            consumed = state.getBoolean(STATE_PERMISSION_CONSUMED, false),
        )
        when (permissionGate.restorePendingRequest(request, SystemClock.elapsedRealtime())) {
            is PendingPermissionSnapshotResult.Restored -> Unit
            PendingPermissionSnapshotResult.Consumed,
            PendingPermissionSnapshotResult.ExpiredOrMissing -> {
                if (permissionState == AccessoryPermissionUiModel.WaitingForCallback) {
                    permissionState = AccessoryPermissionUiModel.CallbackMissingOrCanceled
                }
            }
        }
    }

    private fun AccessoryPermissionUiModel.toSavedName(): String = when (this) {
        AccessoryPermissionUiModel.Idle -> "idle"
        AccessoryPermissionUiModel.WaitingForCallback -> "waiting"
        AccessoryPermissionUiModel.Granted -> "granted"
        AccessoryPermissionUiModel.Denied -> "denied"
        AccessoryPermissionUiModel.MissingAccessory -> "missing_accessory"
        AccessoryPermissionUiModel.MissingPermissionResult -> "missing_permission_result"
        AccessoryPermissionUiModel.CallbackMissingOrCanceled -> "callback_missing"
        AccessoryPermissionUiModel.RejectedWrongAccessory -> "rejected_wrong_accessory"
        AccessoryPermissionUiModel.RejectedDuplicateOrConsumed -> "rejected_duplicate"
        AccessoryPermissionUiModel.RejectedStaleOrMissingRequest -> "rejected_stale"
        AccessoryPermissionUiModel.RejectedMalformedToken -> "rejected_malformed_token"
    }

    private fun permissionStateFromSavedName(name: String?): AccessoryPermissionUiModel = when (name) {
        "waiting" -> AccessoryPermissionUiModel.WaitingForCallback
        "granted" -> AccessoryPermissionUiModel.Granted
        "denied" -> AccessoryPermissionUiModel.Denied
        "missing_accessory" -> AccessoryPermissionUiModel.MissingAccessory
        "missing_permission_result" -> AccessoryPermissionUiModel.MissingPermissionResult
        "callback_missing" -> AccessoryPermissionUiModel.CallbackMissingOrCanceled
        "rejected_wrong_accessory" -> AccessoryPermissionUiModel.RejectedWrongAccessory
        "rejected_duplicate" -> AccessoryPermissionUiModel.RejectedDuplicateOrConsumed
        "rejected_stale" -> AccessoryPermissionUiModel.RejectedStaleOrMissingRequest
        "rejected_malformed_token" -> AccessoryPermissionUiModel.RejectedMalformedToken
        else -> AccessoryPermissionUiModel.Idle
    }

    private fun AccessorySmokeResult.toUiMessage(): String = when (this) {
        is AccessorySmokeResult.AckWritten -> "ACK enviado para stream $streamId ($payloadBytes bytes)."
        is AccessorySmokeResult.DecodeFailed -> "Frame USB inválido: ${reason.javaClass.simpleName}."
        is AccessorySmokeResult.ReadFailed -> "Lectura USB incompleta: ${reason.javaClass.simpleName}."
    }

    private fun currentAccessory(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()

    @Suppress("DEPRECATION")
    private fun callbackAccessory(intent: Intent): UsbAccessory? = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    private class SharedPreferencesStringStore(
        private val preferences: SharedPreferences,
        private val key: String,
    ) : StringPreferenceStore {
        override fun get(): String? = preferences.getString(key, null)
        override fun put(value: String) {
            preferences.edit().putString(key, value).apply()
        }
        override fun clear() {
            preferences.edit().remove(key).apply()
        }
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val PERMISSION_CALLBACK_TIMEOUT_MILLIS = 10_000L
        const val STATE_PERMISSION_UI = "dev.chinchillacam.usbprobe.state.PERMISSION_UI"
        const val STATE_PERMISSION_TOKEN = "dev.chinchillacam.usbprobe.state.PERMISSION_TOKEN"
        const val STATE_ACCESSORY_FINGERPRINT = "dev.chinchillacam.usbprobe.state.ACCESSORY_FINGERPRINT"
        const val STATE_PERMISSION_EXPIRES_AT = "dev.chinchillacam.usbprobe.state.PERMISSION_EXPIRES_AT"
        const val STATE_PERMISSION_CONSUMED = "dev.chinchillacam.usbprobe.state.PERMISSION_CONSUMED"
        const val REQUEST_CAMERA_PERMISSION = 2001
        const val CAMERA_PREFERENCES = "dev.chinchillacam.usbprobe.camera"
        const val CAMERA_SELECTION_KEY = "selected_direct_camera_id"
    }
}
