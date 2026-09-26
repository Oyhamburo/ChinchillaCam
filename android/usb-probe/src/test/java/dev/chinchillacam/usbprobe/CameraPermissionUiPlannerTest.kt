package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionUiPlannerTest {
    @Test
    fun spanishPermissionUiRequiresExplicitTapBeforeAndroidPermissionRequest() {
        val ui = CameraPermissionUiPlanner.plan(CameraPermissionUiModel.NotRequested)

        assertEquals("Permiso de cámara", ui.title)
        assertEquals("La cámara no se abrirá en este paso. Tocá el botón solo para autorizar futuros pasos.", ui.status)
        assertEquals("Solicitar permiso de cámara", ui.primaryActionLabel)
        assertTrue(ui.primaryActionEnabled)
        assertFalse(ui.openCameraAllowed)
    }

    @Test
    fun grantedPermissionUiDoesNotOpenCameraAutomatically() {
        val ui = CameraPermissionUiPlanner.plan(CameraPermissionUiModel.Granted)

        assertEquals("Permiso de cámara concedido. La apertura real queda para un paso posterior.", ui.status)
        assertEquals("Permiso concedido", ui.primaryActionLabel)
        assertFalse(ui.primaryActionEnabled)
        assertFalse(ui.openCameraAllowed)
    }

    @Test
    fun deniedPermissionUiAllowsRetryWithoutHardwareClaim() {
        val ui = CameraPermissionUiPlanner.plan(CameraPermissionUiModel.Denied)

        assertEquals("Android denegó el permiso de cámara; podés intentarlo de nuevo.", ui.status)
        assertEquals("Volver a solicitar permiso de cámara", ui.primaryActionLabel)
        assertTrue(ui.primaryActionEnabled)
        assertFalse(ui.openCameraAllowed)
    }
    @Test
    fun unknownPermissionResultIsVisibleAndRetryableWithoutOpeningCamera() {
        val ui = CameraPermissionUiPlanner.plan(CameraPermissionUiModel.UnknownResult)

        assertEquals("Android devolvió un resultado de permiso de cámara incompleto; podés intentarlo de nuevo.", ui.status)
        assertEquals("Volver a solicitar permiso de cámara", ui.primaryActionLabel)
        assertTrue(ui.primaryActionEnabled)
        assertFalse(ui.openCameraAllowed)
    }

}
