# ODD: boundary de sink de video codificado en desktop

## Estado

- Rama: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base limpia esperada: `b3faac8`
- Estado inicial: plan creado antes de escribir código fuente.

## Contexto

El plan paraguas define M5 como recepción/decodificación en desktop. Esta unidad adelanta una dependencia pequeña y pura de Rust: un boundary de recepción de chunks de video ya codificados. No define el protocolo de sesión Android, no asume el wire format de `SessionFrame`, no abre USB/Wi‑Fi/sockets, no descifra, no decodifica H.264 y no publica una cámara virtual.

## Objetivo

Crear un módulo de dominio testeable que acepte chunks de video codificado con metadata explícita y los entregue a un sink inyectado o a una cola acotada con backpressure observable.

## Alcance permitido

- Modelo owned de chunk codificado.
- Metadata tipada:
  - PTS/timestamp de presentación;
  - indicador de configuración/codec config;
  - indicador de keyframe;
  - identificador de stream local, sin semántica de transporte.
- Errores tipados para payload vacío, payload sobre límite, capacidad inválida y backpressure.
- Sink inyectable para pruebas y adapters futuros.
- Cola acotada en memoria para desacoplar recepción y consumo.
- Export del módulo desde `desktop/usb-probe/src/lib.rs`.

## Fuera de alcance

- USB, Wi‑Fi, sockets o listeners.
- Criptografía, pairing o autoridad local.
- Packet framing, `SessionFrame` o compatibilidad con el trabajo Android paralelo.
- Decoder real, virtual camera o OBS/Media Foundation.
- Claims de soporte físico, latencia real o compatibilidad con apps externas.

## Superficies de edición autorizadas

- `desktop/usb-probe/src/lib.rs`
- `desktop/usb-probe/src/encoded_video_sink.rs` nuevo
- `desktop/usb-probe/tests/encoded_video_sink_test.rs` nuevo
- `odd/tasks/desktop-encoded-video-sink.md`

## Plan TDD

1. RED: escribir tests de API pública para construir chunks, rechazar inputs inválidos, enviar a sink inyectado y aplicar backpressure en cola acotada.
2. Ejecutar `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml encoded_video_sink` y observar fallos por símbolos ausentes.
3. GREEN: implementar el módulo mínimo.
4. Reejecutar focused tests y luego `cargo test --manifest-path desktop/usb-probe/Cargo.toml`.
5. Ejecutar `git diff --check`.
6. Hacer revisión local independiente del diff.
7. No iniciar revisión nativa RDD mientras el worktree Android paralelo tenga una transacción activa en el clone común.
8. Commit local Conventional Commit si las verificaciones cierran.

## Criterios de aceptación

- El código compila sin dependencias nuevas.
- Los tests demuestran que el chunk es owned y no depende de lifetimes externos.
- La cola rechaza capacidad cero y devuelve error cuando está llena, sin descartar silenciosamente.
- El sink inyectado recibe metadata y bytes intactos.
- La documentación mantiene las promesas limitadas: boundary puro, no transporte, no decode, no cámara virtual.

## Evidencia

Pendiente de completar durante la ejecución:

- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml encoded_video_sink` falló por imports ausentes (`BoundedEncodedVideoQueue`, `EncodedVideoChunk`, `EncodedVideoChunkLimits`, `EncodedVideoFrameKind`, `EncodedVideoSink`, `EncodedVideoSinkError`, `PresentationTimestamp`).
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml encoded_video_sink` pasó con 5 tests del sink.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 44 tests existentes + 5 nuevos + doctests.
- Formato: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó después de formatear.
- `git diff --check`: pasó sin salida.
- Revisión independiente: `gentle-ai-verify` PASS; verificó archivos acotados, focused/full tests y `git diff --check`; sin blockers.
- Revisión nativa RDD: `review-68037388e1cdc56e` aprobada y acknowledged; authority burned; target `sha256:4dec3e6e513feea685dd333c7a9b8af52599656ea7b5a9294bd8881e0b909dde`; consumed revision `sha256:b5611d68bd16b6251e67f416244890eb0f2358a39936def4ded781f171a75de9`.
- Advisory informativo no bloqueante: `R3-oversized-capacity-panic`. Seguimiento acotado recomendado antes de aceptar capacidad de cola no confiable: limitar o validar capacidades extremadamente grandes para evitar pánico de reserva en `VecDeque::with_capacity`.
- Commit local: pendiente.
