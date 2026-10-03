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

1. [ ] f1 — Tests robustos: loopback sin cuelgue ante falla y cota inferior del timeout de accept; tests de caminos de falla del duplex en memoria (EOF del par, timeout de lectura). ~200 líneas.
2. [ ] f2 — `arrival_fps` por chunk completo. RED: `arrival_fps_counts_reassembled_chunks_not_fragments`. ~150 líneas.
3. [ ] f3 — Lints de clippy en `trusted_phone_store.rs` y en archivos de tests tocados. ~50 líneas.

## Progreso

Plan creado el 2026-10-03.
