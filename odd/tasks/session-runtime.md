# Runtime de sesión (loop de keepalive y lectura)

## 1. Objetivo

Que una sesión autenticada (después de `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT`) quede viva en ambos lados: enviar frames salientes con una secuencia coherente, enviar `KEEPALIVE` cuando no hubo tráfico saliente durante un intervalo, leer frames entrantes, detectar par muerto en tiempo acotado y cerrar limpio con una causa tipada.

## 2. Problema

`session-liveness` dejó listos la lectura en dos fases, el frame `KEEPALIVE` y el tracker puro, pero ningún lado tiene un loop que los use (§4.6 de ese doc). Además:

- La secuencia no sobrevive al handshake: Android no devuelve `sessionId` en `Reconnected` y el desktop descarta `session_id`/secuencia en `AuthenticatedPhoneSession`.
- El receptor de video del desktop exige secuencia +1 exacta entre frames de video; con keepalives intercalados en la misma secuencia de envelope, la sesión se cortaría.
- Desktop: en la fase de espera de `read_session_frame_with_budgets`, un primer byte que llega después del deadline se consume y se reporta `PeerIdle`, desincronizando el stream bajo polling.
- Android: `SslEngineUsbTlsEstablishedChannel` no serializa escrituras; el camino de lectura puede hacer `wrap` (KeyUpdate, close_notify) concurrente con un write de aplicación.

## 3. Decisión

Continuación del plan maestro (`complete-webcam-product.md`, "Próxima unidad autorizada", actualización 2026-09-30, candidato (a)). Decisiones técnicas del orquestador en §4; sin decisión de producto nueva. El comportamiento ante saturación mantiene el contrato fail-closed vigente de los sinks de video.

## 4. Contrato compartido Android ↔ desktop

1. Secuencia: un contador por dirección, +1 estricto, compartido por todos los tipos de frame de la sesión (video, metadata, métricas, comandos, keepalive). Teléfono→desktop: `HELLO` usa la secuencia inicial `h` y el siguiente frame del teléfono es `h + 1`. Desktop→teléfono: `ACCEPT` usa `h + 1` y el siguiente frame del desktop es `h + 2`. Mismo `sessionId` en toda la sesión. Cada runtime valida +1 estricto y `sessionId` en todo frame entrante; discrepancia → error de protocolo y cierre. Agotar el contador cierra la sesión.
2. El receptor de video del desktop pasa de exigir +1 exacto entre frames de video a exigir secuencia estrictamente creciente con el mismo `sessionId`; el +1 global lo garantiza el runtime.
3. Keepalive y par muerto: intervalo 2 s y umbral 6 s (defaults de `session-liveness`); el tracker se inicializa al arrancar el runtime (cuenta el handshake como último tráfico). Cualquier frame entrante válido cuenta como señal de vida. Par muerto → cierre con causa `PeerDead`.
4. Despacho entrante: `KEEPALIVE` → sólo vitalidad; video → receptor (desktop); `CAMERA_CONTROL_COMMAND` → callback (teléfono); métricas/metadata → registro; `HANDSHAKE_*` u otro tipo inesperado tras el arranque → error de protocolo y cierre.
5. Saliente: cola acotada; saturación → `BackpressureExceeded` y fin de sesión (fail-closed vigente). Ninguna escritura TLS ocurre en el hilo del llamador de video/cámara (Android) ni bajo un monitor de sink.
6. Causas de fin tipadas: `PeerDead`, `ProtocolViolation`, `ReadFailed`, `WriteFailed`, `Backpressure`, `LocalClose`. `close` es idempotente y envía close_notify best-effort.
7. Modelo de ejecución: desktop, loop de un hilo por pasos (`step(now)`) con slice de lectura corto; Android, un hilo lector y un hilo escritor con escritura serializada en el canal.
8. Fuera de alcance: runtime sobre USB en el desktop (`UsbTlsCiphertextStream` se envenena ante timeout; seguimiento aparte), sesiones iniciadas por pairing sin `HELLO`, cableado con cámara/UI real, consumidor real de comandos de cámara y la prueba cruzada entre worktrees (pospuesta por el usuario).

