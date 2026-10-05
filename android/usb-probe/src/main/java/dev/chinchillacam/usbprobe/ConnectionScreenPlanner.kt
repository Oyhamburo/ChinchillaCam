package dev.chinchillacam.usbprobe

/**
 * Everything [ConnectionScreenPlanner] needs to render the product screen (task c6a,
 * `android-production-connection` §4.6). [scanning] and [pasteVisible] are UI-local: QR scan mode
 * is only honoured from `Idle`/`Failed`, and [pasteVisible] only while scanning.
 */
data class ConnectionScreenInput(
    val state: PhoneConnectionState,
    val scanning: Boolean = false,
    val pasteVisible: Boolean = false,
    val trusted: List<TrustedDesktopRecord> = emptyList(),
    val identityRegenerated: Boolean = false,
    val cameraStatus: VisibleCameraServiceStatus? = null,
)

/** Buttons the screen can show, with their Spanish labels; the activity maps each id to an operation. */
enum class ConnectionAction(val label: String) {
    START_PAIRING("Vincular una computadora"),
    DIAGNOSTICS("Diagnóstico"),
    PASTE_CODE("Pegar código"),
    SUBMIT_PASTED("Usar código"),
    CANCEL_SCAN("Cancelar"),
    CANCEL_WAIT("Cancelar"),
    CONFIRM_PAIRING("Coincide"),
    REJECT_PAIRING("No coincide"),
    DISCONNECT("Desconectar"),
}

data class TrustedDesktopRow(val desktopId: String, val name: String, val canConnect: Boolean, val canForget: Boolean)

data class ConnectionScreenPlan(
    val title: String,
    val status: String,
    val notice: String?,
    val shortCode: String?,
    val showScanner: Boolean,
    val showPasteField: Boolean,
    val actions: List<ConnectionAction>,
    val trustedRows: List<TrustedDesktopRow>,
    val emptyTrustedText: String?,
)

/** Pure mapping from controller state plus UI-local flags to Spanish texts and buttons; `ConnectionActivity` only renders it. */
object ConnectionScreenPlanner {
    const val TITLE = "ChinchillaCam"
    const val READY = "Listo para conectar."
    const val FAILED = "No se pudo completar la operación."
    const val SCAN_QR = "Apuntá la cámara al código QR que muestra ChinchillaCam en la computadora."
    const val CONNECT_CABLE_TO_PAIR = "Conectá el cable USB a la computadora para vincularla."
    const val CAMERA_STARTING = "Iniciando la cámara…"
    const val CAMERA_ERROR = "La cámara tuvo un error."
    const val IDENTITY_REGENERATED = "La identidad del teléfono cambió. Volvé a vincular tus computadoras."
    const val NO_TRUSTED_DESKTOPS = "Todavía no vinculaste ninguna computadora."

    fun plan(input: ConnectionScreenInput): ConnectionScreenPlan {
        val state = input.state
        val idle = state is PhoneConnectionState.Idle || state is PhoneConnectionState.Failed
        val scanning = idle && input.scanning
        var shortCode: String? = null
        var stateNotice: String? = null
        val status: String
        val actions: List<ConnectionAction>
        when {
            scanning -> {
                status = SCAN_QR
                val paste = if (input.pasteVisible) ConnectionAction.SUBMIT_PASTED else ConnectionAction.PASTE_CODE
                actions = listOf(paste, ConnectionAction.CANCEL_SCAN)
            }
            state is PhoneConnectionState.Idle -> {
                status = READY
                stateNotice = state.notice
                actions = listOf(ConnectionAction.START_PAIRING, ConnectionAction.DIAGNOSTICS)
            }
            state is PhoneConnectionState.Failed -> {
                status = FAILED
                stateNotice = state.message
                actions = listOf(ConnectionAction.START_PAIRING, ConnectionAction.DIAGNOSTICS)
            }
            state is PhoneConnectionState.AwaitingAccessory -> {
                status = if (state.purpose is AccessoryPurpose.Pairing) CONNECT_CABLE_TO_PAIR else PhoneConnectionMessages.CONNECT_CABLE
                actions = listOf(ConnectionAction.CANCEL_WAIT)
            }
            state is PhoneConnectionState.ConfirmPairing -> {
                status = "¿El código coincide con el que muestra «${state.desktopName}»?"
                shortCode = state.shortCode.display
                actions = listOf(ConnectionAction.CONFIRM_PAIRING, ConnectionAction.REJECT_PAIRING)
            }
            state is PhoneConnectionState.AwaitingDesktopConfirmation -> {
                status = "Confirmá el código también en «${state.desktopName}»."
                actions = emptyList()
            }
            state is PhoneConnectionState.Connecting -> {
                status = "Conectando con «${state.desktopName}»…"
                actions = emptyList()
            }
            else -> {
                val connected = state as PhoneConnectionState.Connected
                status = "Transmitiendo a «${connected.desktopName}»."
                stateNotice = cameraNotice(input.cameraStatus)
                actions = listOf(ConnectionAction.DISCONNECT)
            }
        }
        val rows = if (scanning) emptyList() else trustedRows(input.trusted, canConnect = idle)
        val notice = listOfNotNull(IDENTITY_REGENERATED.takeIf { input.identityRegenerated }, stateNotice).joinToString("\n").ifEmpty { null }
        return ConnectionScreenPlan(
            title = TITLE,
            status = status,
            notice = notice,
            shortCode = shortCode,
            showScanner = scanning,
            showPasteField = scanning && input.pasteVisible,
            actions = actions,
            trustedRows = rows,
            emptyTrustedText = NO_TRUSTED_DESKTOPS.takeIf { !scanning && rows.isEmpty() },
        )
    }

    private fun cameraNotice(camera: VisibleCameraServiceStatus?): String? = when (camera?.state) {
        VisibleCameraServiceState.Starting -> CAMERA_STARTING
        VisibleCameraServiceState.Error -> camera.message.ifBlank { CAMERA_ERROR }
        else -> null
    }

    private fun trustedRows(trusted: List<TrustedDesktopRecord>, canConnect: Boolean): List<TrustedDesktopRow> = trusted
        .filter { it.revokedAtEpochSeconds == null }
        .sortedWith(compareBy({ it.desktopName.lowercase() }, { it.desktopId }))
        .map { TrustedDesktopRow(it.desktopId, it.desktopName, canConnect = canConnect, canForget = true) }
}
