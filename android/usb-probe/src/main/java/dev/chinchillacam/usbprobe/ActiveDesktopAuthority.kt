package dev.chinchillacam.usbprobe

class ActiveDesktopAuthority(initialState: State = State.NoActiveDesktop) {
    var state: State = initialState
        private set

    sealed class State {
        object NoActiveDesktop : State()
        data class ActiveDesktop(val desktopId: String) : State()
        data class HandoffPending(
            val activeDesktopId: String,
            val requestedDesktopId: String,
        ) : State()
    }

    sealed class ActivationResult {
        data class Activated(val desktopId: String) : ActivationResult()
        data class KeptActive(val desktopId: String) : ActivationResult()

        sealed class Rejected : ActivationResult() {
            data class TrustRejected(
                val desktopId: String,
                val reason: TrustedDesktopAuthResult,
            ) : Rejected()

            data class SecondActiveDesktop(
                val activeDesktopId: String,
                val requestedDesktopId: String,
                val uiMessage: String = SECOND_ACTIVE_DESKTOP_MESSAGE_ES,
            ) : Rejected()
        }
    }

    sealed class HandoffResult {
        data class Pending(
            val activeDesktopId: String,
            val requestedDesktopId: String,
        ) : HandoffResult()

        data class Completed(val desktopId: String) : HandoffResult()
        data class Cancelled(val activeDesktopId: String) : HandoffResult()

        sealed class Rejected : HandoffResult() {
            data class NoActiveDesktop(val requestedDesktopId: String) : Rejected()
            data class NoPendingHandoff(val requestedDesktopId: String) : Rejected()
            data class UnexpectedPendingDesktop(
                val expectedDesktopId: String,
                val presentedDesktopId: String,
            ) : Rejected()

            data class TrustRejected(
                val desktopId: String,
                val reason: TrustedDesktopAuthResult,
            ) : Rejected()
        }
    }

    sealed class StopResult {
        data class Stopped(val desktopId: String) : StopResult()
        data class NotActive(val desktopId: String) : StopResult()
    }

    sealed class StoreInvalidationResult {
        data class ClearedActive(
            val desktopId: String,
            val reason: TrustedDesktopAuthResult,
        ) : StoreInvalidationResult()

        data class ClearedPending(
            val desktopId: String,
            val reason: TrustedDesktopAuthResult,
        ) : StoreInvalidationResult()

        data class Ignored(
            val desktopId: String,
            val reason: TrustedDesktopAuthResult,
        ) : StoreInvalidationResult()
    }

    fun requestActivation(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
        trustedDesktopStore: TrustedDesktopStore,
    ): ActivationResult {
        val trustResult = trustedDesktopStore.evaluate(desktopId, presentedTrustMaterialFingerprint, nowEpochSeconds)
        if (trustResult != TrustedDesktopAuthResult.Trusted) {
            handleTrustedStoreResult(desktopId, trustResult)
            return ActivationResult.Rejected.TrustRejected(desktopId, trustResult)
        }

        return when (val current = state) {
            State.NoActiveDesktop -> {
                state = State.ActiveDesktop(desktopId)
                ActivationResult.Activated(desktopId)
            }
            is State.ActiveDesktop -> {
                if (current.desktopId == desktopId) {
                    ActivationResult.KeptActive(desktopId)
                } else {
                    ActivationResult.Rejected.SecondActiveDesktop(
                        activeDesktopId = current.desktopId,
                        requestedDesktopId = desktopId,
                    )
                }
            }
            is State.HandoffPending -> {
                if (current.activeDesktopId == desktopId) {
                    ActivationResult.KeptActive(desktopId)
                } else {
                    ActivationResult.Rejected.SecondActiveDesktop(
                        activeDesktopId = current.activeDesktopId,
                        requestedDesktopId = desktopId,
                    )
                }
            }
        }
    }