## 5. Riesgos

- Cambio de la regla de secuencia del receptor del desktop: se ajustan los tests existentes de binding y se declara.
- Concurrencia en Android (dos hilos sobre un `SSLEngine`): se serializa la escritura en el canal antes de construir el runtime y se prueba con tráfico simultáneo.
- Tiempos: tests con reloj inyectado donde se pueda y cotas holgadas donde haya I/O real (lección de `session-liveness`).
- Una escritura bloqueada (par que no lee) retrasa la detección hasta el timeout de escritura del transporte; aceptable y documentado.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits ≤400 líneas como heurística. Revisión nativa RDD por commit o slice (pendiente mientras siga el incidente del facade del 2026-09-30). Ruta: writer delegado acotado por tarea. Push sólo por decisión explícita del usuario; sin PR ni merge.

## 7. Tareas (desktop)

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 231 tests, 0 fallos (HEAD `cbd1c70`).

1. [x] s1 — Prerrequisitos: corregir la carrera de la fase de espera (un byte recibido nunca se descarta como `PeerIdle`), `DesktopVideoSessionReceiver::receive_session_frame` (el camino `BulkFrame` delega sin cambio), regla de secuencia estrictamente creciente en el receptor, y `AuthenticatedPhoneSession` conserva `session_id` y las próximas secuencias. RED: `first_byte_after_idle_deadline_is_not_lost`, `receiver_accepts_session_frames_directly`, `receiver_accepts_sequence_gaps_from_interleaved_keepalives`, `reconnect_session_keeps_session_identity`. ~300 líneas.
2. [x] s2 — `SessionRuntime::step(now)`: lectura con slice corto, validación de secuencia/`session_id`, despacho a receptor/métricas, keepalive y par muerto por tracker, `send_command`, `shutdown` con close_notify, causas de fin tipadas. RED: `video_frames_reach_sink_through_runtime`, `sends_keepalive_after_silence`, `detects_dead_peer`, `rejects_out_of_order_sequence`, `handshake_frame_after_start_is_protocol_violation`. ~400 líneas.
3. [x] s3 — Runtime de punta a punta sobre `LoopbackLanListener`: reconexión y luego sesión viva con keepalives y video sobre TCP loopback real. RED: `runtime_keeps_loopback_session_alive_with_keepalives`. ~200 líneas.

Criterios: `cargo fmt -- --check` y `cargo test --offline` verdes sin regresiones; ningún bind fuera de loopback.

## Progreso

Plan creado el 2026-10-01. s1 verde (236 tests). s2 verde el 2026-10-01: `SessionRuntime::step`
con lectura por slice, validación de secuencia/`session_id`, despacho §4.4, keepalive y par
muerto por tracker, `send_command`, `shutdown` y causas de fin tipadas; `cargo test --offline`
241 pasados, 0 fallos. s3 verde el 2026-10-01 (prueba de caracterización, sin RED fingido):
runtime de punta a punta sobre `LoopbackLanListener` con TCP loopback real — reconexión
HELLO/ACCEPT, sesión viva con keepalives en ambas direcciones y video al sink sobre 3×
`dead_threshold`, cierre limpio con `LocalClose`/close_notify, más el desconectado del par;
sin cambios en `src/` (sin defecto de producción). `cargo test --offline` 243 pasados, 0
fallos. Lado desktop de `session-runtime` completo (s1–s3).

### Evidencia s1 (2026-10-01)

TDD estricto, RED observado antes de cada implementación:

