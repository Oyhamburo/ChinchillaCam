# Pairing TLS sobre USB AOA

## Alcance M3e

Construir el transporte mínimo para llevar TLS estándar sobre el canal USB Accessory existente sin usar LAN, ADB, hardware nuevo ni `localhost` productivo en el teléfono. El objetivo es que la prueba de posesión `CCP1` y los futuros `SessionFrame` viajen dentro de TLS, nunca como eco cleartext por USB.

## Reglas

- El stream cleartext existente `0x01020304` queda reservado para `SessionFrame` no TLS legacy/test; el nuevo stream TLS usa `0x01020305`.
- Cada `AccessoryFrame` del stream TLS transporta bytes opacos de ciphertext TLS, no mensajes de aplicación.
- No se asume correspondencia 1:1 entre TLS records y frames USB: JSSE/rustls pueden fragmentar o agregar.
- Cada chunk ciphertext debe ser no vacío y medir como máximo 32768 bytes; el frame USB total queda acotado por 32776 bytes.
- Cualquier stream incorrecto, frame vacío, oversized, truncado o malformado falla cerrado y cierra la sesión.
- El buffer pendiente total de ciphertext para el futuro `SSLEngine` debe quedar acotado a 65536 bytes.
- No se acepta `CCP1` raw/cleartext como prueba de posesión.

## Tareas

### [x] e1 — Adaptador USB de ciphertext acotado

Agregar un adaptador pequeño sobre `AccessoryIoSession`/`AccessoryFrameCodec` que lea y escriba chunks TLS opacos en el stream `0x01020305`, con límites estrictos, cierre fail-closed y tests JVM sin hardware.

Estado (2026-09-28): implementado en `6a5349c feat(android): frame tls ciphertext over usb`.

Evidencia:

- RED: el test enfocado falló antes del adaptador por referencias no resueltas a `UsbTlsCiphertextIoAdapter`, resultados tipados y constantes TLS USB.
- GREEN enfocado: `:android:usb-probe:testDebugUnitTest --tests dev.chinchillacam.usbprobe.UsbTlsCiphertextIoAdapterTest --rerun-tasks` pasó.
- Full: `:android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó.
- Revisión independiente exacta: PASS sobre el candidato staged; verificó sólo los 3 archivos permitidos, 356 líneas, stream `0x01020305`, split outbound `<=32768`, total write `<=65536`, cierres fail-closed, sin `CCP1` raw ni wiring UI/LAN.

### [ ] e2 — TLS pinneado con `SSLEngine` y `CCP1`

Estado: en progreso para e2a. Con autorización fresca, montar `SSLEngine` cliente sobre el adaptador e1, pinear el SPKI del QR con `PinnedDesktopTlsTrustManager`, completar handshake TLS 1.2+ sin socket/localhost y rechazar peers no esperados. e2a no confirma pairing ni envía `CCP1`; e2b requerirá autorización fresca para llevar `CCP1` dentro del canal TLS establecido. Esta tarea no debe usar `localhost` como ruta productiva en teléfono.

Evidencia e2a1 (2026-09-28): `fb4ada9 feat(android): open pinned USB TLS channel` abre un canal TLS autenticado por SPKI y conserva el `SSLEngine`/sesión vivos para e2b. RED enfocado: referencias no resueltas antes de crear el canal. GREEN enfocado y full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasaron; revisión independiente exacta PASS con 370 líneas cambiadas y sin `CCP1`, pairing confirmation, sockets, LAN ni hardware. Native START sobre el rango exacto falló por consent binding stale/expired, sin invocación nativa ni lineage; no hay ACK e2a1.

Estado e2a2: en progreso para pruebas fail-closed de deadline absoluto, read/write error, overflow/step-limit si cabe; e2a2 debe conservar el canal vivo para e2b y no autenticar el teléfono por handshake solo.

Evidencia e2a2 (2026-09-28): se agregaron pruebas fail-closed para deadline de lectura bloqueada, error de escritura USB y límite de pasos. Desviación TDD: el RED no se capturó por separado antes de los cambios; no se reclama TDD estricto para e2a2. GREEN enfocado y full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasaron. El cambio no modifica producción porque los tests pasaron sobre `fb4ada9`; se evitó editar source sin necesidad.

Estado e2b1: completado en `6517121 fix(android): harden USB TLS app data`. Endurece el API app-data del canal TLS vivo: deadline absoluto fail-closed, `NEED_WRAP` con `engine.wrap(empty)`, límite `maxBytes <= 64KiB`, guard de no progreso en write y cierre/shutdown en fallas, sin `CCP1`, pairing confirmation, UI, LAN ni hardware.

Evidencia e2b1: diff candidate 396 líneas por numstat y commit 393 inserciones/3 borrados. Focused incremental RED válido: `expiredApplicationReadDeadlineClosesBeforeReturningPendingPlaintext` y `applicationWriteRejectsEngineNoProgress` fallaron antes del fix. GREEN enfocado pasó. Full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó local e independiente. Verificador independiente leyó source y confirmó wrap real, deadlines antes/después, cap de `maxBytes`, guard de no progreso y cierre fail-closed. Los borradores e2b2 fueron restaurados de `.kt.pending` a `.kt` con SHA-256 `4cc08c5330d0179b60c417aab1e41ddb8462fdcbd0d573d90b4321724a2c34eb` y `50d57bcc2bd4461642db9951ff4410171d83f6493901a417de5734cb8770422d`; `.tmp-e2b-quarantine` quedó vacío y sin limpieza.

Nota de disciplina e2b1: decisión de usuario `accept_one_e2b1_tdd_exception`. No hubo RED histórico válido para el primer bloque de e2b1; el primer fallo enfocado fue de setup de prueba (pipe no conectado). No se reclama TDD estricto para ese bloque. Incidente de aislamiento: antes de la instrucción exacta `.kt.pending`, los borradores e2b2 se movieron inicialmente a `.tmp-e2b-quarantine/...`; luego se movieron a los nombres same-path `.kt.pending` autorizados, con SHA-256 preservado.

Estado e2b2: completado en `3433ac7 feat(android): verify USB TLS pairing proof`. Verifica `CCP1` dentro del mismo canal TLS vivo y devuelve `Verified(proof, channel)` sin cerrar el canal; cubre app-data post-prueba sobre la misma conexión. Rechaza fail-closed challenge inválido con close best-effort, status CCP1 nonzero y eco status0 incorrecto, sin afirmar autenticación del teléfono.

Evidencia e2b2: RED test-only fresco con source SHA `4cc08c5330d0179b60c417aab1e41ddb8462fdcbd0d573d90b4321724a2c34eb`: `rejectsExpiredChallengeWhenUsbCloseThrowsIOException` falló porque IOException en close enmascaraba `Rejected`; status nonzero ya pasaba. GREEN enfocado `UsbTlsPairingProofVerifierTest` pasó, luego full `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó local e independiente. Verificador independiente confirmó 391 líneas <=400, live-channel status0, close best-effort, status nonzero, wrong echo y ausencia de UI/LAN/hardware/native ACK.

