# Transporte Wi‑Fi fake/loopback (T16)

## 1. Objetivo

Que el stack TLS mutuo y de sesión ya probado sobre USB (pairing con prueba de posesión, reconexión confiable, `SessionFrame` sobre TLS, lectura en dos fases) funcione sin cambios de lógica sobre un segundo transporte de bytes que modela Wi‑Fi LAN, sin abrir ningún listener fuera de loopback ni en el teléfono ni en el desktop.

## 2. Problema

Ambos lados acoplan el stack TLS a USB:

- Android: `SslEngineUsbTlsChannel` y `SslEngineUsbTlsEstablishedChannel` reciben `AccessoryIoSession` + `UsbTlsCiphertextIoAdapter`, que envuelve cada registro TLS en un `AccessoryFrame` (stream `0x01020305`). No hay un seam de ciphertext bajo el `SSLEngine`.
- Desktop: `usb_tls_pairing_proof.rs` y `phone_connection.rs` exponen el tipo concreto `UsbTlsCiphertextStream<I: UsbBulkIo>` en firmas, structs y resultados, aunque el cuerpo sólo necesita `Read + Write`. `tls_session_frame.rs` ya es genérico.

## 3. Decisión

Continuación del plan maestro (`complete-webcam-product.md`, "Próxima unidad autorizada", actualización 2026-09-29: "después, T16"). Decisiones técnicas del orquestador en §4; sin decisión de producto nueva. El gate M3 sigue vigente: T16 no abre LAN; T17 es el único que podrá habilitar un listener no loopback.

## 4. Contrato compartido Android ↔ desktop

1. Wire Wi‑Fi: los registros TLS viajan directamente sobre el stream de bytes, sin envoltorio `AccessoryFrame`/`BulkFrame` (TLS ya delimita sus registros). Por encima de TLS no cambia nada: misma configuración mTLS, mismo pinning, mismo `SessionFrame` v1 con prefijo u32 BE, mismo CCP1.
2. Sin listener externo: el desktop sólo puede enlazar `127.0.0.1` con puerto efímero, como constante no configurable, y lo verifica en tests (`is_loopback`). Android no agrega permiso `INTERNET`, ni `ServerSocket`, ni `java.net` en código de producción nuevo; su transporte Wi‑Fi de T16 es un stream en memoria.
3. Seam: Android introduce `TlsCiphertextTransport` (write/read/close de ciphertext) con una implementación USB que conserva el comportamiento actual y una implementación de stream crudo. Desktop generaliza a `S: Read + Write` las funciones y tipos TLS de pairing/reconexión.
4. Sin enum de tipo de transporte todavía (lo necesita T18/T22; YAGNI en T16). Sin renombrar los tipos `Usb*` existentes (unidad aparte si se decide).
5. Fuera de alcance: listener LAN real, descubrimiento, permisos de red, one-active-phone, loop de sesión con keepalive, UI, hardware y la prueba cruzada entre worktrees (pospuesta por el usuario el 2026-09-29).

## 5. Riesgos

- Refactor del canal `SSLEngine` (Android) con executor de lectura, deadlines y constructores `internal` usados por tests: se aísla en w1 y se acepta sólo con la suite completa sin regresiones.
- Pipes en memoria y concurrencia (`PipedInputStream` "write end dead", umbrales de tiempo ajustados): helper de test compartido y tolerancias holgadas.
- TCP sin timeouts puede bloquear más allá del deadline lógico (desktop): el transporte loopback fija read/write timeouts y un test de par silencioso prueba la cota.
- Loopback es alcanzable por otros procesos locales: aceptable sólo como fake/test; la protección real es mTLS + trust store y llega con T17.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados (incluido RED por falla de compilación). Commits ≤400 líneas como heurística. Revisión nativa RDD por commit o slice. Ruta: writer delegado acotado por tarea. Push sólo por decisión explícita del usuario; sin PR ni merge.

## 7. Tareas (desktop)

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 225 tests, 0 fallos (HEAD `7c15ffd`, 2026-09-30). Último límite revisado: `7c15ffd`.