1. **Carrera de la fase de espera** (`src/tls_session_frame.rs`). Se agregó
   `read_first_byte_before_deadline`: intenta al menos una lectura (un `idle_budget` cero
   igual poliza una vez) y sólo decide `PeerIdle` cuando una lectura no trae byte después del
   deadline; un byte real devuelto nunca se descarta, aunque el deadline ya haya pasado.
   - RED: `first_byte_after_idle_deadline_is_not_lost` → `Err(PeerIdle)` en vez del frame.
   - GREEN: pasa; `zero_idle_budget_still_attempts_one_read` documenta y prueba la decisión
     de que un `idle_budget` cero igual intenta una lectura (no se rechaza con error tipado).
   - TRIANGULATE: siguen verdes `idle_beyond_threshold_fails_as_peer_idle`,
     `stalled_frame_after_first_byte_times_out`, `idle_session_does_not_time_out_waiting_for_next_frame`.
2. **`receive_session_frame`** (`src/desktop_receiver.rs`). `receive`/`receive_open` se
   dividió: `receive` → `receive_bulk_open` (chequeo de `stream_id` + límite de decodificación
   de 64 KiB) → `receive_session_frame_open`; `receive_session_frame` entra directo al mismo
   paso post-decodificación. El `stream_id` del chunk pasa a ser la constante
   `USB_SESSION_FRAME_STREAM_ID` (equivalente, porque el camino `BulkFrame` ya exigía ese
   valor), así el comportamiento del camino `BulkFrame` no cambia.
   - RED: `receiver_accepts_session_frames_directly` → no compila (método ausente).
   - GREEN: pasa.
3. **Secuencia estrictamente creciente** (`validate_and_advance_binding`). El binding guarda
   `last_sequence` (antes `next_sequence`); el primer frame fija la línea base y cada frame
   posterior exige `sequence > last_sequence`, mismo `session_id`. Igual o decreciente se
   sigue rechazando.
   - RED: `receiver_accepts_sequence_gaps_from_interleaved_keepalives` y el test de gap
     renombrado → fallaban con la regla +1 exacta.
   - GREEN: ambos pasan.
4. **Identidad de sesión en `AuthenticatedPhoneSession`** (`src/phone_connection.rs`). Nuevo
   `SessionIdentity { session_id, next_inbound_sequence, next_outbound_sequence }` y campo
   `session: Option<SessionIdentity>`. Reconexión: `Some` con `next_inbound = h + 1`,
   `next_outbound = h + 2` (aritmética chequeada; desborde → `PhoneConnectionError::SessionSequenceOverflow`,
   cierra antes de responder). Camino `confirm` de pairing (sin HELLO): `None` documentado —
   las sesiones de runtime requieren el camino de reconexión (§8 marca pairing sin HELLO
   fuera de alcance).
   - RED: `reconnect_session_keeps_session_identity` → no compilaba (`SessionIdentity` y
     campo `session` ausentes).
   - GREEN: pasa; `reconnect_mode_accepts_trusted_phone_hello` sigue verde (ACCEPT usa
     `h + 1`).

**Tests existentes cambiados:** `desktop_receiver_session_gap_sequence_closes_without_sink_push`
se renombró a `desktop_receiver_session_sequence_gap_is_accepted_decreasing_closes` y ahora
afirma que un gap se acepta (creciente, mismo `session_id`) y que sólo una secuencia
decreciente/igual cierra la sesión; antes afirmaba el rechazo de gap +1 exacto, que el
contrato §4.2 eliminó. El resto de los tests de binding (`replay_same_sequence`,
`mixed_session_id`, los de secuencia máxima) siguen válidos sin cambio.

**Verificación:** `cargo fmt -- --check` verde; `cargo test --offline` 236 pasados, 0
fallos (baseline 231 + 5 nuevos netos, con un test renombrado). El archivo de prueba
sensible al tiempo (`tls_session_frame_test`) corrió 3 veces, verde las tres.

**Nota de tamaño:** el diff quedó en ~426 inserciones / 39 borrados (~465 líneas, por encima
de la cota blanda de ~450); el excedente es sobre todo documentación y tests nuevos, no
lógica. s1 quedó cohesivo y verde.

