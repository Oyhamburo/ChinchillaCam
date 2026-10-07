package dev.chinchillacam.usbprobe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Product screen (task c6b, `android-production-connection.md` §3.5, §4.6): renders
 * [ConnectionScreenPlanner] and forwards user actions to the process-level
 * [PhoneConnectionController]. It only owns UI-local state (scan mode, paste field, local notices),
 * the QR scanner and the permission prompts; the controller keeps running without it (a cable pull
 * is handled by [PhoneConnectionRuntime]). UI work runs on the main thread; the first runtime
 * build, catalog refresh and controller listener work re-post results to the main thread.
 */
class ConnectionActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var usbManager: UsbManager
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var noticeView: TextView
    private lateinit var shortCodeView: TextView
    private lateinit var textureView: TextureView
    private lateinit var pasteField: EditText
    private lateinit var actionsView: LinearLayout
    private lateinit var rowsView: LinearLayout
    private lateinit var qualityView: LinearLayout
    private var cameraCatalog: CameraCatalogSnapshot? = null
    private var selectedCameraId: String? = null
    private var qualityPreference: QualityPreference = QualityPreference.Automatic
    private var catalogRequest = 0

    private var controller: PhoneConnectionController? = null
    private var listener: ((PhoneConnectionState) -> Unit)? = null
    private var state: PhoneConnectionState = PhoneConnectionState.Idle()
    private var trusted: List<TrustedDesktopRecord> = emptyList()
    private var identityRegenerated = false
    private var scanning = false
    private var pasteVisible = false
    private var localNotice: String? = null
    private var resumed = false
    private var awaitingPermissionAsked = false
    private var renderedButtons: Any? = null
    private var scanner: Camera2QrScanner? = null
    private var afterCameraPermission: (() -> Unit)? = null
    private var afterUsbPermission: (() -> Unit)? = null
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private val pollCameraStatus = Runnable { render() }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = render()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeScanner()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        render()
        val appContext = applicationContext
        // The first build touches the Android keystore (it may generate the phone key): never on the main thread.
        Thread({
            val bound = runCatching {
                val runtimeController = PhoneConnectionRuntime.get(appContext)
                Triple(runtimeController, PhoneConnectionRuntime.identityRegenerated(appContext), runtimeController.trustedDesktops())
            }
            mainHandler.post {
                if (isDestroyed) return@post
                bound.onSuccess { (runtimeController, regenerated, desktops) -> bind(runtimeController, regenerated, desktops) }
                    .onFailure {
                        localNotice = START_FAILED
                        render()
                    }
            }
        }, "connection-bind").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAccessoryIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshCameraCatalog()
        render()
        if (state !is PhoneConnectionState.AwaitingAccessory) requestErrorNotificationPermissionOnce()
        resumeAwaitingAccessory()
    }

    override fun onPause() {
        resumed = false
        catalogRequest++
        closeScanner()
        mainHandler.removeCallbacks(pollCameraStatus)
        super.onPause()
    }

    override fun onDestroy() {
        listener?.let { controller?.removeListener(it) }
        listener = null
        closeScanner()
        usbPermissionReceiver?.let { unregisterReceiver(it) }
        usbPermissionReceiver = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        val then = afterCameraPermission
        afterCameraPermission = null
        if (grantResults.singleOrNull() == PackageManager.PERMISSION_GRANTED) then?.invoke() else localNotice = CAMERA_DENIED
        render()
    }

    private fun bind(bound: PhoneConnectionController, regenerated: Boolean, desktops: List<TrustedDesktopRecord>) {
        val onState: (PhoneConnectionState) -> Unit = { next ->
            // Controller worker thread: the trusted-store read stays off the main thread.
            val latest = runCatching { bound.trustedDesktops() }.getOrNull()
            mainHandler.post { if (!isDestroyed) apply(next, latest) }
        }
        bound.addListener(onState)
        controller = bound
        listener = onState
        identityRegenerated = regenerated
        apply(bound.snapshot(), desktops)
        handleAccessoryIntent(intent)
        if (resumed) resumeAwaitingAccessory()
    }

    private fun apply(next: PhoneConnectionState, desktops: List<TrustedDesktopRecord>?) {
        state = next
        desktops?.let { trusted = it }
        if (next !is PhoneConnectionState.AwaitingAccessory) awaitingPermissionAsked = false
        render()
    }

    private fun handleAccessoryIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) controller?.accessoryAttached()
    }

    /** An accessory attached while its system prompt was dismissed still needs the USB permission (asked once per wait). */
    private fun resumeAwaitingAccessory() {
        val bound = controller ?: return
        if (state !is PhoneConnectionState.AwaitingAccessory || awaitingPermissionAsked || afterUsbPermission != null) return
        if (PhoneConnectionRuntime.accessoryAwaitingPermission(this) == null) return
        awaitingPermissionAsked = true
        withUsbPermission { bound.accessoryAttached() }
    }

    private fun onAction(action: ConnectionAction) {
        val bound = controller ?: return
        localNotice = null
        when (action) {
            ConnectionAction.START_PAIRING -> withCameraPermission { scanning = true }
            ConnectionAction.PAIR_AGAIN -> withCameraPermission { scanning = true }
            ConnectionAction.RETRY -> bound.lastDesktopId()?.let(::connectTo)
            ConnectionAction.RETRY_CAMERA -> retryCamera()
            ConnectionAction.OPEN_APP_SETTINGS -> runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }.onFailure { localNotice = "No se pudieron abrir los ajustes de la app." }
            ConnectionAction.CANCEL_SCAN -> leaveScan()
            ConnectionAction.PASTE_CODE -> pasteVisible = true
            ConnectionAction.SUBMIT_PASTED -> {
                val text = pasteField.text.toString().trim()
                leaveScan()
                withUsbPermission { bound.qrScanned(text) }
            }
            ConnectionAction.CANCEL_WAIT -> bound.cancel()
            ConnectionAction.CONFIRM_PAIRING -> bound.confirmPairing()
            ConnectionAction.REJECT_PAIRING -> bound.rejectPairing()
            ConnectionAction.DISCONNECT -> bound.disconnect()
            ConnectionAction.DIAGNOSTICS -> startActivity(Intent(this, UsbProbeActivity::class.java))
        }
        render()
    }

    private fun connectTo(desktopId: String) {
        val bound = controller ?: return
        localNotice = null
        // Sessions start the camera service, so the camera permission comes first.
        withCameraPermission { withUsbPermission { bound.connect(desktopId) } }
    }

    private fun confirmForget(row: TrustedDesktopRow) {
        val bound = controller ?: return
        AlertDialog.Builder(this)
            .setMessage("¿Olvidar «${row.name}»? Vas a tener que vincularla de nuevo.")
            .setPositiveButton(FORGET) { _, _ -> bound.forget(row.desktopId) }
            .setNegativeButton(CANCEL, null)
            .show()
    }

    /** Ask once on entry, before pairing/connect; denial never waits on a callback or blocks the session. */
    private fun requestErrorNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)) return
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply()
        runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION) }
    }

    private fun withCameraPermission(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return then()
        afterCameraPermission = then
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    /**
     * Runs [then] right away unless the matching accessory is attached without USB permission; then
     * it asks for it and runs [then] on the answer. A denial still runs it: the controller reports it.
     */
    private fun withUsbPermission(then: () -> Unit) {
        val accessory = PhoneConnectionRuntime.accessoryAwaitingPermission(this) ?: return then()
        afterUsbPermission = then
        requestUsbPermission(accessory)
    }

    private fun requestUsbPermission(accessory: UsbAccessory) {
        val action = "$packageName$USB_PERMISSION_ACTION_SUFFIX"
        if (usbPermissionReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != action) return
                    val then = afterUsbPermission ?: return
                    afterUsbPermission = null
                    then()
                    render()
                }
            }
            registerNotExportedReceiver(this, receiver, IntentFilter(action))
            usbPermissionReceiver = receiver
        }
        // Same flags as AccessoryPermissionPlanner: UsbManager fills in the grant extras, so the
        // PendingIntent must be mutable; the intent is scoped to this package.
        val intent = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        usbManager.requestPermission(accessory, PendingIntent.getBroadcast(this, REQUEST_USB_PERMISSION, intent, flags))
    }

    private fun updateScanner(show: Boolean) {
        if (!show || !resumed) return closeScanner()
        if (scanner != null) return
        val texture = textureView.surfaceTexture ?: return // onSurfaceTextureAvailable renders again
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = scanCameraId(cameraManager) ?: return scanFailed(NO_CAMERA)
        scanner = Camera2QrScanner(
            cameraManager = cameraManager,
            cameraId = cameraId,
            previewTexture = texture,
            onQrText = { text ->
                leaveScan()
                controller?.let { bound -> withUsbPermission { bound.qrScanned(text) } }
                render()
            },
            onError = { message -> scanFailed(message) },
        ).also { it.start() }
    }

    /** Same catalog adapter as [PhoneConnectionRuntime]: first back-facing direct candidate, else the first direct one. */
    private fun scanCameraId(cameraManager: CameraManager): String? {
        val direct = (cameraCatalog ?: CameraCapabilityCatalog(AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(cameraManager))).snapshot())
            .entries.filter { it.role is CameraIdRole.DirectOpenCandidate }
        return (direct.firstOrNull { it.facing == CapabilityState.Known(CameraFacing.Back) } ?: direct.firstOrNull())?.id
    }

    private fun scanFailed(message: String) {
        leaveScan()
        localNotice = message
        render()
    }

    private fun leaveScan() {
        scanning = false
        pasteVisible = false
        pasteField.text.clear()
        closeScanner()
    }

    private fun closeScanner() {
        scanner?.close()
        scanner = null
    }

    private fun render() {
        // The desktop writes the same preferences; refresh the visible controls while connected.
        if (state is PhoneConnectionState.Connected) cameraCatalog?.let { snapshot ->
            val preferences = getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
            selectedCameraId = CameraSelectionPreference(SharedPreferencesStringStore(preferences, CAMERA_SELECTION_KEY)).restoreSelection(snapshot)
            qualityPreference = QualityPreferenceStore(SharedPreferencesStringStore(preferences, QUALITY_KEY)).load()
        }
        val cameraStatus = VisibleCameraServiceStatusStore.snapshot()
        val quality = cameraCatalog?.let { QualityControlsPlanner.plan(it, selectedCameraId, qualityPreference) }
        val plan = ConnectionScreenPlanner.plan(ConnectionScreenInput(
            state, scanning, pasteVisible, trusted, identityRegenerated, cameraStatus, quality,
            lastDesktopId = controller?.lastDesktopId(), cameraPermissionDenied = localNotice == CAMERA_DENIED,
        ))
        if (scanning && !plan.showScanner) {
            scanning = false
            pasteVisible = false
        }
        titleView.text = plan.title
        statusView.text = plan.status
        val notice = listOfNotNull(plan.notice, localNotice).joinToString("\n")
        noticeView.text = notice
        noticeView.visibility = visibleIf(notice.isNotEmpty())
        shortCodeView.text = plan.shortCode.orEmpty()
        shortCodeView.visibility = visibleIf(plan.shortCode != null)
        textureView.visibility = visibleIf(plan.showScanner)
        pasteField.visibility = visibleIf(plan.showPasteField)
        // Rebuilt only on change, so the 1 s camera poll never swaps a button under the user's finger.
        val buttons = Pair(Triple(plan.actions, plan.trustedRows, controller != null), plan.quality)
        if (buttons != renderedButtons) {
            renderedButtons = buttons
            actionsView.removeAllViews()
            plan.actions.forEach { action -> actionsView.addView(button(action.label) { onAction(action) }, matchWidth()) }
            rowsView.removeAllViews()
            plan.trustedRows.forEach { row -> rowsView.addView(rowView(row), matchWidth()) }
            plan.emptyTrustedText?.let { empty -> rowsView.addView(TextView(this).apply { text = empty }, matchWidth()) }
            qualityView.removeAllViews()
            plan.quality?.let { renderQuality(it) }
        }
        updateScanner(plan.showScanner)
        mainHandler.removeCallbacks(pollCameraStatus)
        if (resumed && state is PhoneConnectionState.Connected) mainHandler.postDelayed(pollCameraStatus, CAMERA_POLL_MILLIS)
    }

    /** Refresh only on resume, off the UI thread; old responses cannot overwrite a later resume. */
    private fun refreshCameraCatalog() {
        val request = ++catalogRequest
        val context = applicationContext
        Thread({
            val catalog = runCatching {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                CameraCapabilityCatalog(AndroidCameraManagerGateway(AndroidCameraManagerFacadeImpl(manager))).snapshot()
            }
            mainHandler.post {
                if (!resumed || isDestroyed || request != catalogRequest) return@post
                catalog.onSuccess { snapshot ->
                    cameraCatalog = snapshot
                    val preferences = getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)
                    selectedCameraId = CameraSelectionPreference(SharedPreferencesStringStore(preferences, CAMERA_SELECTION_KEY)).restoreSelection(snapshot)
                    qualityPreference = QualityPreferenceStore(SharedPreferencesStringStore(preferences, QUALITY_KEY)).load()
                }.onFailure { localNotice = "No se pudieron leer las cámaras disponibles." }
                render()
            }
        }, "camera-catalog").start()
    }

    private fun selectCamera(id: String?) {
        val snapshot = cameraCatalog ?: return
        val backing = SharedPreferencesStringStore(
            getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE), CAMERA_SELECTION_KEY)
        if (id == null) backing.clear() else CameraSelectionPreference(backing).saveSelection(snapshot, id)
        selectedCameraId = id
        applyQuality(id)
        PhoneConnectionRuntime.notifyLocalQualityChange(this)
    }

    private fun selectQuality(preference: QualityPreference) {
        QualityPreferenceStore(SharedPreferencesStringStore(
            getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE), QUALITY_KEY)).save(preference)
        qualityPreference = preference
        applyQuality(selectedCameraId)
        PhoneConnectionRuntime.notifyLocalQualityChange(this)
    }

    private fun applyQuality(cameraId: String?) {
        localNotice = null
        if (state is PhoneConnectionState.Connected) {
            // The service interprets null as "keep the previous camera", so resolve automatic
            // selection to the same direct candidate shown by the planner.
            val effectiveId = cameraCatalog?.let { QualityControlsPlanner.plan(it, cameraId, qualityPreference).effectiveCameraId }
            runCatching { PhoneConnectionRuntime.applyCameraQuality(this, effectiveId) }
                .onFailure { localNotice = "No se pudo aplicar la calidad de cámara. Intentá nuevamente." }
        }
        render()
    }

    private fun retryCamera() {
        if (state !is PhoneConnectionState.Connected) return
        val effectiveId = cameraCatalog?.let { QualityControlsPlanner.plan(it, selectedCameraId, qualityPreference).effectiveCameraId }
        runCatching { PhoneConnectionRuntime.applyCameraQuality(this, effectiveId) }
            .onFailure { localNotice = "No se pudo reiniciar la cámara. Intentá nuevamente." }
    }

    private fun renderQuality(plan: QualityControlsPlan) {
        qualityView.addView(TextView(this).apply { text = plan.summary; textSize = 16f }, matchWidth())
        qualityView.addView(TextView(this).apply { text = "Cámara" }, matchWidth())
        plan.cameras.forEach { choice ->
            qualityView.addView(button((if (choice.selected) "✓ " else "") + choice.label) { selectCamera(choice.id) }, matchWidth())
        }
        qualityView.addView(TextView(this).apply { text = "Resolución" }, matchWidth())
        plan.resolutions.forEach { choice ->
            qualityView.addView(button(choice.label) { QualityControlsPlanner.selectResolution(plan, choice.value)?.let(::selectQuality) }
                .apply { isEnabled = isEnabled && choice.enabled }, matchWidth())
        }
        plan.resolutions.firstOrNull { !it.enabled }?.disabledReason?.let { reason ->
            qualityView.addView(TextView(this).apply { text = reason; textSize = 12f }, matchWidth())
        }
        qualityView.addView(TextView(this).apply { text = "FPS" }, matchWidth())
        plan.frameRates.forEach { choice ->
            qualityView.addView(button(choice.label) { QualityControlsPlanner.selectFps(plan, choice.value)?.let(::selectQuality) }
                .apply { isEnabled = isEnabled && choice.enabled }, matchWidth())
        }
        plan.frameRates.firstOrNull { !it.enabled }?.disabledReason?.let { reason ->
            qualityView.addView(TextView(this).apply { text = reason; textSize = 12f }, matchWidth())
        }
        qualityView.addView(button("Automático (calidad)") { selectQuality(QualityPreference.Automatic) }, matchWidth())
    }

    private fun rowView(row: TrustedDesktopRow): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = row.name; textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (row.canConnect) addView(button(CONNECT) { connectTo(row.desktopId) })
        if (row.canForget) addView(button(FORGET) { confirmForget(row) })
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = controller != null
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        titleView = TextView(this).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD }
        statusView = TextView(this).apply { textSize = 18f }
        noticeView = TextView(this).apply { textSize = 14f }
        shortCodeView = TextView(this).apply { textSize = 44f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
        textureView = TextureView(this).apply { surfaceTextureListener = previewListener }
        pasteField = EditText(this).apply {
            hint = PASTE_HINT
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        actionsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qualityView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            listOf(titleView, statusView, noticeView, shortCodeView).forEach { addView(it, matchWidth()) }
            addView(textureView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
            listOf(pasteField, qualityView, actionsView, rowsView).forEach { addView(it, matchWidth()) }
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun visibleIf(show: Boolean): Int = if (show) View.VISIBLE else View.GONE

    private companion object {
        const val CAMERA_PREFERENCES = "dev.chinchillacam.usbprobe.camera"
        const val CAMERA_SELECTION_KEY = "selected_direct_camera_id"
        const val QUALITY_KEY = "quality_preference"
        const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
        const val REQUEST_NOTIFICATION_PERMISSION = 3003
        const val REQUEST_CAMERA_PERMISSION = 3001
        const val REQUEST_USB_PERMISSION = 3002
        const val USB_PERMISSION_ACTION_SUFFIX = ".action.CONNECTION_USB_PERMISSION"
        const val CAMERA_POLL_MILLIS = 1_000L
        const val CONNECT = "Conectar"
        const val FORGET = "Olvidar"
        const val CANCEL = "Cancelar"
        const val PASTE_HINT = "CHINCHILLACAM-PAIR:v1:…"
        const val START_FAILED = "No se pudo iniciar la conexión del teléfono."
        const val CAMERA_DENIED = "Sin permiso de cámara no podés escanear el código ni transmitir."
        const val NO_CAMERA = "No se encontró una cámara para escanear."
    }
}
