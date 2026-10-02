# Decoder en hilo propio

## 1. Objetivo

Que la decodificación (y la creación de la sesión de VideoToolbox) no bloquee el loop de un hilo de `SessionRuntime`/`DesktopSessionPipeline`, para que keepalives y lectura sigan a tiempo con video sostenido.

## 2. Problema

`odd/tasks/videotoolbox-decoder.md` v3 midió que crear la sesión de VideoToolbox al recibir `CodecConfig` tarda 375–710 ms de forma síncrona dentro de `step`, y cada frame decodificado suma tiempo al mismo hilo. Con reenvíos de `CodecConfig` o 720p30 sostenido, el loop puede atrasar keepalives y la detección de par muerto.

## 3. Decisión

Seguimiento técnico de `videotoolbox-decoder` (recomendado por el orquestador y aceptado por el usuario el 2026-10-01). Sin decisión de producto nueva; la política de saturación sigue siendo la del usuario (descartar hasta el próximo keyframe, Engram `decision/chinchillacam-pipeline-wiring-policies`).

## 4. Contrato

1. `ThreadedVideoDecoder<D>` implementa `VideoDecoder` + `DecodedFrameCounter`. El decoder interno `D` se construye dentro del hilo trabajador a partir de una fábrica `FnOnce() -> D + Send`, así `D` no necesita ser `Send` (los objetos de VideoToolbox no lo son).
2. `decode_encoded_video` sólo encola en una cola acotada sin bloquear: cola llena → `VideoDecoderError::Backpressure` (el `KeyframeGatedSink` descarta hasta el próximo keyframe). Nunca bloquea el hilo del runtime más que una operación de cola.
3. Un `Failure` del decoder interno queda registrado y se devuelve en la siguiente llamada a `decode_encoded_video` (fail-closed); a partir de ahí todo chunk devuelve ese mismo error.
4. `frames_emitted` es un contador atómico compartido que actualiza el hilo trabajador.
5. Salida: `ChannelDecodedFrameSink` entrega los frames decodificados por una cola acotada a un consumidor (futura cámara virtual); si el consumidor no lee, la cola llena se reporta como `DecodedFrameSinkError::Backpressure` dentro del trabajador.
6. Cierre: al soltar el decoder se cierra la cola y se hace join del hilo con tiempo acotado; nunca se queda colgado.
7. Fuera de alcance: cámara virtual, Windows, IOSurface sin copia, varios decoders en paralelo.

## 5. Riesgos

- Orden: una cola FIFO con un único trabajador preserva el orden de los chunks.
- Error diferido: el fallo se ve un chunk después; se documenta y se prueba.
- Tiempos en tests: cotas holgadas (lección de `session-liveness`).

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits ≤400 líneas como heurística. Revisión nativa RDD pendiente mientras siga el incidente del facade del 2026-09-30. Writer delegado acotado por tarea con lectura del orquestador. Push autorizado por el usuario.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 265 tests, 0 fallos (HEAD `f1d1c91`).

1. [ ] h1 — `ThreadedVideoDecoder` y `ChannelDecodedFrameSink` genéricos, probados con `FakeVideoDecoder`. RED: `enqueue_does_not_block_on_slow_decoder`, `full_queue_reports_backpressure`, `inner_failure_is_reported_on_next_chunk`, `frames_reach_channel_consumer_in_order`, `drop_joins_worker_within_bound`. ~350 líneas.
2. [ ] h2 — Integración con VideoToolbox en el pipeline: el step que recibe `CodecConfig` ya no espera la creación de la sesión. RED: `config_step_does_not_wait_for_session_creation` (sobre loopback, con el fixture real). ~250 líneas.

Criterios: suite completa verde sin regresiones; build `--offline`; sin dependencias nuevas.

## Progreso

Plan creado el 2026-10-01.
