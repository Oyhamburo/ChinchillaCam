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

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Último límite revisado: `08c905e`.

### [x] s1 — Flujo de pairing USB con canal vivo

Servicio de aplicación que compone las reglas de `PendingPairingCoordinator` (sin duplicarlas; refactor mínimo, p. ej. un verificador con canal) con `UsbTlsPairingProofVerifier` sobre `AccessoryIoSession` y `PhoneTlsIdentity`; el pendiente retiene el canal; confirmar persiste y activa y entrega el canal; rechazo/cancelación/vencimiento lo cierran. RED: `usbPairingKeepsLiveChannelUntilConfirm`, `usbPairingRejectClosesChannel`, `usbPairingExpiryClosesChannel`, `usbPairingConfirmPersistsTrustAndActivates`. ~350 líneas.

#### Evidencia s1

- **Corrección sobre los "verified facts" del encargo**: el harness JSSE con `needClientAuth = true` que exige certificado de cliente vive en `SslEngineUsbTlsChannelTest.kt` (tests de m1); el que responde el protocolo CCP1 (`readApplicationFrame`/`writeApplicationFrame`, status 0) vive en `UsbTlsPairingProofVerifierTest.kt`. Ninguno de los dos combina ambos rasgos por sí solo — `UsbTlsPairingProofVerifierTest.kt` no fija `needClientAuth`. El "fake desktop" de s1 combina ambos patrones en un harness nuevo dentro de `UsbPairingFlowTest.kt`.
- **RED observado** (falla de compilación, no de ejecución, porque `UsbPairingFlow` y `ChannelPairingProofVerifier` todavía no existían): al agregar sólo `UsbPairingFlowTest.kt` (sin tocar producción), `:android:usb-probe:compileDebugUnitTestKotlin` falló con, entre otras:
  ```
  e: .../UsbPairingFlowTest.kt:56:20 Unresolved reference: UsbPairingFlow
  e: .../UsbPairingFlowTest.kt:93:20 Unresolved reference: UsbPairingFlow
  e: .../UsbPairingFlowTest.kt:130:20 Unresolved reference: UsbPairingFlow
  e: .../UsbPairingFlowTest.kt:170:20 Unresolved reference: UsbPairingFlow
  e: .../UsbPairingFlowTest.kt:208:90 Unresolved reference: ChannelPairingProofVerifier
  e: .../UsbPairingFlowTest.kt:210:16 Unresolved reference: ChannelPairingProofVerifier
  BUILD FAILED
  ```
  (Un intento anterior de este mismo RED reveló primero un error propio del test, no del diseño: `PairingProofVerifier` es un `interface` plano, no un `fun interface`, así que la sintaxis SAM `PairingProofVerifier { _, _ -> ... }` no compila — "Interface PairingProofVerifier does not have constructors". Corregido con un `object : PairingProofVerifier` explícito antes de tomar el RED de arriba como válido.)
- **Diseño** (por qué no se duplican las reglas del coordinador): `PendingPairingCoordinator.start(qrPayload, proofBytes)` se refactorizó a un núcleo privado compartido `startCore(qrPayload, verify: (PairingProofChallenge) -> ProofOutcome)` que conserva intacta toda la validación existente (QR/trust-material, nonces, expiración, binding); sólo el paso de verificación cambia. Un nuevo overload público `start(qrPayload, session, channelVerifier: ChannelPairingProofVerifier)` reutiliza ese mismo núcleo pasándole `UsbTlsPairingProofVerifier.verify(challenge, session)` (misma firma exacta, referencia a método). El resultado `ChannelPairingStartResult(result, channel)` garantiza `channel != null` si y sólo si `result is PendingConfirmation`: cualquier rechazo posterior a una verificación exitosa (expiración tardía de qr/challenge/proof, `InvalidProofTime`, `BindingMismatch`) cierra el canal antes de retornar, así que ningún llamador necesita cerrar un canal rechazado. `PendingPairingCoordinator.confirm()` y `cancel()` quedan sin ningún cambio: `UsbPairingFlow` (archivo nuevo) retiene el canal vivo por `pendingId` y decide cerrarlo o entregarlo según el resultado —ya existente— de esas llamadas. Deliberadamente `UsbPairingFlow.confirm()` no pre-limpia expiración antes de invocar `coordinator.confirm()`: esa función ya re-verifica expiración y devuelve `Rejected.Expired` con precisión; adelantarse degradaría ese motivo a un `NoPendingPairing` genérico.
- **GREEN focused** (`--tests dev.chinchillacam.usbprobe.UsbPairingFlowTest --tests dev.chinchillacam.usbprobe.PendingPairingCoordinatorTest --rerun-tasks`), verde en el primer intento tras implementar producción, 3 corridas consecutivas: `BUILD SUCCESSFUL` cada vez; XML `UsbPairingFlowTest` → `tests="4" skipped="0" failures="0" errors="0"`; `PendingPairingCoordinatorTest` → `tests="22" skipped="0" failures="0" errors="0"` en las tres (las 22 pruebas existentes del camino por bytes, sin modificar, siguen verdes tras el refactor).
- **Los 4 RED requeridos, cubiertos por 4 tests** (dos de ellos con una aserción extra de bajo costo — no un quinto harness completo — para probar "un pendiente se consume una sola vez" sin duplicar infraestructura TLS):
  - `usbPairingKeepsLiveChannelUntilConfirm`: tras `start()`, el canal retenido intercambia datos de aplicación con el desktop falso (sigue vivo) antes de confirmar.
  - `usbPairingRejectClosesChannel`: `reject()` cierra el canal (`AccessoryIoSession`/`Closeable` cerrado); un segundo `reject()` devuelve `NoPendingPairing`.
  - `usbPairingExpiryClosesChannel`: con reloj mutable, avanzar más allá de `expiresAtEpochSeconds` y llamar a `expire()` (explícito) cierra el canal y `state()` pasa a `Idle`; un segundo `expire()` devuelve `false`.
  - `usbPairingConfirmPersistsTrustAndActivates`: `confirm()` persiste en `InMemoryTrustedDesktopStore`, activa en `ActiveDesktopAuthority`, y entrega el canal vivo (round-trip posterior confirma que sigue abierto); un segundo `confirm()` sobre el mismo `pendingId` devuelve `Rejected.NoPendingPairing` con canal `null`.