1. [x] d1 — Seam genérico: `complete_handshake`, `complete_handshake_and_pairing_proof`, `complete_trusted_phone_handshake`, `Completed*`, `PendingPairedPhoneSession`, `AuthenticatedPhoneSession`, `accept_phone_pairing_connection`, `accept_phone_reconnect_connection` y `close_best_effort` pasan de `UsbTlsCiphertextStream<I: UsbBulkIo>` a `S: Read + Write`; ajustar las 3 anotaciones `CompletedTrustedHandshake<CrossedBulkIo>` de tests; duplex en memoria `Read + Write` sin framing USB como doble de test compartido. RED: `trusted_reconnect_over_in_memory_duplex`, `pairing_over_in_memory_duplex`. ~350 líneas.
2. [x] d2 — Transporte loopback TCP: listener que sólo enlaza `127.0.0.1:0` (constante), acepta con deadline y fija read/write timeouts; pairing y reconexión mTLS de punta a punta sobre `TcpStream` real; no se reutiliza el `LoopbackPairingProofServer` sin client auth. RED: `loopback_lan_listener_binds_only_loopback`, `pairing_then_reconnect_over_loopback_tcp`, `silent_loopback_peer_fails_bounded`. ~350 líneas.

Criterios: `cargo fmt -- --check` y `cargo test --offline` verdes sin regresiones; el mismo stack TLS/sesión pasa sobre USB fake, duplex en memoria y TCP loopback; ningún bind fuera de loopback.

## Progreso

Plan creado el 2026-09-30.
d1 completada el 2026-09-30 (commit `02206e1`; 227 tests, 0 fallos). Revisión nativa pendiente: el START del facade ignoró `baseRef` y resolvió como candidato toda la rama desde `32d0401`; no se creó lineage. Incidente de tooling a diagnosticar antes de revisar.
d2 completada el 2026-09-30 (commit `4108428`; 231 tests, 0 fallos; baseline 227 + 4 nuevos). Revisión nativa pendiente.
Lado desktop de T16 cerrado el 2026-09-30: stack TLS genérico sobre `S: Read + Write` y `LoopbackLanListener` sólo en `127.0.0.1`, sin listener externo. Lado Android cerrado en `feat/t15c-fake-usb-sustained` (`9c11014`, `227a733`, `2952e65`). Pendientes: revisión nativa de `7c15ffd..4108428` (incidente del facade), interop real Android stream crudo ↔ `LoopbackLanListener` entre worktrees (pospuesta, requiere autorización), y consolidar el CCP1 duplicado de `LoopbackPairingProofServer`.

### Evidencia d1 (2026-09-30)

Seam genérico aplicado sin cambio de lógica: las funciones y tipos TLS de pairing/reconexión
pasaron de `UsbTlsCiphertextStream<I: UsbBulkIo>` a `S: Read + Write`. Se parametrizaron
`CompletedPairingProof<S>`, `CompletedTrustedHandshake<S>`, `PendingPairedPhoneSession<S>`,
`AuthenticatedPhoneSession<S>` (cada struct lleva la cota `S: Read + Write` que exige
`rustls::StreamOwned`), `complete_handshake`, `complete_handshake_and_pairing_proof`,
`complete_trusted_phone_handshake`, `accept_phone_pairing_connection`,
`accept_phone_reconnect_connection` y `close_best_effort`. Los nombres públicos se conservan.
Los callers USB siguen compilando por inferencia; sólo se reajustaron las 3 anotaciones
explícitas a `CompletedTrustedHandshake<UsbTlsCiphertextStream<CrossedBulkIo>>`
(tests/usb_tls_trusted_session_test.rs y tests/paired_phone_candidate_confirm_test.rs).

Doble de test compartido `tests/common/duplex.rs` (`InMemoryDuplex`): par de pipes cruzados
`Read + Write` sin framing USB, con timeout de lectura (`TimedOut`) y EOF (`Ok(0)`) al cerrar
el par; tolerancias holgadas.

TDD estricto:
- RED (fallo de compilación esperado) — `cargo test --offline --test in_memory_duplex_session_test`:
  `error[E0308]: mismatched types ... expected 'UsbTlsCiphertextStream<_>', found 'InMemoryDuplex'`
  en `accept_phone_reconnect_connection` y `accept_phone_pairing_connection`.
- GREEN — tras el seam genérico, `trusted_reconnect_over_in_memory_duplex` y
  `pairing_over_in_memory_duplex` pasan; el test enfocado corrió 3 veces, 2 passed/0 failed cada vez.

