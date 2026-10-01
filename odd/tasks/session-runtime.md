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

1. [ ] s1 — Prerrequisitos: corregir la carrera de la fase de espera (un byte recibido nunca se descarta como `PeerIdle`), `DesktopVideoSessionReceiver::receive_session_frame` (el camino `BulkFrame` delega sin cambio), regla de secuencia estrictamente creciente en el receptor, y `AuthenticatedPhoneSession` conserva `session_id` y las próximas secuencias. RED: `first_byte_after_idle_deadline_is_not_lost`, `receiver_accepts_session_frames_directly`, `receiver_accepts_sequence_gaps_from_interleaved_keepalives`, `reconnect_session_keeps_session_identity`. ~300 líneas.
2. [ ] s2 — `SessionRuntime::step(now)`: lectura con slice corto, validación de secuencia/`session_id`, despacho a receptor/métricas, keepalive y par muerto por tracker, `send_command`, `shutdown` con close_notify, causas de fin tipadas. RED: `video_frames_reach_sink_through_runtime`, `sends_keepalive_after_silence`, `detects_dead_peer`, `rejects_out_of_order_sequence`, `handshake_frame_after_start_is_protocol_violation`. ~400 líneas.
3. [ ] s3 — Runtime de punta a punta sobre `LoopbackLanListener`: reconexión y luego sesión viva con keepalives y video sobre TCP loopback real. RED: `runtime_keeps_loopback_session_alive_with_keepalives`. ~200 líneas.

Criterios: `cargo fmt -- --check` y `cargo test --offline` verdes sin regresiones; ningún bind fuera de loopback.

## Progreso

Plan creado el 2026-10-01.
