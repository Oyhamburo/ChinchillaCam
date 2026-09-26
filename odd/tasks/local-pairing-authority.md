# Pairing local, autoridad y framing de sesión

> Estado (2026-09-26): M3 inicia en rama local `feat/local-pairing-authority` desde cierre M2 `3907df0`. Commits locales autorizados; sin push, PR ni merge. Sin listener LAN externo hasta que existan pairing, confianza local y una sola PC activa.

## Alcance M3

Construir el contrato local de sesión entre Android y desktop antes de transportes sostenidos: framing tipado, payload QR, confianza persistida local y enforcement de una computadora activa.

## Reglas de seguridad

- No abrir listener Wi‑Fi externo no autenticado.
- Wi‑Fi inicial, si aparece antes de confianza, debe ser fake/loopback-only.
- No cuentas, nube, backend, audio, grabación ni fotos.
- Una sola computadora activa por teléfono; la segunda sesión debe rechazarse salvo handoff explícito.
- Los mensajes deben estar autenticados por sesión/trust antes de controlar cámara o transportar video.
- Sin claims Windows/macOS/Samsung/físicos hasta la matriz final.

## Plan secuencial

- [x] T11: framing de sesión tipado. Define envelopes para handshake, stream metadata, video chunks, métricas y controles; decoder/encoder deterministas con límites de tamaño/versionado; sin transporte real.
  - 2026-09-26: Implementación pura añadida en `SessionFrame.kt`; tests en `SessionFrameTest.kt`. Validado localmente con `testDebugUnitTest --rerun-tasks`, `assembleDebug`, APK presente y `git diff --check`.
- [ ] T12: payload QR local. Incluir identificador de PC, clave pública o material de confianza, expiración y versión; tests de expiración/tamper.
- [ ] T13: persistencia de confianza local en Android y desktop con revocación; fakes primero.
- [ ] T14: una computadora activa. Rechazar segunda sesión activa con mensaje español; handoff explícito como acción separada.

## Diseño T11 — framing de sesión tipado

T11 crea un contrato puro y testeable para mensajes de sesión sin transporte real. El objetivo es que USB/Wi‑Fi posteriores transporten bytes ya autenticables/decodificables sin inventar payloads ad hoc.

Alcance T11:

- Definir `SessionFrame` con `version`, `type`, `sequence`, `sessionId` y payload tipado.
- Tipos iniciales: handshake hello/accept/reject, stream metadata, video chunk, metrics snapshot y camera/control command.
- Codificación determinista a bytes y decodificación con errores tipados: versión no soportada, tipo desconocido, tamaño excedido, payload inválido, secuencia inválida.
- Límites de tamaño por frame; video chunk puede contener bytes H.264 pero no define transporte/backpressure final.
- Tests RED para roundtrip, rechazo de versión/tipo/tamaño, orden de secuencia y payloads mínimos.
- Sin USB/Wi‑Fi listener, red externa, crypto real, QR, persistencia, cámara o desktop decode en este lote.