- **Fake desktop en proceso**: combina `needClientAuth = true` (exige certificado de cliente del teléfono) con el framing CCP1 (`readApplicationFrame`/`writeApplicationFrame`, status 0); identidades PKCS12 vía la misma receta `keytool` ya duplicada en otros tres archivos de test de este módulo (seguimiento no bloqueante `R2-duplicated-keytool-fixture`, registrado en `phone-mtls-identity.md`, no resuelto ahí — ésta es la cuarta duplicación consciente, no una nueva). Fallas del hilo servidor propagadas con `AtomicReference<Throwable?>` + `AtomicBoolean` de completitud, relanzadas y afirmadas tras `server.join(2000)` (no sólo `!isAlive`); pipes cerrados en cada test vía `AccessoryIoSession.close()`/`SslEngineUsbTlsEstablishedChannel.close()`.
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo (agregando todos los XML de `testDebugUnitTest`): 375 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (baseline `bfd41ec`: 371/2/0; +4 = los tests nuevos). `lintDebug`: 0 coincidencias de `UsbPairingFlow`/`PendingPairingCoordinator` en `lint-results-debug.xml`.
- **Líneas cambiadas**: 622 (98+19 en `PendingPairingCoordinator.kt`; 112 nuevas en `UsbPairingFlow.kt`; 393 nuevas en `UsbPairingFlowTest.kt`). Excede la heurística de ~400: sin recorte artificial ni "golfing". La producción real (`PendingPairingCoordinator.kt` + `UsbPairingFlow.kt`) son 229 líneas, cerca de la estimación original; el resto (393) es infraestructura de test SSLEngine/ByteBuffer de bajo nivel, del mismo estilo ya establecido en este módulo. No se dividió en dos commits: el único archivo de test permitido para el coordinador, `PendingPairingCoordinatorTest.kt`, sólo podía tocarse "si una prueba existente debe adaptarse" (no fue el caso — las 22 pasaron sin cambios), así que no había forma autorizada de dar cobertura directa e independiente al nuevo overload del coordinador separada de `UsbPairingFlow`; ambos viajan en un solo commit con su cobertura real.
- **Commit**: `feat(android): keep live TLS channel through USB pairing`.

#### Evidencia s1b

