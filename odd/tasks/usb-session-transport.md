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
- [ ] T15b: adapter sobre `AccessoryIoSession` testeado con streams fake; lectura de frame completo, short read, oversize, EOF y cierre limpio.
- [ ] T15c: backpressure/timeouts fake para stream sostenido; no prueba física.

## Diseño T15a — transporte lógico fake

Alcance T15a:

- Definir stream id dedicado para frames de sesión sobre USB.
- Serializar con `SessionFrameCodec.encode(frame)` y envolver con `AccessoryFrameCodec.encode`.
- Decodificar primero `AccessoryFrame`, verificar stream id, luego `SessionFrameCodec.decode` sobre payload opaco.
- Errores tipados: stream inesperado, payload USB oversize, frame de sesión inválido, cola vacía/EOF fake.
- Tests RED: roundtrip handshake/video metadata/video chunk; rechazo de stream id incorrecto; oversize; bytes de sesión inválidos; demostrar que payload `SessionFrame` no se reinterpreta como little-endian.
- Sin Android USB real, sin permiso USB, sin desktop, sin cámara, sin red.
