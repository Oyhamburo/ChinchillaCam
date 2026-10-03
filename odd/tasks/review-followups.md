# Seguimientos técnicos de revisiones (desktop)

## 1. Objetivo

Cerrar los hallazgos no bloqueantes de las revisiones nativas y los seguimientos técnicos anotados en features anteriores que no requieren decisiones de producto.

## 2. Problema

- Revisión `review-bf1dda5561b0891f` (T16 d2): `R3-silent-peer-hang-on-failure` (WARNING; `tests/loopback_lan_transport_test.rs` puede colgarse si la aserción falla) y `R3-accept-timeout-lower-bound` (SUGGESTION; falta cota inferior).
- Revisión `review-c049e151e6ec692f` (T16 d1): `R3-duplex-failure-paths-unexercised` (SUGGESTION; caminos de falla del duplex en memoria sin test).
- `odd/tasks/session-pipeline-wiring.md` q3b: `arrival_fps` cuenta fragmentos en vez de frames completos.
- Dos lints de clippy preexistentes en `src/trusted_phone_store.rs` (`manual_is_multiple_of`, `chunks_exact_to_as_chunks`).

## 3. Decisión

Elegido por el orquestador el 2026-10-03 con delegación del usuario ("continuá sin preguntar"). Las revisiones nativas restantes quedan pendientes: el consentimiento venció 4 veces sin respuesta en la UI del host (incluso tras reiniciar Pi) y es una decisión humana que no se automatiza.

## 4. Contrato

1. Ningún test de loopback puede colgarse: todo hilo auxiliar se une con cota o se libera antes de aserciones que pueden fallar.
2. `arrival_fps` mide chunks de video completos (no fragmentos); los fragmentos se cuentan aparte.
3. `cargo clippy --offline --all-targets -- -D warnings` sin advertencias en los archivos tocados y en `trusted_phone_store.rs`.
4. Sin cambios de comportamiento de producción fuera de §4.2.

## 5. Riesgos

- Tests con tiempos: cotas holgadas.

## 6. Reglas de ejecución

TDD estricto donde aplica (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); caracterización declarada para endurecimiento de tests. Revisión nativa pendiente (consentimiento sin respuesta). Push autorizado por el usuario.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 292 tests, 0 fallos (HEAD `0eca383`).

1. [x] f1 — Tests robustos: loopback sin cuelgue ante falla y cota inferior del timeout de accept; tests de caminos de falla del duplex en memoria (EOF del par, timeout de lectura). ~200 líneas.
   - Evidencia: `silent_loopback_peer_fails_bounded` libera el par silencioso por canal (`recv_timeout` con tope `PEER_HOLD_LIMIT` de 10 s; el emisor se descarta también al desenrollar un pánico) y lo une antes de cualquier aserción; las aserciones corren fuera del `thread::scope`. `pairing_then_reconnect_over_loopback_tcp` documenta que su hilo auxiliar ya está acotado por timeouts de socket de 1500 ms. `accept_times_out_without_peer` agrega cota inferior (`elapsed >= 200 ms - 50 ms`). Nuevo `tests/in_memory_duplex_test.rs`: `duplex_read_times_out_without_data`, `duplex_read_returns_eof_after_peer_drop`, `duplex_blocked_read_wakes_with_eof_when_peer_drops`, `duplex_buffered_data_is_read_before_eof`.
   - Caracterización declarada: los tests nuevos y modificados pasaron en la primera ejecución (sin RED). Mutante temporal (pánico tras `accept` en el test del par silencioso) falló en 0,01 s sin colgarse; se revirtió. Sin cambios de producción.
   - Verificación: tests focalizados 5 de 5 ejecuciones en verde (4 + 4); runner completo 296 tests, 0 fallos (292 + 4 nuevos).
2. [x] f2 — `arrival_fps` por chunk completo. RED: `arrival_fps_counts_reassembled_chunks_not_fragments`. ~150 líneas.
   - Diseño mínimo: `DesktopVideoSessionReceiver` cuenta de por vida los chunks enteros que entrega al sink (`chunks_delivered`, `chunk_bytes_delivered`; uno por `VideoChunk`/`VideoChunkV2` y uno por conjunto de fragmentos reensamblado; sólo si el sink devuelve `Ok`; no se reinician en `reset_for_new_session`). `SessionRuntime` los expone (`video_chunks_delivered`, `video_chunk_bytes_delivered`) y agrega `video_fragments_received` (fragmentos aparte). El pipeline alimenta `record_chunk_arrived` por deltas de esos contadores en vez de `received_video` + `last_video_bytes_delivered` (accesor que se conserva, documentado como por frame de cable). `DesktopSessionPipeline::video_fragments_received` expone el contador de fragmentos.
   - Decisión: un arribo es un chunk que el receptor entregó al sink aunque el `KeyframeGatedSink` lo descarte después bajo saturación (ese descarte suma además en `dropped_chunks`). Documentado en el módulo del pipeline.
   - Evidencia TDD: RED observado como fallo de aserción — `arrival_fps_counts_reassembled_chunks_not_fragments` (3 chunks enteros, cada uno en 3 fragmentos de 2 bytes, sobre `LoopbackLanListener`) falló con `total_chunks` `left: 9, right: 3`. GREEN: pasa con `total_chunks` 3, `total_bytes` 18, `arrival_fps` `Some` y `video_fragments_received` 9. Triangulación: `receiver_counts_whole_chunks_delivered_not_fragments` (tipos 5/8/9; un fragmento parcial no cuenta; un chunk rechazado por el sink no cuenta) pasó a la primera (caracterización). Sin cambio de expectativas en tests existentes (`total_chunks` 5 del camino feliz sigue igual).
   - Verificación: test enfocado del pipeline 5 de 5 ejecuciones en verde; `cargo fmt -- --check` limpio; runner completo 298 tests, 0 fallos (296 + 2 nuevos).
3. [ ] f3 — Lints de clippy en `trusted_phone_store.rs` y en archivos de tests tocados. ~50 líneas.

## Progreso

Plan creado el 2026-10-03.

- f1 completada: endurecimiento de tests de loopback y del duplex en memoria, sin cambios de producción; 296 tests, 0 fallos.
- f2 completada: `arrival_fps`/`total_chunks`/`total_bytes` cuentan chunks enteros entregados al sink (contadores del receptor por deltas) y los fragmentos se cuentan aparte (`video_fragments_received`); 298 tests, 0 fallos.
