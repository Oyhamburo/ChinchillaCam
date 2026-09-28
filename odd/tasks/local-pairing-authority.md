# Pairing local, autoridad y framing de sesión

> Estado (2026-09-26): M3 inicia en rama local `feat/local-pairing-authority` desde cierre M2 `3907df0`. Commits locales autorizados; sin push, PR ni merge. Sin listener LAN externo hasta que existan pairing, confianza local y una sola PC activa.

## Alcance M3

Construir el contrato local de sesión entre Android y desktop antes de transportes sostenidos: framing tipado, payload QR, seams de confianza, autoridad de una computadora activa y gates explícitos antes de cualquier LAN autenticado. La persistencia completa Android+desktop queda abierta hasta T13b/T13c.

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
- [ ] T13: persistencia de confianza local en Android y desktop con revocación. No está completo.
  - [x] T13a: seam Android-only/in-memory con revocación fake; implementado en `TrustedDesktopStore.kt` con tests en `TrustedDesktopStoreTest.kt`.
  - [ ] T13b: persistencia Android segura/local para confianza y revocación.
    - C6 autorizado: port manual acotado desde worktree T13b solo para store persistente y tests; sin merge/cherry-pick, sin UI, sin wiring productivo amplio y sin tocar el worktree fuente.
  - [ ] T13c: persistencia desktop de confianza/revocación en worktree desktop separado.
  - Gate: T17 LAN autenticado sigue bloqueado hasta T13b+T13c y prueba de posesión/confirmación explícita.
- [x] T14: una computadora activa. Rechazar segunda sesión activa con mensaje español; handoff explícito como acción separada.
  - 2026-09-26: Modelo puro Android añadido en `ActiveDesktopAuthority.kt`; tests en `ActiveDesktopAuthorityTest.kt`. Valida primera activación confiada, renovación, rechazo de segunda PC activa, handoff explícito, cancelación, rechazos de confianza, stop e invalidación sin fallback.





## Diseño T12b — hardening QR antes de LAN autenticado

T12b es un lote pequeño de corrección de advisories antes de T17, separado de T14 para no mezclar autoridad de sesión con parsing QR. No debe abrir transportes ni agregar autenticación propia.

Alcance T12b:

- Definir explícitamente la semántica de expiración: un payload con `expiresAtEpochSeconds == nowEpochSeconds` debe estar expirado o aceptado, con test que fije la decisión.
- Rechazar percent-encoding malformed y UTF-8 inválido en campos QR sin normalización ambigua.
- Mantener `ChecksumMismatch` como detección de corrupción accidental solamente; no convertirlo en autenticación ni MAC.
- Mantener rechazo de campos desconocidos/duplicados.
- Sin persistencia, sin listener, sin LAN, sin prueba de posesión y sin cambios de trust store salvo que un test demuestre acoplamiento directo.

Criterios de tests futuros:

- Boundary de expiración en `now == expiresAt`.
- Percent escape incompleto o inválido se rechaza con error tipado.
- Octetos percent-encoded que no formen UTF-8 válido se rechazan con error tipado.
- Un QR válido existente sigue roundtripeando.

Estado T12b (2026-09-26): implementado parser hardening QR-only. Expiración en `now == expiresAt` queda rechazada; percent escapes malformed y bytes percent-decoded con UTF-8 inválido devuelven `InvalidField("percentEncoding")`. Validado con `testDebugUnitTest --rerun-tasks` antes de actualizar este estado.

## Diseño T14 — una computadora activa

T14 debe modelar autoridad de sesión antes de cualquier transporte real. Mientras el review nativo de T12/T11b/T13a siga pendiente, este bloque queda como planificación solamente: no agrega source ni tests nuevos.

Alcance propuesto T14:

- Definir un estado puro de autoridad local con `NoActiveDesktop`, `ActiveDesktop` y `HandoffPending` como modelo testeable.
- Autorizar una sesión solo cuando el desktop presentado esté confiado, no revocado, no expirado y coincida con la fingerprint esperada.
- Rechazar una segunda computadora activa con resultado tipado y mensaje UI español futuro: "Ya hay una computadora activa".
- Permitir handoff explícito como acción separada, nunca como fallback silencioso.
- Mantener `sessionId`/desktop activo separado del transporte; USB/Wi‑Fi solo podrán consultar esta autoridad cuando existan.
- No abrir LAN, no crear listener, no tocar cámara, no integrar desktop, no asumir prueba de posesión hasta el gate pre-T17.

Criterios de tests futuros:

- Primera PC confiada pasa a activa.
- Misma PC activa puede renovar sesión si la confianza sigue válida.
- Segunda PC confiada se rechaza sin handoff.
- Handoff explícito cambia la PC activa y registra evidencia de decisión.
- PC desconocida, revocada, expirada o con fingerprint distinta no puede quedar activa.
- Stop/forget/revoke de la PC activa libera o invalida autoridad según la acción, sin fallback automático a otra PC.

## Diseño T13a — persistencia de confianza local Android

T13 se divide para mantener worktrees aislados: T13a implementa solo el seam Android de confianza local; el lado desktop queda fuera de este worktree y no se integra sin decisión fresca.

Alcance T13a:

