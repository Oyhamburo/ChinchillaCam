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
    val quality: QualityControlsPlan? = null,
    val lastDesktopId: String? = null,
    val cameraPermissionDenied: Boolean = false,
)

/** Buttons the screen can show, with their Spanish labels; the activity maps each id to an operation. */
enum class ConnectionAction(val label: String) {
    START_PAIRING("Vincular una computadora"),
    RETRY("Reintentar"),
    RETRY_CAMERA("Reintentar cámara"),
    OPEN_APP_SETTINGS("Abrir ajustes"),
    PAIR_AGAIN("Vincular de nuevo"),
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
    val quality: QualityControlsPlan? = null,
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
                actions = failureActions(input, state.cause?.let(UserFailureCatalog::actionFor), null)
            }
            state is PhoneConnectionState.Failed -> {
                status = FAILED
                stateNotice = state.message
                actions = failureActions(input, null, state.kind)
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
                actions = if (input.cameraStatus?.state == VisibleCameraServiceState.Error) {
                    val recovery = if (input.cameraStatus.cause == FailureCause.CameraPermissionDenied)
                        ConnectionAction.OPEN_APP_SETTINGS else ConnectionAction.RETRY_CAMERA
                    listOf(recovery, ConnectionAction.DISCONNECT)
                } else listOf(ConnectionAction.DISCONNECT)
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
            quality = input.quality.takeIf { idle || state is PhoneConnectionState.Connected },
        )
    }

    private fun failureActions(input: ConnectionScreenInput, recovery: RecoveryAction?, kind: ConnectionFailureKind?): List<ConnectionAction> {
        val primary = when {
            input.cameraPermissionDenied -> ConnectionAction.OPEN_APP_SETTINGS
            recovery != null -> when (recovery) {
                RecoveryAction.None -> null
                RecoveryAction.Retry -> ConnectionAction.RETRY
                RecoveryAction.RetryCamera -> ConnectionAction.RETRY
                RecoveryAction.OpenAppSettings -> ConnectionAction.OPEN_APP_SETTINGS
                RecoveryAction.PairAgain -> ConnectionAction.PAIR_AGAIN
                RecoveryAction.Disconnect -> ConnectionAction.DISCONNECT
            }
            kind == ConnectionFailureKind.DESKTOP_NOT_TRUSTED || kind == ConnectionFailureKind.RECONNECT_TLS_REJECTED -> ConnectionAction.PAIR_AGAIN
            kind == ConnectionFailureKind.QR_INVALID || kind == ConnectionFailureKind.QR_EXPIRED ||
                kind == ConnectionFailureKind.PAIRING_FAILED -> ConnectionAction.START_PAIRING
            kind == ConnectionFailureKind.FORGET_FAILED || kind == null -> null
            else -> ConnectionAction.RETRY
        }
        val pairing = if (primary == ConnectionAction.PAIR_AGAIN) emptyList() else listOf(ConnectionAction.START_PAIRING)
        return (listOfNotNull(primary?.takeUnless { it == ConnectionAction.RETRY && input.lastDesktopId == null }) +
            pairing + ConnectionAction.DIAGNOSTICS).distinct()
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