### Evidencia s2 (2026-10-01)

TDD estricto, RED observado antes de implementar.

Nuevo módulo `src/session_runtime.rs` (exportado desde `lib.rs`):
`SessionRuntime<S: Read + Write, C: VideoFrameKindClassifier, K: EncodedVideoSink>`,
construido desde un `AuthenticatedPhoneSession<S>` cuya identidad de sesión es `Some`
(`None` → `SessionRuntimeError::MissingSessionIdentity`), un
`DesktopVideoSessionReceiver`, un sink y un `SessionRuntimeConfig { poll_slice,
frame_budget, keepalive_interval, dead_threshold }` con defaults (`DEFAULT_POLL_SLICE`
200 ms, `DEFAULT_FRAME_BUDGET` 2 s, más los defaults de `session-liveness`). Se valida
`poll_slice < keepalive_interval` y el par intervalo/umbral lo valida el tracker.

- `step(now)`: lee un frame con `read_session_frame_with_budgets(poll_slice,
  frame_budget)`; `PeerIdle` = sin frame este slice; otro error de lectura →
  `SessionEnd::ReadFailed`. Sobre un frame válido: valida `session_id` y +1 estricto
  contra `next_inbound_sequence` (discrepancia → `SessionEnd::ProtocolViolation`),
  `record_received`, y despacha por §4.4: `Keepalive` → sólo vitalidad; video
  (`VideoChunk`/`VideoChunkV2`/`VideoChunkFragmentV1`) → `receive_session_frame`
  (error del receptor → `ProtocolViolation` con detalle); `MetricsSnapshot`/
  `StreamMetadata` → último snapshot con getters; `HANDSHAKE_*` o
  `CameraControlCommand` entrantes u otro → `ProtocolViolation`. Luego par muerto
  (`is_peer_dead` → `PeerDead`) y keepalive debido (`should_send_keepalive` → escribe
  `KEEPALIVE` con `next_outbound_sequence`, `record_sent`; error de escritura →
  `WriteFailed`). Contadores con incremento chequeado; agotar un contador cierra.
- `send_command(command, arguments, now)` escribe un `CameraControlCommand` sobre el
  contador saliente compartido y lo registra como enviado.
- `shutdown(self)` → `close_best_effort` (lógica compartida de `phone_connection`,
  ahora `pub(crate)`), devuelve `SessionEnd::LocalClose`.
- Tras cualquier `SessionEnd` el runtime queda inservible (bandera `ended` + `tls` en
  `Option` tomado al cerrar); `step`/`send_command` devuelven la misma causa y el stream
  queda cerrado best-effort.
- `SessionEnd`: `PeerDead`, `ProtocolViolation(String)`, `ReadFailed(String)`,
  `WriteFailed(String)`, `Backpressure` (incluido por paridad con el contrato §4.6; el
  desktop escribe síncrono sin cola, así que hoy no lo produce) y `LocalClose`.
- Observabilidad: `frames_received`, `keepalives_received`, `keepalives_sent`,
  `video_frames_delivered`, más `latest_metrics`/`latest_stream_metadata`.

RED/GREEN (nuevo `tests/session_runtime_test.rs`, sobre `InMemoryDuplex` con un par
teléfono rustls en un hilo; decisiones de tiempo con `now` inyectado, cotas de lectura
holgadas):

- RED: `cargo test --test session_runtime_test` no compilaba (importes sin resolver:
  `SessionRuntime`, `SessionRuntimeConfig`, `SessionEnd`, `StepOutcome`).
- GREEN: pasan los 5: `video_frames_reach_sink_through_runtime`,
  `sends_keepalive_after_silence` (el teléfono ve `KEEPALIVE` con secuencia HELLO+2 y el
  `session_id`), `detects_dead_peer` (silencio pasado el umbral; config 100/300/20 ms),
  `rejects_out_of_order_sequence`, `handshake_frame_after_start_is_protocol_violation`.
  El archivo corrió 3 veces, verde las tres.

