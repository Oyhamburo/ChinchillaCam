# Vitalidad de la sesión

## 1. Objetivo

Que una sesión autenticada sobre TLS sobreviva a períodos sin tráfico y detecte un par muerto en un tiempo acotado, en ambos lados y con el mismo contrato, y cerrar los tests de seguridad pendientes de la reconexión.

## 2. Problema

`TlsSessionFrameIoAdapter.read()` (Android) y `read_session_frame(stream, deadline)` (desktop) usan un único deadline que cubre tanto la espera del próximo frame como su lectura, así que una sesión ociosa se cierra a los 5 s (hallazgo `R4-idle-deadline-teardown` de `usb-authenticated-session`). `SessionFrame` v1 no tiene frame de keepalive y el tráfico desktop→teléfono es escaso. Además faltan pruebas de que `PinnedDesktopFingerprintTrustManager` rechace un fingerprint distinto, y `UsbTrustedReconnect` no cierra el canal si `ActiveDesktopAuthority.requestActivation` lanza una excepción (fuga real).

## 3. Decisión

Continuación del plan maestro (`complete-webcam-product.md`, "Próxima unidad autorizada", actualización 2026-09-29). Decisiones técnicas del orquestador en §4; sin decisión de producto nueva.

## 4. Contrato compartido Android ↔ desktop

1. Lectura en dos fases: (a) espera del primer byte del próximo frame acotada por el umbral de par muerto; si vence, error tipado de par inactivo (`PeerIdle`) y cierre del canal; (b) una vez recibido el primer byte, el resto del frame (prefijo y payload) se completa dentro de un deadline acotado de 5 s; si vence, error tipado de timeout y cierre.
2. Valores por defecto: intervalo de keepalive 2 s; umbral de par muerto 6 s (tres intervalos). Configurables; el umbral debe ser mayor que el intervalo.
3. Nuevo frame `KEEPALIVE`: tipo 10 de `SessionFrame` v1, payload vacío, usa la secuencia normal del envelope. Ambos codecs se actualizan juntos; un decodificador que no lo conozca lo rechaza (fail closed).
4. Envío simétrico: cada lado envía `KEEPALIVE` cuando no envió ningún frame durante un intervalo; cualquier frame recibido cuenta como señal de vida.
5. Tracker de vitalidad puro y sin hilos en cada lado (`recordSent`, `recordReceived`, `shouldSendKeepalive(now)`, `isPeerDead(now)`), probado con reloj inyectado.
6. Fuera de alcance: el loop de sesión que envía keepalives y lee en paralelo (arquitectura nueva de runtime de sesión, unidad posterior), UI, LAN, hardware y la prueba cruzada entre worktrees (pospuesta por el usuario el 2026-09-29).

## 5. Riesgos

- Sin el loop de envío, una sesión ociosa sigue cerrándose al superar el umbral: es el contrato esperado (los pares deben enviar keepalive) y se completa en la unidad del loop.
- Desalineación de codecs entre lenguajes: se mitiga con el tipo y payload fijos de §4.3 y tests de round trip en ambos lados.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados (incluido un RED obtenido con stubs sobre código ya escrito). Commits locales ≤400 líneas como heurística; sin push, PR ni merge. Revisión nativa RDD por commit o slice. Ruta: writer delegado acotado por tarea. Runtime entre worktrees requiere autorización fresca.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Último límite revisado: `1efd50e`.

1. [x] l1 — Lectura en dos fases: `read_session_frame` con espera acotada por el umbral y deadline de frame (la cota dura de cada lectura bloqueante sigue siendo el presupuesto del transporte). RED: `idle_session_does_not_time_out_waiting_for_next_frame`, `idle_beyond_threshold_fails_as_peer_idle`, `stalled_frame_after_first_byte_times_out`. ~250 líneas.
2. [x] l2 — KEEPALIVE y tracker: tipo 10 en el codec y tracker de vitalidad. RED: `keepalive_frame_round_trips`, `sends_keepalive_after_interval_of_silence`, `declares_dead_peer_after_threshold`, `receiving_any_frame_resets_peer_deadline`. ~250 líneas.

Criterios: iguales al lado Android en lo que aplica; `cargo fmt -- --check` y `cargo test` verdes.

## Evidencia l1/l2

### l1 — lectura en dos fases (commit `6de4c1d`, "fix(desktop): let idle TLS sessions wait for the next frame")

RED (`cargo test --test tls_session_frame_test`, fallo de compilación real antes de tocar `src/`):
```
error[E0432]: unresolved import `usb_probe::read_session_frame_with_budgets`
error[E0599]: no variant, associated function, or constant named `PeerIdle` found for enum `TlsSessionFrameError`
```

GREEN: nueva `read_session_frame_with_budgets(stream, idle_budget, frame_budget)` en `src/tls_session_frame.rs` — fase (a) espera sólo el primer byte del prefijo de longitud acotada por `idle_budget` (falla con el nuevo `TlsSessionFrameError::PeerIdle`); fase (b) completa el resto del frame (resto del prefijo + payload) bajo un deadline `frame_budget` recién calculado desde ese momento (falla con `Timeout`, igual que antes). `read_session_frame` queda sin cambios de comportamiento (un solo deadline cubre espera y lectura) y sigue siendo el usado por `accept_phone_reconnect_connection` (lectura única del HELLO, sin noción de "ocioso entre frames"). Refactor interno para no duplicar el loop de reintento: `read_exact_before_deadline`/`ensure_before_deadline` reciben ahora el error de timeout como parámetro (`Timeout` para `read_session_frame`, `PeerIdle` para la fase (a) de la nueva función).

