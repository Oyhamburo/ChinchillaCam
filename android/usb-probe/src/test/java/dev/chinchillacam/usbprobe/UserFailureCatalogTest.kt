package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFailureCatalogTest {
    @Test fun allCausesHaveSafeSpanishCopyAndAnAction() {
        FailureCause.values().forEach { cause ->
            val message = UserFailureCatalog.messageFor(cause)
            assertTrue("$cause has no message", message.isNotBlank())
            assertFalse(message.contains("CameraAccessException: platform-secret"))
            listOf("Backpressure", "encoder", "Exception").forEach { forbidden ->
                assertFalse("$cause exposed $forbidden", message.contains(forbidden))
            }
            assertTrue("$cause is not actionable", UserFailureCatalog.actionFor(cause) in RecoveryAction.values())
        }
        assertEquals(RecoveryAction.OpenAppSettings, UserFailureCatalog.actionFor(FailureCause.CameraPermissionDenied))
        assertEquals(RecoveryAction.RetryCamera, UserFailureCatalog.actionFor(FailureCause.CameraOpenFailed))
        assertEquals(
            "La computadora y el teléfono no se entendieron. Actualizá ChinchillaCam en los dos e intentá de nuevo.",
            UserFailureCatalog.messageFor(FailureCause.SessionProtocolViolation),
        )
        assertEquals(RecoveryAction.Retry, UserFailureCatalog.actionFor(FailureCause.SessionProtocolViolation))
        assertEquals(RecoveryAction.None, UserFailureCatalog.actionFor(FailureCause.SessionLocalClose))
    }

    @Test fun sessionEndReasonsMapToTypedSafeCopy() {
        val ends = listOf(
            SessionEnd.PeerDead to FailureCause.SessionPeerDead,
            SessionEnd.Backpressure to FailureCause.VideoSaturated,
            SessionEnd.ReadFailed("platform-secret") to FailureCause.SessionReadFailed,
            SessionEnd.WriteFailed("platform-secret") to FailureCause.SessionWriteFailed,
            SessionEnd.ProtocolViolation("platform-secret") to FailureCause.SessionProtocolViolation,
            SessionEnd.LocalClose to FailureCause.SessionLocalClose,
        )
        ends.forEach { (end, expected) ->
            assertEquals(expected, UserFailureCatalog.causeFor(end))
            assertEquals(UserFailureCatalog.messageFor(expected), SessionEgressBinding.messageForEnd(end))
            assertFalse(SessionEgressBinding.messageForEnd(end).contains("platform-secret"))
        }
    }
}
