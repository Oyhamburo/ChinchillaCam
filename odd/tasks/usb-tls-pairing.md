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

### e1 — Adaptador USB de ciphertext acotado

Agregar un adaptador pequeño sobre `AccessoryIoSession`/`AccessoryFrameCodec` que lea y escriba chunks TLS opacos en el stream `0x01020305`, con límites estrictos, cierre fail-closed y tests JVM sin hardware.

### e2 — TLS pinneado con `SSLEngine` y `CCP1`

Con autorización fresca, montar `SSLEngine` cliente sobre el adaptador e1, pinear el SPKI del QR con `PinnedDesktopTlsTrustManager`, enviar `CCP1` dentro de TLS y rechazar peers no esperados. Esta tarea no debe usar `localhost` como ruta productiva en teléfono.

### e3 — Interoperabilidad socketless Rust ↔ JVM

Con autorización fresca cross-worktree, probar rustls `ServerConnection` y JSSE `SSLEngine` sobre el envelope USB acotado, sin TCP, LAN, ADB ni dispositivo físico.
