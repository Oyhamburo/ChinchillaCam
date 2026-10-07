# Transporte Wi‑Fi fake/loopback (T16) (Android y desktop)

Este documento reúne los registros de las dos ramas, integradas en `main` el 2026-10-07 (`odd/tasks/branch-integration.md`). Cada parte conserva su evidencia original sin cambios.

## Parte Android (rama `feat/t15c-fake-usb-sustained`)

## Transporte Wi‑Fi fake/loopback (T16)

### 1. Objetivo

Que el stack TLS mutuo y de sesión ya probado sobre USB (pairing con prueba de posesión, reconexión confiable, `SessionFrame` sobre TLS, lectura en dos fases) funcione sin cambios de lógica sobre un segundo transporte de bytes que modela Wi‑Fi LAN, sin abrir ningún listener fuera de loopback ni en el teléfono ni en el desktop.

### 2. Problema

Ambos lados acoplan el stack TLS a USB:

- Android: `SslEngineUsbTlsChannel` y `SslEngineUsbTlsEstablishedChannel` reciben `AccessoryIoSession` + `UsbTlsCiphertextIoAdapter`, que envuelve cada registro TLS en un `AccessoryFrame` (stream `0x01020305`). No hay un seam de ciphertext bajo el `SSLEngine`.
- Desktop: `usb_tls_pairing_proof.rs` y `phone_connection.rs` exponen el tipo concreto `UsbTlsCiphertextStream<I: UsbBulkIo>` en firmas, structs y resultados, aunque el cuerpo sólo necesita `Read + Write`. `tls_session_frame.rs` ya es genérico.

### 3. Decisión

Continuación del plan maestro (`complete-webcam-product.md`, "Próxima unidad autorizada", actualización 2026-09-29: "después, T16"). Decisiones técnicas del orquestador en §4; sin decisión de producto nueva. El gate M3 sigue vigente: T16 no abre LAN; T17 es el único que podrá habilitar un listener no loopback.

### 4. Contrato compartido Android ↔ desktop

1. Wire Wi‑Fi: los registros TLS viajan directamente sobre el stream de bytes, sin envoltorio `AccessoryFrame`/`BulkFrame` (TLS ya delimita sus registros). Por encima de TLS no cambia nada: misma configuración mTLS, mismo pinning, mismo `SessionFrame` v1 con prefijo u32 BE, mismo CCP1.
2. Sin listener externo: el desktop sólo puede enlazar `127.0.0.1` con puerto efímero, como constante no configurable, y lo verifica en tests (`is_loopback`). Android no agrega permiso `INTERNET`, ni `ServerSocket`, ni `java.net` en código de producción nuevo; su transporte Wi‑Fi de T16 es un stream en memoria.
3. Seam: Android introduce `TlsCiphertextTransport` (write/read/close de ciphertext) con una implementación USB que conserva el comportamiento actual y una implementación de stream crudo. Desktop generaliza a `S: Read + Write` las funciones y tipos TLS de pairing/reconexión.
4. Sin enum de tipo de transporte todavía (lo necesita T18/T22; YAGNI en T16). Sin renombrar los tipos `Usb*` existentes (unidad aparte si se decide).
5. Fuera de alcance: listener LAN real, descubrimiento, permisos de red, one-active-phone, loop de sesión con keepalive, UI, hardware y la prueba cruzada entre worktrees (pospuesta por el usuario el 2026-09-29).

### 5. Riesgos

- Refactor del canal `SSLEngine` (Android) con executor de lectura, deadlines y constructores `internal` usados por tests: se aísla en w1 y se acepta sólo con la suite completa sin regresiones.
- Pipes en memoria y concurrencia (`PipedInputStream` "write end dead", umbrales de tiempo ajustados): helper de test compartido y tolerancias holgadas.
- TCP sin timeouts puede bloquear más allá del deadline lógico (desktop): el transporte loopback fija read/write timeouts y un test de par silencioso prueba la cota.
- Loopback es alcanzable por otros procesos locales: aceptable sólo como fake/test; la protección real es mTLS + trust store y llega con T17.

