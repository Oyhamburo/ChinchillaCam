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

## Requisitos M3b1

- Modelar el estado pendiente de pairing y prueba/desafío antes de cualquier guardado de confianza o activación de desktop.
- El constructor requiere `EpochSecondsSource`, `ChallengeNonceSource` y `PairingProofVerifier` inyectados; no hay defaults inseguros ni APIs Android 26+ nuevas en el coordinador.
- El coordinador llama por sí mismo a `PairingProofVerifier.verify(challenge, proofBytes)`; los callers no pueden entregar un `Verified` forjado como autoridad.
- Vincular `desktopId`, `PairingTrustFingerprint`, nonce QR, nonce de desafío fresco, session id, expiración QR, expiración de desafío y expiración de proof.
- Permitir una sola confirmación pendiente a la vez.
- La cache de nonces QR tiene como máximo 64 nonces vivos y falla cerrada en capacidad; no expulsa nonces vivos.
- Replay, expiración, mismatch, proof rechazado, capacidad de cache, reject y cancel devuelven resultados tipados.
- No llamar `TrustedDesktopStore.save` ni `ActiveDesktopAuthority` en M3b1.
- El fingerprint no es prueba ni autenticación.

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

## Evidencia de seguimiento M3b1 API 23 y verificador lento

- RED: la revisión de compatibilidad minSdk 23 detectó que el coordinador usaba `java.time.Clock` y `java.util.Base64` (Android 26+ sin desugaring); la revisión de seguridad del verificador lento exigió releer el tiempo después de `verify`.
- GREEN focused tests: el coordinador usa `EpochSecondsSource`, claves de nonce inmutables, y rechaza expiración QR/challenge/proof observada después del verificador manteniendo el nonce consumido hasta la expiración QR — PASS.
- Full API 23 follow-up `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- API 23 follow-up `:android:usb-probe:assembleDebug` — PASS.
- Grep del coordinador para `java.time`/`java.util.Base64` — PASS (sin coincidencias).
- Follow-up `git diff --check` — PASS.
- `:android:usb-probe:lintDebug` se intentó por separado y falló por problemas preexistentes no relacionados (primero: `CameraCapabilityCatalog.kt` `Map.putIfAbsent`, API 24 con minSdk 23); no se identificó hallazgo lint del coordinador M3b1.
- Verificador independiente API 23 follow-up: PASS.
- Pendiente planificado: después de M3b2, hacer un corte docs-only <=400 para traducir este task file completo al español sin tocar código.

## M3b2 — confirmación explícita, guardado de confianza y activación

### Superficies permitidas

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/PendingPairingCoordinator.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/PendingPairingCoordinatorTest.kt`
- `odd/tasks/android-pending-pairing.md`

### Requisitos

- Confirmar explícitamente un pending antes de guardar confianza o activar desktop.
- Sin confirmación no hay `TrustedDesktopStore.save` ni `ActiveDesktopAuthority.requestActivation`.
- `pendingId` incorrecto no produce efectos laterales.
- Pending expirado al confirmar devuelve expirado tipado, limpia pending y no guarda/activa.
- Precheck del store: rechazar record revocado y mismo `desktopId` con fingerprint distinto sin sobrescribir.
- Si `save` falla, no activar.
- Confirm válido: guardar primero y luego solicitar activación.
- Si ya hay otro desktop activo, devolver `TrustedButInactive`; no auto-handoff ni claim de activación.
- Rutas terminales limpian pending y retienen el nonce QR hasta su expiración.
- `state()` no debe exponer pending accionable ya expirado.

### No objetivos

- No Activity, implementación TLS, LAN, auth real, source de compatibilidad API23 ni native review.

## Evidencia M3b2

- RED: las pruebas de confirmación fallaron al compilar antes de `confirm`, `pendingId` y `PendingPairingConfirmResult`.
- GREEN focused tests: sin confirmación no guarda/activa; `pendingId` incorrecto no tiene efectos; expirado no guarda/activa y `state()` no expone pending accionable; store revocado o fingerprint distinto rechaza sin overwrite; fallo de save no activa; confirm válido guarda y activa; segundo desktop queda `TrustedButInactive` sin auto-handoff; cancel retiene nonce — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
