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

## Siguientes cortes

- T21b0b: preservar `csd-0`/`csd-1` Android como bytes reales antes de depender de ellos en desktop.
- T21b: backend macOS VideoToolbox real, sólo con investigación oficial y grant fresco.
- T21c: backend Windows Media Foundation real en runner/host Windows o con estrategia cfg clara.

## Evidencia

- ODD/Engram mirror creado antes de editar source.
- T21a2 ODD/Engram transition registrada antes de editar tests.
- T21b0a ODD/Engram transition registrada antes de editar source.
