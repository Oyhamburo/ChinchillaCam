# Sesión USB autenticada

## 1. Objetivo

Componer de punta a punta, a nivel de aplicación/dominio y sin UI ni LAN, el pairing USB y la reconexión confiable sobre el canal TLS mutuo, y llevar `SessionFrame` dentro de ese canal: cierra M3 de punta a punta y abre los transportes autenticados de M4 por USB.

## 2. Problema

Las piezas existen pero nadie las compone: en Android `PendingPairingCoordinator` no tiene instancias de producción y su verificador (`PairingProofVerifier.verify(challenge, proofBytes)`) no transporta canal, mientras `UsbTlsPairingProofVerifier.verify(challenge, session)` sí devuelve el canal vivo; no existe reconexión (T19). En el desktop no hay capa que elija por conexión entre `complete_handshake_and_pairing_proof` y `complete_trusted_phone_handshake`, ni nada que llame a `PairedPhoneCandidate::confirm`. El video sigue en claro sobre el stream AOA `0x01020304`.

## 3. Decisión

Continuación del plan maestro tras `phone-mtls-identity` (ver `odd/tasks/complete-webcam-product.md`, "Próxima unidad autorizada"). Sin decisión de producto nueva: las decisiones técnicas quedan en §4.

## 4. Contrato compartido Android ↔ desktop

1. Modo por conexión: el teléfono inicia pairing (tras escanear un QR) o reconexión (desktop ya confiable en su store). El desktop elige la política por conexión según su propio estado: ventana de pairing abierta (QR visible) → `complete_handshake_and_pairing_proof`; si no → `complete_trusted_phone_handshake`. Un desacuerdo de modo falla cerrado (CCP1 inválido o certificado no confiable) sin persistir nada.
2. Tras un pairing exitoso, el canal TLS vivo se conserva hasta la confirmación explícita de cada lado; rechazo, cancelación o vencimiento cierran el canal. La confirmación del teléfono persiste la confianza (store C6) y activa vía `ActiveDesktopAuthority`; la del desktop usa `PairedPhoneCandidate::confirm` (atómica, rechaza revocados).
3. `SessionFrame` sobre TLS: cada frame de aplicación es un prefijo u32 big-endian con la longitud (1..=1048576) seguido de exactamente un `SessionFrame` CCSF v1 codificado; lecturas acotadas con deadline absoluto; longitud cero, excesiva o frame inválido cierran el canal (fail closed). El tráfico de sesión autenticado viaja sólo dentro de TLS (stream AOA `0x01020305`); `0x01020304` en claro queda como legacy/test.
4. Reconexión: el teléfono abre TLS pinneando el SPKI del desktop desde su store y presentando su certificado de cliente. Como en TLS 1.3 el cliente no ve el rechazo del desktop hasta leer, la sesión sólo se considera activa cuando el teléfono envía `HANDSHAKE_HELLO` y recibe `HANDSHAKE_ACCEPT`; `HANDSHAKE_REJECT`, cierre o deadline → rechazo tipado y cierre. La activación pasa por `ActiveDesktopAuthority`.
5. Después de un pairing confirmado en ambos lados, el mismo canal vivo puede iniciar la sesión con `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT`.
6. Fuera de alcance: UI/Activity, LAN/listener, hardware, reemplazar el receptor de video de producción del desktop (el camino TLS se agrega en paralelo), one-active-phone en el desktop (pregunta de producto para T16/T17), marcador durable de regeneración y acción "restablecer identidad" (seguimientos de `phone-mtls-identity` para la etapa de wiring).

## 5. Riesgos

- Canal vivo retenido durante una confirmación lenta: requiere vencimiento y cierre explícitos.
- Framing incorrecto entre lenguajes: se mitiga con el contrato §4.3 y una prueba cruzada posterior (requiere autorización fresca del usuario).
- Asimetría TLS 1.3: cubierta por §4.4.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits locales ≤400 líneas como heurística; sin push, PR ni merge. Revisión nativa RDD por commit o slice con `gentle-ai review assess`. Ruta: writer delegado acotado por tarea. Cualquier runtime entre worktrees requiere autorización fresca del usuario.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Último límite revisado: `9c0d94a`.

