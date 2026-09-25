package dev.chinchillacam.usbprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
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
    private var permissionRequested: Boolean = false
    private var busy: Boolean = false
    private var lastResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        action.setOnClickListener { requestPermissionFromUserAction() }
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
            permissionRequested = permissionRequested,
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
        val intent = Intent(plan.action).apply {
            setClassName(plan.packageName, plan.receiverClassName)
            putExtra(UsbAccessoryPermissionReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, plan.pendingIntentFlags)
        permissionRequested = true
        lastResult = "Esperando permiso del sistema Android."
        render()
        usbManager.requestPermission(accessory, pendingIntent)
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
                permissionRequested = false
                render()
            }
        }.start()
    }

    private fun runApprovedAccessoryOffMainThread(accessory: UsbAccessory): String = when (val opened = AndroidUsbAccessoryBoundary(usbManager).open(accessory)) {
        is AccessoryOpenResult.Opened -> opened.session.use { session ->
            when (val result = AccessorySmokeRunner(maxPayloadBytes = MAX_PAYLOAD_BYTES).runApprovedSession(session)) {
                is AccessorySmokeResult.AckWritten -> "ACK enviado para stream ${result.streamId} (${result.payloadBytes} bytes)."
                is AccessorySmokeResult.DecodeFailed -> "Frame USB inválido: ${result.reason.javaClass.simpleName}."
                is AccessorySmokeResult.ReadFailed -> "Lectura USB incompleta: ${result.reason.javaClass.simpleName}."
            }
        }
        is AccessoryOpenResult.OpenFailed -> "Android aprobó el permiso, pero no se pudo abrir el accesorio."
        is AccessoryOpenResult.PermissionDenied -> "Android no aprobó el permiso del accesorio."
    }

    private fun currentAccessory(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()

    private companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
