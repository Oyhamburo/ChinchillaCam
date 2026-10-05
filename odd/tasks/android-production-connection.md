# Composición de producción Android + UI de vinculación y conexión

## 1. Objetivo

Que el teléfono, sin herramientas de desarrollo, pueda: escanear el QR de vinculación del desktop, confirmar un código corto, recordar PCs confiables, conectarse/desconectarse por USB y transmitir la cámara a través de la sesión autenticada (USB AOA → TLS → sesión → cámara/encoder en el foreground service), con estado visible en español.

## 2. Problema

Todas las piezas de dominio existen (pairing, reconexión, `SessionRuntime`, `SessionEgressServicePipelineComposition`, store persistente de desktops), pero nada en `src/main` las compone:

- no hay código corto de confirmación (SAS) en ningún lado;
- tras un pairing confirmado no hay forma de iniciar la sesión (`HANDSHAKE_HELLO`/`ACCEPT`) sobre el mismo canal: el intercambio vive privado en `UsbTrustedReconnect`;
- no hay dueño a nivel de proceso del canal/sesión ni de `ActiveDesktopAuthority` (en memoria), y nadie llama `stopActiveDesktop`;
- el service construye el controlador sin `encodedVideoSinkFactory` (el video se descarta);
- no hay escáner QR ni UI de vinculación/conexión; `UsbProbeActivity` sólo hace el smoke AOA.

## 3. Decisiones

Fuente: `odd/tasks/complete-webcam-product.md`, actualización 2026-10-03 (UI del teléfono delegada al orquestador: mínima, español, sin cuentas ni nube). Decisiones tomadas por el orquestador dentro de esa delegación:

1. **Código corto (SAS) v1.** `SHA-256("CHINCHILLACAM-SAS-v1" ‖ len32(desktopSpki) ‖ desktopSpki ‖ len32(phoneSpki) ‖ phoneSpki ‖ len32(qrNonce) ‖ qrNonce ‖ len32(challengeNonce) ‖ challengeNonce)`, longitudes como entero big-endian de 4 bytes; los primeros 4 bytes como entero sin signo big-endian módulo 1 000 000, mostrado con 6 dígitos como `123 456`. Ambos lados conocen las cuatro entradas (el desktop ve el SPKI del teléfono por mTLS y ambos nonces por CCP1). El SAS no reemplaza al pin del QR: agrega la comparación humana que protege la confianza del lado desktop frente a un tercero que haya visto el QR. Vector de prueba fijo compartido con el desktop (paso 2).
2. **Inicio de sesión tras el pairing.** Sobre el mismo canal vivo confirmado el teléfono envía `HANDSHAKE_HELLO` y espera `HANDSHAKE_ACCEPT` con un plazo largo (hasta el vencimiento del pending, porque el usuario puede confirmar en la computadora después). Mismo intercambio que la reconexión, extraído a una función compartida. El desktop implementará el lado receptor en el paso 2.
3. **Conexión explícita.** "Conectar" se elige por PC confiable de la lista (el pin TLS depende del desktop elegido); sin auto-conexión en esta unidad. "Desconectar" cierra la sesión, libera `ActiveDesktopAuthority` y detiene la cámara.
4. **Escáner QR con ZXing core 3.3.3** (`com.google.zxing:core`, Apache-2.0, Java puro sin dependencias transitivas, compatible con `minSdk 23`), decodificando el plano Y de Camera2 `ImageReader`; alternativa manual "Pegar código" para el texto `CHINCHILLACAM-PAIR:v1:…` que mostrará el desktop. El escaneo usa la cámara sólo con la pantalla visible y antes de transmitir.
5. **Activity de producto nueva** `ConnectionActivity` (launcher, recibe `USB_ACCESSORY_ATTACHED`); `UsbProbeActivity` queda como diagnóstico no exportado abierto desde un botón. Filtro de accesorio sin cambios (renombrado en T29).
6. **Cámara usada:** la preferencia persistida existente (`CameraSelectionPreference`); si no hay, el primer candidato directo trasero del catálogo; si no hay ninguno, error visible.

