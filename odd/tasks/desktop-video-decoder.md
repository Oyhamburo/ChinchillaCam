# ODD: Límite decoder de video desktop

## Estado

- Rama: `feat/desktop-video-sink`
- Base para T21a1: `04679ec test(desktop): cover max receiver sequence`
- Revisión nativa: no iniciar mientras el consentimiento/review esté bloqueado.

## Objetivo

Agregar un límite honesto entre el receptor desktop real y un decoder H.264, sin implementar todavía backend de sistema operativo ni afirmar soporte de decodificación. El primer corte conecta `DesktopVideoSessionReceiver` con un adapter `EncodedVideoSink` que entrega chunks codificados al límite decoder.

## Hallazgo read-only

- No existe decoder H.264 real en desktop hoy.
- `desktop/usb-probe/Cargo.toml` sólo depende de `rusb`; no hay VideoToolbox, Media Foundation, FFmpeg ni GStreamer.
- `encoded_video_sink.rs` modela chunks H.264 codificados y una cola acotada.
- `desktop_receiver.rs` recibe `SessionFrame` type5/type8/type9, valida/reensambla y empuja bytes codificados a `EncodedVideoSink`.
- Los documentos de requisitos/factibilidad mencionan salida de cámara virtual/OBS/Media Foundation, pero no definen un decoder H.264 desktop implementado.

## Alcance T21a1

- Crear un módulo `video_decoder` con un trait de límite decoder para chunks codificados.
- Crear `DecodingEncodedVideoSink<D>` que implemente `EncodedVideoSink` y conecte el receptor real con el límite decoder.
- Cubrir sólo type8 `CodecConfig`, `Key` y `Delta` desde `DesktopVideoSessionReceiver` hacia un `RecordingDecoder` de test.
- Preservar PTS, kind, bytes y orden de entrega.
- Propagar backpressure/fallo tipado del decoder hacia `DesktopReceiverError::SinkRejected` por medio de `EncodedVideoSinkError`.
- Mantener comportamiento existente de sink/queue y `receive_desktop_video_frame` stateless.

## Fuera de alcance

- Backend VideoToolbox, Media Foundation, FFmpeg, GStreamer o pixels decodificados.
- Políticas genéricas de exigir `CodecConfig` o keyframe antes de delta; cada backend real definirá sus requisitos.
- Métricas de FPS, preview, OBS, webcam, USB real, LAN, auth/PoP, hardware, push, merge o revisión nativa.

## Pruebas T21a1

- RED→GREEN: type8 `CodecConfig` recibido por `DesktopVideoSessionReceiver` llega al decoder con PTS, kind y bytes intactos.
- RED→GREEN: type8 `Key` y `Delta` llegan al decoder en orden después de config.
- RED→GREEN: backpressure/fallo del decoder se propaga como `DesktopReceiverError::SinkRejected` con causa tipada.

## Alcance T21a2

- Slice de integración de tests desde `94f68a6 feat(desktop): add decoder sink boundary`.
- Cubrir type9 fragmentado de dos frames a través de `DesktopVideoSessionReceiver` y `DecodingEncodedVideoSink`.
- Verificar que el fragmento parcial no entrega nada al decoder.
- Verificar que el fragmento final entrega exactamente un `EncodedVideoChunk` access unit con H264 concatenado, PTS y kind preservados.
- Verificar que `VideoDecoderError::Backpressure` y `VideoDecoderError::Failure` en el fragmento final se propagan como `DesktopReceiverError::SinkRejected`, dejan el receiver stateful terminal (`Closed`) y no hacen retry.
- Verificar que `reset_for_new_session()` más un decoder fresco permite una secuencia nueva.
- Mantener fuera de alcance pixels decodificados, FPS, backend OS, OBS/webcam, USB real, LAN o auth.

## Alcance T21b0a

