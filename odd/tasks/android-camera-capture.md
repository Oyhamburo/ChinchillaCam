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
- [x] T10a: shell seguro de foreground service de cámara visible. Declara permisos/tipo FGS cámara, agrega planner puro para permitir start solo desde Activity visible con permiso `CAMERA` fresco y selección directa actual, publica notificación honesta de prueba local visible descartada en memoria, y mantiene la Activity como dueña del pipeline local: start del shell solo después de `Running`; stop en stop de usuario, `onStop` y `onDestroy`; sin transferencia de ownership, background cold-start, USB/Wi‑Fi/network/storage/audio/wire protocol ni claims de pantalla bloqueada.
- [x] T10b: transferir ownership completo cámara→encoder al foreground service no exportado. El service inicia desde Activity visible con permiso `CAMERA` fresco, selección directa actual y marcador de arranque visible; revalida permiso/snapshot en el service; Activity no retiene la cámara ni la detiene en `onStop` mientras el ownership de service está solicitado/activo; STOP/destrucción del service detienen el pipeline; `START_NOT_STICKY`; sin background cold-start, Activity retenida, tests físicos ni claims de compatibilidad.

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

## Verificación T9b

T9b corrige las métricas visibles para que el texto no sobredeclare FPS ni latencia.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló en:
  - `LocalPipelineMetricsTest.encodedFpsCountsOnlyVideoFrameChunksNotCodecConfigBuffers` porque FPS contaba SPS/PPS como frame.
  - `LocalPipelineMetricsTest.presentationTimestampDoesNotProduceLatencyWithoutProvenClockDomain` porque PTS aparentemente comparable producía latencia sin prueba de dominio de reloj.
  - `LocalPipelineMetricsTest.stopClearsMetricsSoStaleSamplesDoNotRemainCurrent` porque Stop conservaba muestras visibles viejas.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T9b

- FPS cuenta sólo chunks no-config con `presentationTimeUs >= 0` y timestamps distintos dentro del drain observado; los buffers SPS/PPS siguen contándose sólo en chunks/bytes descartados.
- La latencia visible queda `sin estimación` hasta que exista medición monotónica propia o evidencia explícita de mismo dominio de reloj.
- Stop resetea métricas para que la UI detenida/reiniciada no muestre muestras de una sesión previa.
- No cambia FGS, transporte, storage, audio, wire protocol, pruebas físicas ni claims.

### Revisión nativa RDD T9b