### [x] e3 — Interoperabilidad socketless Rust ↔ JVM

Estado e3: completado para la prueba JVM opt-in en `test(android): prove socketless USB TLS interop` (sólo tests; sin cambios de producción). Ejecuta el helper Rust existente por stdio, lee sólo las dos líneas públicas (`CHINCHILLACAM-USB-INTEROP:v1` y `qr=...`) con lector raw sin prefetch, y luego usa los mismos pipes como `AccessoryIoSession` para que JSSE y rustls completen handshake y `CCP1` sobre el stream USB TLS `0x01020305`. Sin TCP, LAN, ADB, hardware, UI, push ni afirmación de autenticación del teléfono. La prueba requiere env explícito `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER`, omite sólo si está unset/blank, falla si está seteado pero no es ruta absoluta/ejecutable, y acepta `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER_SHA256` sólo como verificación opcional del binario invocado.

Evidencia e3: test-only characterization; no se reclama RED funcional para el cruce JVM↔rustls porque el helper y el cliente ya existían. Helper opt-in verificado en `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink/desktop/usb-probe/target/debug/usb_pairing_proof_stdio_helper` con SHA-256 `384cad52ef84a20d0880a389ea06fc57d79bfb328f5e15c0c877380c945b3272`. Focused opt-in `:android:usb-probe:testDebugUnitTest --tests dev.chinchillacam.usbprobe.SocketlessUsbPairingProofInteropTest --rerun-tasks` pasó con XML `tests=1 skipped=0 failures=0 errors=0`. Full sin env explícito `:android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` pasó, con skip esperado para la prueba opt-in. Verificador independiente exacto confirmó focused opt-in PASS/XML `1/0/0/0`, lector raw sin `BufferedReader`, skip sólo sin env, fallos para env inválido, hash sólo opcional, `waitFor` antes de cerrar canal, watchdog/cleanup acotados y candidato `174` líneas <=400.

Spot check de retoma (2026-09-28, sesión nueva tras corte por límite de uso): helper con SHA-256 `384cad52…3272` sin cambios; focused opt-in con `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER` y `..._SHA256` seteados pasó con XML `tests=1 skipped=0 failures=0 errors=0`; full sin env re-ejecutado antes del commit.
