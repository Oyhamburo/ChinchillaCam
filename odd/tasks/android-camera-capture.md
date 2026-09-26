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
- [x] T8: `MediaCodec` H.264/AVC MVP con state machine/fakes; producir chunks codificados o errores tipados.
- [x] T8b: orquestador local cámara→encoder con fakes. Conectar selección directa actual + permiso `CAMERA` actual + acción explícita Start/Stop visible a la secuencia abrir cámara → configurar encoder Surface → sesión repeating → drenar chunks codificados acotados/tipados → stop/release en fallos y ciclo de vida, sin Activity real todavía. Debe corregir el advisory T7 `R3-stop-failure-cleanup` antes de captura prolongada.
- [x] T8c: cableado Activity visible Start/Stop del pipeline local. Integrar el orquestador T8b con `UsbProbeActivity` solo mientras la app está visible; cerrar en `onPause`/`onDestroy`; sin USB/Wi‑Fi/network/storage/FGS ni claim de producto.
- [x] T9: métricas Android de captura/encode visibles desde el pipeline real local: FPS, chunks drenados/dropped por backpressure, latencia encode y estado.
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

### Revisión nativa RDD T8

- Candidato inicial: `a0fc849` contra base `4f9a6f4`.
- Hallazgo bloqueante corregido: `R3-001`; `MediaCodec.createEncoderByType` puede lanzar `java.io.IOException`, y el adapter debía devolver `H264EncoderStartOutcome.Failed` en lugar de escapar el contrato tipado.
- Corrección local: `430668e` (`fix: map encoder IO startup failure`) agrega manejo explícito de `java.io.IOException` como `encoder unavailable`.
- Lineage: `review-20f6156759bd8f98`.
- Resultado: corrección validada, aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-002`, `R3-003`, `R3-004`, `R3-005`. No abrieron corrección para T8; quedan como hardening futuro antes de conectar pipeline prolongado real.


## Reconciliación M2 después de T8

La lectura estructural posterior a T8 confirmó que `UsbProbeActivity` no referencia todavía los seams de T6 (`CameraDeviceOpenBoundary`), T7 (`CameraCaptureSessionBoundary`) ni T8 (`H264EncoderBoundary`). Por eso T9 no puede inventar métricas sintéticas y T10 no debe construir un foreground service sin captura real. Se inserta trabajo reviewable entre T8 y T9:

- **T8b — Orquestador local cámara→encoder.** Unidad pura/fake-first para coordinar selección directa actual, permiso `CAMERA` actual, apertura Camera2, arranque del encoder, sesión repeating al `EncoderInputSurface`, drain acotado de chunks y stop/release ante fallos. Sin `Activity`, USB, Wi‑Fi, storage, wire protocol, FGS ni pruebas físicas. Esta unidad debe corregir en el mismo límite el advisory T7 `R3-stop-failure-cleanup`: si `stopRepeating` falla, la sesión igualmente debe intentar cerrar recursos y reportar error tipado.
- **T8c — Cableado Activity visible.** Unidad separada si T8b + UI excede el tamaño reviewable: agregar acción explícita Start/Stop en español a `UsbProbeActivity`, usar selección directa persistida y permiso actual, arrancar/parar solo mientras visible y cerrar en lifecycle. Sin transporte externo ni claims.

T9 solo puede contar métricas a partir de estados/chunks/eventos del pipeline local de T8b/T8c. T10 solo puede evaluar FGS después de que el pipeline local esté cableado y cerrado de forma confiable.

## Nota T10 — foreground service de cámara

Para T10, la documentación oficial de Android indica que en API 34 el servicio con cámara debe declarar `foregroundServiceType="camera"`, permiso `FOREGROUND_SERVICE_CAMERA`, permiso genérico de foreground service y permiso runtime `CAMERA`. La restricción de permisos "while-in-use" implica que el FGS de cámara debe iniciarse mientras la Activity está visible; no basta con que `checkSelfPermission` diga concedido si el arranque ocurre desde background, pantalla bloqueada o callback tardío. T10 debe mantener notificación y acción de parada visible, y la continuidad con pantalla bloqueada queda sin claim hasta validación física.

## Verificación T8b

T8b agrega un orquestador local fake-first para conectar los seams T6/T7/T8 sin cablear todavía la Activity. Coordina selección directa actual, permiso `CAMERA` actual, apertura Camera2, encoder H.264 con `Surface`, sesión repeating y drain de chunks codificados. También endurece el cierre T7 para que un fallo en `stopRepeating` no impida intentar `close`.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraEncoderPipeline`, `CameraEncoderPipelineStartResult`, `CameraEncoderPipelineStopResult` y `RepeatingCaptureStopResult` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 4 ejecutadas y 27 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T8b

