package dev.chinchillacam.usbprobe

import dev.chinchillacam.usbprobe.ConnectionAction.CANCEL_SCAN
import dev.chinchillacam.usbprobe.ConnectionAction.CANCEL_WAIT
import dev.chinchillacam.usbprobe.ConnectionAction.CONFIRM_PAIRING
import dev.chinchillacam.usbprobe.ConnectionAction.DIAGNOSTICS
import dev.chinchillacam.usbprobe.ConnectionAction.DISCONNECT
import dev.chinchillacam.usbprobe.ConnectionAction.PASTE_CODE
import dev.chinchillacam.usbprobe.ConnectionAction.REJECT_PAIRING
import dev.chinchillacam.usbprobe.ConnectionAction.START_PAIRING
import dev.chinchillacam.usbprobe.ConnectionAction.SUBMIT_PASTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for task c6a (`odd/tasks/android-production-connection.md` §4.6): [ConnectionScreenPlanner]. */
class ConnectionScreenPlannerTest {
    private val trusted = listOf(record("pc-2", "Studio"), record("pc-old", "Antigua", revokedAt = 1_500), record("pc-1", "casa"))

    @Test
    fun pendingPairingShowsShortCodeAndConfirmButtons() {
        val plan = plan(PhoneConnectionState.ConfirmPairing("Studio", PairingShortCode.fromValue(841_406)))

        assertEquals("ChinchillaCam", plan.title)
        assertEquals("¿El código coincide con el que muestra «Studio»?", plan.status)
        assertEquals("841 406", plan.shortCode)
        assertEquals(listOf(CONFIRM_PAIRING, REJECT_PAIRING), plan.actions)
        assertEquals(listOf("Coincide", "No coincide"), plan.actions.map { it.label })
        assertFalse(plan.showScanner)
    }

    @Test
    fun idleAndFailedOfferPairingAndConnectableRowsWithoutRevokedSortedByName() {
        val idle = plan(PhoneConnectionState.Idle("Desconectado."))
        assertEquals("Listo para conectar." to "Desconectado.", idle.status to idle.notice)
        assertEquals(listOf(START_PAIRING, DIAGNOSTICS), idle.actions)
        assertEquals(listOf("Vincular una computadora", "Diagnóstico"), idle.actions.map { it.label })
        assertEquals(listOf(TrustedDesktopRow("pc-1", "casa", true, true), TrustedDesktopRow("pc-2", "Studio", true, true)), idle.trustedRows)
        assertNull(idle.emptyTrustedText)
        assertNull(idle.shortCode)

        val failed = plan(PhoneConnectionState.Failed(PhoneConnectionMessages.QR_INVALID))
        assertEquals("No se pudo completar la operación." to PhoneConnectionMessages.QR_INVALID, failed.status to failed.notice)
        assertEquals(idle.actions to idle.trustedRows, failed.actions to failed.trustedRows)
    }

    @Test
    fun scanningShowsScannerPasteChoiceAndHidesRows() {
        for ((state, pasteVisible, actions) in listOf(
            Triple(PhoneConnectionState.Idle(), false, listOf(PASTE_CODE, CANCEL_SCAN)),
            Triple(PhoneConnectionState.Failed("x"), true, listOf(SUBMIT_PASTED, CANCEL_SCAN)),
        )) {
            val plan = plan(state, scanning = true, pasteVisible = pasteVisible)
            assertEquals("Apuntá la cámara al código QR que muestra ChinchillaCam en la computadora.", plan.status)
            assertTrue(plan.showScanner)
            assertEquals(pasteVisible, plan.showPasteField)
            assertEquals(actions, plan.actions)
            assertEquals(emptyList<TrustedDesktopRow>() to null, plan.trustedRows to plan.emptyTrustedText)
        }
        assertEquals(listOf("Pegar código", "Usar código", "Cancelar"), listOf(PASTE_CODE, SUBMIT_PASTED, CANCEL_SCAN).map { it.label })
    }