- Candidato: `5219a45` contra base `633dd5a`.
- Lineage: `review-14346f5071aab802`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-batching-dependent-fps`, `R3-restart-test-gap`. No abrieron corrección para T9b; quedan como hardening posterior si se requiere mayor precisión temporal o cobertura de reinicio end-to-end.

## Verificación T10a

T10a agrega un shell seguro de foreground service de cámara visible sin transferir todavía la propiedad del pipeline desde la Activity.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `VisibleCameraForegroundServicePlanner`, `VisibleCameraForegroundServiceStartPlan`, `VisibleCameraForegroundServiceNotificationSpec` y `VisibleCameraForegroundService` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date.

### Límites y decisiones T10a

- Manifest declara `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` y `.VisibleCameraForegroundService` no exportado con `foregroundServiceType="camera"`.
- `VisibleCameraForegroundServicePlanner` permite start solo si la Activity está visible, `CAMERA` está concedido y la selección actual sigue siendo `DirectOpenCandidate`; los bloqueos devuelven mensajes en español.
- La notificación dice prueba local visible, video codificado descartado en memoria, no transmite y no graba; no hace claim de pantalla bloqueada, USB, Wi‑Fi, red, storage, audio ni protocolo.
- El servicio expone acciones explícitas START/STOP, devuelve `START_NOT_STICKY`, y STOP llama `stopForeground(true)`/`stopSelf()`.
- `UsbProbeActivity` inicia el shell solo después de que el pipeline local llega a `Running` y el plan fresco visible+permiso+selección lo permite; sigue deteniendo pipeline y shell en stop de usuario, `onStop` y `onDestroy`.
- T10a no transfiere ownership de la captura al service, no agrega background cold-start ni valida pantalla bloqueada.

## Diseño T10 — servicio foreground de cámara visible

T10 mueve la captura local visible a un contrato de foreground service de cámara sin hacer claims de persistencia con pantalla bloqueada ni soporte físico. La activación sigue siendo sólo por acción explícita del usuario con la Activity visible.

Alcance T10:

- Manifest declara permisos `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_CAMERA`, además del `CAMERA` existente.
- Manifest declara un `Service` no exportado con `android:foregroundServiceType="camera"`.
- El arranque del servicio se permite sólo desde flujo visible de la Activity después de re-leer permiso runtime CAMERA y selección directa actual; no hay auto-start por resume, permiso callback, boot, USB ni recreación.
- El servicio debe publicar notificación foreground con texto honesto: prueba local visible; video codificado descartado en memoria; no transmite ni graba.
- Stop de usuario y `onStop` de Activity deben detener la captura local y/o pedir al servicio que se detenga; la notificación también debe ofrecer detener.
- Debido a restricciones de permiso while-in-use, T10 no promete comenzar cámara desde background/lock; si la app ya no está visible antes de start foreground, el flujo debe fallar seguro.
- Tests RED iniciales: permisos/manifest/type, start planner sólo visible+permiso+selección directa, bloqueo de start no visible, stop action detiene servicio/controlador, texto de notificación sin claims de transmisión/grabación/pantalla bloqueada.
- Sin Wi‑Fi/USB/network/storage/audio/wire protocol, pruebas físicas ni claims Samsung/Windows/macOS.

### Revisión nativa RDD T10a

- Candidato: `6976832` contra base `22bc71a`.
- Lineage: `review-b6bff246b4c2347c`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-foreground-start-failure-unhandled`, `R3-notification-stop-does-not-stop-pipeline`. No abrieron corrección para T10a. `R3-notification-stop-does-not-stop-pipeline` confirma que T10a es solo shell; T10b debe transferir ownership real al service antes de claim de pantalla bloqueada.


## Diseño T10b — ownership del pipeline en foreground service

T10b existe porque T10a es sólo shell: mientras `UsbProbeActivity.onStop` detenga siempre el pipeline, la continuidad bajo pantalla bloqueada no puede funcionar. T10b debe mover la propiedad real del pipeline cámara→encoder a un `Service` no exportado, manteniendo el arranque seguro y visible.

Alcance T10b:

- La Activity sólo autoriza el inicio mientras está visible, con permiso runtime `CAMERA` fresco y selección actual `DirectOpenCandidate`; no hay start por background, boot, USB, permiso callback, resume automático ni recreación.
- El `Service` posee y cierra `CameraEncoderPipeline`/handle, drain loop y métricas de sesión; no retiene referencia a Activity ni vistas.
- Si `onStop`/lock ocurre después de un inicio válido y el service ya posee el pipeline, la Activity no debe detener la captura por lifecycle; sólo debe desasociarse de la UI.
- Stop explícito de usuario y acción de notificación detienen el pipeline/service; revocación de permiso o destrucción del service cierran recursos.
- El arranque pendiente debe ser cancelable por stop/notificación/destrucción y debe cerrar handles tardíos, siguiendo la disciplina T8d.
- El service devuelve `START_NOT_STICKY` y nunca cold-starts cámara en background tras kill/recreate.
- Si el cambio es demasiado grande, partir en T10b adapter/orquestador service con fakes y T10c binding Activity; no mezclar transportes.
- Tests RED esperados: service no retiene Activity; inicio bloqueado si no visible/no permiso/no selección directa; Activity `onStop` no llama `controller.stopForLifecycle` cuando el ownership ya fue transferido; notificación STOP detiene pipeline; startup pendiente cancelado cierra recursos tardíos; `START_NOT_STICKY` sin auto-restart.
- Sin USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims Samsung/Windows/macOS/pantalla bloqueada hasta M9.

## Verificación T10b