Tests nuevos en `tests/tls_session_frame_test.rs`: `idle_session_does_not_time_out_waiting_for_next_frame`, `idle_beyond_threshold_fails_as_peer_idle`, `stalled_frame_after_first_byte_times_out` (mocks `DelayedRead<R>`/`StallsAfterFirstByte` nuevos; se reutilizó `AlwaysWouldBlockRead` ya existente).

- Focused: `cargo test --offline --test tls_session_frame_test` → 13 passed; 0 failed (0.15s).
- Full: `cargo fmt -- --check` limpio; `cargo test --offline` → 220 passed; 0 failed (baseline 217 + 3).
- Cambios: 3 archivos, 216 inserciones(+), 10 eliminaciones(-).

### l2 — KEEPALIVE y tracker de vitalidad (commit `e837059`, "feat(desktop): add session keepalive frames and liveness tracker")

RED:
- Codec (`cargo test --test session_frame_test`): `error[E0599]: no variant, associated function, or constant named `Keepalive` found for enum `SessionFramePayload``.
- Tracker (`cargo test --test session_liveness_test`, re-obtenido tras la nota de proceso de abajo): `error[E0432]: unresolved import `usb_probe::SessionLivenessTracker``.

GREEN: `SessionFramePayload::Keepalive` (tipo 10, payload siempre vacío) agregado al sizer/encoder/decoder de `src/session_frame.rs`. Se encontraron y corrigieron dos copias exhaustivas adicionales, preexistentes, de la tabla payload→type-id fuera de ese archivo (`desktop_receiver.rs::session_payload_type_id`, `video_fragment_reassembler.rs::payload_type_id`; `SessionFramePayload::type_id()` no es `pub`, de ahí la duplicación ya existente) que de otro modo no hubieran compilado con la variante nueva. Módulo nuevo `src/session_liveness.rs`: `SessionLivenessTracker::new(now, interval, dead_threshold) -> Result<Self, SessionLivenessError>` (falla si `dead_threshold <= interval`), `record_sent`/`record_received`/`should_send_keepalive`/`is_peer_dead`; reloj inyectado en cada método, sin hilos ni `Instant::now()` interno. Constantes `DEFAULT_KEEPALIVE_INTERVAL` (2 s) y `DEFAULT_DEAD_THRESHOLD` (6 s) exportadas.

Tests nuevos: golden `keepalive_frame_round_trips` en `tests/session_frame_test.rs`; `sends_keepalive_after_interval_of_silence`, `declares_dead_peer_after_threshold`, `receiving_any_frame_resets_peer_deadline` y `dead_threshold_must_exceed_interval` (no listada en el contrato; ver desvíos) en `tests/session_liveness_test.rs` (archivo nuevo).

- Focused: `cargo test --offline --test session_frame_test --test session_liveness_test` → 20 passed + 4 passed; 0 failed.
- Full: `cargo fmt -- --check` limpio; `cargo test --offline` → 225 passed; 0 failed (220 + 1 golden + 4 tracker).
- Cambios: 7 archivos (2 nuevos), 207 inserciones(+), 0 eliminaciones(-).

**Hex del golden KEEPALIVE** (`SessionFrame(1, 7, "s", Keepalive)`, minúsculas, 17 bytes, confirmado contra el encoder real): `43435346010a0000000700017300000000`. Android implementa el mismo vector; la comparación cruzada entre worktrees queda pospuesta (sección 4.6).

### Desvíos declarados

- l2/tracker: `src/session_liveness.rs` se escribió antes de confirmar un RED genuino para `tests/session_liveness_test.rs` (la primera corrida del test ya compiló en verde porque la implementación ya existía). Se corrigió antes de commitear: se comentaron temporalmente `mod session_liveness;` y el `pub use session_liveness::{...}` en `src/lib.rs`, se volvió a correr el test (RED real, `E0432` citado arriba) y se restauraron ambas líneas antes de continuar. Ningún commit quedó con el RED falso.
- Se agregó `dead_threshold_must_exceed_interval`, prueba no listada explícitamente en el contrato de la unidad, para cubrir la validación del constructor (`dead_threshold` debe superar `interval`, sección 4.2) que de otro modo quedaba sin ejercitar.
- Duplicación preexistente de la tabla type-id (ver GREEN de l2 arriba): se agregó únicamente el brazo faltante en las dos copias externas; no se refactorizó esa duplicación ya presente en el repo (fuera de alcance de esta unidad).

## Progreso

Plan creado el 2026-09-29. l1 y l2 completados el mismo día (commits `6de4c1d`, `e837059`); `cargo fmt -- --check` y `cargo test --offline` verdes en ambos (220 y 225 tests respectivamente, 0 fallos). Pendiente para una unidad posterior (fuera de alcance aquí, contrato sección 4.6): el loop de sesión que envía keepalives y lee en paralelo, UI, LAN, hardware y la prueba cruzada de golden bytes entre este worktree y el de Android.
