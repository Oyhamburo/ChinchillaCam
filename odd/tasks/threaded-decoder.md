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

1. [x] h1 — `ThreadedVideoDecoder` y `ChannelDecodedFrameSink` genéricos, probados con `FakeVideoDecoder`. RED: `enqueue_does_not_block_on_slow_decoder`, `full_queue_reports_backpressure`, `inner_failure_is_reported_on_next_chunk`, `frames_reach_channel_consumer_in_order`, `drop_joins_worker_within_bound`. ~350 líneas.
   - Evidencia: nuevo `src/threaded_video_decoder.rs` (exportado en `lib.rs`, todas las plataformas) y `tests/threaded_video_decoder_test.rs`. RED observado: `error[E0432]: unresolved imports usb_probe::ChannelDecodedFrameSink, usb_probe::ThreadedVideoDecoder, usb_probe::ThreadedVideoDecoderConfig, usb_probe::ThreadedVideoDecoderError`. GREEN: 8/8 en 5 corridas focalizadas (5 RED + triangulación `inner_backpressure_is_counted_and_decoding_continues`, `factory_panic_is_reported_as_failure`, `config_rejects_zero_capacity_and_zero_join_timeout`). Suite completa: 273 tests, 0 fallos; `cargo fmt --check` limpio; clippy sin avisos en archivos tocados.
   - Desvíos: `ThreadedVideoDecoder` no es genérico en su tipo (el `D` sólo aparece en `spawn`), así el pipeline no arrastra el tipo del decoder interno. `ChannelDecodedFrameSink::bounded` devuelve el `Receiver<DecodedVideoFrame>` de `std`. Un `Backpressure` del decoder interno descarta sólo ese chunk y se cuenta en `dropped_decodes`; ojo: `FakeVideoDecoder` convierte cualquier error del sink (incluido `Backpressure`) en `Failure`, por lo que con el fake una cola de salida llena es fallo fijo (VideoToolbox sí propaga `Backpressure`). Si el trabajador sigue trabado al vencer `join_timeout`, el hilo se suelta (detach) y termina solo. Tamaño: ~530 líneas (por encima de la meta de ~350, por las pruebas de triangulación y la documentación).
2. [ ] h2 — Integración con VideoToolbox en el pipeline: el step que recibe `CodecConfig` ya no espera la creación de la sesión. RED: `config_step_does_not_wait_for_session_creation` (sobre loopback, con el fixture real). ~250 líneas.

Criterios: suite completa verde sin regresiones; build `--offline`; sin dependencias nuevas.

## Progreso

Plan creado el 2026-10-01.

h1 implementada (sin commit): decoder en hilo propio y sink por canal, 273/0.

Nota del orquestador en la lectura de h1 (2026-10-01): `ThreadedVideoDecoder` no es genérico en `D` (sólo `spawn` lo es); se acepta porque oculta el tipo del decoder interno al pipeline. Seguimiento: si el trabajador descarta un chunk `Key` por backpressure de la salida, los `Delta` siguientes llegan sin referencia; conviene aplicar dentro del trabajador la misma regla de descarte hasta el próximo keyframe. Tamaño ~534 líneas (declarado: tres tests extra).
