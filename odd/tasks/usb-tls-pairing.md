# Pairing TLS sobre USB AOA (Android y desktop)

Este documento reúne los registros de las dos ramas, integradas en `main` el 2026-10-07 (`odd/tasks/branch-integration.md`). Cada parte conserva su evidencia original sin cambios.

## Parte Android (rama `feat/t15c-fake-usb-sustained`)

## Pairing TLS sobre USB AOA

### Alcance M3e

Construir el transporte mínimo para llevar TLS estándar sobre el canal USB Accessory existente sin usar LAN, ADB, hardware nuevo ni `localhost` productivo en el teléfono. El objetivo es que la prueba de posesión `CCP1` y los futuros `SessionFrame` viajen dentro de TLS, nunca como eco cleartext por USB.

### Reglas

- El stream cleartext existente `0x01020304` queda reservado para `SessionFrame` no TLS legacy/test; el nuevo stream TLS usa `0x01020305`.
- Cada `AccessoryFrame` del stream TLS transporta bytes opacos de ciphertext TLS, no mensajes de aplicación.
- No se asume correspondencia 1:1 entre TLS records y frames USB: JSSE/rustls pueden fragmentar o agregar.
- Cada chunk ciphertext debe ser no vacío y medir como máximo 32768 bytes; el frame USB total queda acotado por 32776 bytes.
- Cualquier stream incorrecto, frame vacío, oversized, truncado o malformado falla cerrado y cierra la sesión.
- El buffer pendiente total de ciphertext para el futuro `SSLEngine` debe quedar acotado a 65536 bytes.
- No se acepta `CCP1` raw/cleartext como prueba de posesión.

### Tareas

#### [x] e1 — Adaptador USB de ciphertext acotado

Agregar un adaptador pequeño sobre `AccessoryIoSession`/`AccessoryFrameCodec` que lea y escriba chunks TLS opacos en el stream `0x01020305`, con límites estrictos, cierre fail-closed y tests JVM sin hardware.

Estado (2026-09-28): implementado en `6a5349c feat(android): frame tls ciphertext over usb`.

Evidencia:

