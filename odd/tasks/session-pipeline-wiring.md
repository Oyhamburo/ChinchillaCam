# Cableado de la sesión al pipeline (dominio)

## 1. Objetivo

Conectar el runtime de sesión con los pipelines existentes a nivel de dominio y con fakes: en el teléfono, el video codificado de cámara→encoder sale por el runtime y el fin de sesión detiene la cámara con un aviso visible; en el desktop, los frames recibidos llegan a un decoder (fake por ahora) con frames decodificados, métricas medidas localmente y descarte hasta el próximo keyframe ante saturación.

## 2. Problema

- Android: el controller del servicio se construye sin `encodedVideoSinkFactory` (el video se descarta en memoria) y los fallos de egreso (`failAndStop`) no llegan a `VisibleCameraServiceStatusStore` ni a `stopService`. No existe composición de producción USB → TLS → sesión.
- Desktop: `VideoDecoder` no tiene tipo de salida ni backend real; `SessionRuntime` sólo expone `sink(&self)`; la saturación del sink se reporta como `ProtocolViolation` y termina la sesión; no hay agregador de métricas.

## 3. Decisión

Decisiones del usuario del 2026-10-01:

1. Fin de sesión en el teléfono: parar cámara/encoder y avisar en el estado visible y la notificación; el usuario vuelve a iniciar. Reconexión automática queda para T19.
2. Saturación en el desktop: descartar frames delta hasta el próximo keyframe, contando los descartes; la sesión sigue. Reemplaza el fail-closed del desktop ante saturación de video.
3. Decoder real: nativo por plataforma (VideoToolbox primero, luego Media Foundation), sin FFmpeg, como unidad aparte.
4. UI de pairing no autorizada todavía: esta unidad no agrega UI nueva ni fuente de sesión de producción.

## 4. Contrato

1. Teléfono: un `SessionEgressBinding` arranca el runtime desde un `Reconnected`, provee la fábrica de sink de video (fragmentador sobre `SessionRuntimeVideoTransport`), y ante `SessionEnd` distinto de `LocalClose` pide la detención del pipeline fuera del hilo del runtime y publica un error tipado por causa. La fábrica de sink no hace E/S.
2. Teléfono: todo fallo del pipeline o del egreso que detiene la cámara publica `Error` en `VisibleCameraServiceStatusStore` y detiene el servicio; los textos "egreso fake" pasan a textos neutros que no afirman transmisión.
3. Desktop: el decoder produce `DecodedVideoFrame` hacia un `DecodedFrameSink`; un fake lo implementa en tests. Un error de decoder o de sink no es `ProtocolViolation`: tiene causa propia.
4. Desktop: ante saturación (cola o decoder con backpressure), se descartan frames hasta el próximo `Key`; los `CodecConfig` nunca se descartan; se cuentan descartes. Un error de decoder que no es backpressure termina la sesión con causa de decoder.
5. Desktop: métricas medidas localmente con reloj inyectado (fps de llegada y decodificado en ventana, descartes, bytes) más los valores reportados por el teléfono, siempre etiquetados como reportados.
6. Fuera de alcance: UI nueva, fuente de sesión de producción (USB real), reconexión automática, decoder nativo, cámara virtual, emisor de métricas del teléfono, red no loopback y prueba cruzada entre worktrees.

## 5. Riesgos

- Interbloqueo entre `onEnd` del runtime y el monitor del controller: el handoff va a otro hilo y se prueba con drenaje concurrente.
- Descarte hasta keyframe sin keyframes periódicos del encoder: la sesión puede quedar sin video hasta el próximo keyframe; se documenta y se mide.
- Un decoder lento en el loop de un hilo del desktop retrasa keepalives: el fake mide y los tests acotan; el decoder real tendrá su propio hilo en su unidad.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits ≤400 líneas como heurística. Revisión nativa RDD por commit o slice (pendiente mientras siga el incidente del facade del 2026-09-30). Writer delegado acotado por tarea con lectura del orquestador. Push por decisión del usuario.

## 7. Tareas (Android)

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 417 tests, 2 omitidos, 0 fallos (HEAD `76fae83`).