### 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados (incluido RED por falla de compilación). Commits ≤400 líneas como heurística. Revisión nativa RDD por commit o slice. Ruta: writer delegado acotado por tarea. Push sólo por decisión explícita del usuario; sin PR ni merge.

### 7. Tareas (Android)

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 398 tests, 2 omitidos, 0 fallos (HEAD `05a4279`, 2026-09-30). Último límite revisado: `05a4279`.

1. [x] w1 — Seam `TlsCiphertextTransport`: interfaz + `UsbAccessoryTlsCiphertextTransport` (envuelve `AccessoryIoSession` + `UsbTlsCiphertextIoAdapter`, mismo comportamiento); los canales `SslEngine*` dependen de la interfaz; se conservan los overloads con `AccessoryIoSession`. RED: `handshakeRunsOverInjectedCiphertextTransport`. ~300 líneas.

   Evidencia w1:
   - RED observado (compilación de tests contra el `src/main` sin cambios): `Unresolved reference: TlsCiphertextTransport`, `Unresolved reference: UsbAccessoryTlsCiphertextTransport`, `Unresolved reference: TlsCiphertextWriteResult`, `Unresolved reference: TlsCiphertextReadResult`, `Type mismatch: inferred type is ... RecordingCiphertextTransport but AccessoryIoSession was expected` (overload `handshake(transport, ...)` inexistente) y `'maxWriteBytes'/'writeCiphertext'/'readCiphertext'/'close' overrides nothing`.
   - GREEN: `handshakeRunsOverInjectedCiphertextTransport` pasa; el handshake pineado y un ida-y-vuelta de datos de aplicación corren sobre un `TlsCiphertextTransport` inyectado (grabador) que registra writes y reads.
   - Diseño: nueva interfaz `TlsCiphertextTransport` (write/read/close + `maxWriteBytes`, sin tipos USB/AccessoryFrame en la firma) con resultados neutrales `TlsCiphertextWriteResult`/`TlsCiphertextReadResult` (Received/Eof/Failed). `UsbAccessoryTlsCiphertextTransport` traduce los resultados `Usb*` sin cambiar chunking, stream id, tope de 64 KiB ni el fail-closed. `SslEngineUsbTlsChannel` y `SslEngineUsbTlsEstablishedChannel` dependen de la interfaz; los overloads `handshake(session, ...)`/`handshakeWithPinnedFingerprint(session, ...)` envuelven la sesión en `UsbAccessoryTlsCiphertextTransport` y los llamadores no cambian. El constructor `internal` del canal establecido pasó de `(session, adapter)` a `transport` (única adaptación mecánica en el helper de test `establishedChannel`).
   - Suite completa: 399 tests, 2 omitidos, 0 fallos (antes 398/2/0: +1 por el test nuevo). `assembleDebug` y `lintDebug` OK. Foco (clase nueva + `SslEngineUsbTlsChannelTest`, `UsbTrustedReconnectTest`, `UsbPairingFlowTest`, `TlsSessionFrameIoAdapterTest`, `UsbTlsPairingProofVerifierTest`) verde 3×.
