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

### [ ] e3 — Interoperabilidad socketless Rust ↔ JVM

Con autorización fresca cross-worktree, probar rustls `ServerConnection` y JSSE `SSLEngine` sobre el envelope USB acotado, sin TCP, LAN, ADB ni dispositivo físico.
