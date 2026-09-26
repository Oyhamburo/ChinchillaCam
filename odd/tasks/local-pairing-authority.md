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
- [x] T12: payload QR local. Incluir identificador de PC, clave pública o material de confianza, expiración y versión; tests de expiración y corrupción accidental por checksum sin clave.
  - 2026-09-26: Implementación pura añadida en `PairingQrPayload.kt`; tests en `PairingQrPayloadTest.kt`. Validado localmente con `testDebugUnitTest --rerun-tasks` y `git diff --check`.
  - 2026-09-26: Semántica ajustada: el checksum sin clave solo detecta corrupción accidental y campos extra/desconocidos; no autentica ni resiste manipulación maliciosa.
- [ ] T13: persistencia de confianza local en Android y desktop con revocación; fakes primero.
- [ ] T14: una computadora activa. Rechazar segunda sesión activa con mensaje español; handoff explícito como acción separada.


## Diseño T12 — payload QR local

T12 define un payload QR puramente local y testeable para iniciar confianza sin abrir listeners de red. No establece transporte, no abre Wi‑Fi y no controla cámara.

Alcance T12:

- Definir `PairingQrPayload` versionado con identificador de PC, nombre visible, clave pública/material de confianza, expiración y nonce.
- Codificación textual determinista apta para QR y decodificación con errores tipados.
- Validación de expiración mediante reloj inyectado; rechazo de payload expirado, manipulado, versión desconocida, campos faltantes y material inválido.
- Pruebas RED para roundtrip, orden determinista, expiración, mismatch de checksum por corrupción accidental y límites de tamaño/campo.
- Sin persistencia de confianza todavía, sin listener LAN, sin USB/Wi‑Fi real, sin crypto handshake completo ni claims físicos.

Gate explícito M3 antes de cualquier LAN autenticado/T17:

- La autenticidad debe venir más adelante de prueba de posesión de la clave privada correspondiente al `trustMaterial` del QR.
- El nonce debe ser de uso único y expirar.
- Debe existir confirmación explícita de confianza antes de activar cualquier sesión LAN autenticada.
- No se habilita LAN sin protección ni listener externo no autenticado.

## Diseño T11 — framing de sesión tipado

T11 crea un contrato puro y testeable para mensajes de sesión sin transporte real. El objetivo es que USB/Wi‑Fi posteriores transporten bytes ya autenticables/decodificables sin inventar payloads ad hoc.

Alcance T11:

- Definir `SessionFrame` con `version`, `type`, `sequence`, `sessionId` y payload tipado.
- Tipos iniciales: handshake hello/accept/reject, stream metadata, video chunk, metrics snapshot y camera/control command.
- Codificación determinista a bytes y decodificación con errores tipados: versión no soportada, tipo desconocido, tamaño excedido, payload inválido, secuencia inválida.
- Límites de tamaño por frame; video chunk puede contener bytes H.264 pero no define transporte/backpressure final.
- Tests RED para roundtrip, rechazo de versión/tipo/tamaño, orden de secuencia y payloads mínimos.
- Sin USB/Wi‑Fi listener, red externa, crypto real, QR, persistencia, cámara o desktop decode en este lote.

### Revisión nativa RDD T11

- Candidato: `c19693a` contra base `b3faac8`.
- Lineage: `review-0321f52b8e7283e3`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante: `R3-collection-count-overflow` en `SessionFrame.kt:186`. No abrió corrección para T11; queda para hardening cuando se validen límites máximos de colecciones dentro del payload.
