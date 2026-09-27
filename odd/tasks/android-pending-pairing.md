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
