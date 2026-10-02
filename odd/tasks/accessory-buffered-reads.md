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

1. [x] a1 — Lectura bufferizada en `AccessoryIoSession` con fake de transferencias exactas. RED: `readsFrameDeliveredInSingleTransfer`, `readsMultipleFramesFromOneTransfer`, `readsFrameSplitAcrossTransfers`, `neverReadsWithBufferSmallerThanTransfer`, `eofAndShortReadSemanticsUnchanged`. ~250 líneas.
   - Cambio: `AccessoryIoSession` recibe `readTransferBytes` (por defecto `DEFAULT_ACCESSORY_READ_TRANSFER_BYTES` = 16384; `require` ≥ `MIN_ACCESSORY_READ_TRANSFER_BYTES` = 512), lee siempre `input.read(transferBuffer, 0, transferBuffer.size)` y sirve `readExactly` desde el residual. `Eof` solo si no llegó ningún byte del pedido; `ShortRead` si el stream terminó a mitad. Un solo lector por sesión (documentado en KDoc). Escritura sin cambios.
   - Tests: `AccessoryIoSessionBufferedReadTest.kt` con el fake local `TransferExactInputStream` (cola de transferencias; `read` con `len` menor que la transferencia lanza `IOException("overflow…")`; `-1` al vaciarse; registra cada `len`). Se agregó también `rejectsTooSmallReadTransferBytes`. Tests existentes sin cambios.
   - RED 1 (tests contra el código previo): falla la compilación: `Unresolved reference: DEFAULT_ACCESSORY_READ_TRANSFER_BYTES` y `Cannot find a parameter with this name: readTransferBytes`.
   - RED 2 (solo con el parámetro y la constante, sin validación ni buffer): 6/6 fallan. `readsFrameDeliveredInSingleTransfer`: `IOException: overflow: read buffer 8 bytes, transfer 308 bytes`; `readsMultipleFramesFromOneTransfer`: `… 8 bytes, transfer 965 bytes`; `readsFrameSplitAcrossTransfers`: `… 3 bytes, transfer 695 bytes`; `neverReadsWithBufferSmallerThanTransfer`: `… 8 bytes, transfer 16384 bytes`; `eofAndShortReadSemanticsUnchanged`: `… 8 bytes, transfer 10 bytes`; `rejectsTooSmallReadTransferBytes`: `AssertionError` (no hubo `IllegalArgumentException`).
   - GREEN: con `--rerun-tasks`, el test nuevo pasa 6/6, `UsbAccessoryBoundaryTest` 32/32, `UsbSessionFrameIoAdapterTest` 9/9 y `UsbTlsCiphertextIoAdapterTest` 8/8. El runner completo da `BUILD SUCCESSFUL`, con 51 suites y 431 tests: 2 omitidos y 0 fallos ni errores (baseline 425 + 6 nuevos). `assembleDebug` y `lintDebug` pasan.

## Progreso

Plan creado el 2026-10-01.

a1 implementado y verificado (431/2/0). Commits: plan y a1, a cargo del orquestador. Revisión nativa pendiente. Seguimientos: ZLP y validación física (M9).
