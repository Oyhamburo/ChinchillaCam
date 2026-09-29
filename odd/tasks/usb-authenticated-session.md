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

### [ ] s1 — Flujo de pairing USB con canal vivo

Servicio de aplicación que compone las reglas de `PendingPairingCoordinator` (sin duplicarlas; refactor mínimo, p. ej. un verificador con canal) con `UsbTlsPairingProofVerifier` sobre `AccessoryIoSession` y `PhoneTlsIdentity`; el pendiente retiene el canal; confirmar persiste y activa y entrega el canal; rechazo/cancelación/vencimiento lo cierran. RED: `usbPairingKeepsLiveChannelUntilConfirm`, `usbPairingRejectClosesChannel`, `usbPairingExpiryClosesChannel`, `usbPairingConfirmPersistsTrustAndActivates`. ~350 líneas.

### [ ] s2 — SessionFrame sobre TLS

`TlsSessionFrameIoAdapter` sobre `SslEngineUsbTlsEstablishedChannel` con el framing de §4.3. RED: `roundTripsSessionFrameOverTls`, `rejectsOversizedLengthBeforeAllocating`, `rejectsZeroLength`, `truncatedFrameClosesChannel`. ~250 líneas.

### [ ] s3 — Reconexión confiable

`UsbTrustedReconnect` con SPKI pinneado del store, certificado de cliente, `HANDSHAKE_HELLO` → `HANDSHAKE_ACCEPT` y activación. RED: `reconnectsToTrustedDesktopAfterAccept`, `reconnectRejectedByDesktopFailsClosed`, `reconnectTimesOutWithoutAccept`, `revokedDesktopIsNotReconnected`. ~350 líneas.

### [ ] s4 — Prueba cruzada de la sesión completa

Requiere autorización fresca del usuario: pairing + confirmación + reconexión + hello contra el helper desktop. ~150 líneas.

Criterios: ningún camino entrega un canal sin confirmación o aceptación; todo rechazo cierra el canal; framing acotado y fail closed; tests existentes verdes.

## Progreso

Plan creado el 2026-09-28; ninguna tarea iniciada.
