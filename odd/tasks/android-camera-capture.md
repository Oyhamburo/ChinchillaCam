# Captura Android y encoder local

> Estado (2026-09-26): M2 inicia en rama local `feat/android-camera-capture` desde HEAD limpio `d558885` de M1. Commits locales autorizados; sin push, PR ni merge. Pruebas físicas se mantienen para el final.

## Alcance M2

Construir la ruta Android desde selección de cámara hasta frames/encoder/metricas con límites verificables por tests y fakes antes de cualquier validación física.

## Reglas de seguridad

- No declarar soporte físico Samsung ni compatibilidad de captura hasta pruebas finales.
- No abrir cámaras físicas-only ni IDs stale/persistidos si ya no son `DirectOpenCandidate`.
- Releer permiso `CAMERA` real justo antes de abrir; no confiar en booleanos persistidos.
- Mantener estable el flujo de prueba USB existente.
- No streaming externo hasta que pairing/trust/one-active-PC exista en M3/M4.

## Plan secuencial

- [ ] T6: apertura `CameraDevice` acotada detrás de seam testeable. Abrir solo ID seleccionado que siga siendo `DirectOpenCandidate` y solo con permiso real concedido; errores tipados para sin selección, físico-only/stale, permiso faltante y fallo del opener; `StateCallback` maneja `onOpened`, `onDisconnected`, `onError`; cerrar recursos en stop/cancel; callbacks stale no activan cámara después de deselección. Sin sesión de captura, sin `ImageReader`, sin frames, sin encoder, sin streaming externo y sin pruebas físicas.
- [ ] T7: sesión de captura preview/frame source con recursos cerrables; no encoder aún.
- [ ] T8: `MediaCodec` H.264/AVC MVP con state machine/fakes; producir chunks codificados o errores tipados.
- [ ] T9: métricas Android de captura/encode visibles: FPS, dropped frames, latencia encode y estado.
- [ ] T10: foreground service/pantalla bloqueada experimental, con límites honestos y sin claims hasta prueba física.

## T6 límites de aceptación

- Tests RED antes de producción para selección directa, sin selección, físico-only/stale, permiso faltante y fallo del opener.
- El boundary debe impedir open si el permiso real fue revocado entre UI y apertura.
- La selección persistida no puede causar fallback silencioso a otra cámara para abrir.
- La integración real puede crear un adapter con `CameraManager.openCamera`, pero no puede crear sesión de captura, `ImageReader`, frame stream ni encoder.
- Cancel/stop debe cerrar cualquier cámara abierta y prevenir activación por callbacks stale.

## Verificación T6

T6 agrega un boundary de apertura acotada y un adapter Android mínimo para solicitar `CameraManager.openCamera` sin crear sesiones de captura ni frames.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraDeviceOpenBoundary`, `CameraDeviceOpenGateway`, `CameraDeviceOpenRequestOutcome`, `CameraDeviceOpenResult`, `CameraOpenCallbacks` y `CloseableCameraDevice` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T6

- `CameraDeviceOpenBoundary` abre solo un ID seleccionado que siga siendo `DirectOpenCandidate` en el snapshot actual.
- El permiso `CAMERA` se reevalúa como parámetro inmediato de apertura; si está ausente/revocado, el gateway no se llama.
- Selecciones físicas-only, stale o ausentes devuelven errores tipados y no hacen fallback silencioso a otra cámara.
- `CameraOpenSession` activa `onOpened`, cierra en disconnect/error/cancel y cierra dispositivos tardíos si la sesión fue cancelada antes del callback.
- `AndroidCameraDeviceOpenGateway` encapsula `CameraManager.openCamera` y traduce fallos esperados a resultado tipado. No crea `CameraCaptureSession`, `ImageReader`, frames, encoder ni transporte externo.