Sin cambios de política: fin de sesión → la cámara se detiene y se avisa (ya implementado en la composición); sin red, sin cuentas, sin nube.

## 4. Contrato

1. `PairingShortCode.derive(desktopSpki, phoneSpki, qrNonce, challengeNonce)` → código de 6 dígitos según §3.1, con vector de prueba fijo documentado aquí.
2. `SessionHelloExchange` compartido: `UsbTrustedReconnect` lo usa sin cambio de comportamiento; nueva entrada que inicia la sesión sobre el canal devuelto por `UsbPairingFlow.confirm` y produce el mismo `Reconnected` (sin volver a activar en `ActiveDesktopAuthority`, ya activado por `confirm`), con plazo configurable; rechazo/timeout → cierre del canal y error tipado.
3. `PhoneConnectionController` (dominio puro, puertos inyectables, un solo hilo de trabajo): estados `Idle`, `AwaitingAccessory`, `Pairing(code, desktopName)`, `AwaitingDesktopConfirmation`, `Connecting(desktop)`, `Connected(desktop)`, `Failed(mensaje)`; operaciones `qrScanned`, `accessoryAttached`, `confirmPairing`, `rejectPairing`, `connect(desktopId)`, `disconnect`, `forget(desktopId)`. Al conectar arranca la composición de sesión y pide al service iniciar la cámara con la fábrica de sink de la sesión; al desconectar o al terminar la sesión libera la autoridad y cierra todo fuera del hilo principal. Estado observable (listener) además de snapshot.
4. El service obtiene la fábrica de sink desde un registro de proceso (`ActiveSessionRegistry`) cuando arranca en modo sesión; arranque sin sesión sigue funcionando para diagnóstico. Todo callback nuevo queda cableado en producción (verificado por test de contrato).
5. `QrLuminanceDecoder`: plano de luminancia → texto QR o "sin QR"; escáner Camera2 delgado sin test JVM.
6. `ConnectionScreenPlanner` (puro, testeado) → textos/botones en español por estado; `ConnectionActivity` sólo renderiza el plan.

## 5. Riesgos

- Dependencia nueva (ZXing): versión fija, sólo `core`, sin red ni permisos.
- Canal retenido durante la espera del ACCEPT: plazo acotado y cierre explícito.
- `close()` de la sesión hace `join`: siempre fuera del hilo principal.
- Sin validación física (M9): AOA real, cámara, lifecycle.
- Interop con el desktop pendiente hasta el paso 2 (SAS y hello post-pairing).

## 6. Reglas de ejecución

TDD estricto con RED observado (fuente: `odd/tasks/complete-webcam-product.md`). Commits de work unit ≤400 líneas con tests y docs. Writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push de la rama autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 435 tests, 2 omitidos, 0 fallos (HEAD `bf7caae`).

1. [x] c1 — `PairingShortCode` (SAS v1) con vector fijo. RED: `shortCodeMatchesFixedVector`. ~150 líneas.

   **Evidencia c1**
   - Vector fijo (oráculo calculado aparte con Python): `desktopSpki` = 91 bytes `0x10 + i`; `phoneSpki` = 91 bytes `0x80 + i`; `qrNonce` = 16 bytes `0xA5`; `challengeNonce` = 32 bytes `0x5A`. Digest `7371dabe90866b11a3e505c78991d0fe7699818a70029299bfc6b8de7d306d91` → `841406` (`841 406`). Con los SPKI intercambiados → `418534`.
   - RED (stub compilable que devuelve vacío): 4 tests, 4 fallos; `shortCodeMatchesFixedVector`: `org.junit.ComparisonFailure: expected:<[841406]> but was:<[]>`; `emptyInputIsRejected`: `expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown`.
   - GREEN: `PairingShortCodeTest` 4 tests, 0 fallos (`shortCodeMatchesFixedVector`, `swappingSpkiRolesChangesCode`, `leadingZerosArePadded` vía `fromValue(42)` → `000 042`, `emptyInputIsRejected`).
   - Runner completo: BUILD SUCCESSFUL; 439 tests, 2 omitidos, 0 fallos (baseline 435 + 4); `assembleDebug` y `lintDebug` OK.
