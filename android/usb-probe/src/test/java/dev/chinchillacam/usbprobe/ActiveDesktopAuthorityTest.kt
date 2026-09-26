package dev.chinchillacam.usbprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveDesktopAuthorityTest {
    private val trustedFingerprint = byteArrayOf(0x01, 0x02, 0x03)
    private val secondFingerprint = byteArrayOf(0x04, 0x05, 0x06)

    @Test
    fun firstTrustedDesktopBecomesActive() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint)
        val authority = ActiveDesktopAuthority()

        val result = authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        assertEquals(ActiveDesktopAuthority.ActivationResult.Activated("pc-1"), result)
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun sameActiveDesktopRenewsWhenStillTrusted() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        val result = authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 180L, trustedDesktopStore = store)

        assertEquals(ActiveDesktopAuthority.ActivationResult.KeptActive("pc-1"), result)
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun secondTrustedDesktopIsRejectedWhileOneIsActive() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint, "pc-2", secondFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        val result = authority.requestActivation("pc-2", secondFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        assertEquals(
            ActiveDesktopAuthority.ActivationResult.Rejected.SecondActiveDesktop(
                activeDesktopId = "pc-1",
                requestedDesktopId = "pc-2",
                uiMessage = ActiveDesktopAuthority.SECOND_ACTIVE_DESKTOP_MESSAGE_ES,
            ),
            result,
        )
        assertEquals("Ya hay una computadora activa", ActiveDesktopAuthority.SECOND_ACTIVE_DESKTOP_MESSAGE_ES)
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun explicitHandoffConfirmChangesActiveDesktop() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint, "pc-2", secondFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        assertEquals(
            ActiveDesktopAuthority.HandoffResult.Pending("pc-1", "pc-2"),
            authority.requestHandoff("pc-2", secondFingerprint, nowEpochSeconds = 160L, trustedDesktopStore = store),
        )
        assertEquals(ActiveDesktopAuthority.State.HandoffPending("pc-1", "pc-2"), authority.state)

        val result = authority.confirmHandoff("pc-2", secondFingerprint, nowEpochSeconds = 170L, trustedDesktopStore = store)

        assertEquals(ActiveDesktopAuthority.HandoffResult.Completed("pc-2"), result)
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-2"), authority.state)
    }

    @Test
    fun cancelHandoffPreservesOriginalActiveDesktop() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint, "pc-2", secondFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)
        authority.requestHandoff("pc-2", secondFingerprint, nowEpochSeconds = 160L, trustedDesktopStore = store)

        val result = authority.cancelHandoff()

        assertEquals(ActiveDesktopAuthority.HandoffResult.Cancelled("pc-1"), result)
        assertEquals(ActiveDesktopAuthority.State.ActiveDesktop("pc-1"), authority.state)
    }

    @Test
    fun unknownRevokedExpiredAndMismatchCannotBecomeActive() {
        val store = InMemoryTrustedDesktopStore()
        store.save(record("revoked", byteArrayOf(0x01), revokedAtEpochSeconds = 120L))
        store.save(record("expired", byteArrayOf(0x02), expiresAtEpochSeconds = 140L))
        store.save(record("mismatch", byteArrayOf(0x03)))
        val authority = ActiveDesktopAuthority()

        assertEquals(
            ActiveDesktopAuthority.ActivationResult.Rejected.TrustRejected("unknown", TrustedDesktopAuthResult.Unknown),
            authority.requestActivation("unknown", byteArrayOf(0x09), nowEpochSeconds = 150L, trustedDesktopStore = store),
        )
        assertEquals(
            ActiveDesktopAuthority.ActivationResult.Rejected.TrustRejected("revoked", TrustedDesktopAuthResult.Revoked),
            authority.requestActivation("revoked", byteArrayOf(0x01), nowEpochSeconds = 150L, trustedDesktopStore = store),
        )
        assertEquals(
            ActiveDesktopAuthority.ActivationResult.Rejected.TrustRejected("expired", TrustedDesktopAuthResult.Expired),
            authority.requestActivation("expired", byteArrayOf(0x02), nowEpochSeconds = 150L, trustedDesktopStore = store),
        )
        assertEquals(
            ActiveDesktopAuthority.ActivationResult.Rejected.TrustRejected("mismatch", TrustedDesktopAuthResult.FingerprintMismatch),
            authority.requestActivation("mismatch", byteArrayOf(0x04), nowEpochSeconds = 150L, trustedDesktopStore = store),
        )
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    @Test
    fun explicitStopClearsActiveDesktop() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)

        val result = authority.stopActiveDesktop("pc-1")

        assertEquals(ActiveDesktopAuthority.StopResult.Stopped("pc-1"), result)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    @Test
    fun revokedActiveInvalidatesWithoutFallbackToAnotherTrustedDesktop() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint, "pc-2", secondFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)
        store.revoke("pc-1", revokedAtEpochSeconds = 160L)

        val result = authority.handleTrustedStoreResult("pc-1", TrustedDesktopAuthResult.Revoked)

        assertEquals(ActiveDesktopAuthority.StoreInvalidationResult.ClearedActive("pc-1", TrustedDesktopAuthResult.Revoked), result)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    @Test
    fun forgottenActiveInvalidatesWithoutFallbackToAnotherTrustedDesktop() {
        val store = storeWithTrustedDesktop("pc-1", trustedFingerprint, "pc-2", secondFingerprint)
        val authority = ActiveDesktopAuthority()
        authority.requestActivation("pc-1", trustedFingerprint, nowEpochSeconds = 150L, trustedDesktopStore = store)
        store.forget("pc-1")

        val result = authority.handleTrustedStoreResult("pc-1", TrustedDesktopAuthResult.Unknown)

        assertEquals(ActiveDesktopAuthority.StoreInvalidationResult.ClearedActive("pc-1", TrustedDesktopAuthResult.Unknown), result)
        assertEquals(ActiveDesktopAuthority.State.NoActiveDesktop, authority.state)
    }

    private fun storeWithTrustedDesktop(
        firstDesktopId: String,
        firstFingerprint: ByteArray,
        secondDesktopId: String? = null,
        secondFingerprint: ByteArray? = null,
    ): InMemoryTrustedDesktopStore {
        val store = InMemoryTrustedDesktopStore()
        store.save(record(firstDesktopId, firstFingerprint))
        if (secondDesktopId != null && secondFingerprint != null) {
            store.save(record(secondDesktopId, secondFingerprint))
        }
        return store
    }

    private fun record(
        desktopId: String,
        fingerprint: ByteArray,
        expiresAtEpochSeconds: Long? = null,
        revokedAtEpochSeconds: Long? = null,
    ) = TrustedDesktopRecord(
        desktopId = desktopId,
        desktopName = "Desktop $desktopId",
        trustMaterialFingerprint = fingerprint,
        createdAtEpochSeconds = 100L,
        lastSeenAtEpochSeconds = 110L,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        revokedAtEpochSeconds = revokedAtEpochSeconds,
    )
}