2. [x] w2 — `StreamTlsCiphertextTransport` sobre `InputStream`/`OutputStream` sin `AccessoryFrame`; entradas de pairing y reconexión que aceptan un `TlsCiphertextTransport`; helper de test compartido. RED: `pinnedHandshakeOverRawStreamTransport`, `sessionFramesRoundTripOverRawStreamTransport`, `trustedReconnectOverRawStreamTransport`, `rawStreamTransportFailsClosedOnPeerEof`. ~350 líneas.

   Evidencia w2:
   - RED observado (compilación de tests contra el `src/main` sin cambios): `Unresolved reference: StreamTlsCiphertextTransport` (en el helper y en el test) y `Overload resolution ambiguity: handshake(...)` al no existir aún el tipo de transporte de stream; el overload `reconnect(desktopId, transport, ...)` tampoco resolvía.
   - GREEN: las cuatro pruebas pasan. `pinnedHandshakeOverRawStreamTransport` hace el handshake pineado mTLS sobre el stream crudo contra un desktop falso que habla registros TLS directos (sin `AccessoryFrame`); `sessionFramesRoundTripOverRawStreamTransport` manda y recibe un `SessionFrame` por `TlsSessionFrameIoAdapter` sobre el canal establecido; `trustedReconnectOverRawStreamTransport` corre HELLO→ACCEPT por el nuevo overload de `reconnect` con activación por `ActiveDesktopAuthority`; `rawStreamTransportFailsClosedOnPeerEof` prueba que el peer cerrando produce Rejected, transporte cerrado y lectura posterior Eof/Failed, sin colgarse.
   - Diseño: `StreamTlsCiphertextTransport(input, output, closeable)` escribe cada registro TLS directo al stream (`writeCiphertext` = write+flush) y `readCiphertext` bloquea por al menos un byte y devuelve lo disponible hasta `maxWriteBytes` (constante de clase de 64 KiB); `-1`→`Eof`, `IOException`→`Failed`, ambos cierran el transporte (fail-closed); `close` idempotente; sólo `java.io`, sin `java.net` ni socket. Nuevos overloads transport-neutrales `UsbTrustedReconnect.reconnect(desktopId, transport, …)` y `UsbTlsPairingProofVerifier.verify(challenge, transport)`; los overloads existentes de `AccessoryIoSession` delegan vía `UsbAccessoryTlsCiphertextTransport`, comportamiento idéntico. Helper de test único `RawStreamTlsTestSupport` (par de pipes crudos con buffers holgados, identidades keytool PKCS12 desktop/teléfono y peer de desktop falso que habla TLS crudo), usado por el nuevo test.
   - Dejado afuera (declarado): la ruta de pairing de punta a punta por `ChannelPairingProofVerifier`/`PendingPairingCoordinator`/`UsbPairingFlow` no se cableó sobre transporte — habría requerido cambiar la `fun interface` y el coordinador, excediendo el presupuesto; se hizo sólo reconnect + verifier. `UsbPairingFlow.kt` y `PendingPairingCoordinator.kt` quedaron sin tocar.
   - Suite completa: 403 tests, 2 omitidos, 0 fallos (antes 399/2/0: +4 por las pruebas nuevas). `assembleDebug` y `lintDebug` OK. Foco (clase nueva + `UsbTrustedReconnectTest`, `UsbPairingFlowTest`, `UsbTlsPairingProofVerifierTest`, `SslEngineUsbTlsChannelTest`, `TlsSessionFrameIoAdapterTest`) verde 3×.
   - Tamaño: 557 líneas agregadas / 5 borradas (producción ~116, helper de test 236, test 205), por encima del objetivo (~350) y del tope (~450). Casi todo el excedente es test obligatorio + el helper compartido pedido por la tarea; sin code-golf. Reportado al orquestador.