- **Defecto encontrado en readback del orquestador** (sobre commit `b3a52cc`, tras completarse s1): `UsbPairingFlow.start` reemplazaba `held` incondicionalmente en cada llamada — `held = if (summary != null) HeldChannel(...) else null` —, así que un segundo `start()` mientras el primer pendiente seguía vigente (`Rejected.AlreadyPending`, sin canal propio) perdía la referencia al canal vivo del primero sin cerrarlo (fuga). Un `confirm(primerPendingId, ...)` posterior obtenía `Activated` del coordinador (confianza ya persistida, activación ya hecha), y el `requireNotNull(ours)` posterior lanzaba excepción sin devolver nunca el canal — es decir, después de esos efectos de lado, no antes.
- **RED observado** (`secondStartWhilePendingKeepsFirstChannelForConfirm`, contra el código anterior al fix): `IllegalArgumentException: an activated pairing must have a retained live channel` en `UsbPairingFlow.kt:76`, disparada desde el `flow.confirm(...)` del test (`UsbPairingFlowTest.kt:242`). El resto del focused run (26 tests: 4 de `UsbPairingFlowTest` + 22 de `PendingPairingCoordinatorTest`) siguió en verde — la falla estuvo acotada al escenario nuevo.
- **GREEN**: en `start()`, `held` sólo se reemplaza cuando el resultado es una nueva `PendingConfirmation`; cualquier rechazo (incluido `AlreadyPending`) deja `held` intacto. No hace falta cerrar nada aparte en ese caso: por construcción no puede haber otro pendiente vigente distinto en simultáneo (el coordinador rechaza con `AlreadyPending` antes de tocar `pending`), y un `held` desactualizado ya fue cerrado por `expireIfNeeded()` al entrar. En `confirm()`, el `requireNotNull(ours)` posterior a los efectos de lado se reemplazó por `ours?.channel`: bajo ownership exclusivo del coordinador ese `null` es inalcanzable en un `Activated` (queda documentado como invariante en el KDoc de `UsbPairingFlow` y de `UsbPairingConfirmOutcome`), pero si alguna vez se violara, la función devuelve el canal en `null` en lugar de lanzar después de que el coordinador ya persistió y activó.
- **Documentación**: el KDoc de `start()` deja explícito que `session` en un intento rechazado — incluido `AlreadyPending`, que retorna antes de invocar `channelVerifier` — no se toca; su ownership y ciclo de vida quedan del lado del llamador.
- **Focused** (`--tests dev.chinchillacam.usbprobe.UsbPairingFlowTest --tests dev.chinchillacam.usbprobe.PendingPairingCoordinatorTest --rerun-tasks`), verde tras el fix, 3 corridas consecutivas: `BUILD SUCCESSFUL` cada vez; XML `UsbPairingFlowTest` → `tests="5" skipped="0" failures="0" errors="0"` (los 4 tests de s1 sin modificar + el nuevo); `PendingPairingCoordinatorTest` → `tests="22" skipped="0" failures="0" errors="0"` en las tres.
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo: 376 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (baseline s1 `b3a52cc`: 375/2/0; +1 = el test nuevo). `lintDebug`: 0 coincidencias de `UsbPairingFlow`/`PendingPairingCoordinator` en `lint-results-debug.xml`.
- **Líneas cambiadas**: 102 (37+11 en `UsbPairingFlow.kt`; 54 nuevas en `UsbPairingFlowTest.kt`).
- **Commit**: `fix(android): keep pending USB pairing channel on rejected restart`.

### [ ] s2 — SessionFrame sobre TLS

`TlsSessionFrameIoAdapter` sobre `SslEngineUsbTlsEstablishedChannel` con el framing de §4.3. RED: `roundTripsSessionFrameOverTls`, `rejectsOversizedLengthBeforeAllocating`, `rejectsZeroLength`, `truncatedFrameClosesChannel`. ~250 líneas.

### [ ] s3 — Reconexión confiable

`UsbTrustedReconnect` con SPKI pinneado del store, certificado de cliente, `HANDSHAKE_HELLO` → `HANDSHAKE_ACCEPT` y activación. RED: `reconnectsToTrustedDesktopAfterAccept`, `reconnectRejectedByDesktopFailsClosed`, `reconnectTimesOutWithoutAccept`, `revokedDesktopIsNotReconnected`. ~350 líneas.

### [ ] s4 — Prueba cruzada de la sesión completa

Requiere autorización fresca del usuario: pairing + confirmación + reconexión + hello contra el helper desktop. ~150 líneas.

Criterios: ningún camino entrega un canal sin confirmación o aceptación; todo rechazo cierra el canal; framing acotado y fail closed; tests existentes verdes.

## Progreso

Plan creado el 2026-09-28. s1 completada el 2026-09-28 (ver Evidencia s1): `UsbPairingFlow` compone `PendingPairingCoordinator` (refactor mínimo y aditivo: núcleo `startCore` compartido + overload `start(qrPayload, session, channelVerifier)` + `ChannelPairingProofVerifier`/`ChannelPairingStartResult` nuevos) con `UsbTlsPairingProofVerifier`; canal vivo retenido hasta confirmar, cerrado en rechazo/cancelación/vencimiento (explícito o en la siguiente interacción), entregado al llamador sólo en `Activated`. Los 22 tests existentes de `PendingPairingCoordinatorTest` (camino por bytes) siguen verdes sin modificarse. Corrección s1b el 2026-09-28 (ver Evidencia s1b, defecto hallado en readback del orquestador sobre `b3a52cc`): un segundo `start()` con un pendiente vigente ya no descarta el canal retenido del primero (antes se perdía sin cerrarse); `confirm()` ya no puede lanzar `IllegalArgumentException` tras persistir confianza y activar — degrada a canal `null` si la invariante de ownership exclusivo se violara. Pendiente: s2 (SessionFrame sobre TLS), s3 (reconexión confiable), s4 (prueba cruzada, requiere autorización fresca del usuario).