- RED: el test enfocado falló antes del adaptador por referencias no resueltas a `UsbTlsCiphertextIoAdapter`, resultados tipados y constantes TLS USB.
- GREEN enfocado: `:android:usb-probe:testDebugUnitTest --tests dev.chinchillacam.usbprobe.UsbTlsCiphertextIoAdapterTest --rerun-tasks` pasó.
- Full: `:android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó.
- Revisión independiente exacta: PASS sobre el candidato staged; verificó sólo los 3 archivos permitidos, 356 líneas, stream `0x01020305`, split outbound `<=32768`, total write `<=65536`, cierres fail-closed, sin `CCP1` raw ni wiring UI/LAN.

#### [x] e2 — TLS pinneado con `SSLEngine` y `CCP1`

Estado e2 (2026-09-28): completado en e2a1 `fb4ada9`, e2a2 `63f1b05`, e2b1 `6517121` y e2b2 `3433ac7`. Sólo e2b2 quedó cubierto por revisión nativa (`review-caa7ac895634660b`); e2a1, e2a2 y e2b1 no tienen revisión nativa.

Alcance original: con autorización fresca, montar `SSLEngine` cliente sobre el adaptador e1, pinear el SPKI del QR con `PinnedDesktopTlsTrustManager`, completar handshake TLS 1.2+ sin socket/localhost y rechazar peers no esperados. e2a no confirma pairing ni envía `CCP1`; e2b requerirá autorización fresca para llevar `CCP1` dentro del canal TLS establecido. Esta tarea no debe usar `localhost` como ruta productiva en teléfono.

Evidencia e2a1 (2026-09-28): `fb4ada9 feat(android): open pinned USB TLS channel` abre un canal TLS autenticado por SPKI y conserva el `SSLEngine`/sesión vivos para e2b. RED enfocado: referencias no resueltas antes de crear el canal. GREEN enfocado y full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasaron; revisión independiente exacta PASS con 370 líneas cambiadas y sin `CCP1`, pairing confirmation, sockets, LAN ni hardware. Native START sobre el rango exacto falló por consent binding stale/expired, sin invocación nativa ni lineage; no hay ACK e2a1.

Estado e2a2: en progreso para pruebas fail-closed de deadline absoluto, read/write error, overflow/step-limit si cabe; e2a2 debe conservar el canal vivo para e2b y no autenticar el teléfono por handshake solo.

Evidencia e2a2 (2026-09-28): se agregaron pruebas fail-closed para deadline de lectura bloqueada, error de escritura USB y límite de pasos. Desviación TDD: el RED no se capturó por separado antes de los cambios; no se reclama TDD estricto para e2a2. GREEN enfocado y full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasaron. El cambio no modifica producción porque los tests pasaron sobre `fb4ada9`; se evitó editar source sin necesidad.

Estado e2b1: completado en `6517121 fix(android): harden USB TLS app data`. Endurece el API app-data del canal TLS vivo: deadline absoluto fail-closed, `NEED_WRAP` con `engine.wrap(empty)`, límite `maxBytes <= 64KiB`, guard de no progreso en write y cierre/shutdown en fallas, sin `CCP1`, pairing confirmation, UI, LAN ni hardware.

Evidencia e2b1: diff candidate 396 líneas por numstat y commit 393 inserciones/3 borrados. Focused incremental RED válido: `expiredApplicationReadDeadlineClosesBeforeReturningPendingPlaintext` y `applicationWriteRejectsEngineNoProgress` fallaron antes del fix. GREEN enfocado pasó. Full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó local e independiente. Verificador independiente leyó source y confirmó wrap real, deadlines antes/después, cap de `maxBytes`, guard de no progreso y cierre fail-closed. Los borradores e2b2 fueron restaurados de `.kt.pending` a `.kt` con SHA-256 `4cc08c5330d0179b60c417aab1e41ddb8462fdcbd0d573d90b4321724a2c34eb` y `50d57bcc2bd4461642db9951ff4410171d83f6493901a417de5734cb8770422d`; `.tmp-e2b-quarantine` quedó vacío y sin limpieza.

Nota de disciplina e2b1: decisión de usuario `accept_one_e2b1_tdd_exception`. No hubo RED histórico válido para el primer bloque de e2b1; el primer fallo enfocado fue de setup de prueba (pipe no conectado). No se reclama TDD estricto para ese bloque. Incidente de aislamiento: antes de la instrucción exacta `.kt.pending`, los borradores e2b2 se movieron inicialmente a `.tmp-e2b-quarantine/...`; luego se movieron a los nombres same-path `.kt.pending` autorizados, con SHA-256 preservado.

Estado e2b2: completado en `3433ac7 feat(android): verify USB TLS pairing proof`. Verifica `CCP1` dentro del mismo canal TLS vivo y devuelve `Verified(proof, channel)` sin cerrar el canal; cubre app-data post-prueba sobre la misma conexión. Rechaza fail-closed challenge inválido con close best-effort, status CCP1 nonzero y eco status0 incorrecto, sin afirmar autenticación del teléfono.

Evidencia e2b2: RED test-only fresco con source SHA `4cc08c5330d0179b60c417aab1e41ddb8462fdcbd0d573d90b4321724a2c34eb`: `rejectsExpiredChallengeWhenUsbCloseThrowsIOException` falló porque IOException en close enmascaraba `Rejected`; status nonzero ya pasaba. GREEN enfocado `UsbTlsPairingProofVerifierTest` pasó, luego full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó local e independiente. Verificador independiente confirmó 391 líneas <=400, live-channel status0, close best-effort, status nonzero, wrong echo y ausencia de UI/LAN/hardware/native ACK.

#### [x] e3 — Interoperabilidad socketless Rust ↔ JVM

Estado e3: completado para la prueba JVM opt-in en `test(android): prove socketless USB TLS interop` (sólo tests; sin cambios de producción). Ejecuta el helper Rust existente por stdio, lee sólo las dos líneas públicas (`CHINCHILLACAM-USB-INTEROP:v1` y `qr=...`) con lector raw sin prefetch, y luego usa los mismos pipes como `AccessoryIoSession` para que JSSE y rustls completen handshake y `CCP1` sobre el stream USB TLS `0x01020305`. Sin TCP, LAN, ADB, hardware, UI, push ni afirmación de autenticación del teléfono. La prueba requiere env explícito `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER`, omite sólo si está unset/blank, falla si está seteado pero no es ruta absoluta/ejecutable, y acepta `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER_SHA256` sólo como verificación opcional del binario invocado.

Evidencia e3: test-only characterization; no se reclama RED funcional para el cruce JVM↔rustls porque el helper y el cliente ya existían. Helper opt-in verificado en `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink/desktop/usb-probe/target/debug/usb_pairing_proof_stdio_helper` con SHA-256 `384cad52ef84a20d0880a389ea06fc57d79bfb328f5e15c0c877380c945b3272`. Focused opt-in `:android:usb-probe:testDebugUnitTest --tests dev.chinchillacam.usbprobe.SocketlessUsbPairingProofInteropTest --rerun-tasks` pasó con XML `tests=1 skipped=0 failures=0 errors=0`. Full sin env explícito `:android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó, con skip esperado para la prueba opt-in. Verificador independiente exacto confirmó focused opt-in PASS/XML `1/0/0/0`, lector raw sin `BufferedReader`, skip sólo sin env, fallos para env inválido, hash sólo opcional, `waitFor` antes de cerrar canal, watchdog/cleanup acotados y candidato `174` líneas <=400.