- `CameraEncoderPipeline` usa `CameraDeviceOpenBoundary`, `H264EncoderBoundary` y `CameraCaptureSessionBoundary`; no abre transportes ni escribe storage.
- Si la apertura Camera2 queda pendiente, el resultado `Opening` expone un avance explícito para continuar después de `onOpened`; esto permite cubrir callbacks async sin Activity todavía.
- Si falla encoder o configuración de captura, el orquestador intenta parar/release del encoder y cerrar cámara.
- `CameraEncoderPipelineSession.stop` cierra sesión de captura, encoder y cámara, y conserva errores de cierre como resultado tipado.
- `RepeatingCaptureSession.stop` ahora devuelve `RepeatingCaptureStopResult` y siempre intenta `close` aunque `stopRepeating` falle; esto resuelve el hardening T7 `R3-stop-failure-cleanup` antes de captura prolongada.
- T8b no integra `UsbProbeActivity`, no agrega UI, no inicia FGS y no define USB/Wi‑Fi/network/wire protocol/storage ni prueba física. Esa integración queda para T8c.

### Revisión nativa RDD T8b

- Candidato inicial: `59eaeca` contra base `19b3fbc`.
- Hallazgo bloqueante corregido: `R3-premature-capture-start`; el pipeline no debía reportar `Started` con una sesión de captura solo submitted antes de `onConfigured`.
- Corrección local: `0933fc0` (`fix: wait for capture configuration`) agrega estado `ConfiguringCapture`, avance explícito posterior a `onConfigured`, fallo tipado si `onConfigureFailed` cierra la sesión y test de fallo async de configuración que limpia encoder y cámara.
- Lineage: `review-a66bdc560529df5b`.
- Resultado: corrección validada, aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-discarded-startup-cleanup-failures`, `R3-terminal-open-state`. No abrieron corrección para T8b; quedan para hardening antes del cableado Activity/prolongado.

## Diseño T8c — Start/Stop visible en Activity

T8c cablea el pipeline local en `UsbProbeActivity` con acción explícita del usuario mientras la pantalla está visible. Esta unidad sigue siendo local: descarta outputs codificados en memoria después de drenar, no graba, no envía a USB/Wi‑Fi y no activa foreground service.

Límites T8c:

- UI española con botón Start/Stop de cámara local y estado claro: inactivo, iniciando, configurando, corriendo, detenido o error.
- Start solo por tap explícito; no auto-start tras recreación, callbacks de permiso ni restauración de estado.
- Start usa selección directa actual y permiso `CAMERA` leído fresco del SO inmediatamente antes de abrir.
- La ruta Android real debe construir adapters existentes: `AndroidCameraDeviceOpenGateway`, `AndroidH264EncoderGateway` y `AndroidCameraCaptureSessionGateway` cuando el callback `onOpened` entregue el `CameraDevice`.
- El trabajo de open/encoder/session/drain se orquesta fuera del hilo principal; la UI solo publica estado.
- Drain acotado descarta chunks en memoria y llama `consumeEncoded`; sin storage, wire format, transporte ni métricas finales todavía.
- Stop explícito, `onStop` y `onDestroy` cierran captura, encoder y cámara; hasta T10 no se promete continuidad con pantalla bloqueada.
- T8c debe manejar los advisories T8b antes de captura prolongada: exponer/mostrar errores de cleanup de arranque y tratar callback terminal de open como fallo/stop en vez de quedar en estado pendiente.
- Sin USB, Wi‑Fi, network, audio, storage, FGS, screen-lock claim, pruebas físicas ni claim de producto funcional.

## Verificación T8c

T8c agrega cableado visible Start/Stop en `UsbProbeActivity` para una prueba local cámara→encoder. La salida codificada se drena de forma acotada y se descarta en memoria; no hay grabación, transporte ni foreground service.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `VisibleCameraPipelineController`, `VisibleCameraPipelineLauncher`, `VisibleCameraPipelineLaunchResult`, `VisibleCameraPipelineHandle` y `VisibleCameraPipelineStatus` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T8c

- `VisibleCameraPipelineController` mantiene estado UI en español y exige tap explícito para iniciar; no hay auto-start en resume, permiso o recreación.
- Start rechaza falta de permiso `CAMERA` fresco y selección no directa antes de llamar al launcher.
- `AndroidVisibleCameraPipelineLauncher` usa adapters reales Android: `AndroidCameraDeviceOpenGateway`, `AndroidH264EncoderGateway` y `AndroidCameraCaptureSessionGateway`.
- El launcher espera apertura/configuración de captura en hilo de trabajo y trata callback terminal de open como fallo en vez de quedar pendiente.
- `UsbProbeActivity` agrega botón local Start/Stop, arranca fuera del hilo principal, drena chunks con límite pequeño, descarta en memoria y llama `consumeEncoded`.
- Stop explícito, `onStop` y `onDestroy` cierran el pipeline; el texto aclara que no continúa con pantalla bloqueada hasta T10.
- No hay USB, Wi‑Fi, network, storage, audio, wire protocol, FGS, prueba física ni claim de producto funcional.


### Revisión nativa RDD T8c

- Candidato inicial: `ed3c6a8` contra base `2ff48ee`.
- Hallazgos bloqueantes corregidos: `R3-drain-error-does-not-stop` y `R3-repeated-start-leaks-pipeline`; los fallos de drain/backpressure debían detener el pipeline activo, y un segundo Start no podía reemplazar o perder el handle de una captura ya activa.
- Corrección local: `bc29567` (`fix: stop local pipeline on drain errors`) agrega bloqueo/generación de start, `prepareStart`/`completeStart`, stop al fallar drain/backpressure y tests de doble start y fallo de drain.
- Lineage: `review-a092c738d58691bd`.
- Resultado: corrección validada, aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-start-lock-blocks-ui-stop`. No abrió corrección para T8c; queda para hardening antes de captura prolongada o T9/T10.

