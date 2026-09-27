# Transporte USB de frames de sesión

> Estado (2026-09-27): M4/T15 inicia en rama local `feat/usb-session-transport` desde cierre M3 `57ff6a5`. Commits locales autorizados; sin push, PR, merge ni integración con worktrees desktop. Sin prueba física todavía.

## Alcance M4/T15

Modelar transporte USB lógico sobre AOA bulk usando fakes y el framing ya existente. El objetivo de este lote es transportar bytes de `SessionFrame` como payload opaco dentro de `AccessoryFrame`, sin reinterpretar su endianness ni abrir hardware real.

## Reglas de seguridad

- No prueba física USB ni claim de soporte hardware.
- No abrir listener LAN ni introducir Wi‑Fi.
- No controlar cámara desde transporte todavía.
- No integrar desktop real ni decode real.
- `SessionFrame` se anida como bytes opacos dentro del payload USB; el header `AccessoryFrame` sigue little-endian, pero el contenido no se reinterpreta.
- Respetar una computadora activa: el transporte no autoriza por sí solo, solo transporta frames de sesión ya autorizables por capas superiores.

## Plan secuencial

- [x] T15a: seam puro `UsbSessionFrameTransport` con fake in-memory. Enviar/recibir `SessionFrame` serializado dentro de `AccessoryFrame`, stream id dedicado, límites de payload y errores tipados; sin `UsbManager`, sin hardware. Validado con `:android:usb-probe:testDebugUnitTest` y `:android:usb-probe:assembleDebug`.
- [x] T15b: adapter sobre `AccessoryIoSession` testeado con streams fake; lectura de frame completo, short header, short payload, oversize, EOF y stream id incorrecto. Validado con `:android:usb-probe:testDebugUnitTest`, `:android:usb-probe:assembleDebug` y `git diff --check`.
- [ ] T15c: backpressure/timeouts fake para stream sostenido; no prueba física.


## Diseño T15b — adapter sobre `AccessoryIoSession`

T15b conecta el seam lógico T15a con `AccessoryIoSession` ya existente usando streams fake/unit tests. No abre `UsbManager` ni hardware real.

Alcance T15b:

- Leer primero el header `AccessoryFrame` de 8 bytes desde `AccessoryIoSession`, luego el payload declarado, respetando `maxPayloadBytes`.
- Escribir frames ya codificados por `UsbSessionFrameTransport` al `AccessoryIoSession`.
- Mapear short header, short payload, EOF y oversize a errores tipados de transporte sin clasificarlos como `SessionFrame` inválido.
- Cerrar/propagar close solo mediante el dueño de `AccessoryIoSession`; el adapter no inventa permisos ni lifecycle Android.
- Tests con streams fake: write exact bytes, read complete frame, short header, short payload, oversize, EOF vacío, stream id incorrecto.
- Sin hardware, sin prueba física, sin desktop real, sin cámara, sin LAN/Wi‑Fi.

## Diseño T15a — transporte lógico fake

Alcance T15a:

- Definir stream id dedicado para frames de sesión sobre USB.
- Serializar con `SessionFrameCodec.encode(frame)` y envolver con `AccessoryFrameCodec.encode`.
- Decodificar primero `AccessoryFrame`, verificar stream id, luego `SessionFrameCodec.decode` sobre payload opaco.
- Errores tipados: stream inesperado, payload USB oversize, frame de sesión inválido, cola vacía/EOF fake.
- Tests RED: roundtrip handshake/video metadata/video chunk; rechazo de stream id incorrecto; oversize; bytes de sesión inválidos; demostrar que payload `SessionFrame` no se reinterpreta como little-endian.
- Sin Android USB real, sin permiso USB, sin desktop, sin cámara, sin red.

### Revisión nativa RDD T15a

- Candidato: `7801e8c` contra base `dc6eeed`.
- Lineage: `review-0466538640e95493`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante: `R3-accessory-truncation-classification` en `UsbSessionFrameTransport.kt:81-86`. No abrió corrección para T15a.

### Revisión nativa RDD T15b

- Candidato: `36e2b01` contra base `efb7f40`.
- Lineage: `review-711b35babdedff6e`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes: `R3-clean-close-unproved` en `odd/tasks/usb-session-transport.md:21` y `R3-oversize-desynchronizes-session` en `UsbSessionFrameIoAdapter.kt:79-83`. No abrieron corrección para T15b.

### Hardening T15b antes de T15c