1. [x] s1 — SessionFrame sobre TLS: lectura/escritura de `SessionFrame` sobre `StreamOwned<ServerConnection, _>` con el framing de §4.3. RED: `round_trips_session_frame_over_tls`, `rejects_oversized_length_before_allocating`, `rejects_zero_length`, `truncated_frame_fails_closed`. ~250 líneas.
2. [ ] s2 — Conexión de teléfono por modo: API con modo explícito (`Pairing { issuer }` → pendiente con canal vivo hasta `confirm(label, store)` o `reject()` que cierra; `Reconnect { lookup }` → handshake confiable, lee `HANDSHAKE_HELLO`, responde `HANDSHAKE_ACCEPT`, entrega sesión autenticada con `phone_id`; hello inválido → `HANDSHAKE_REJECT` y cierre). RED: `pairing_mode_holds_channel_until_confirm`, `pairing_reject_closes_channel`, `reconnect_mode_accepts_trusted_phone_hello`, `reconnect_mode_rejects_invalid_hello`. ~350 líneas.
3. [ ] s3 — Helper para la prueba cruzada (con la s4 Android, requiere autorización fresca): modo del helper que ejecuta pairing, confirmación y reconexión con hello. ~150 líneas.

Criterios: nada se persiste sin confirmación; reconexión sólo con teléfonos confiables; framing acotado y fail closed; `cargo fmt -- --check` y `cargo test` verdes.

## Evidencia s1

- RED observado (`cargo test --offline --test tls_session_frame_test` contra `write_session_frame`/`read_session_frame` reemplazados por `todo!()`): 5 tests fallaron por panic, no por error de compilación -- `round_trips_session_frame_over_tls` panicó en `write_session_frame` (`not yet implemented: RED: task s1 write_session_frame not yet implemented`, `src/tls_session_frame.rs:78`); las otras 4 (`rejects_oversized_length_before_allocating`, `rejects_zero_length`, `truncated_frame_fails_closed`, `read_times_out_when_deadline_already_passed`) panicaron en `read_session_frame` (`not yet implemented: RED: task s1 read_session_frame not yet implemented`, `src/tls_session_frame.rs:92`); `test result: FAILED. 0 passed; 5 failed`.
- GREEN: `cargo test --offline --test tls_session_frame_test` PASS, 5/5.
- Full: `cargo fmt -- --check && cargo test --offline` PASS; 206 tests pasando (baseline 201 + 5 nuevos), 0 fallidos.
- Alcance: sólo framing (prefijo u32 BE `1..=1_048_576` + `SessionFrameCodec` v1 vía `decode_with_limit`/`encode`); deadline absoluto sólo en lectura, reutilizando el patrón de `read_exact_before`/`ensure_before_deadline` de `usb_tls_pairing_proof.rs`; longitud cero o excesiva rechazada ANTES de asignar el buffer del payload; error tipado `TlsSessionFrameError`, fail-closed (el módulo documenta que el llamador no debe reusar el stream tras un error). No incluye selección de modo por conexión ni `HANDSHAKE_*` (queda para s2).
- Desvíos: (1) tamaño ~433 líneas de autoría (170 en `tls_session_frame.rs` + 261 en `tls_session_frame_test.rs` + 2 en `lib.rs`) por encima de la heurística de ~250 líneas de la tarea; la mayor parte es infraestructura de prueba TLS/`CrossedBulkIo` duplicada a propósito, siguiendo la convención ya existente en `usb_tls_pairing_proof_test.rs`/`usb_tls_trusted_session_test.rs` (cada archivo de test mantiene su propia copia en vez de una compartida); no se recortó cobertura para encajar en la heurística. (2) No se tocó `src/usb_tls_pairing_proof.rs`: su helper de deadline devuelve `UsbTlsPairingProofError`, un dominio de error distinto, y la tarea sólo autorizaba un cambio de visibilidad, no de firma; se reimplementó el mismo patrón (~15 líneas) localmente en `tls_session_frame.rs`. (3) No se agregó una prueba dedicada para "encoding debe rechazar frames que exceden el límite": ya queda garantizado transitivamente por `SessionFrameCodec::encode` (cubierto en `session_frame_test.rs`) y no figuraba en la lista RED obligatoria de la tarea.
- Commit: `feat(desktop): frame session frames over TLS`.

## Revisión nativa s1

Lineage `review-a6f229d306a8aefc`, 1 lente (reliability/R3), **aprobada sin corrección**, autoridad `burned` (acknowledged). Hallazgos no bloqueantes (advisory), los tres atendidos por el commit 1 de esta unidad (s1b):