3. [x] w3 — Guard sin listener: test que falla si el manifest declara `INTERNET` o si `src/main` usa `ServerSocket`; evidencia y cierre. ~120 líneas.

   Evidencia w3:
   - Diseño: `NoNetworkListenerContractTest` lee manifest y fuentes de disco con la misma base relativa al módulo que `VisibleCameraForegroundServiceContractTest` (`File("src/main/AndroidManifest.xml")`, `File("src/main/java").walkTopDown()`). Dos guardias: `manifestDeclaresNoInternetPermission` (el manifest no declara `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` ni `CHANGE_WIFI_MULTICAST_STATE`) y `mainSourcesOpenNoServerSocket` (ninguna fuente `.kt`/`.java` bajo `src/main/java` contiene `ServerSocket`, `ServerSocketChannel`, `DatagramSocket`, `MulticastSocket` ni `NsdManager`). Los tokens prohibidos viven en un `companion object` compartido por ambas guardias.
   - Método del RED (guardia de caracterización; las guardias ya pasan sobre el árbol actual): se agregó temporalmente un `@Test redProbeGuardsDetectForbiddenTokensInline` que alimenta la MISMA lógica de guardia con fixtures inline que contienen los tokens prohibidos (`android.permission.INTERNET` y `import java.net.ServerSocket`). RED observado: `NoNetworkListenerContractTest > redProbeGuardsDetectForbiddenTokensInline FAILED` (`java.lang.AssertionError`) mientras las dos guardias reales pasaban. Luego se removió el probe para llegar a GREEN. No se tocó `src/main` ni el manifest.
   - GREEN: foco `--tests 'dev.chinchillacam.usbprobe.NoNetworkListenerContractTest'` verde (ambas guardias). Suite completa 405 tests, 2 omitidos, 0 fallos (antes 403/2/0: +2 por los dos métodos de la nueva guardia). `assembleDebug` y `lintDebug` OK.
   - Estado del árbol verificado: el manifest no declara ningún permiso de red; `grep` sobre `src/main/java` no encuentra ninguno de los tokens prohibidos. El transporte Wi‑Fi de T16 sigue siendo un stream en memoria sin listener.
   - Revisión nativa: pendiente. El START del facade de revisión ignora `baseRef` (incidente registrado 2026-09-30), por lo que el candidato w3 queda sin registro de revisión nativa.
   - Follow-ups declarados: (1) la ruta de pairing de punta a punta sobre transporte por `ChannelPairingProofVerifier`/`PendingPairingCoordinator`/`UsbPairingFlow` todavía no está cableada; (2) los strings de falla del canal siguen diciendo "USB TLS"; (3) la interop cruzada de raw‑TLS entre worktrees (stream crudo de Android ↔ `LoopbackLanListener` del desktop) no se ejecutó (pospuesta; requiere autorización nueva).

Criterios: suite completa sin regresiones; el mismo stack TLS/sesión pasa sobre USB fake y sobre stream crudo; ningún listener ni permiso de red.

### Progreso

Plan creado el 2026-09-30.
w1 completada (green) el 2026-09-30: seam `TlsCiphertextTransport` introducido sin cambio de comportamiento; suite 399/2/0, assemble+lint OK. Commit `9c11014`.
w2 completada (green) el 2026-09-30: `StreamTlsCiphertextTransport` (TLS crudo sobre stream, fail-closed, sólo `java.io`), overloads transport-neutrales de reconexión y verificador de prueba de pairing, helper de test compartido `RawStreamTlsTestSupport`; suite 403/2/0, assemble+lint OK. Ruta de pairing completa por el coordinador dejada para una unidad aparte (presupuesto). Tamaño 557/-5 por encima del tope ~450 (declarado: ~116 de producción, resto tests y helper compartido). Corrección del orquestador en la lectura: los overloads USB de reconexión y verificador construían el transporte con un adapter por defecto e ignoraban el `adapter` configurado en `SslEngineUsbTlsChannel`; ahora usan `tlsChannel.usbTransport(session)`. Suite 403/2/0 verificada de forma independiente.
w3 completada (green) el 2026-09-30: guardia de caracterización `NoNetworkListenerContractTest` (sin permiso de red en el manifest; sin `ServerSocket`/`ServerSocketChannel`/`DatagramSocket`/`MulticastSocket`/`NsdManager` en `src/main/java`), leyendo manifest y fuentes de disco. Suite 405/2/0 (+2 por los dos métodos de la guardia), assemble+lint OK. Sólo test; `src/main` y el manifest sin cambios.