1. [x] p1 — Propagación de fallos del pipeline: todo fallo que detiene la cámara publica `Error` en `VisibleCameraServiceStatusStore` y pide detener el servicio; textos neutros sin "egreso fake". RED: `egressFailurePublishesErrorAndStopsService`, `drainLoopExitOnErrorIsVisible`. ~250 líneas.

   Hecho el 2026-10-01. Seam agregado: `VisibleCameraServiceDrainLoop.start(pipeline, onPipelineStopped)` invoca el callback sólo cuando el loop termina por un fallo (drenaje no `Running` o excepción) y nunca ante una parada del usuario; la invocación ocurre fuera de la sección crítica del hilo de drenaje y sin sostener el monitor del controller. El dueño (`VisibleCameraForegroundServicePipelineOwner`) recibe `onPipelineFailureStop`, y al fallar publica `Error` con el detalle del pipeline (`VisibleCameraServicePipeline.currentDetail()`) y pide la parada; el servicio usa un camino de parada que conserva el `Error` (`stopForegroundAndSelfPreservingStatus`, más guardas en `onDestroy`/`handleDestroy`). Una parada normal del usuario sigue terminando en `Stopped`, sin `Error`. Los textos "Egreso fake …" pasaron a textos neutros que no afirman transmisión ni mencionan "fake" (p. ej. "El envío de video se detuvo: …"); el caso sin sink conserva "se descarta en memoria; no se transmite ni se graba". Tests nuevos: `egressFailurePublishesErrorAndStopsService`, `userStopDoesNotPublishError` (owner) y `drainLoopExitOnErrorIsVisible` (drain loop). RED observado como fallo de compilación por el seam ausente; GREEN tras implementar. Verificación: `VisibleCamera*` 3 corridas verdes; suite completa 420 tests, 2 omitidos, 0 fallos (baseline 417/2/0 + 3 tests nuevos); `assembleDebug` y `lintDebug` OK. Sin commit (decisión del usuario).
2. [x] p2 — `SessionEgressBinding`: runtime desde `Reconnected`, fábrica de sink de video, fin de sesión → detención del pipeline fuera del hilo del runtime con error tipado por causa, cierre idempotente. RED: `videoFlowsFromControllerThroughBinding`, `peerDeadStopsPipelineWithVisibleError`, `localCloseDoesNotReportError`, `endDuringConcurrentDrainDoesNotDeadlock`. ~350 líneas.

   Hecho el 2026-10-01. Nuevo `SessionEgressBinding` (archivo nuevo): arranca un `SessionRuntime` desde un `Reconnected` (o desde sus partes explícitas: canal, adapter, `sessionId`, próxima secuencia saliente/entrante), expone `videoSinkFactory: () -> EncodedVideoEgressSink` trivial sin E/S (`FragmentingEncodedVideoEgressSink(EncodedVideoFragmentingSessionFrameSink(SessionRuntimeVideoTransport(runtime)))`), y ante el `onEnd` del runtime hace handoff al `endExecutor` (nunca actúa en el hilo del runtime, riesgo §5): `LocalClose` no reporta nada visible; cualquier otra causa llama `onSessionEndedWithError(cause, mensaje)` con un texto neutro en español por causa (`PeerDead`: "Se perdió la conexión con la computadora."; `Backpressure`/`WriteFailed`/`ReadFailed`/`ProtocolViolation` con mensajes honestos y cortos). `close()` es idempotente (guarda `AtomicBoolean`) y delega en `runtime.close()`, que hace join acotado saltando el hilo actual: seguro de llamar desde el hilo del `endExecutor` y desde un hilo de test; en producción no debe llamarse en el hilo principal (documentado). Composición para el service layer sin fuente de sesión de producción (§4.6, decisión 4): `SessionEgressServicePipelineComposition` expone `encodedVideoSinkFactory` (= `binding.videoSinkFactory`) para `VisibleCameraPipelineController` y un `onSessionEndedWithError` que publica `Error` en `VisibleCameraServiceStatusStore` con el mensaje tipado y pide la parada por el mismo camino de fallo que p1 (`requestPipelineFailureStop`, espejo de `onPipelineFailureStop`); la construcción real del servicio queda sin cambios. Tests nuevos (`SessionEgressBindingTest` sobre `RawStreamTlsTestSupport` + par desktop falso, como en `SessionRuntimeVideoTransportTest`): `videoFlowsFromControllerThroughBinding` (un `VisibleCameraPipelineController` con launcher/handle falsos usando `binding.videoSinkFactory`; el par desktop recibe los chunks de video con secuencia +1 estricta y mismo `sessionId`, y los bytes reensamblan la entrada), `peerDeadStopsPipelineWithVisibleError` (peer en silencio → `onSessionEndedWithError(PeerDead, msg)` invocado en el hilo del `endExecutor`, no en el hilo del runtime; el status store muestra `Error` con ese mensaje y la parada se pidió una vez), `localCloseDoesNotReportError` (y `close()` idempotente), y `endDuringConcurrentDrainDoesNotDeadlock` (fin de sesión mientras el controller drena, con `close()` llamado desde el handoff: completa en tiempo acotado sin interbloqueo). RED observado como fallo de compilación (`Unresolved reference: SessionEgressBinding` / `SessionEgressServicePipelineComposition`); GREEN tras implementar. Los 4 tests enfocados corridos 5× seguidos sin fallos; `VisibleCamera*` en verde. Verificación completa: suite `:android:usb-probe:testDebugUnitTest` 425 tests, 2 omitidos, 0 fallos (baseline 421/2/0 + 4 tests nuevos); `assembleDebug` y `lintDebug` OK; `NoNetworkListenerContractTest` en verde (sin red). Sin commit (lo hace el orquestador).