Suite completa: `cargo fmt -- --check` verde; `cargo test --offline` = 227 passed, 0 failed
(baseline 225 + 2 tests nuevos), sin regresiones.

Riesgo anotado: cambio de API pública en el parámetro genérico (de `I: UsbBulkIo` a
`S: Read + Write`). No rompe a los callers USB observados; cualquier consumidor externo que
nombrara `Completed*`/`*PhoneSession` con el parámetro de IO concreto debe pasar ahora el tipo
de stream (p. ej. `UsbTlsCiphertextStream<I>`).

### Evidencia d2 (2026-09-30)

Nuevo módulo `src/loopback_lan_listener.rs` (exportado desde `src/lib.rs`): `LoopbackLanListener`
modela el transporte Wi‑Fi fake. Enlaza SÓLO `Ipv4Addr::LOCALHOST` con puerto efímero mediante
constantes internas (`LOOPBACK_BIND_IP`/`LOOPBACK_BIND_PORT`), sin parámetro de dirección ni
configuración. `bind()`/`bind_with_options(LoopbackLanOptions)` construyen el listener;
`local_addr()` devuelve la dirección efectiva; `accept(deadline)` acepta una conexión con el
enfoque nonblocking-con-deadline (espejo del `accept_with_deadline` del
`LoopbackPairingProofServer`, sin modificar ese servidor), vuelve a bloqueante
(`set_nonblocking(false)`), fija read/write timeouts acotados (por defecto 1500 ms, configurables
vía `LoopbackLanOptions`) y rechaza defensivamente (cierra y devuelve `NonLoopbackPeer`) cualquier
par cuya dirección no sea loopback. No se reutiliza ni se cambia `LoopbackPairingProofServer`
(ese usa `with_no_client_auth`). Ningún bind fuera de loopback: `grep -rn "0.0.0.0\|UNSPECIFIED"
desktop/usb-probe/src` no muestra nada.

El mismo stack mTLS de pairing/reconexión corre sin cambios de lógica sobre `TcpStream` real: el
listener sólo aporta el socket y los timeouts; `accept_phone_pairing_connection` y
`accept_phone_reconnect_connection` reciben el `TcpStream` directamente.

TDD estricto:
- RED (fallo de compilación esperado) — `cargo test --offline --test loopback_lan_transport_test`:
  `error[E0432]: unresolved imports usb_probe::LoopbackLanError, usb_probe::LoopbackLanListener,
  usb_probe::LoopbackLanOptions ... no LoopbackLanListener in the root` (módulo/tipos ausentes).
- GREEN — tras crear el módulo y exportarlo, los 4 tests pasan:
  `loopback_lan_listener_binds_only_loopback` (is_loopback y puerto != 0),
  `pairing_then_reconnect_over_loopback_tcp` (CCP1 de pairing -> confirm al store temporal ->
  segunda conexión TCP -> HELLO/ACCEPT de reconexión para el mismo teléfono),
  `silent_loopback_peer_fails_bounded` (par silencioso: el handshake falla por el read timeout
  del stream, cota `< 10x` el read timeout configurado, sin colgarse) y
  `accept_times_out_without_peer` (`accept` sin cliente devuelve `LoopbackLanError::Timeout`).
  El test enfocado corrió 3 veces: 4 passed/0 failed cada vez.

Suite completa: `cargo fmt -- --check` verde; `cargo test --offline` = 231 passed, 0 failed
(baseline 227 + 4 tests nuevos), sin regresiones.

Desvío de tamaño declarado: diff total ≈ 471 líneas (146 módulo + 320 test + 5 `lib.rs`), por
encima de la heurística de ~450. El exceso es íntegramente boilerplate de helpers de test
duplicado por conexión según la convención por-archivo del proyecto (`proof_fixture`,
`tls_client`, `client_config`, `read_ccp1_frame`, `unique_store_path`, `TestRng`); el módulo de
producción es compacto y no se hizo code-golf para esconder tamaño.

Riesgo anotado: loopback es alcanzable por otros procesos locales; aceptable sólo como
fake/test (la protección real es mTLS + trust store, llega con T17). El guard `NonLoopbackPeer`
es defensivo y, sobre un bind loopback-only, inalcanzable en la práctica; se mantiene por
defensa en profundidad. Los timeouts dependen de la granularidad del temporizador del SO, por lo
que las cotas de los tests son holgadas (`< 10x` read timeout, `< 5 s`).