- Definir `TrustedDesktopRecord` con `desktopId`, nombre visible, hash/fingerprint del material de confianza, timestamps locales y estado revocable.
- Definir `TrustedDesktopStore` como interfaz pura con implementación fake/in-memory testeable primero; persistencia Android real solo detrás de seam pequeño si el lote sigue acotado.
- Validar que una confianza expirada, revocada o con fingerprint distinto no autoriza sesión.
- Exponer resultado tipado para `Trusted`, `Unknown`, `Revoked`, `Expired` y `FingerprintMismatch`.
- No abrir transporte, no escuchar LAN, no controlar cámara, no asumir desktop real ni handshake completo.
- Mantener gate: QR no autentica por sí solo; la autorización final requiere prueba de posesión, nonce single-use/expiry, confirmación explícita y TLS/peer identity antes de T17.

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
- Debe existir un threat model acotado y un plan testeable con TLS estándar: `SSLEngine`/TLS para USB no-socket cuando aplique, TLS para LAN, identidad de par pinneada o certificado esperado, y rechazo de peers no esperados.
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

### Revisión nativa RDD T12/T11b/T13a

- Candidato: `f8504a9` contra base `7d1332e`.
- Lineage: `review-1a21ff7d946cbfa5`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Nota de disciplina: este candidato fue demasiado amplio para una unidad de review ideal; agrupó T12, T11b, T13a y planificación T14. Las siguientes revisiones deben volver a cortes por unidad.
- Hallazgos advisory no bloqueantes: `R3-expiry-boundary` en `PairingQrPayload.kt:134` y `R3-malformed-percent-utf8` en `PairingQrPayload.kt:235`. No abrieron corrección para este candidato.

### Revisión nativa RDD T12b

- Candidato: `afc14dc` contra base `09b8115` en worktree temporal detached `/Users/jele/Desktop/codes/ChinchillaCam-t12b-review`.
- Lineage: `review-676a75b529ab8cab`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante: `R3-supplementary-unicode` en `PairingQrPayload.kt:251`. No abrió corrección para T12b.

### Revisión nativa RDD T14

- Candidato: `f860ba4` contra base `afc14dc`.
- Lineage: `review-32d058495f927285`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes: `R3-001` en `ActiveDesktopAuthority.kt:196-198` y `R3-002` en `ActiveDesktopAuthority.kt:179`. No abrieron corrección para T14.


## Estado M3 local parcial

M3 queda parcialmente implementado como contrato previo a transportes: framing tipado, payload QR local, trust store Android in-memory, hardening de parser QR y autoridad de una computadora activa. No está cerrado como milestone completo porque faltan T13b persistencia Android segura/local y T13c persistencia desktop/revocación.

Evidencia final local:

- `testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`.
- `assembleDebug`: pasó con `BUILD SUCCESSFUL`.
- APK debug presente en `android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`.
- `git diff --check`: sin salida.
- Revisiones nativas RDD aprobadas y reconocidas: T11 `review-0321f52b8e7283e3`, T12/T11b/T13a `review-1a21ff7d946cbfa5`, T12b `review-676a75b529ab8cab`, T14 `review-32d058495f927285`.

Límites del estado parcial:

- Sin transportes USB/Wi‑Fi de producto aún.
- Sin persistencia completa Android+desktop de confianza/revocación.
- Sin listener LAN externo.
- El checksum QR no autentica ni resiste manipulación maliciosa; solo detecta corrupción accidental.
- Antes de T17 siguen obligatorios: T13b, T13c, prueba de posesión de clave privada, nonce single-use/expiry, confirmación explícita de confianza, threat model y TLS estándar/identidad de par esperada.
- Sin claims Samsung/Windows/macOS/físicos.

## Diseño T13b — persistencia Android segura/local

T13b debe convertir el seam Android in-memory en persistencia local testeable sin cerrar todavía M3 completo ni desbloquear LAN. El material persistido representa identidad pública/fingerprint de la PC y metadatos de revocación; cualquier clave privada generada en Android debe permanecer en AndroidKeyStore y nunca serializarse en SharedPreferences/JSON/tests.

Alcance propuesto:

- Mantener `TrustedDesktopStore` como contrato; agregar implementación Android local detrás de una abstracción de key-value/archivo inyectable para poder testear sin dispositivo.
- Persistir solo datos públicos/locales: `desktopId`, nombre visible, fingerprint/hash del material público de la PC, timestamps, expiración opcional y revocación opcional.
- Cargar defensivamente: corrupción, campos faltantes, desktopId inválido o fingerprint inválida no deben autorizar confianza; deben devolver error/estado seguro sin borrar silenciosamente evidencia salvo operación explícita.
- Revocación persistente: después de `revoke`, reiniciar la store fake/persistente debe seguir devolviendo `Revoked`; `forget` sí borra el registro de forma explícita.
- Migración/versionado mínimo del formato para no bloquear cambios posteriores de prueba de posesión.
- Sin LAN, sin listener externo, sin transporte físico, sin desktop real y sin claim de soporte. T13c desktop y handshake de posesión siguen pendientes.

Criterios RED/GREEN esperados:

- Registro guardado se recupera tras recrear la store.
- Revocación persiste tras recreación y bloquea `evaluate`.
- `forget` elimina de forma persistente.
- Fingerprint distinta sigue devolviendo `FingerprintMismatch` tras recarga.
- Archivo/JSON corrupto o versión desconocida falla cerrado (`Unknown`/error tipado) y no produce `Trusted`.
- La implementación no expone ni serializa claves privadas; solo material público/fingerprint.

Gate antes de review nativa: reportar `READY T13b` al coordinador con worktree, rango `base..HEAD` y líneas diff, y esperar `GRANT T13b` explícito.