## Progreso

Plan creado el 2026-10-01.

p1 hecho el 2026-10-01: propagación de fallos del pipeline al estado visible y parada del servicio con `Error` preservado, más textos de egreso neutros. Suite completa 420/2/0 (baseline 417/2/0), `assembleDebug` y `lintDebug` OK. Sin commit.

Corrección del orquestador en la lectura de p1 (2026-10-01): el writer agregó `stopForegroundAndSelfPreservingStatus()` y el parámetro `onPipelineFailureStop` del owner, pero el servicio de producción no lo conectaba; ante un fallo se publicaba `Error` y el servicio quedaba en primer plano. Se conectó `onPipelineFailureStop = { mainHandler.post { stopForegroundAndSelfPreservingStatus() } }` y se fijó con `serviceStopsItselfWhenThePipelineFails` (test de contrato sobre el fuente; RED estructural: la línea estaba ausente al recibir el handoff). Suite 421/2/0 verificada de forma independiente.

p2 hecho el 2026-10-01 (RED→GREEN, TDD estricto): `SessionEgressBinding` (runtime desde `Reconnected`, `videoSinkFactory` trivial sobre `SessionRuntimeVideoTransport`, fin de sesión con handoff al `endExecutor` y error tipado en español, `close()` idempotente sin deadlock) más `SessionEgressServicePipelineComposition` para el service layer sin fuente de sesión de producción (§4.6). Suite completa 425/2/0 (baseline 421/2/0 + 4 tests nuevos); tests enfocados 5× estables; `assembleDebug` y `lintDebug` OK; `NoNetworkListenerContractTest` en verde. Commits del lado Android: `a95235f` plan, `f81af5e` p1, más este commit de p2 (lo hace el orquestador). Revisión nativa pendiente mientras siga el incidente del facade. Seguimientos: la fuente de sesión de producción necesita la decisión de UI de pairing/connect (T25); emisor de `MetricsSnapshot` del teléfono; auto-reconexión (T19); y la prueba de interoperabilidad entre worktrees.

Nota del orquestador en la lectura de p2 (2026-10-01): `SessionEgressServicePipelineComposition` recibe el binding ya creado, mientras que `SessionEgressBinding.start` necesita de antemano el callback `onSessionEndedWithError` de la composición; hoy se resuelve con un holder. Seguimiento para cuando exista la fuente de sesión de producción: construir composición y binding en un solo paso. Tamaño de p2 ~565 líneas (142 de producción; declarado).