- Slice puro de parser/config H.264 desde `8d7f441 test(desktop): cover decoder fragment flow`.
- Agregar API explícita para input framing `AnnexB`, `AvccLengthPrefixed` o `Unknown`; no autodetectar Annex-B/AVCC de forma ambigua.
- Soportar sólo CSD Annex-B con start codes de 3 o 4 bytes, NAL bytes sin start code en la salida.
- Rechazar `AvccLengthPrefixed` y `Unknown` con `UnsupportedFraming`.
- Límites: config hasta 256 KiB, máximo 64 NAL units, parameter set hasta 128 KiB, exactamente un SPS type 7 y un PPS type 8.
- Rechazar NAL vacío, SPS/PPS faltante, duplicado, over limit, header inválido, truncation/adversarial input y ceros finales ambiguos, sin panic ni overflow.
- No implementar decode, pixels, VideoToolbox, CoreMedia, Media Foundation ni inferencia mágica desde buffers Android raw.
- T21b0b preservará `csd-0`/`csd-1` Android en otro worktree/corte.

## Alcance T21b1

- Slice puro de conversión de access units H.264 Annex-B a muestras H.264 con prefijo de longitud big-endian de 4 bytes desde `964393a feat(desktop): parse h264 annex-b config`.
- No es backend, no decodifica, no usa VideoToolbox y no afirma pixels ni compatibilidad real.
- Reutilizar el scanner Annex-B del parser de config en vez de duplicar lógica.
- Aceptar sólo entrada explícita `H264InputFraming::AnnexB`; rechazar `AvccLengthPrefixed` y `Unknown` sin autodetección.
- Límites: access unit hasta 4 MiB, máximo 1024 NAL units, cada longitud NAL validada para prefijo `u32` big-endian.
- Soportar start codes de 3 y 4 bytes; rechazar input vacío, start code ausente, NAL vacío/truncado, header inválido, ceros finales ambiguos y NAL count excesivo.
- El converter devuelve sólo bytes convertidos; PTS y kind se preservan en el caller.
- Fixture binario local `tests/fixtures/t21b1-16x16-idr.h264`, 873 bytes, SHA-256 `f3675fa51432d06661e25c05b89952d073111de276ae6229cfb74609476ef8a4`.
- Comando de procedencia del fixture, no requerido por CI:

```bash
/opt/homebrew/bin/ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=16x16:rate=1 \
  -frames:v 1 \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -x264-params keyint=1:min-keyint=1:scenecut=0 \
  -f h264 t21b1-16x16-idr.h264
```

## Pruebas T21b1

- RED→GREEN: fixture sintético contiene SPS/PPS/SEI/IDR y se convierte a NALs con prefijos de longitud BE de 4 bytes sin depender de FFmpeg en CI.
- RED→GREEN: se preservan bytes NAL y el caller conserva PTS/kind alrededor del converter bytes-only.
- RED→GREEN: soporta start codes de 3 y 4 bytes.
- RED→GREEN: rechaza límites y entradas adversariales: vacío, ausente, truncado, NAL vacío, ceros finales ambiguos, demasiado grande, más de 1024 NALs, y framing AVCC/Unknown.

## Siguientes cortes

- T21b0b: preservar `csd-0`/`csd-1` Android como bytes reales antes de depender de ellos en desktop.
- T21b2: crear descripción de formato macOS con `CMVideoFormatDescriptionCreateFromH264ParameterSets` usando bindings `objc2-*`, con grant fresco.
- T21b3: construir `CMBlockBuffer`/`CMSampleBuffer` length-prefixed con PTS, con grant fresco.
- T21b4: smoke real `VTDecompressionSessionDecodeFrame` macOS con callback síncrono y ownership explícito, con grant fresco.
- T21c: backend Windows Media Foundation real en runner/host Windows o con estrategia cfg clara.

## Evidencia

- ODD/Engram mirror creado antes de editar source.
- T21a2 ODD/Engram transition registrada antes de editar tests.
- T21b0a ODD/Engram transition registrada antes de editar source.
- T21b1 ODD/Engram transition registrada antes de editar source.