- **R3-soft-deadline** (el deadline sólo se chequea entre lecturas; una lectura bloqueante puede excederlo por el timeout propio del transporte): atendido documentando el límite como *soft*, no *hard*, en el doc del módulo (`tls_session_frame.rs`) -- no se cambió el mecanismo de chequeo (sigue siendo entre `read()`s), porque un límite duro requeriría una lectura no bloqueante/cancelable que el transporte actual no ofrece.
- **R3-error-kind-mapping** (`WouldBlock` se mapeaba a `Timeout` antes de que venciera el deadline; `Interrupted` no se reintentaba, a diferencia de `read_exact`): corregido en código -- ver Evidencia s1b.
- **R3-missing-boundary-coverage** (sin prueba para un largo-prefijo truncado ni para el límite superior inclusivo `1_048_576`): cerrado con dos pruebas nuevas -- ver Evidencia s1b.

## Evidencia s1b

- RED observado (`cargo test --offline --test tls_session_frame_test` con los 5 tests nuevos agregados pero sin tocar todavía `src/tls_session_frame.rs`): 2 de 10 tests fallaron -- `retries_interrupted_read_like_read_exact` (`left: Err(Io("simulated interrupt")), right: Ok(SessionFrame{...})`) y `retries_would_block_before_the_deadline_instead_of_timing_out_immediately` (`left: Err(Timeout), right: Ok(SessionFrame{...})`); `test result: FAILED. 8 passed; 2 failed`. Los otros 3 tests nuevos (`truncated_length_prefix_fails_closed`, `accepts_frame_at_exact_max_length_boundary`, `would_block_past_the_deadline_still_times_out`) ya pasaban contra el código sin cambios: no son RED de comportamiento -- los dos primeros cierran cobertura de un comportamiento ya correcto (R3-missing-boundary-coverage), y el tercero es una prueba de regresión/red de seguridad (mismo resultado externo `Err(Timeout)` antes y después del fix, por razones distintas) más que una prueba que demuestre el cambio. Precisión TDD: no es test-first estricto para esos 3 -- se escribieron junto con los 2 RED reales y se corrieron todos juntos contra el código viejo para observar honestamente cuáles fallaban.
- GREEN: `cargo test --offline --test tls_session_frame_test` PASS, 10/10.
- Full: `cargo fmt -- --check && cargo test --offline` PASS; 211 tests pasando (baseline 206 + 5 nuevos), 0 fallidos.
- Alcance: `read_exact_before_deadline` reintenta `Interrupted` (igual que `read_exact`) y reintenta `WouldBlock`/`TimedOut` hasta que el deadline realmente venza (en vez de mapear a `Timeout` en la primera ocurrencia); ambos reintentos vuelven a pasar por el mismo chequeo de deadline en la próxima iteración, así que no hay espera indefinida ante una señal o un `WouldBlock` sostenido más allá del deadline. Se eliminó `map_read_error` (quedó sin uso). Se documentó R3-soft-deadline en el doc del módulo. Dos pruebas de límite nuevas (prefijo de longitud truncado, límite superior inclusivo exacto de 1_048_576 bytes).
- Desvíos: (1) no se tocó `usb_tls_pairing_proof.rs` (`read_exact_before`/`map_read_error`, mismo patrón pero dominio de error distinto): la tarea acotó el commit 1 a `tls_session_frame.rs`; queda una asimetría documentada entre ambos módulos (éste reintenta `Interrupted`/`WouldBlock` hasta el deadline, aquél sigue mapeando `WouldBlock` a `Timeout` de inmediato), fuera de alcance de esta unidad. (2) `accepts_frame_at_exact_max_length_boundary` usa 16 entradas `CameraControlCommand` (no un solo campo) porque cada campo string/bytes tiene prefijo de longitud `u16` (máx 65_535 bytes) y ningún campo aislado puede alcanzar el límite de 1_048_576 bytes por sí solo; la aritmética se autoverifica con un `assert_eq!` sobre `SessionFrameCodec::encode(...).len()` antes del round-trip, en vez de confiar ciegamente en el cálculo a mano.
- Commit: `fix(desktop): retry interrupted TLS session frame reads`.

## Progreso

Plan creado el 2026-09-28; s1 completada el 2026-09-28 (ver Evidencia s1). s2 y s3 sin iniciar.