Cierre del lado Android de T16 (2026-09-30): el seam `TlsCiphertextTransport` y el stack TLS/sesión ya probado corren tanto sobre USB fake como sobre stream crudo en memoria, sin listener externo ni permiso de red, con la guardia de no‑red en su lugar. Commits locales: `fcba3c6` (plan), `9c11014` (w1), `227a733` (w2) y el commit de w3 que hará el padre. Revisión nativa pendiente: el START del facade de revisión ignora `baseRef` (incidente registrado 2026-09-30). Follow-ups pendientes: (1) cablear la ruta de pairing de punta a punta sobre transporte por `ChannelPairingProofVerifier`/`PendingPairingCoordinator`/`UsbPairingFlow`; (2) los strings de falla del canal todavía dicen "USB TLS"; (3) la interop cruzada raw‑TLS entre worktrees (stream crudo de Android ↔ `LoopbackLanListener` del desktop) no se ejecutó (pospuesta, requiere autorización nueva).

## Parte desktop (rama `feat/desktop-video-sink`)

## Transporte Wi‑Fi fake/loopback (T16)

### 1. Objetivo

Que el stack TLS mutuo y de sesión ya probado sobre USB (pairing con prueba de posesión, reconexión confiable, `SessionFrame` sobre TLS, lectura en dos fases) funcione sin cambios de lógica sobre un segundo transporte de bytes que modela Wi‑Fi LAN, sin abrir ningún listener fuera de loopback ni en el teléfono ni en el desktop.

### 2. Problema

Ambos lados acoplan el stack TLS a USB:

- Android: `SslEngineUsbTlsChannel` y `SslEngineUsbTlsEstablishedChannel` reciben `AccessoryIoSession` + `UsbTlsCiphertextIoAdapter`, que envuelve cada registro TLS en un `AccessoryFrame` (stream `0x01020305`). No hay un seam de ciphertext bajo el `SSLEngine`.
- Desktop: `usb_tls_pairing_proof.rs` y `phone_connection.rs` exponen el tipo concreto `UsbTlsCiphertextStream<I: UsbBulkIo>` en firmas, structs y resultados, aunque el cuerpo sólo necesita `Read + Write`. `tls_session_frame.rs` ya es genérico.

### 3. Decisión

Continuación del plan maestro (`complete-webcam-product.md`, "Próxima unidad autorizada", actualización 2026-09-29: "después, T16"). Decisiones técnicas del orquestador en §4; sin decisión de producto nueva. El gate M3 sigue vigente: T16 no abre LAN; T17 es el único que podrá habilitar un listener no loopback.

### 4. Contrato compartido Android ↔ desktop

1. Wire Wi‑Fi: los registros TLS viajan directamente sobre el stream de bytes, sin envoltorio `AccessoryFrame`/`BulkFrame` (TLS ya delimita sus registros). Por encima de TLS no cambia nada: misma configuración mTLS, mismo pinning, mismo `SessionFrame` v1 con prefijo u32 BE, mismo CCP1.
2. Sin listener externo: el desktop sólo puede enlazar `127.0.0.1` con puerto efímero, como constante no configurable, y lo verifica en tests (`is_loopback`). Android no agrega permiso `INTERNET`, ni `ServerSocket`, ni `java.net` en código de producción nuevo; su transporte Wi‑Fi de T16 es un stream en memoria.
3. Seam: Android introduce `TlsCiphertextTransport` (write/read/close de ciphertext) con una implementación USB que conserva el comportamiento actual y una implementación de stream crudo. Desktop generaliza a `S: Read + Write` las funciones y tipos TLS de pairing/reconexión.
4. Sin enum de tipo de transporte todavía (lo necesita T18/T22; YAGNI en T16). Sin renombrar los tipos `Usb*` existentes (unidad aparte si se decide).
5. Fuera de alcance: listener LAN real, descubrimiento, permisos de red, one-active-phone, loop de sesión con keepalive, UI, hardware y la prueba cruzada entre worktrees (pospuesta por el usuario el 2026-09-29).

