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

1. [ ] w1 — Seam `TlsCiphertextTransport`: interfaz + `UsbAccessoryTlsCiphertextTransport` (envuelve `AccessoryIoSession` + `UsbTlsCiphertextIoAdapter`, mismo comportamiento); los canales `SslEngine*` dependen de la interfaz; se conservan los overloads con `AccessoryIoSession`. RED: `handshakeRunsOverInjectedCiphertextTransport`. ~300 líneas.
2. [ ] w2 — `StreamTlsCiphertextTransport` sobre `InputStream`/`OutputStream` sin `AccessoryFrame`; entradas de pairing y reconexión que aceptan un `TlsCiphertextTransport`; helper de test compartido. RED: `pinnedHandshakeOverRawStreamTransport`, `sessionFramesRoundTripOverRawStreamTransport`, `trustedReconnectOverRawStreamTransport`, `rawStreamTransportFailsClosedOnPeerEof`. ~350 líneas.
3. [ ] w3 — Guard sin listener: test que falla si el manifest declara `INTERNET` o si `src/main` usa `ServerSocket`; evidencia y cierre. ~120 líneas.

Criterios: suite completa sin regresiones; el mismo stack TLS/sesión pasa sobre USB fake y sobre stream crudo; ningún listener ni permiso de red.

## Progreso

Plan creado el 2026-09-30.
