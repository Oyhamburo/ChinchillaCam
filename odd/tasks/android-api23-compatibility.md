# Compatibilidad Android API 23

## Bloqueo conocido

`:android:usb-probe:lintDebug` falla con errores `NewApi` preexistentes para `minSdk = 23`.

Primer hallazgo observado:

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalog.kt`
- Uso de `Map.putIfAbsent`, requiere API 24 con minSdk 23.

## Alcance

Este documento sólo registra el bloqueo. No corrige código en el corte M3b2.

## Requisito para corte futuro

Abrir un corte TDD independiente para corregir todos los hallazgos lint API23 relevantes y no afirmar soporte runtime minSdk 23 hasta resolverlos.

## C1 — CameraCapabilityCatalog

### Alcance permitido

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalog.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalogTest.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/AndroidCameraManagerGatewayTest.kt` sólo si es necesario
- `odd/tasks/android-api23-compatibility.md`

### Objetivo

Eliminar los hallazgos `NewApi` de cámara sin cambiar `minSdk` ni ocultar el fallo global de lint.

### Evidencia RED inicial

`lintDebug` reportó 8 errores `NewApi`; 3 están en `CameraCapabilityCatalog.kt`:

- `Map.putIfAbsent` requiere API 24.
- `CameraCharacteristics.physicalCameraIds` requiere API 28.
- `CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE` requiere API 30.

Los 5 errores restantes están en `PairingQrPayload.kt` por `java.util.Base64` API 26 y quedan para C2.

### Evidencia GREEN C1

- RED: `lintDebug` inicial tenía 3 errores `NewApi` en `CameraCapabilityCatalog.kt` y una prueba focused cubrió que un physical child duplicado conserva el primer parent ordenado sin depender de `Map.putIfAbsent`.
- GREEN focused tests: `CameraCapabilityCatalogTest` y `AndroidCameraManagerGatewayTest` — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `:android:usb-probe:lintDebug` — FAILED esperado con 5 errores `NewApi` restantes en `PairingQrPayload.kt`; ya no aparecen errores de cámara.
- `git diff --check` — PASS.
- Independent verifier: PASS.