T10b transfiere el ownership del pipeline local cámara→encoder desde `UsbProbeActivity` hacia `VisibleCameraForegroundService` no exportado. La Activity ahora lanza directamente el FGS con `selectedCameraId` y marcador de arranque visible, sin arrancar antes un pipeline propio; el service revalida permiso `CAMERA` y snapshot actual antes de abrir, posee el controller/drain loop y detiene por STOP/destrucción. Si el ownership de service está solicitado/activo, `onStop`/`onDestroy` de la Activity solo desacoplan UI y no llaman `stopForLifecycle`.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `VisibleCameraForegroundServiceCommandPolicy`, `VisibleCameraServiceStartDecision`, `VisibleCameraForegroundServicePipelineOwner`, `VisibleCameraServiceDrainLoop`, `VisibleCameraServiceCommandOutcome`, `VisibleCameraServiceStartRequest`, `VisibleCameraActivityLifecyclePolicy`, `VisibleCameraActivityLifecycleAction` y `VisibleCameraServicePipeline` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T10b

- El start intent del service lleva `selectedCameraId` y marcador explícito `visibleStartRequested`; no hay cold-start desde intent nulo/vacío.
- El planner/command policy bloquea selección nula, vacía, stale o physical-only, y reusa el mensaje seguro existente en español.
- `VisibleCameraForegroundServicePipelineOwner` no requiere referencia a Activity; envuelve el pipeline y drain loop propios del service.
- La Activity muestra estado honesto de servicio solicitado/activo en español y delega el stop explícito al service.
- No se agrega USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims de compatibilidad/pantalla bloqueada.

### Revisión nativa RDD T10b

