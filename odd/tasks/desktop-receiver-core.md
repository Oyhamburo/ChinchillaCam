# ODD: fake desktop receiver core

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base real para T20a: `55e751c` (cierre B3 documentado en un commit local separado).
- Estado inicial: plan creado antes de escribir código fuente.
- Commit local T20a: `e95110b feat(desktop): add fake video receiver`.

## Contexto

M5/T20a conecta piezas puras ya existentes del desktop: `BulkFrame` outer little-endian, `SessionFrameCodec::decode` inner big-endian y el boundary `EncodedVideoSink`/cola acotada. Esta unidad no lee hardware, no abre USB real, no decodifica H.264 y no publica cámara virtual.

## Objetivo

Crear un receptor fake de escritorio para pruebas que acepte un `BulkFrame` ya recibido, valide que corresponde al stream de sesión Android y entregue sólo payloads `SessionFramePayload::VideoChunk` a un sink de video codificado inyectado.

## Alcance permitido T20a

- Nuevo módulo `desktop_receiver` exportado desde `desktop/usb-probe/src/lib.rs`.
- Constante desktop para el stream Android `USB_SESSION_FRAME_STREAM_ID = 0x01020304`.
- Validación en boundary del stream id del `BulkFrame`.
- Decodificación de `SessionFrame` desde payload del `BulkFrame` manteniendo outer little-endian e inner big-endian.
- Rechazo tipado de stream incorrecto, frame de sesión malformado, payload no-video, PTS negativo, frame-kind desconocido y backpressure/errores del sink.
- Seam inyectado para clasificar `EncodedVideoFrameKind` sin inferir desde `VideoChunk` v1.
- Tests RED/GREEN para preservación Key/CodecConfig/Delta, malformed, wrong stream, nonvideo, negative PTS y queue-full.

## Fuera de alcance

- No decoder H.264 real.
- No claims de decode-ready: `VideoChunk` v1 no transporta keyframe ni codec-config.
- No hardware read, LAN, sockets, crypto, pairing ni cámara virtual.
- No merge/cherry-pick/push/PR/rebase/reset/amend.

## Superficies de edición autorizadas

- `desktop/usb-probe/src/desktop_receiver.rs`
- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `desktop/usb-probe/src/lib.rs`
- `odd/tasks/desktop-receiver-core.md`
- `odd/tasks/desktop-session-frame.md` sólo para cierre B3

## Plan TDD

1. RED: escribir tests públicos para `FakeDesktopReceiver`/clasificador inyectado cubriendo Key/CodecConfig/Delta y fallos tipados.
2. Ejecutar `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver` y observar fallos por símbolos ausentes.
3. GREEN: implementar receptor mínimo y exportarlo.
4. Ejecutar focused tests, full `cargo test`, `cargo fmt --check`, `git diff --check` y diff budget.
5. Ejecutar verificación independiente `gentle-ai-verify`.
6. Commit local Conventional Commit si todas las verificaciones pasan.
7. Reportar READY T20a con base..HEAD/diff/tests y esperar GRANT explícito antes de INSPECT/START nativo.

## Evidencia

- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver` falló por símbolos ausentes (`DesktopReceiverError`, `StaticFrameKindClassifier`, `USB_SESSION_FRAME_STREAM_ID`, `receive_desktop_video_frame`).
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver` pasó con 7 tests del receptor fake.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 44 AOA + 7 desktop receiver + 6 encoded video sink + 13 session frame + 7 trusted-phone store tests + doctests.
- Formato: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó sin salida.
- `git diff --check`: pasó sin salida.
- Diff budget: 340 inserciones / 0 borrados en 4 archivos T20a, dentro del corte <=400 líneas.
- Revisión independiente: `gentle-ai-verify` PASS; confirmó stream id `0x01020304`, decode de `SessionFrame`, rechazos tipados, seam de clasificación Key/CodecConfig/Delta, y ausencia de decoder/hardware/LAN/crypto/cámara virtual.
- T20a `e95110b` + corrección de procedencia `1cd0271`: revisión nativa de `55e751c..1cd0271` aprobada y ACK completado en `review-2d5dead8813633b7`; autoridad consumida. No representa prueba de USB físico ni de decoder.