    fun requestHandoff(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
        trustedDesktopStore: TrustedDesktopStore,
    ): HandoffResult {
        val trustResult = trustedDesktopStore.evaluate(desktopId, presentedTrustMaterialFingerprint, nowEpochSeconds)
        if (trustResult != TrustedDesktopAuthResult.Trusted) {
            handleTrustedStoreResult(desktopId, trustResult)
            return HandoffResult.Rejected.TrustRejected(desktopId, trustResult)
        }

        return when (val current = state) {
            State.NoActiveDesktop -> HandoffResult.Rejected.NoActiveDesktop(desktopId)
            is State.ActiveDesktop -> {
                state = State.HandoffPending(
                    activeDesktopId = current.desktopId,
                    requestedDesktopId = desktopId,
                )
                HandoffResult.Pending(current.desktopId, desktopId)
            }
            is State.HandoffPending -> {
                state = current.copy(requestedDesktopId = desktopId)
                HandoffResult.Pending(current.activeDesktopId, desktopId)
            }
        }
    }

    fun confirmHandoff(
        desktopId: String,
        presentedTrustMaterialFingerprint: ByteArray,
        nowEpochSeconds: Long,
        trustedDesktopStore: TrustedDesktopStore,
    ): HandoffResult {
        val current = state as? State.HandoffPending
            ?: return HandoffResult.Rejected.NoPendingHandoff(desktopId)
        if (current.requestedDesktopId != desktopId) {
            return HandoffResult.Rejected.UnexpectedPendingDesktop(
                expectedDesktopId = current.requestedDesktopId,
                presentedDesktopId = desktopId,
            )
        }

        val trustResult = trustedDesktopStore.evaluate(desktopId, presentedTrustMaterialFingerprint, nowEpochSeconds)
        if (trustResult != TrustedDesktopAuthResult.Trusted) {
            handleTrustedStoreResult(desktopId, trustResult)
            return HandoffResult.Rejected.TrustRejected(desktopId, trustResult)
        }

        state = State.ActiveDesktop(desktopId)
        return HandoffResult.Completed(desktopId)
    }

    fun cancelHandoff(): HandoffResult {
        return when (val current = state) {
            is State.HandoffPending -> {
                state = State.ActiveDesktop(current.activeDesktopId)
                HandoffResult.Cancelled(current.activeDesktopId)
            }
            is State.ActiveDesktop -> HandoffResult.Cancelled(current.desktopId)
            State.NoActiveDesktop -> HandoffResult.Rejected.NoPendingHandoff("")
        }
    }

    fun stopActiveDesktop(desktopId: String): StopResult {
        return when (val current = state) {
            State.NoActiveDesktop -> StopResult.NotActive(desktopId)
            is State.ActiveDesktop -> {
                if (current.desktopId == desktopId) {
                    state = State.NoActiveDesktop
                    StopResult.Stopped(desktopId)
                } else {
                    StopResult.NotActive(desktopId)
                }
            }
            is State.HandoffPending -> {
                if (current.activeDesktopId == desktopId || current.requestedDesktopId == desktopId) {
                    state = State.NoActiveDesktop
                    StopResult.Stopped(desktopId)
                } else {
                    StopResult.NotActive(desktopId)
                }
            }
        }
    }

    fun handleTrustedStoreResult(
        desktopId: String,
        result: TrustedDesktopAuthResult,
    ): StoreInvalidationResult {
        if (result == TrustedDesktopAuthResult.Trusted) {
            return StoreInvalidationResult.Ignored(desktopId, result)
        }

        return when (val current = state) {
            State.NoActiveDesktop -> StoreInvalidationResult.Ignored(desktopId, result)
            is State.ActiveDesktop -> {
                if (current.desktopId == desktopId) {
                    state = State.NoActiveDesktop
                    StoreInvalidationResult.ClearedActive(desktopId, result)
                } else {
                    StoreInvalidationResult.Ignored(desktopId, result)
                }
            }
            is State.HandoffPending -> {
                when (desktopId) {
                    current.activeDesktopId -> {
                        state = State.NoActiveDesktop
                        StoreInvalidationResult.ClearedActive(desktopId, result)
                    }
                    current.requestedDesktopId -> {
                        state = State.ActiveDesktop(current.activeDesktopId)
                        StoreInvalidationResult.ClearedPending(desktopId, result)
                    }
                    else -> StoreInvalidationResult.Ignored(desktopId, result)
                }
            }
        }
    }

    companion object {
        const val SECOND_ACTIVE_DESKTOP_MESSAGE_ES = "Ya hay una computadora activa"
    }
}