Spot check de retoma (2026-09-28, sesión nueva tras corte por límite de uso): helper con SHA-256 `384cad52…3272` sin cambios; focused opt-in con `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER` y `..._SHA256` seteados pasó con XML `tests=1 skipped=0 failures=0 errors=0`; full sin env re-ejecutado antes del commit.

### Revisión nativa e2b2 + e3

Rango `520416c..4263e6d` (e2b2 `3433ac7` + `1895f46` + e3 `4263e6d`, 4 archivos, 573 líneas). Assess `high` (`process_boundary` en el test interop). Consentimiento `granted` por el usuario; lineage `review-caa7ac895634660b`, 4 lentes (risk, resilience, readability, reliability), `approved` sin corrección; acknowledgement `gentle-ai.review-acknowledged/v1` con autoridad `burned`. Nuevo límite revisado: `4263e6d`.

Hallazgos no bloqueantes (seguimiento; requieren decisión antes de convertirse en tareas):

- `R3-server-thread-assertions-swallowed` (WARNING, `UsbTlsPairingProofVerifierTest.kt:95-114`): la aserción de app-data post-proof corre en un thread y su fallo no llega a JUnit; `isAlive == false` también se cumple si el thread murió.
- `R3-handshake-reject-session-close-unproved` (WARNING, `UsbTlsPairingProofVerifier.kt:23-26`): ningún test prueba que un handshake rechazado (pin SPKI incorrecto) cierre la `AccessoryIoSession`.
- `R2-001` (WARNING, `UsbTlsPairingProofVerifier.kt:78-82`): offsets de header CCP1 hardcodeados, duplican conocimiento de `PairingProofProtocol`; longitud negativa reportada como "too large".
- `R3-uncovered-rejection-branches`, `R3-interrupt-swallowed`, `R4-opaque-proof-failure-reason`, `R2-002`, `R2-003`, `R2-004`, `R2-005` (SUGGESTION): ramas de rechazo sin test, `InterruptedException` sin restaurar el flag, razón única "tls proof failed", catch redundante, chequeo de expiración duplicado sin nota, comentario de "typed reason" engañoso, ruta absoluta local en la evidencia e3.

## Parte desktop (rama `feat/desktop-video-sink`)

## USB TLS pairing

### Objetivo

Llevar los bytes TLS de rustls sobre el transporte USB AOA existente sin abrir LAN ni reclamar hardware en este corte.

### Tareas

1. [x] M3e1: implementar un adaptador `Read`/`Write` acotado para ciphertext TLS sobre `FramedUsbStream`, usando `stream_id` `0x01020305`, chunks no vacíos de hasta 32768 bytes y buffer pendiente máximo de 65536 bytes.
2. [x] M3e2: ejecutar CCP1 proof dentro de rustls sobre el stream USB en memoria, sin listener TCP ni fake proof-of-possession. M3e2c devuelve el stream TLS vivo después de status0 y refuerza deadline absoluto post-read/post-write.
3. [x] M3e3: preparar prueba cross-language in-memory con Android. Slice desktop helper stdio agregado para exponer QR y luego transportar TLS-over-AOA por stdin/stdout sin TCP/LAN/hardware; integración Android cross-language completada en la rama Android (ver Evidencia M3e3).

### Restricciones

- No LAN, no hardware claims, no merge/cherry-pick/push.
- `.codegraph/` preexistente queda intacto.
- `0x01020304` queda reservado para SessionFrame claro; `0x01020305` queda reservado para TLS-over-AOA.
- El stream TLS es opaco y no implica un TLS record por BulkFrame.

### Evidencia M3e1

- Commit: `e835b53358941b190a590b148867bca89cc6ec7c` (`feat(desktop): add USB TLS ciphertext stream`).
- RED/GREEN: pruebas focused del adaptador guiaron la implementación y los readbacks de seguridad; resultado final `cargo test --test usb_tls_ciphertext_stream_test` PASS, 8 tests.
- Formato y suite completa: `cargo fmt -- --check && cargo test` PASS.
- Verificación independiente: PASS; confirmó stream id `0x01020305`, chunks no vacíos `<=32768`, pending read `<=65536`, lectura corta sin bloqueo, poison fail-closed para read/write/flush, sin `Clone`, sin API de extracción, sin TCP/LAN/hardware.
- Tamaño: 351 inserciones, dentro del límite de 400 líneas cambiadas.