2. [x] c2 — `SessionHelloExchange` compartido + inicio de sesión tras pairing confirmado. RED: `confirmedPairingChannelStartsSessionAfterDesktopAccept`. ~300 líneas.

   **Evidencia c2**
   - RED (fallo de compilación contra tipos ausentes): `PairedSessionStarterTest.kt:33:41 Unresolved reference: PairedSessionStartResult` (más 60:42, 79:26, 89:21) y `PairedSessionStarterTest.kt:155:22 Unresolved reference: PairedSessionStarter`.
   - GREEN: `PairedSessionStarterTest` 3 tests, 0 fallos (`confirmedPairingChannelStartsSessionAfterDesktopAccept`, `desktopRejectClosesChannel`, `silentDesktopTimesOutAndClosesChannel`); `UsbTrustedReconnectTest` 8 tests, 0 fallos, sin cambios. Set focalizado corrido 3× con `--rerun-tasks`: estable.
   - Runner completo: BUILD SUCCESSFUL; 442 tests, 2 omitidos, 0 fallos (439 + 3); `assembleDebug` y `lintDebug` OK.
   - Diseño: `SessionHelloExchange` (internal) recibe `phoneId` y `helloTimeoutMillis` y usa ese único valor como idle budget y como plazo de frame del adapter del hello, así que los 120 s por defecto no quedan recortados por los 5 s/6 s por defecto de `TlsSessionFrameIoAdapter` (el transporte no tiene timeout propio). Resultado: `Accepted`, `Rejected` (reutiliza `DesktopRejected`, `UnexpectedFrame`, `InvalidHandshakeAccept`, `TimedOut`) o `Failed(detail)`; `UsbTrustedReconnect` mapea `Failed` a `TlsRejected` como antes. `PairedSessionStarter(phoneTlsIdentity, helloTimeoutMillis = 120_000, sessionFrameAdapter)` devuelve `Started(Reconnected)`, `Rejected(rejection)` o `ExchangeFailed(desktopId, detail)`; no toca `ActiveDesktopAuthority` y cierra el canal ante cualquier rechazo (el llamador libera la autoridad). El test del desktop mudo verifica que gobierna el plazo configurado (300 ms), muy por debajo de los defaults del adapter.
   - Desvíos: no hay test con espera > 5 s (sería lento); el plazo largo se cubre leyendo el camino de código. Sin reloj inyectado: no hace falta. Las corridas 3× usaron `--rerun-tasks` para evitar UP-TO-DATE.
3. [ ] c3 — `PhoneConnectionController` (pairing y conexión, estado observable, desconexión libera autoridad). RED: `scannedQrPairsConfirmsAndConnects`. ~400 líneas.
4. [ ] c4 — Registro de sesión de proceso + service en modo sesión + adaptadores de producción de los puertos. RED: `serviceUsesSessionSinkFactoryWhenSessionActive`. ~300 líneas.
5. [ ] c5 — ZXing + `QrLuminanceDecoder` + escáner Camera2. RED: `decodesGeneratedPairingQr`. ~250 líneas.
6. [ ] c6 — `ConnectionScreenPlanner` + `ConnectionActivity` + manifest. RED: `pendingPairingShowsShortCodeAndConfirmButtons`. ~400 líneas.

## Progreso

Plan creado el 2026-10-03 (HEAD `bf7caae`).
- c1 completada: `PairingShortCode` (SAS v1) con vector fijo; 439 tests, 2 omitidos, 0 fallos. Commit `45cc119`.
- c2 completada: `SessionHelloExchange` compartido y `PairedSessionStarter` (HELLO/ACCEPT sobre el canal confirmado, plazo por defecto 120 s); 442 tests, 2 omitidos, 0 fallos.
