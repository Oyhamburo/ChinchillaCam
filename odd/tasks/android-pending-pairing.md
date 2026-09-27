# M3 — Android pending pairing state model

## Scope

Build the pure Android state model for future desktop pairing without Activity, transport, LAN, TLS implementation, or persistent store work.

## M3a allowed edit surfaces

- NEW `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/PairingTrustFingerprint.kt`
- NEW `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/PairingTrustFingerprintTest.kt`
- `odd/tasks/android-pending-pairing.md`

## M3a requirements

- Derive a SHA-256 fingerprint from QR `trustMaterial` bytes.
- Return defensive copies so callers cannot mutate stored/derived fingerprint bytes.
- Reject empty trust material.
- State clearly: this fingerprint is an identity binding input for later verified proof and trust records, not authentication by itself.
- Do not implement PoP, TLS, QR scanning, Activity, transport, LAN, or persistence.

## M3b design constraints (not implemented in M3a)

- Coordinator calls an injected `PairingProofVerifier.verify(challenge, proof)` itself; no public method accepts a forged `Verified` object as authority.
- QR nonce cache is bounded (for example 64 outstanding/unexpired) and fail-closed on capacity instead of evicting live nonces.
- Nonce single-use is same-coordinator in-memory only; no restart guarantee.
- Precheck trusted store before save: reject revoked records and same desktop id with a different fingerprint unless explicit forget/reset happens elsewhere.
- Save before activation; if activation rejects due an already active different desktop, return typed `TrustedButInactive` and do not claim activation.
- If save throws, do not activate.
- No auto-handoff.

## Evidence

- RED: focused fingerprint tests failed to compile before `PairingTrustFingerprint` existed.
- GREEN focused tests: SHA-256 derivation, defensive input/output copies, empty trust material rejection, and explicit no-authentication note — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.

## M3b1 allowed edit surfaces

- NEW `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/PendingPairingCoordinator.kt`
- NEW `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/PendingPairingCoordinatorTest.kt`
- `odd/tasks/android-pending-pairing.md`

## M3b1 requirements

- Model pending pairing and proof/challenge state before any trust save or active desktop activation.
- Constructor requires injected `java.time.Clock`, `ChallengeNonceSource`, and `PairingProofVerifier`; no insecure defaults.
- Coordinator calls `PairingProofVerifier.verify(challenge, proofBytes)` itself; callers cannot submit a forged verified object directly as authority.
- Bind `desktopId`, `PairingTrustFingerprint`, QR nonce, fresh challenge nonce, session id, QR expiry, and proof expiry.
- Allow only one pending confirmation at a time.
- QR nonce cache has at most 64 live nonces and fails closed at capacity; it does not evict live nonces.
- Replay, expiry, mismatch, rejected proof, cache capacity, reject, and cancel return typed results.
- Do not call `TrustedDesktopStore.save` or `ActiveDesktopAuthority` in M3b1.
- Fingerprint is not proof/authentication.

## M3b1 evidence

- RED: focused coordinator tests failed to compile before `PendingPairingCoordinator`, challenge/verifier, pending state, and typed results existed.
- GREEN focused tests: matching verified proof creates pending confirmation; unverified proof and binding mismatches reject without pending; QR/proof/challenge expiry reject; one-pending-at-a-time, cancel, replay, bounded nonce capacity fail-closed; proof bytes and pending nonce bytes are defensively copied — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.

## M3b1 security follow-up evidence

- RED: regression tests covered QR nonce retention for full QR lifetime after shorter proof expiry, nonce consumption on rejected/mismatched proof, invalid proof verified-at bounds, invalid direct QR metadata, constructor cache bounds, and mutable QR byte snapshotting before verifier callbacks.
- GREEN focused tests: pending coordinator security regression suite — PASS.
- Full follow-up `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- Follow-up `:android:usb-probe:assembleDebug` — PASS.
- Follow-up `git diff --check` — PASS.
- Independent follow-up verifier: PASS.