- Candidato: `bdceec7` contra base `ea3394c`.
- Lineage: `review-f79a78cb39273bd6`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-activity-recreation-ownership`, `R3-drain-stop-race`. No abrieron corrección para T10b; quedan para T10c/hardening de binding UI y carreras finas de drain.

## Diseño T10c1 — corregir deadlock de arranque FGS/cámara

T10c1 corrige un blocker funcional detectado por readback estructural en T10b: `VisibleCameraForegroundService.onStartCommand` corre en main thread y llama sincrónicamente a `pipelineOwner.handleStartCommand`; el pipeline usa `AndroidVisibleCameraPipelineLauncher` con `waitForOpen`/`waitForCapture`, mientras los callbacks Camera2 se enrutan al `mainHandler`. En dispositivo real, el main thread queda bloqueado esperando callbacks que no pueden ejecutarse, por lo que la captura puede timeoutear siempre o acercarse a ANR. T10c1 debe eliminar esa posibilidad antes de M2/M3.

Alcance T10c1:

- `onStartCommand(ACTION_START)` debe llamar `startForeground` pronto en main cuando el intent visible explícito es estructuralmente válido, pero no debe abrir cámara ni esperar captura en main thread.
- La apertura/configuración de cámara debe ejecutarse fuera del main thread.
- Los callbacks Camera2 usados por el pipeline del service deben usar un `HandlerThread`/handler independiente del main looper, no `Handler(Looper.getMainLooper())`.
- STOP/destrucción durante arranque pendiente debe cancelar por generación, volver pronto y cerrar recursos tardíos; no debe quedar bloqueado por locks largos.
- Si `startForeground` lanza `SecurityException` o `ForegroundServiceStartNotAllowedException` por token visible stale/política Android, el service debe fallar seguro: no abrir cámara, detenerse y no reclamar soporte.
- Tests RED: comando start no invoca pipeline start sincrónicamente en main; stop puede ejecutarse mientras start background está bloqueado; callbacks no usan main handler en service factory; foreground exception evita abrir cámara.
- Sin USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims hardware/pantalla bloqueada.

## Verificación T10c1

T10c1 corrige el arranque del foreground service para que `onStartCommand` publique foreground de forma temprana pero no ejecute apertura/configuración Camera2 en el hilo principal, y para que los callbacks Camera2 del service usen un hilo dedicado.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `VisibleCameraForegroundServiceCommandRunner`, `VisibleCameraForegroundServiceCallbackThreadSpec` y `VisibleCameraForegroundStarter` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T10c1

- `VisibleCameraForegroundServiceCommandRunner` publica foreground para un start visible estructuralmente válido y agenda el start real en un executor; si `startForeground` lanza `SecurityException` o `ForegroundServiceStartNotAllowedException`, detiene el servicio y no agenda apertura.
- `VisibleCameraForegroundServicePipelineOwner` ya no mantiene un lock durante `pipeline.start`; STOP/destrucción incrementan generación, vuelven pronto durante start bloqueado y fuerzan `pipeline.stop` si un resultado tardío llega después de cancelación.
- El service crea `AndroidVisibleCameraPipelineLauncher` con `HandlerThread` dedicado (`visible-camera-service-camera-callbacks`) en lugar del handler del main looper.
- No se agregan USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims de hardware/pantalla bloqueada.

### Revisión nativa RDD T10c1

- Candidato inicial: `4f45ed1` contra base `dd20814`.
- Lineage: `review-632e3038d3c2a865`.
- Corrección requerida: `R3-stop-before-start-race` detectó que STOP/destrucción podía ocurrir mientras el worker aún obtenía snapshot antes de registrar generación, y luego abrir cámara tarde.
- Corrección local: `d0ffcf2` registra generación antes de obtener snapshot y agrega test `stopBeforeSnapshotCompletesCancelsStartBeforeCameraOpen`.
- Resultado: validación dirigida aprobada y reconocida mediante `acknowledge-approved`; la autoridad quedó consumida.
- Advisory no bloqueante final: `R3-null-start-not-stopped`.

## Diseño T10c2 — drain loop con generación propia

T10c2 corrige la carrera remanente de `ThreadedVisibleCameraServiceDrainLoop`: el loop actual usa un único booleano `active` compartido y un thread anónimo; un Stop seguido de Start rápido puede permitir que el loop viejo observe `active=true` de la nueva sesión y drene contra el pipeline nuevo, dejando dos loops vivos o tocando una sesión que no posee.

Alcance T10c2:

- Cada `start` de drain debe crear un job/token/generación inmutable propio.
- `stop` invalida la generación actual y, si existe, interrumpe/solicita parada del thread de esa generación.
- Un loop viejo debe salir si su token ya no es el actual antes de drenar, después de dormir/interrupción, y ante resultado no `Running`.
- Un Start rápido después de Stop no debe permitir que el loop viejo drene la nueva sesión ni reviva usando estado compartido.
- Fallos runtime de `drainOnce` deben terminar sólo el job dueño y no filtrar threads.
- Tests RED: stop+restart no produce doble drain desde loop viejo; interrupción/stop sale; excepción de drain no deja loop vivo.
- Mantener el arranque FGS no bloqueante de T10c1 y no mezclar Activity recreation/binding en este lote si excede tamaño reviewable.
- Sin USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims hardware/pantalla bloqueada.

## Verificación T10c2

T10c2 reemplaza el drain loop basado en un booleano compartido por un job/generación propio por arranque. Stop invalida la generación actual e interrumpe el hilo dueño; loops stale salen antes de drenar de nuevo, después de dormir/interrupción y al terminar por estado no `Running` o excepción.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: falló en:
  - `VisibleCameraForegroundServiceDrainLoopTest.runtimeExceptionTerminatesOnlyThatGenerationAndAllowsLaterStart` porque una excepción dejaba `active=true` y bloqueaba un start posterior.
  - `VisibleCameraForegroundServiceDrainLoopTest.stopThenFastRestartDoesNotAllowOldGenerationToDrainRestartedSession` porque el loop viejo podía despertar después del restart y drenar la nueva generación.
  - `VisibleCameraForegroundServiceDrainLoopTest.stopInterruptsSleepingGenerationSoItExitsPromptly` porque `stop` no interrumpía el sleep del loop.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.

### Límites y decisiones T10c2

- Cada `start` aceptado crea un `DrainJob` inmutable con generación, pipeline y thread nombrado; un start repetido mientras el job actual sigue activo se ignora.
- `stop` invalida el job actual bajo lock e interrumpe sólo el thread de esa generación.
- El loop comprueba que su job sigue siendo actual antes de drenar, después de cada drain `Running`, después de dormir/interrupción y en salida; una excepción de `drainOnce` termina sólo ese job y permite un start posterior.
- No se cambia binding/recreación de Activity, USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims de hardware/pantalla bloqueada.

## Diseño T10c3 — binding de Activity a service-owned state

T10c3 queda separado si T10c2 ya ocupa el lote: una Activity recreada debe consultar estado real del service/owner mediante API tipada de sólo lectura o binder/status process-local, mostrar Stop usable si el service está `Starting`/`Running`, y un Start repetido no debe abrir una segunda cámara. No confiar en booleanos guardados de Activity. No declarar M2 completo hasta cubrirlo.

### Revisión nativa RDD T10c2

- Candidato: `08c413f` contra base `d594adf`.
- Lineage: `review-8a93c76b34342afd`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes: `R3-generation-tracker-race`, `R3-repeated-start-false-pass`, `R3-test-thread-cleanup`. No abrieron corrección para T10c2; quedan como hardening de tests/observabilidad si se toca de nuevo el drain loop.

## Diseño T10c3 — Activity recreada se vincula al estado real del service

T10c3 corrige la brecha de ownership UI después de T10b/T10c: una Activity recreada no debe confiar en un booleano propio perdido ni mostrar la cámara como detenida si el foreground service sigue `Starting`/`Running`; tampoco debe permitir Start repetido que abra una segunda cámara.

Alcance T10c3:

- Agregar API tipada y de sólo lectura para estado del service-owned pipeline en el proceso actual (por ejemplo store process-local del service/owner o binder/status simple), sin retener Activity.
- La Activity al renderizar/volver a `onResume` debe consultar el estado real del service y mostrar acción Stop usable si el service está `Starting` o `Running`.
- Start repetido desde Activity recreada mientras el service ya está `Starting`/`Running` debe ser tratado como estado existente, no como segundo start/cámara.
- Stop explícito desde Activity recreada debe detener el service/pipeline y limpiar el estado process-local.
- No confiar en `savedInstanceState` ni en booleanos locales como fuente de verdad de ownership.
- Tests RED: estado process-local Running/Starting produce UI Stop después de recreación; Start repetido no invoca nuevo start si service ya activo; Stop recreado envía stop y estado vuelve a Stopped; no referencia Activity guardada.
- Sin USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims hardware/pantalla bloqueada.

## Verificación T10c3

T10c3 vincula la UI recreada al estado real process-local del foreground service, en lugar de depender de un booleano local perdido por recreación. La Activity consulta el status store al renderizar/onResume, muestra Stop cuando el service-owned pipeline está `Starting`/`Running`, y el Stop explícito desde una Activity recreada detiene el service y limpia el estado local.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `VisibleCameraServiceActivityBindingPolicy`, `VisibleCameraServiceStatus`, `VisibleCameraServiceState`, `VisibleCameraServiceStatusStore` y `VisibleCameraServiceActivityStarter` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.

### Límites y decisiones T10c3

- `VisibleCameraServiceStatusStore` expone un snapshot tipado process-local sin retener Activity: `Idle`, `Starting`, `Running`, `Stopping`, `Stopped` y `Error`, con `selectedCameraId`/mensaje opcionales.
- `VisibleCameraForegroundService`/owner publican `Starting`, `Running`, `Stopping`, `Stopped` y errores/bloqueos al aceptar start, arrancar pipeline, detener, destruir o rechazar start.
- `UsbProbeActivity.renderLocalCameraPipeline` consulta el store real, no sólo `localCameraServiceOwnershipRequested`; una Activity recreada ve `Starting`/`Running` y muestra Stop habilitado.
- La acción principal sobre `Starting`/`Running` se mapea a Stop, por lo que un Start repetido desde una Activity recreada no manda otro start/open.
- Stop explícito limpia el status process-local a `Stopped` y pide detener el service/pipeline.
- No se agregan USB/Wi‑Fi/network/storage/audio/wire protocol, pruebas físicas ni claims hardware/pantalla bloqueada.

### Revisión nativa RDD T10c3

- Candidato: `94a87b2` contra base `24b22ca`.
- Lineage: `review-d1a8af462b5cd580`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes: `R3-error-status-not-rendered`, `R3-stale-service-status`, `R3-stop-test-manufactures-result`. No abrieron corrección para T10c3; quedan para hardening de estado/error si se sigue puliendo la UI del service.
