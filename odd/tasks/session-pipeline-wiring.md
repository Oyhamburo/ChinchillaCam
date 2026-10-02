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

## 7. Tareas (desktop)

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 243 tests, 0 fallos (HEAD `d78ebeb`).

1. [ ] q1 — Frontera de frames decodificados: `DecodedVideoFrame`, `DecodedFrameSink`, decoder fake; `SessionRuntime` da acceso mutable al sink; errores de sink/decoder con causa propia en vez de `ProtocolViolation`. RED: `fake_decoder_emits_frames_in_pts_order`, `decoder_failure_ends_session_with_decoder_cause`. ~300 líneas.
2. [ ] q2 — Descarte hasta keyframe: ante backpressure se descartan frames delta hasta el próximo `Key`, nunca `CodecConfig`, con contador. RED: `backpressure_drops_until_next_keyframe`, `codec_config_is_never_dropped`, `session_continues_after_saturation`. ~250 líneas.
3. [ ] q3 — Métricas y pipeline: `DesktopMetricsAggregator` con reloj inyectado y `DesktopSessionPipeline` (runtime + receptor + decoder + métricas) con loop y cierre limpio; prueba de punta a punta sobre `LoopbackLanListener`. RED: `aggregator_reports_arrival_and_decoded_fps`, `phone_reported_metrics_are_labelled`, `pipeline_decodes_over_loopback_and_shuts_down`. ~400 líneas.

## Progreso

Plan creado el 2026-10-01.