    @Test
    fun busyStatesShowStatusOnlyCancelWhileWaitingAndForgettableRows() {
        for ((state, status, actions) in listOf(
            Triple(PhoneConnectionState.AwaitingAccessory(AccessoryPurpose.Pairing), "Conectá el cable USB a la computadora para vincularla.", listOf(CANCEL_WAIT)),
            Triple(PhoneConnectionState.AwaitingAccessory(AccessoryPurpose.Connect("pc-1")), "Conectá el cable USB a la computadora.", listOf(CANCEL_WAIT)),
            Triple(PhoneConnectionState.AwaitingDesktopConfirmation("Studio"), "Confirmá el código también en «Studio».", emptyList()),
            Triple(PhoneConnectionState.Connecting("Studio"), "Conectando con «Studio»…", emptyList()),
        )) {
            val plan = plan(state, scanning = true)
            assertEquals(status, plan.status)
            assertEquals(actions, plan.actions)
            assertFalse("scanning only applies from Idle/Failed", plan.showScanner)
            assertEquals(listOf(false, false), plan.trustedRows.map { it.canConnect })
            assertEquals(listOf(true, true), plan.trustedRows.map { it.canForget })
        }
        assertEquals("Cancelar", CANCEL_WAIT.label)
    }

    @Test
    fun connectedOffersDisconnectAndSurfacesCameraProblems() {
        val connected = PhoneConnectionState.Connected("pc-2", "Studio")
        for ((camera, notice) in listOf(
            null to null,
            VisibleCameraServiceStatus(VisibleCameraServiceState.Running) to null,
            VisibleCameraServiceStatus(VisibleCameraServiceState.Starting) to "Iniciando la cámara…",
            VisibleCameraServiceStatus(VisibleCameraServiceState.Error, message = "Cámara ocupada.") to "Cámara ocupada.",
        )) {
            val plan = plan(connected, camera = camera)
            assertEquals("Transmitiendo a «Studio».", plan.status)
            assertEquals(notice, plan.notice)
            assertEquals(listOf(DISCONNECT), plan.actions)
            assertEquals("Desconectar", DISCONNECT.label)
            assertEquals(listOf(false to true, false to true), plan.trustedRows.map { it.canConnect to it.canForget })
        }
    }

    @Test
    fun identityRegeneratedWarningPrecedesStateNotice() {
        val warning = "La identidad del teléfono cambió. Volvé a vincular tus computadoras."
        assertEquals(warning, plan(PhoneConnectionState.Idle(), identityRegenerated = true).notice)
        assertEquals("$warning\nDesconectado.", plan(PhoneConnectionState.Idle("Desconectado."), identityRegenerated = true).notice)
        assertEquals(warning, plan(PhoneConnectionState.Failed("x"), scanning = true, identityRegenerated = true).notice)
    }

    @Test
    fun emptyTrustedListShowsEmptyTextOnlyWhereRowsAreShown() {
        val text = "Todavía no vinculaste ninguna computadora."
        assertEquals(text, plan(PhoneConnectionState.Idle(), trusted = emptyList()).emptyTrustedText)
        assertEquals(text, plan(PhoneConnectionState.Idle(), trusted = listOf(record("pc-old", "Antigua", revokedAt = 1))).emptyTrustedText)
        assertNull(plan(PhoneConnectionState.Idle(), scanning = true, trusted = emptyList()).emptyTrustedText)
    }

    private fun plan(
        state: PhoneConnectionState,
        scanning: Boolean = false,
        pasteVisible: Boolean = false,
        trusted: List<TrustedDesktopRecord> = this.trusted,
        identityRegenerated: Boolean = false,
        camera: VisibleCameraServiceStatus? = null,
    ): ConnectionScreenPlan = ConnectionScreenPlanner.plan(
        ConnectionScreenInput(state, scanning, pasteVisible, trusted, identityRegenerated, camera),
    )

    private fun record(id: String, name: String, revokedAt: Long? = null) =
        TrustedDesktopRecord(id, name, ByteArray(32), 1_000, 1_000, revokedAtEpochSeconds = revokedAt)
}
