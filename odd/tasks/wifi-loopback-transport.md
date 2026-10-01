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

## 7. Tareas (Android)

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

## Progreso

Plan creado el 2026-09-30.
w1 completada (green) el 2026-09-30: seam `TlsCiphertextTransport` introducido sin cambio de comportamiento; suite 399/2/0, assemble+lint OK. Commit `9c11014`.
w2 completada (green) el 2026-09-30: `StreamTlsCiphertextTransport` (TLS crudo sobre stream, fail-closed, sólo `java.io`), overloads transport-neutrales de reconexión y verificador de prueba de pairing, helper de test compartido `RawStreamTlsTestSupport`; suite 403/2/0, assemble+lint OK. Ruta de pairing completa por el coordinador dejada para una unidad aparte (presupuesto). Tamaño 557/-5 por encima del tope ~450 (declarado: ~116 de producción, resto tests y helper compartido). Corrección del orquestador en la lectura: los overloads USB de reconexión y verificador construían el transporte con un adapter por defecto e ignoraban el `adapter` configurado en `SslEngineUsbTlsChannel`; ahora usan `tlsChannel.usbTransport(session)`. Suite 403/2/0 verificada de forma independiente.
w3 completada (green) el 2026-09-30: guardia de caracterización `NoNetworkListenerContractTest` (sin permiso de red en el manifest; sin `ServerSocket`/`ServerSocketChannel`/`DatagramSocket`/`MulticastSocket`/`NsdManager` en `src/main/java`), leyendo manifest y fuentes de disco. Suite 405/2/0 (+2 por los dos métodos de la guardia), assemble+lint OK. Sólo test; `src/main` y el manifest sin cambios.

Cierre del lado Android de T16 (2026-09-30): el seam `TlsCiphertextTransport` y el stack TLS/sesión ya probado corren tanto sobre USB fake como sobre stream crudo en memoria, sin listener externo ni permiso de red, con la guardia de no‑red en su lugar. Commits locales: `fcba3c6` (plan), `9c11014` (w1), `227a733` (w2) y el commit de w3 que hará el padre. Revisión nativa pendiente: el START del facade de revisión ignora `baseRef` (incidente registrado 2026-09-30). Follow-ups pendientes: (1) cablear la ruta de pairing de punta a punta sobre transporte por `ChannelPairingProofVerifier`/`PendingPairingCoordinator`/`UsbPairingFlow`; (2) los strings de falla del canal todavía dicen "USB TLS"; (3) la interop cruzada raw‑TLS entre worktrees (stream crudo de Android ↔ `LoopbackLanListener` del desktop) no se ejecutó (pospuesta, requiere autorización nueva).
