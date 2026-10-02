# Runtime de sesión sobre USB (desktop)

## 1. Objetivo

Que `SessionRuntime`/`DesktopSessionPipeline` funcionen sobre el transporte USB real (AOA bulk) igual que sobre TCP loopback: lectura por slices cortos sin romper el stream, y framing que no pierda datos con transferencias USB reales.

## 2. Problema

1. Framing: `FramedUsbStream::read_frame` lee el header de 8 bytes y el payload con `read_bulk` separados. Android escribe header y payload en una sola escritura (`AccessoryIoSession.write(encoded)`), que llega como una transferencia bulk; con un paquete USB real (512 bytes en high speed) un `read_bulk` de 8 bytes termina en `LIBUSB_ERROR_OVERFLOW` y se pierden datos. Los fakes (`RecordingUsbBulkIo` con buffer residual) lo ocultan; las pruebas físicas siguen pendientes (M9).
2. Timeouts: `RusbClaimedBulkIo::read_bulk` convierte todo error de rusb en `UsbBulkTransferFailed(String)`, sin distinguir `Timeout`, y `UsbTlsCiphertextStream` envenena el stream ante cualquier error. El runtime necesita `io::ErrorKind::TimedOut` cuando no llegó nada.

## 3. Decisión

Elegido por el orquestador el 2026-10-01 con delegación del usuario ("lo que vos consideres"): USB es el único transporte real autorizado (LAN externo cerrado hasta T17) y sin esto no hay sesión por cable. Decisiones técnicas en §4; sin decisión de producto.

## 4. Contrato

1. Lectura bufferizada: el desktop lee transferencias bulk completas en un buffer de tamaño de transferencia (por defecto 16 KiB, múltiplo de 512, configurable con mínimo 512) y arma los `BulkFrame` desde un buffer residual; una transferencia puede contener un frame, parte de uno o varios.
2. Timeout tipado: `rusb::Error::Timeout` en lectura → `UsbProbeError::BulkReadTimeout` (rusb garantiza cero bytes transferidos en ese caso). Escritura: todo error sigue siendo fatal.
3. Inactividad segura: `read_frame` informa inactividad (`BulkReadTimeout`) sólo si el residual está vacío y no se consumió ningún byte del frame nuevo; un timeout con un frame empezado es fatal (`BulkFrameStalled`).
4. `UsbTlsCiphertextStream::read` traduce esa inactividad a `io::ErrorKind::TimedOut` sin envenenar (sólo con `pending_read` vacío); todo otro error sigue envenenando.
5. El timeout corto de inactividad se aplica después del handshake (el handshake conserva su presupuesto actual) y nunca es menor a 1 ms (libusb trata 0 como infinito).
6. Fuera de alcance: lado Android (su `readExactly` sobre el `InputStream` del accesorio tiene el mismo riesgo de buffer chico; seguimiento en el worktree Android), pruebas físicas, cambio de modo USB/Wi‑Fi.

## 5. Riesgos

- Sin hardware, el comportamiento de overflow se modela con un fake que rechaza buffers menores que la transferencia; se documenta como modelo, no como prueba física.
- Paquetes de longitud cero (ZLP) siguen siendo fatales; puede requerir ajuste con hardware real.
- Cambio de API pública (variante nueva de error, presupuesto con inactividad).

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits ≤400 líneas como heurística. Revisión nativa RDD pendiente mientras siga el incidente del facade del 2026-09-30. Writer delegado acotado por tarea con lectura del orquestador. Push autorizado por el usuario.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 275 tests, 0 fallos (HEAD `24a8c86`).

1. [x] u1 — Lectura bufferizada de `BulkFrame`: buffer de transferencia + residual; fake que rechaza buffers menores que la transferencia (modelo de overflow). RED: `reads_frame_delivered_in_single_transfer`, `reads_multiple_frames_from_one_transfer`, `reads_frame_split_across_transfers`, `read_buffer_never_smaller_than_transfer`. ~300 líneas.
   - Evidencia: `FramedUsbStream` guarda un residual y lee transferencias completas en un buffer de `read_transfer_len` bytes (`DEFAULT_BULK_READ_TRANSFER_LEN` = 16384; `FrameTransferBudget::with_read_transfer_len` exige múltiplo de 512 distinto de cero, si no `UsbProbeError::InvalidBulkReadTransferLen`). `max_io_attempts` acota transferencias por frame; `OversizeBulkFrame` se valida con el header antes de esperar el payload; lectura de 0 bytes a mitad de frame → `BulkFrameHeaderTruncated`/`BulkFramePayloadTruncated`; conteo mayor al buffer → `BulkTransferCountExceeded`. Fake nuevo `TransferExactBulkIo` (en `src/lib.rs`): entrega cada transferencia entera y falla con `UsbBulkTransferFailed("Overflow")` (texto de `rusb::Error::Overflow`) si el buffer es menor; es un modelo, no prueba física.
   - RED observado (fake agregado, código previo): 5 de 6 tests fallaron con `UsbBulkTransferFailed("Overflow")`, incluido `reads_frame_delivered_in_single_transfer` (`bounds_transfers_per_frame_by_max_io_attempts` pasaba con transferencias de 1 byte). `rejects_invalid_read_transfer_len` falló en compilación (API inexistente).
   - GREEN: `tests/framed_usb_stream_test.rs` 7/7; suite completa 282 pasados, 0 fallos (baseline 275 + 7 nuevos); `cargo fmt -- --check` limpio.
   - Cambios mecánicos declarados en `tests/aoa_boundary_test.rs`: `framed_usb_stream_rejects_backend_read_count_larger_than_buffer_without_panic` ahora usa `limit` = tamaño del buffer de transferencia (antes 8 = header restante); `claimed_bulk_interface_selects_alt_zero_then_exchanges_fixed_frame` espera un solo `read_bulk` (antes header y payload por separado).
   - Escritura sin cambios: `write_frame` ya envía header+payload en un solo `write_bulk`. Seguimiento Android: su `readExactly` del accesorio tiene el riesgo espejo de buffer chico (fuera de alcance, §4.6).
2. [ ] u2 — Timeout tipado e inactividad segura: `BulkReadTimeout`, mapeo de rusb, `BulkFrameStalled`, `TimedOut` sin envenenar en `UsbTlsCiphertextStream`, timeout de inactividad post-handshake. RED: `rusb_read_timeout_maps_to_bulk_read_timeout`, `idle_timeout_before_frame_is_not_fatal`, `timeout_mid_frame_is_fatal_stall`, `idle_bulk_timeout_surfaces_timed_out_without_poisoning`, `write_timeout_still_poisons`. ~350 líneas.
3. [ ] u3 — Runtime sobre USB: `SessionRuntime` sobre `UsbTlsCiphertextStream` con un par bulk cruzado que hace timeout; la sesión sobrevive a la inactividad y recibe frames. RED: `session_runtime_survives_usb_idle_and_receives_frames`. ~250 líneas.

Criterios: suite completa verde sin regresiones; build `--offline`; sin dependencias nuevas.

## Progreso

Plan creado el 2026-10-01.
- u1 implementada (lectura bufferizada + `TransferExactBulkIo`); RED/GREEN observados; suite 282/0; sin commit (lo hace el orquestador).
