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

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Último límite revisado: `0d7f048`.

### [x] l1 — Tests de seguridad de la reconexión y fuga de canal

Test unitario de `PinnedDesktopFingerprintTrustManager` que rechaza un fingerprint distinto (fixtures de `PinnedDesktopTlsTrustManagerTest`); `UsbTrustedReconnect` cierra el canal si `requestActivation` lanza o rechaza. RED: `rejectsMismatchedFingerprint`, `activationExceptionClosesChannel`, `activationRejectedClosesChannel`. ~200 líneas.

**Evidencia l1** (commit `fix(android): close reconnect channel when activation fails`):
- `rejectsMismatchedFingerprint` + `acceptsMatchingFingerprintForPinnedDesktopFingerprintTrustManager` (agregado): caracterización honesta. `PinnedDesktopFingerprintTrustManager` ya rechazaba/aceptaba correctamente antes de este commit (sin test dedicado); ambos PASAN sin ningún cambio de producción.
- `activationRejectedClosesChannel`: caracterización honesta. El branch `Rejected` de `requestActivation` ya cerraba el canal (`runCatching { channel.close() }`) antes de este commit; ese código quedó textualmente idéntico. No se pudo aislar su RED porque el archivo entero no compilaba (ver el punto siguiente), pero la revisión del código confirma que este camino nunca cambió.
- `activationExceptionClosesChannel`: RED real, desvío declarado. Antes del fix el módulo no compilaba: `Unresolved reference: ActivationFailed` en `UsbTrustedReconnectTest.kt:247` (el tipo de rechazo tipado todavía no existía) -- RED obtenido por falla de compilación en vez de una aserción, tal como habilitan las reglas de ejecución.
- GREEN: `UsbTrustedReconnect.reconnect()` envuelve la llamada a `requestActivation` en `try/catch(Exception)`; cualquier excepción cierra el canal (`runCatching { channel.close() }`) y se reporta como `UsbTrustedReconnectResult.Rejected.ActivationFailed(desktopId, cause)` (tipo nuevo, mismo estilo que los demás rechazos tipados del archivo).
- Focalizado: `PinnedDesktopTlsTrustManagerTest` 8/8 verdes; `UsbTrustedReconnectTest` 6/6 verdes.
- Full (`testDebugUnitTest --rerun-tasks`, `assembleDebug`, `lintDebug`): BUILD SUCCESSFUL. Totales XML: 388 tests, 2 skipped, 0 failures, 0 errors (baseline 384 + 4 nuevos).
- Cambios: `UsbTrustedReconnect.kt` +20/-9; `PinnedDesktopTlsTrustManagerTest.kt` +27/-0; `UsbTrustedReconnectTest.kt` +98/-0.

### [x] l2 — Lectura en dos fases

`TlsSessionFrameIoAdapter` con espera acotada por el umbral y deadline de frame. RED: `idleSessionDoesNotTimeOutWaitingForNextFrame`, `idleBeyondThresholdFailsAsPeerIdle`, `stalledFrameAfterFirstByteTimesOut`. ~250 líneas.

**Evidencia l2** (commit `fix(android): let idle TLS sessions wait for the next frame`):
- RED real, desvío declarado (mismo patrón que l1): las 3 pruebas nuevas referencian el parámetro `idleBudgetMillis` y el enum `TlsSessionFrameIoFailureReason`, que todavía no existían -- el módulo no compilaba (`Cannot find a parameter with this name: idleBudgetMillis`, `Unresolved reference: TlsSessionFrameIoFailureReason`).
- GREEN: `TlsSessionFrameIoAdapter.read()` ahora es de dos fases -- primero espera el/los primer(os) byte(s) del prefijo de longitud acotado por `idleBudgetMillis` (default 6000 ms, contrato §4.2); apenas llega alguno, el resto del prefijo + payload se completa dentro de un deadline nuevo derivado de `readTimeoutMillis` (default 5000 ms, sin cambios). El vencimiento del umbral ocioso cierra el canal y lanza `TlsSessionFrameIoException` con `reason = PEER_IDLE`; cualquier otra falla (incluido el deadline de frame una vez iniciado) usa `reason = GENERAL`.
- `UsbTrustedReconnect.helloFrameAdapter` pasa `idleBudgetMillis = helloTimeoutMillis` explícitamente para conservar su comportamiento de deadline único pre-l2 (si no, el default de 6000 ms del umbral ocioso superaría un `helloTimeoutMillis` corto, p. ej. los 300 ms de `reconnectTimesOutWithoutAccept`).
- Focalizado: `TlsSessionFrameIoAdapterTest` 7/7 verdes (corrido 3 veces por ser pruebas con hilos; sin flakiness); `UsbTrustedReconnectTest` 6/6 verdes (sin regresión en su propio deadline de hello).
- Full (`testDebugUnitTest --rerun-tasks`, `assembleDebug`, `lintDebug`): BUILD SUCCESSFUL. Totales XML: 391 tests, 2 skipped, 0 failures, 0 errors (388 + 3 nuevos).
- Cambios: `TlsSessionFrameIoAdapter.kt` +57/-12; `UsbTrustedReconnect.kt` +9/-2; `TlsSessionFrameIoAdapterTest.kt` +76/-0.

### [ ] l3 — KEEPALIVE y tracker

Tipo 10 en el codec y `SessionLivenessTracker`. RED: `roundTripsKeepaliveFrame`, `sendsKeepaliveAfterIntervalOfSilence`, `declaresDeadPeerAfterThreshold`, `receivingAnyFrameResetsPeerDeadline`. ~250 líneas.

Criterios: ninguna sesión se corta por esperar el próximo frame dentro del umbral; frames empezados se completan acotados; KEEPALIVE interoperable; tests de seguridad verdes; tests existentes verdes.

## Progreso

Plan creado el 2026-09-29.
l1 completada el 2026-09-29 (ver evidencia bajo la tarea).
l2 completada el 2026-09-29 (ver evidencia bajo la tarea). Siguiente: l3.