- Se corrigió el advisory `R3-oversize-desynchronizes-session`: un header con payload declarado mayor a `maxPayloadBytes` ahora cierra la `AccessoryIoSession` y devuelve resultado tipado con `sessionClosed = true`, evitando re-sincronización insegura sobre bytes restantes.
- Validado con test RED/GREEN de oversize que comprueba cierre, además de `testDebugUnitTest --rerun-tasks`, `assembleDebug`, APK presente y `git diff --check`.

## Gate T11c — límite de encode para `SessionFrame`

Antes de T15c/T17 sostenido, `SessionFrameCodec.encode` debe respetar el mismo límite máximo que `decode` acepta por defecto: frame completo, incluyendo header y payload, no puede superar 1 MiB. Esto debe coordinarse con el peer Rust para evitar que un lado emita frames que el otro rechaza.

Alcance T11c Android:

- Agregar test RED para `SessionFrameCodec.encode` que rechace un frame completo que exceda 1 MiB. En Android, `VideoChunk.h264Bytes` ya está limitado por campo u16, así que el caso oversized usa payload agregado de handshake/capabilities; el máximo `VideoChunk` queda cubierto por el límite de campo y debajo del límite de frame.
- Mantener roundtrip de un frame que queda justo por debajo del límite.
- Exponer error local claro en encode; sin transporte, sin USB real, sin LAN.
- No cambiar el formato wire salvo agregar la validación de tamaño.

### Revisión nativa RDD T15b hardening oversize

- Candidato: `2404aae` contra base `2e89376` en worktree temporal detached `/Users/jele/Desktop/codes/ChinchillaCam-t15b-oversize-review`.
- Lineage: `review-53af7ca505d640da`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes: `R3-close-failure-bypasses-result` en `UsbSessionFrameIoAdapter.kt:80` y `R3-payload-nonconsumption-unproved` en `UsbSessionFrameIoAdapterTest.kt:90`. No abrieron corrección para este candidato.

### Revisión nativa RDD T11c

- Candidato: `fbb1068` contra base `2404aae` en worktree temporal detached `/Users/jele/Desktop/codes/ChinchillaCam-t11c-encode-cap-review`.
- Lineage: `review-b7dd0ce3fc2fcf12`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante: `R3-post-allocation-size-check` en `SessionFrame.kt:126`. No abrió corrección para T11c; queda como hardening si se necesita evitar asignación grande antes de sustained streaming.

### T11d hardening: preflight de tamaño al codificar

Al evaluar `R3-post-allocation-size-check`, el codec Android ya limita cada campo binario/texto a `u16` y el frame completo a 1 MiB, pero antes de T11d calculaba ese límite después de construir el payload y el buffer de salida. T11d agrega un preflight puro de tamaño que suma header, `sessionId` y payload antes de construir `PayloadWriter`/frame final. El preflight se detiene en cuanto el acumulado excede 1 MiB, por lo que una lista adversarial de capabilities no necesita recorrerse ni materializarse completa para rechazar el frame. No cambia el formato de cable; solo adelanta la validación local.

## Plan T15c — backpressure/timeouts fake

T15c seguirá siendo una capa de pruebas unitarias/fakes sobre AOA bulk; no abre `UsbManager`, hardware, desktop real, cámara ni LAN/Wi‑Fi.

Alcance propuesto:

- Introducir un seam pequeño de sesión USB fake con reloj inyectado para simular `readExactly` bloqueado, progreso parcial y deadline vencido sin dormir tests reales.
- Modelar resultados tipados para stream sostenido: frame recibido/enviado, timeout de lectura, timeout de escritura/backpressure y cierre de sesión.
- Poison/close de sesión ante truncation sostenida o timeout después de progreso parcial, para no re-sincronizar sobre bytes ambiguos.
- Mantener T15b como adapter exacto de frame único; T15c no debe mezclar cámara, encoder, desktop ni transporte físico.
- Tests RED/GREEN esperados: cola llena rechaza/es backpressure sin perder orden; read timeout sin bytes no consume; timeout/truncation después de header o payload parcial cierra; después de close no se aceptan writes; frame válido conserva orden FIFO.

Gate antes de review nativa: reportar `READY T15c` al coordinador con worktree, rango `base..HEAD` y líneas diff, y esperar `GRANT T15c` explícito.

### Revisión nativa RDD T11d

- Candidato: `419e344` contra base `0918821` en worktree temporal detached `/Users/jele/Desktop/codes/ChinchillaCam-t11d-preflight-review`.
- Lineage: `review-5c4e24217e0e993a`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Sin hallazgos advisory reportados para este candidato.