### Evidencia M3e3

- RED: `cd desktop/usb-probe && ~/.cargo/bin/cargo test --test usb_pairing_proof_stdio_helper_test` falló con `CARGO_BIN_EXE_usb_pairing_proof_stdio_helper` no definido porque el binario aún no existía, exit code 101.
- GREEN: `cd desktop/usb-probe && ~/.cargo/bin/cargo test --test usb_pairing_proof_stdio_helper_test` PASS, 1 test; full `cargo fmt -- --check && cargo test` PASS; verificación independiente PASS.
- Alcance: helper stdio emite `CHINCHILLACAM-USB-INTEROP:v1`, `qr=CHINCHILLACAM-PAIR:v1:...`, flush antes de modo binario, y conecta `UsbTlsPairingProofServer::complete_handshake_and_pairing_proof` sobre `UsbTlsCiphertextStream<StdioUsbBulkIo>`/`FramedUsbStream` sin TCP/LAN/hardware ni persistencia de confianza.
- Commit desktop helper: `7be0fda2a643e2b378d177cb99dfcdff5984ffb3` (`feat(desktop): add USB pairing stdio helper`).
- Integración Android (2026-09-28): rama `feat/t15c-fake-usb-sustained`, commit `4263e6d` (`test(android): prove socketless USB TLS interop`). La prueba JVM opt-in `SocketlessUsbPairingProofInteropTest` ejecuta este helper (binario debug con SHA-256 `384cad52ef84a20d0880a389ea06fc57d79bfb328f5e15c0c877380c945b3272`), completa handshake JSSE↔rustls y `CCP1` status0 sobre el stream `0x01020305`; XML `tests=1 skipped=0 failures=0 errors=0`. Revisión nativa del lado Android: `review-caa7ac895634660b`, aprobada y reconocida. Este corte desktop sigue sin revisión nativa propia.

### Evidencia M3e2

- Handshake commit: `d2c5749d86a782f4a3a360f43a925f7fc95896c2` (`feat(desktop): handshake USB TLS pairing proof`). Focused `cargo test --test usb_tls_pairing_proof_test` PASS (2 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS.
- CCP1 proof commit: `c58c384029b3b131e1a090c5eeeb26a41ef198d2` (`feat(desktop): prove pairing over USB TLS`). Focused `cargo test --test usb_tls_pairing_proof_test` PASS (4 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS. TDD caveat: este corte no conserva evidencia histórica RED registrada; no debe inventarse.
- Cobertura parcial: status0 exact response dentro del mismo stream rustls, nonce QR de un solo uso, replay/expired/wrong nonce denegados, raw CCP1 sin TLS denegado.
- Recuperación M3e2c: el usuario autorizó restaurar exactamente `/tmp/m3e2c-usb_tls_pairing_proof-source.patch` con sha256 `4942b5dc4bd8c3680bb093b6d1b4abbf6c98b6a2179db3873941a1507f197c18`; se verificó que tocaba sólo `desktop/usb-probe/src/usb_tls_pairing_proof.rs`, que `git apply --check` pasaba y se aplicó una vez con `git apply`.
- RED M3e2c test-only observado contra HEAD: `cd desktop/usb-probe && ~/.cargo/bin/cargo test --test usb_tls_pairing_proof_test` falló con `E0599` porque el valor devuelto era `()` y no tenía `read_exact`, `write_all` ni `flush` (`tests/usb_tls_pairing_proof_test.rs:92/95/96`), exit code 101. La late-final-bytes test estaba agregada, pero la compilación se cortó primero por el RED de live stream.
- M3e2c commit: `c3c07023b8206160f52efa9c625d613cf8adf8ff` (`fix(desktop): keep USB TLS proof stream alive`). GREEN: `complete_handshake_and_pairing_proof` devuelve el `StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>` vivo tras CCP1 status0, permitiendo tráfico de aplicación cifrado posterior en la misma conexión TLS. También comprueba deadline absoluto después de `complete_io`, después de cada read, antes de consumir nonce, y antes/después de write/flush.
- Verificación M3e2c: focused `cargo test --test usb_tls_pairing_proof_test` PASS (6 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS.
- Alcance: sin TCP/listener, sin LAN, sin hardware claims, sin persistencia de confianza de teléfono.
- Tamaño M3e2c: 135 inserciones / 15 borrados en 3 archivos, dentro del límite de 400 líneas cambiadas.