## Diseño T9 — métricas desde pipeline local real

T9 agrega métricas visibles derivadas de eventos reales del pipeline local T8c, no de datos sintéticos ni de transporte. Las métricas se calculan sobre chunks codificados drenados/descartados en memoria y estados de start/stop/error del controlador.

Límites T9:

- Usar reloj inyectado y determinista en tests; nada de sleeps arbitrarios para probar ventanas.
- Distinguir `Unknown`/"sin muestras aún" de FPS `0`: cero solo cuando hay una ventana con tiempo y conteo real cero, no al arrancar.
- Contar chunks codificados drenados y descartados en memoria, bytes descartados y drops por backpressure local acotado.
- La latencia encode solo puede mostrarse como **estimación** cuando el reloj local y `presentationTimeUs` se pueden comparar de forma coherente; si no, debe ser `Unknown`, no latencia falsa.
- No inventar calidad de red ni niveles de conexión antes de tener transporte real.
- Integrar texto español de métricas en la UI local de `UsbProbeActivity` usando estado real del controlador.
- Sin USB, Wi‑Fi, network, storage, audio, wire protocol, FGS, prueba física ni claim de producto funcional.

## Puerta T8d antes de T10

El advisory `R3-start-lock-blocks-ui-stop` de T8c queda como puerta de privacidad/seguridad antes de T10: si el arranque está pendiente, Stop/onStop debe cancelar pronto y cerrar cualquier recurso tardío. Si T9 no lo resuelve explícitamente, debe hacerse como T8d con RED/GREEN y revisión nativa antes de cualquier FGS o pantalla bloqueada. No se debe auto-resumir captura tras cambios de ciclo de vida.

## Verificación T9

T9 agrega métricas visibles desde eventos reales del pipeline local: chunks codificados drenados/descartados, bytes descartados, drops por backpressure y FPS de chunks codificados. No agrega transporte, storage ni calidad de red.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `LocalPipelineMetricsTracker`, `PipelineMetricsClock`, `MetricValue`, `MetricEstimate`, `LocalPipelineMetricsFormatter` y `metricsText` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T9

- `LocalPipelineMetricsTracker` usa `PipelineMetricsClock` inyectado para tests deterministas y `SystemPipelineMetricsClock` en producción.
- `encodedFps` empieza como `Unknown`/"sin muestras aún" y solo reporta `0.0` después de una ventana observada sin muestras vigentes.
- Los chunks drenados se cuentan como descartados en memoria junto con bytes; `BackpressureExceeded` incrementa drops de backpressure local.
- La latencia encode se muestra como estimación y solo cuando `presentationTimeUs` no está en el futuro respecto del reloj local; si los relojes no son comparables queda `Unknown`.
- `VisibleCameraPipelineController` actualiza `metricsText` desde resultados reales de `drainEncoded`; `UsbProbeActivity` muestra esas métricas en la sección de cámara local.
- No se introduce calidad de red, transporte USB/Wi‑Fi, storage, audio, wire protocol, FGS, prueba física ni claim de producto funcional.

