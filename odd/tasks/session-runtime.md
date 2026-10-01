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

## 7. Tareas (Android)

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 405 tests, 2 omitidos, 0 fallos (HEAD `2952e65`).

1. [x] r1 — Prerrequisitos: escritura serializada en `SslEngineUsbTlsEstablishedChannel` (write de aplicación y `wrap` del camino de lectura bajo el mismo lock), `close` idempotente, y `Reconnected` expone `sessionId` y la próxima secuencia saliente/entrante. RED: `concurrentReadAndWritePreserveFrameIntegrity`, `closeIsIdempotent`, `reconnectedCarriesSessionIdentity`. ~250 líneas.

   Evidencia r1:
   - Lock único (`ReentrantLock`) en `SslEngineUsbTlsEstablishedChannel`: `writeApplicationData` y el `wrapEmptyHandshakeData` del camino de lectura envuelven `engine.wrap` + `transport.writeCiphertext` bajo ese lock; la lectura nunca lo retiene mientras bloquea en el transporte.
   - `close()` idempotente con guarda `AtomicBoolean`; `StreamTlsCiphertextTransport.closed` pasa a `AtomicBoolean` (cierre exactamente una vez, seguro ante concurrencia).
   - `Reconnected` ahora expone `sessionId` (el del HELLO), `nextOutboundSequence` (= hello.seq + 1 = 1) y `nextInboundSequence` (= accept.seq + 1 = hello.seq + 2 = 2). La reconexión valida que el ACCEPT traiga `sequence == hello.seq + 1` y el mismo `sessionId`; discrepancia → rechazo tipado `InvalidHandshakeAccept` con el canal cerrado.
   - RED observado: `closeIsIdempotent` (AssertionError, `closeCount` 2 sin guarda) y `concurrentApplicationWritesAreSerialized` (AssertionError, `maxConcurrent` > 1 sin lock) al revertir las guardas del canal; `reconnectWithMismatchedAcceptSessionIdFailsClosed` (AssertionError, reconectaba en lugar de rechazar) al desactivar la validación. GREEN tras aplicar la corrección.
   - `concurrentReadAndWritePreserveFrameIntegrity` queda declarado como caracterización del lock (cubre lectura+escritura concurrentes): JSSE no expone API para forzar un `wrap` post-handshake (KeyUpdate TLS 1.3) en el camino de lectura, así que el lock se fija aparte con `concurrentApplicationWritesAreSerialized`.
   - Verificación completa: suite `:android:usb-probe:testDebugUnitTest` 410 tests, 2 omitidos, 0 fallos (+5 respecto a la baseline de 405); `assembleDebug` y `lintDebug` OK; `NoNetworkListenerContractTest` en verde (sin red).
2. [ ] r2 — `SessionRuntime`: hilo escritor con cola acotada y keepalive por tracker, hilo lector con validación de secuencia/`sessionId` y despacho, causas de fin tipadas, reloj inyectado. RED: `sendsKeepaliveWhenIdle`, `deliversCameraControlCommand`, `endsWithPeerDeadWhenPeerSilent`, `rejectsOutOfOrderSequence`, `backpressureEndsSession`. ~400 líneas.
3. [ ] r3 — Egress de video por el runtime: interfaz extraída para el sink fragmentador y transporte de video que sólo encola. RED: `fragmentedVideoFlowsThroughRuntimeWithSharedSequence`. ~300 líneas.

Criterios: suite completa sin regresiones; ninguna E/S TLS en el hilo del llamador de video; sin permisos de red.

## Progreso

Plan creado el 2026-10-01.

r1 implementado el 2026-10-01 (RED→GREEN, TDD estricto): lock de escritura único y camino de lectura serializado en `SslEngineUsbTlsEstablishedChannel`, `close` idempotente, `StreamTlsCiphertextTransport.closed` atómico, e identidad de sesión (`sessionId`/`nextOutboundSequence`/`nextInboundSequence`) con validación del ACCEPT en `Reconnected`. Suite: 410 tests, 2 omitidos, 0 fallos. Commit de r1 en la rama de la feature.
