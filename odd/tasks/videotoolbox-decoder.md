# Decoder H.264 nativo en macOS (VideoToolbox)

## 1. Objetivo

Reemplazar el decoder fake por un decoder H.264 real en macOS que implemente el contrato existente (`VideoDecoder` + `DecodedFrameSink`, `odd/tasks/session-pipeline-wiring.md` §4.3) usando VideoToolbox, de modo que `DesktopSessionPipeline` produzca frames decodificados reales.

## 2. Problema

`video_decoder.rs` sólo tiene la frontera y `FakeVideoDecoder`. Los pasos T21b2–T21b4 de `odd/tasks/desktop-video-decoder.md` (descripción de formato, sample buffer, sesión de decompresión) estaban planificados con autorización aparte porque agregan dependencias nativas y código `unsafe` de FFI.

## 3. Decisión

- Decisión del usuario del 2026-10-01: decoder nativo por plataforma, sin FFmpeg, empezando por macOS (Engram `decision/chinchillacam-pipeline-wiring-policies`). El 2026-10-01 el usuario delegó en el orquestador elegir la próxima unidad; se eligió esta. Esa decisión se toma como la autorización de T21b2–T21b4.
- Dependencias: `objc2-core-foundation`, `objc2-core-media`, `objc2-core-video` y `objc2-video-toolbox` 0.3.2 con `default-features = false` y features explícitas, sólo bajo `cfg(target_os = "macos")`. Verificado el 2026-10-01 que resuelven y compilan con `--offline` desde el caché local; con features por defecto arrastran `objc2-core-audio`, que no está en caché.
- Formato de salida: `NV12` (`kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange`) copiado a `DecodedVideoFrame.data` (plano Y seguido del plano UV intercalado, sin padding de stride). La copia es simple y honesta; la optimización sin copia (IOSurface) queda para la salida de cámara virtual.

## 4. Contrato

1. `VideoToolboxDecoder<F: DecodedFrameSink>` implementa `VideoDecoder`: un `CodecConfig` (Annex-B con un SPS y un PPS, vía `parse_h264_config`) crea o recrea la descripción de formato y la sesión; un chunk `Key`/`Delta` antes de la configuración es `Failure`; cada access unit Annex-B se convierte a length-prefixed (`convert_h264_access_unit_to_length_prefixed`) y se decodifica de forma síncrona; cada imagen decodificada se entrega a `F` con el PTS del chunk, ancho y alto reales y formato `Nv12`.
2. Errores de VideoToolbox/CoreMedia se mapean a `VideoDecoderError::Failure(detalle con OSStatus)`; nunca `panic`. Un rechazo del `DecodedFrameSink` por saturación se mapea a `Backpressure` (el `KeyframeGatedSink` decide).
3. Todo el `unsafe` vive en un único módulo `videotoolbox_decoder.rs` con comentarios `SAFETY` por bloque; ownership explícito de objetos CF (retain/release vía los tipos `CFRetained` de `objc2`); el callback de salida no hace `unwind` a través de FFI.
4. En plataformas que no son macOS el módulo no se compila; el resto del crate y la suite no cambian.
5. Fuera de alcance: Media Foundation (Windows), salida a cámara virtual, decodificación asíncrona o con hilo propio, hardware acelerado obligatorio (se acepta el que elija VideoToolbox), escalado o conversión de color.

## 5. Riesgos

- FFI `unsafe`: se acota a un módulo, con tests reales sobre el fixture `tests/fixtures/t21b1-16x16-idr.h264` (SPS, PPS, SEI, IDR) y casos de error.
- Decodificación síncrona dentro del loop de un hilo del runtime: para 1280x720 a 30 fps puede retrasar keepalives; se mide en el test de pipeline y, si hace falta, el hilo propio queda como unidad siguiente.
- Diferencias de comportamiento entre versiones de macOS: sólo se afirma lo probado en esta máquina.

## 6. Reglas de ejecución

TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución); desvíos declarados. Commits ≤400 líneas como heurística. Revisión nativa RDD pendiente mientras siga el incidente del facade del 2026-09-30. Writer delegado acotado por tarea con lectura del orquestador, que verifica `--offline`. Push por decisión del usuario.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline`. Baseline: 258 tests, 0 fallos (HEAD `0b79ab7`).

1. [x] v1 — Dependencias macOS y descripción de formato: `CMVideoFormatDescriptionCreateFromH264ParameterSets` desde `H264ParameterSets`, dimensiones leídas de la descripción. RED: `format_description_reports_fixture_dimensions`, `invalid_parameter_sets_fail_without_panic`. ~250 líneas.
   - Dependencias agregadas en `Cargo.toml` bajo `[target.'cfg(target_os = "macos")'.dependencies]`, todas `0.3.2` con `default-features = false`: `objc2-core-foundation` (`std`, `CFBase`, `CFDictionary`, `CFString`, `CFNumber`), `objc2-core-media` (`std`, `CMBase`, `CMFormatDescription`, `CMBlockBuffer`, `CMSampleBuffer`, `CMTime`, `objc2-core-video`), `objc2-core-video` (`std`, `CVBase`, `CVReturn`, `CVBuffer`, `CVImageBuffer`, `CVPixelBuffer`), `objc2-video-toolbox` (`std`, `VTBase`, `VTErrors`, `VTSession`, `VTDecompressionSession`, `VTDecompressionProperties`, `objc2-core-media`, `objc2-core-video`). `Cargo.lock` sumó `bitflags 2.13.1` y los cuatro crates; resolvió y compiló con `--offline` desde el caché local.
   - `src/videotoolbox_decoder.rs` (sólo macOS): `VideoToolboxFormat::from_parameter_sets` crea la descripción con longitud de cabecera NAL 4, la guarda en `CFRetained<CMFormatDescription>` y expone `dimensions()` leído de `CMVideoFormatDescriptionGetDimensions`; `VideoToolboxError::FormatDescription { status }` lleva el OSStatus y `InvalidDimensions` rechaza dimensiones no positivas. Cada bloque `unsafe` tiene comentario `SAFETY`.
   - RED observado: `cargo test --offline --test videotoolbox_decoder_test` falló con `error[E0432]: unresolved imports usb_probe::VideoToolboxError, usb_probe::VideoToolboxFormat`. GREEN: 2/2; fixture 16x16 y SPS/PPS basura devuelve error tipado con OSStatus distinto de cero, sin `panic`.
   - Suite: `cargo fmt -- --check` limpio, `cargo test --offline` 260 pasan, 0 fallan (baseline 258 + 2). `cargo clippy --offline -- -D warnings` falla sólo por dos lints preexistentes en `src/trusted_phone_store.rs` (`manual_is_multiple_of`, `chunks_exact_to_as_chunks`), sin hallazgos en el módulo nuevo.
2. [ ] v2 — Sesión y decodificación: `CMBlockBuffer`/`CMSampleBuffer` length-prefixed con PTS, `VTDecompressionSession` con callback, copia NV12 a `DecodedVideoFrame`, `VideoToolboxDecoder` implementando `VideoDecoder`. RED: `decodes_fixture_idr_to_one_nv12_frame`, `access_unit_before_config_fails`, `corrupt_access_unit_fails_without_panic`, `sink_backpressure_maps_to_decoder_backpressure`. ~400 líneas.
3. [ ] v3 — Pipeline real: `DesktopSessionPipeline` con `VideoToolboxDecoder` sobre loopback TCP enviando el fixture como `CodecConfig` + `Key`; se mide el tiempo de decodificación por step. RED: `pipeline_decodes_real_h264_over_loopback`. ~250 líneas.

Criterios: suite completa verde sin regresiones; build `--offline`; sin `panic` ante entrada inválida; ningún bind fuera de loopback.

## Progreso

Plan creado el 2026-10-01.

v1 implementado sobre `de9ad3c` sin commit: dependencias objc2 sólo macOS verificadas con `--offline` y descripción de formato H.264 con dimensiones reales; suite 260/0.