### Revisión nativa RDD T9

- Candidato: `17f9326` contra base `8c2abaf`.
- Lineage: `review-ba0213606915aefa`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-latency-clock-domain-not-validated`, `R3-stale-metrics-during-restart`. No abrieron corrección para T9; quedan para hardening antes de métricas públicas más estrictas o captura prolongada.

## Diseño T8d — arranque cancelable antes de FGS

T8d resuelve la puerta de privacidad/seguridad `R3-start-lock-blocks-ui-stop` antes de cualquier T10/FGS. El objetivo es que Stop explícito, `onStop` o `onDestroy` durante arranque pendiente cancelen pronto y cierren recursos tardíos.

Límites T8d:

- Si el usuario detiene mientras el arranque está pendiente, el controlador marca la generación como cancelada y no puede transicionar a `Running` después.
- Si el launcher devuelve un handle tarde para una generación cancelada/stale, el controlador debe cerrarlo inmediatamente y mantener estado detenido.
- `onStop` durante arranque debe volver rápido sin esperar apertura/configuración; el trabajo tardío debe ser descartado/cerrado por generación.
- No auto-resume tras lifecycle ni permiso; el usuario debe tocar Start otra vez.
- Tests con fakes de carrera: stop durante `Starting`, lifecycle stop durante `Starting`, late handle cerrado, late failure no pisa estado detenido, start nuevo usa nueva generación.
- Sin cambios de FGS, USB/Wi‑Fi, network, storage, audio, wire protocol, pruebas físicas ni claims.

## Verificación T8d

T8d endurece el arranque visible para que Stop explícito o lifecycle durante un `Starting` pendiente no bloqueen esperando al launcher y no permitan que recursos tardíos queden activos.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló en `VisibleCameraPipelineControllerTest.lifecycleStopDuringPendingStartReturnsPromptlyAndClosesLateHandle` porque `completeStart` mantenía el lock mientras esperaba al launcher; `stopForLifecycle` no podía volver pronto.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T8d

- `completeStart` valida y marca estado bajo lock, pero ejecuta `launcher.start` fuera del lock para que Stop/onStop no espere el arranque Android.
- Stop/onStop incrementa la generación; si llega un handle tarde con una generación stale, se cierra inmediatamente y el estado detenido no se sobreescribe.
- Un fallo tardío después de lifecycle Stop tampoco pisa el estado detenido.
- No cambia FGS, transporte, storage, audio, wire protocol, pruebas físicas ni claims.

### Revisión nativa RDD T8d

- Candidato: `8dd1adb` contra base `00515fe`.
- Lineage: `review-a6fa0fe7bb03c30d`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-001`, `R3-002`. No abrieron corrección para T8d.

## Diseño T9b — métricas honestas antes de FGS

T9b corrige dos riesgos de verdad/lectura de T9 antes de usar métricas en estados más públicos o persistentes.

Alcance T9b:

- El indicador rotulado como FPS no debe contar buffers de configuración H.264 (`isCodecConfig`, SPS/PPS) ni salidas sin timestamp de frame confiable. Es FPS de video codificado observado, no buffers/s.
- Los contadores de chunks/bytes descartados permanecen separados y siguen contando todos los buffers descartados en memoria, incluidos config buffers, porque reflejan descarte local real.
- La latencia no se estima desde `System.nanoTime()` contra `presentationTimeUs` salvo que el dominio de reloj esté explícitamente probado. Por ahora debe mostrarse `sin estimación` por defecto; una medición futura podrá usar duración de encode con reloj monotónico inyectado o evidencia de mismo dominio.
- Stop/restart debe resetear o epoch-bindear métricas para que muestras antiguas no aparezcan como actuales después de detener e iniciar una nueva sesión visible.
- Tests RED: config buffer + frames cuenta FPS sólo por frames; latencia queda Unknown para PTS aunque parezca comparable; restart no conserva métricas previas visibles.
- Sin cambios de FGS, transporte, storage, audio, wire protocol, pruebas físicas ni claims.
