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
