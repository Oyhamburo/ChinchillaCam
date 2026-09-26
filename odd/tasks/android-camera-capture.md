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

- [x] T6: apertura `CameraDevice` acotada detrás de seam testeable. Abrir solo ID seleccionado que siga siendo `DirectOpenCandidate` y solo con permiso real concedido; errores tipados para sin selección, físico-only/stale, permiso faltante y fallo del opener; `StateCallback` maneja `onOpened`, `onDisconnected`, `onError`; cerrar recursos en stop/cancel; callbacks stale no activan cámara después de deselección. Sin sesión de captura, sin `ImageReader`, sin frames, sin encoder, sin streaming externo y sin pruebas físicas.
- [x] T7: sesión de captura preview/frame source con recursos cerrables; no encoder aún.
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


### Revisión nativa RDD T6

- Candidato inicial: `eef16e9` contra base `d558885`.
- Hallazgo bloqueante corregido: `R3-terminal-callback-device-leak`; si `onDisconnected`/`onError` era el primer callback, el adapter podía descartar el `CameraDevice` callback sin cerrarlo.
- Corrección local: `8c06bbb` (`fix: close terminal camera open callbacks`) permite pasar el device terminal al callback, lo cierra incluso sin `onOpened` previo y agrega test de terminal-before-open.
- Lineage: `review-8b56c568217a22f4`.
- Resultado: corrección validada, aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.

## Diseño T7 — sesión con `Surface` inyectada

T7 no implementa un camino `ImageReader`/YUV CPU-copy porque ese prototipo sería descartable para la ruta de baja latencia de T8. El boundary de T7 configura una sesión Camera2 de repetición hacia una `Surface` inyectada/propiedad del siguiente componente. En T8 esa `Surface` podrá venir de `MediaCodec.createInputSurface()`.

Límites T7:

- Crear solo el seam de sesión/repeating request y recursos cerrables.
- La `Surface` se inyecta; T7 no crea encoder, `ImageReader`, buffers YUV ni frame stream.
- Modelar fallo de configuración, cancel/stop, callback stale y cierre con fakes.
- El adapter real puede compilar contra `CameraDevice.createCaptureSession`, `CaptureRequest` y `CameraCaptureSession.setRepeatingRequest`, pero no debe integrarse todavía en la Activity ni declarar producto funcional.
- Sin USB, Wi‑Fi, foreground service, background capture ni pruebas físicas.


## Verificación T7

T7 agrega un boundary de sesión Camera2 de repetición hacia una `Surface` inyectada, dejando el origen real de la `Surface` para T8.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraCaptureSessionBoundary`, `CaptureSessionRequestOutcome`, `CameraCaptureStartResult`, `CameraCaptureSessionCallbackResult`, `CaptureTargetSurface`, `CameraCaptureSessionGateway`, `CaptureSessionCallbacks` y `CloseableRepeatingCaptureSession` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T7

- `CameraCaptureSessionBoundary` exige una `CameraOpenSession` activa y una `CaptureTargetSurface` explícita.
- Faltante de surface, cámara inactiva y fallo de configuración devuelven errores tipados.
- `RepeatingCaptureSession` cierra recursos en stop/cancel/configure-failed y evita activar callbacks stale si la sesión fue cancelada antes de `onConfigured`.
- `AndroidCameraCaptureSessionGateway` compila contra `CameraDevice.createCaptureSession`, `CameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)` y `CameraCaptureSession.setRepeatingRequest` hacia una `AndroidCaptureTargetSurface`.
- No se crea `ImageReader`, no se leen frames/YUV, no hay encoder, no hay transporte externo, no hay integración Activity y no hay prueba física.

### Revisión nativa RDD T7

- Candidato: `a13495d` contra base `c960d7f`.
- Lineage: `review-ffd0a9367006b39d`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-stop-failure-cleanup`. No abrió corrección para T7; queda como hardening futuro antes de integrar captura real prolongada.

## Diseño T8 — seam `MediaCodec` H.264 con `Surface` de entrada

T8 crea el boundary del encoder H.264/AVC sin conectarlo todavía a cámara, USB, Wi‑Fi, storage ni UI. El encoder es dueño de la `Surface` de entrada que luego podrá inyectarse en T7 como `AndroidCaptureTargetSurface`, pero T8 no decide formato de paquete de red ni contrato de transporte.

Límites T8:

- Configurar solo `MediaCodec` video/avc con entrada por `Surface` y parámetros explícitos de resolución, bitrate, fps e intervalo I-frame.
- Exponer una `CaptureTargetSurface` propiedad del encoder para enlazar futuro T7, sin abrir cámara ni iniciar sesión de captura.
- Drenar outputs a chunks tipados con timestamp, flags de config/keyframe y copia propia de bytes desde `BufferInfo.offset/size`.
- Liberar `releaseOutputBuffer` en todos los caminos donde se obtiene un buffer.
- Manejar `INFO_OUTPUT_FORMAT_CHANGED` y `BUFFER_FLAG_CODEC_CONFIG` sin descartar SPS/PPS.
- Acotar backpressure por cantidad máxima de chunks pendientes; cuando se excede, devolver error tipado y no crecer memoria sin límite.
- Cancel/stop/release deben cerrar recursos de forma idempotente y tolerar errores de cierre como estado tipado.
- Sin audio, almacenamiento, paquetes de wire protocol, transporte, Activity, foreground service, pruebas físicas ni claims de producto funcional.

## Verificación T8

T8 agrega un seam `MediaCodec` H.264/AVC con entrada por `Surface` propia del encoder y salida como chunks codificados tipados. Aún no se conecta con cámara, transportes, storage ni UI.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `H264EncoderBoundary`, `H264EncoderConfig`, `EncoderInputSurface`, `H264EncoderGateway`, `H264EncoderStartOutcome`, `H264EncoderStartResult`, `H264EncoderSession`, `H264DrainResult`, `H264CodecOutput`, `H264BufferInfo`, `H264BufferFlags`, `CloseableH264CodecSession`, `H264CodecCloseOutcome` y `H264EncoderStopResult` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T8

- `H264EncoderBoundary` delega configuración a un gateway y devuelve una `EncoderInputSurface` que implementa `CaptureTargetSurface` para enlace futuro con T7.
- `H264EncoderSession.drain` copia bytes propios desde `H264BufferInfo.offset/size`, conserva `presentationTimeUs`, marca config/keyframe y llama `releaseOutputBuffer` también si la copia falla.
- `INFO_OUTPUT_FORMAT_CHANGED` se modela como formato observado y no descarta los outputs siguientes; `BUFFER_FLAG_CODEC_CONFIG` produce chunk para no perder SPS/PPS.
- El backpressure queda acotado por chunks pendientes; el consumidor debe llamar `consumePending` antes de drenar más cuando llega al límite.
- `stop` y `cancel` son idempotentes y devuelven errores tipados de cierre/release.
- `AndroidH264EncoderGateway` compila contra `MediaCodec.createEncoderByType`, `MediaFormat`, `configure`, `createInputSurface`, `start`, `dequeueOutputBuffer`, `getOutputBuffer` y `releaseOutputBuffer`.
- No hay audio, almacenamiento, formato wire, transporte USB/Wi‑Fi, integración Activity, foreground service, prueba física ni claim de producto funcional.
