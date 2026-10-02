# Lectura bufferizada del accesorio USB (Android)

## 1. Objetivo

Que el teléfono lea el stream del accesorio USB con lecturas de tamaño de transferencia, sin perder datos cuando el desktop envía un frame completo (header + payload) en una sola transferencia bulk.

## 2. Problema

`AccessoryIoSession.readExactly(n)` llama a `input.read(buffer, offset, n - offset)` con buffers del tamaño pedido (8 bytes para el header de `AccessoryFrame`). El desktop escribe cada `BulkFrame` en un único `write_bulk`; en Android, leer el `FileInputStream` del accesorio con un buffer menor que la transferencia entrante puede descartar el resto de la transferencia o fallar con `EIO`, y en versiones viejas se requieren lecturas de al menos 16 KiB. Es el espejo del defecto corregido en el desktop (`feat/desktop-video-sink`, `odd/tasks/usb-session-runtime.md` u1). Los tests usan `PipedInputStream`, que no modela ese comportamiento.

## 3. Decisión

Seguimiento técnico de `usb-session-runtime` (desktop), elegido por el orquestador con delegación del usuario el 2026-10-01. Sin decisión de producto.

## 4. Contrato

1. `AccessoryIoSession` lee siempre con un buffer de transferencia de tamaño fijo (por defecto 16 KiB; configurable, validado ≥ 512) y sirve `readExactly` desde un residual; los bytes sobrantes quedan para la próxima lectura.
2. La semántica de `AccessoryReadResult` no cambia: `Eof` si no llegó ningún byte, `ShortRead` si el stream terminó a mitad, `Complete` en otro caso.
3. Escritura sin cambios (una escritura por frame).
4. Fuera de alcance: ZLP, timeouts del accesorio, pruebas físicas (M9).

## 5. Riesgos

- El comportamiento real del `FileInputStream` del accesorio se modela con un fake que rechaza lecturas menores que la transferencia; no es prueba física.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución). Revisión nativa RDD pendiente mientras siga el incidente del facade. Writer delegado con lectura del orquestador. Push autorizado por el usuario.

## 7. Tareas

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 425 tests, 2 omitidos, 0 fallos (HEAD `cd189b3`).

1. [ ] a1 — Lectura bufferizada en `AccessoryIoSession` con fake de transferencias exactas. RED: `readsFrameDeliveredInSingleTransfer`, `readsMultipleFramesFromOneTransfer`, `readsFrameSplitAcrossTransfers`, `neverReadsWithBufferSmallerThanTransfer`, `eofAndShortReadSemanticsUnchanged`. ~250 líneas.

## Progreso

Plan creado el 2026-10-01.