### 5. Riesgos

- Refactor del canal `SSLEngine` (Android) con executor de lectura, deadlines y constructores `internal` usados por tests: se aísla en w1 y se acepta sólo con la suite completa sin regresiones.
- Pipes en memoria y concurrencia (`PipedInputStream` "write end dead", umbrales de tiempo ajustados): helper de test compartido y tolerancias holgadas.
- TCP sin timeouts puede bloquear más allá del deadline lógico (desktop): el transporte loopback fija read/write timeouts y un test de par silencioso prueba la cota.
- Loopback es alcanzable por otros procesos locales: aceptable sólo como fake/test; la protección real es mTLS + trust store y llega con T17.

### 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados (incluido RED por falla de compilación). Commits ≤400 líneas como heurística. Revisión nativa RDD por commit o slice. Ruta: writer delegado acotado por tarea. Push sólo por decisión explícita del usuario; sin PR ni merge.

### 7. Tareas (desktop)

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 225 tests, 0 fallos (HEAD `7c15ffd`, 2026-09-30). Último límite revisado: `7c15ffd`.

1. [x] d1 — Seam genérico: `complete_handshake`, `complete_handshake_and_pairing_proof`, `complete_trusted_phone_handshake`, `Completed*`, `PendingPairedPhoneSession`, `AuthenticatedPhoneSession`, `accept_phone_pairing_connection`, `accept_phone_reconnect_connection` y `close_best_effort` pasan de `UsbTlsCiphertextStream<I: UsbBulkIo>` a `S: Read + Write`; ajustar las 3 anotaciones `CompletedTrustedHandshake<CrossedBulkIo>` de tests; duplex en memoria `Read + Write` sin framing USB como doble de test compartido. RED: `trusted_reconnect_over_in_memory_duplex`, `pairing_over_in_memory_duplex`. ~350 líneas.
2. [x] d2 — Transporte loopback TCP: listener que sólo enlaza `127.0.0.1:0` (constante), acepta con deadline y fija read/write timeouts; pairing y reconexión mTLS de punta a punta sobre `TcpStream` real; no se reutiliza el `LoopbackPairingProofServer` sin client auth. RED: `loopback_lan_listener_binds_only_loopback`, `pairing_then_reconnect_over_loopback_tcp`, `silent_loopback_peer_fails_bounded`. ~350 líneas.

Criterios: `cargo fmt -- --check` y `cargo test --offline` verdes sin regresiones; el mismo stack TLS/sesión pasa sobre USB fake, duplex en memoria y TCP loopback; ningún bind fuera de loopback.

### Progreso

Plan creado el 2026-09-30.
d1 completada el 2026-09-30 (commit `02206e1`; 227 tests, 0 fallos). Revisión nativa pendiente: el START del facade ignoró `baseRef` y resolvió como candidato toda la rama desde `32d0401`; no se creó lineage. Incidente de tooling a diagnosticar antes de revisar.
d2 completada el 2026-09-30 (commit `4108428`; 231 tests, 0 fallos; baseline 227 + 4 nuevos). Revisión nativa pendiente.
Lado desktop de T16 cerrado el 2026-09-30: stack TLS genérico sobre `S: Read + Write` y `LoopbackLanListener` sólo en `127.0.0.1`, sin listener externo. Lado Android cerrado en `feat/t15c-fake-usb-sustained` (`9c11014`, `227a733`, `2952e65`). Pendientes: revisión nativa de `7c15ffd..4108428` (incidente del facade), interop real Android stream crudo ↔ `LoopbackLanListener` entre worktrees (pospuesta, requiere autorización), y consolidar el CCP1 duplicado de `LoopbackPairingProofServer`.

#### Evidencia d1 (2026-09-30)

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

#### Evidencia d2 (2026-09-30)

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