**Verificación:** `cargo fmt -- --check` verde; `cargo test --offline` 241 pasados, 0
fallos (baseline 236 + 5 nuevos).

**Nota de tamaño:** el diff quedó en ~846 inserciones / 2 borrados, por encima de la cota
blanda (~400) y del umbral de corte (~500). El excedente es casi todo documentación del
módulo y tests nuevos (módulo 438 líneas, test 399), no lógica de dominio; s2 quedó
cohesivo y verde. Se marca como desvío declarado para decisión del orquestador.

### Evidencia s3 (2026-10-01)

Prueba de caracterización honesta: no hubo RED fingido. s3 ejercita `SessionRuntime`
de punta a punta sobre el mismo código genérico ya probado en s2, ahora sobre un socket
TCP loopback real, así que el nuevo archivo de test compiló y pasó en la primera corrida
(no hubo defecto de producción que corregir). Sólo se agregó el archivo
`tests/session_runtime_loopback_test.rs`; ningún cambio en `src/`.

- `runtime_keeps_loopback_session_alive_with_keepalives`:
  `LoopbackLanListener::bind_with_options` con timeout de lectura de socket de 25 ms
  (deliberadamente MÁS corto que `poll_slice`), y `SessionRuntimeConfig { poll_slice 30 ms,
  frame_budget 2000 ms, keepalive 150 ms, dead 600 ms }`; el test valida
  `read_timeout < poll_slice < keepalive_interval < dead_threshold` (las dos primeras
  relaciones también las valida el runtime al construir). El teléfono: `TcpStream::connect`
  real, cliente rustls con certificado de cliente, teléfono de confianza en un store temporal,
  HELLO → ACCEPT vía `accept_phone_reconnect_connection`, y luego mantiene la sesión viva
  enviando `KEEPALIVE` y `VideoChunkV2` con su propia secuencia +1 estricta y el mismo
  `sessionId`, cada 50 ms. El desktop corre `SessionRuntime::step` con el reloj real durante
  3× `dead_threshold` (1800 ms) sin recibir `SessionEnd`, y afirma: keepalives enviados > 0 y
  recibidos > 0, video entregado al sink; luego `shutdown()` devuelve `LocalClose` y el
  teléfono observa el close_notify/EOF (drenando primero los keepalives del desktop). Un
  flag atómico `stopped` sincroniza el cierre para que ninguna escritura del teléfono compita
  con el close_notify del desktop.
- `loopback_peer_disconnect_ends_runtime` (opcional, incluido): el teléfono dropea el
  `StreamOwned` tras el handshake; el runtime termina con `ReadFailed` o `PeerDead` dentro de
  3× `dead_threshold`.

**Interacción timeout/poll-slice (motivo de s3):** un `step` está acotado por el timeout de
lectura del socket, porque `read_session_frame_with_budgets` bloquea dentro de un `Read::read`
hasta ese timeout antes de poder consultar el deadline de `poll_slice` (sólo un retorno
`WouldBlock`/`TimedOut` deja disparar el deadline). Por eso el timeout de socket se elige más
corto que `poll_slice`; si fuera mayor que `dead_threshold`, una sola lectura ociosa podría
agotar todo el presupuesto de vitalidad. El test documenta y ejercita esa interacción real;
no reveló defecto de producción (mapeo de `WouldBlock`/`TimedOut` ya correcto en
`tls_session_frame.rs`), por lo que `src/` quedó sin cambios.

**Verificación:** test enfocado (`--test session_runtime_loopback_test`) corrido 5 veces,
verde las cinco (sensible al tiempo). `cargo fmt -- --check` verde; `cargo test --offline`
243 pasados, 0 fallos (baseline 241 + 2 nuevos). Diff acotado: sólo el archivo de test nuevo.
