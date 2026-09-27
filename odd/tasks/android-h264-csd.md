# CSD H.264 Android

## T21b0b1 — seam fake de CSD desde cambio de formato

### Alcance permitido

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/H264EncoderBoundary.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/H264EncoderBoundaryTest.kt`
- `odd/tasks/android-h264-csd.md`

### Objetivo

Preservar datos codec-specific H.264 cuando el seam del codec informa cambio de formato, sin hacer extracción real de `MediaFormat` todavía.

### Fuente oficial aplicada

La documentación de Android `MediaCodec` indica que, para H.264, `csd-0` contiene SPS y `csd-1` contiene PPS, y cada parameter set ya empieza con `00 00 00 01`. Este corte no antepone otro start code.

Fixture compartida para Android/desktop:

- `csd-0`: `00 00 00 01 67 42 00 1f`
- `csd-1`: `00 00 00 01 68 ce 06 e2`
- combinado: concatenación exacta de ambos buffers.

### Límites

- Sin extracción real de `MediaFormat`; queda para T21b0b2 con fresh grant.
- Sin Activity, USB real, LAN, TLS ni cambios de wire protocol.
- Sin parser H.264 nuevo; sólo copia defensiva de bytes ya tipados por el seam.

### Evidencia RED prevista

- `FormatChanged` con CSD válido debe emitir un chunk `isCodecConfig=true`, PTS `0`, antes del keyframe siguiente.
- `maxOutputs` debe contar el chunk de configuración: con `maxOutputs=1` se entrega sólo CSD y el keyframe queda para el siguiente drain.
- Backpressure pendiente debe impedir que CSD bypassée el límite.
- `FormatChanged` con CSD `null` o vacío no debe inventar configuración.
- Un buffer directo `BUFFER_FLAG_CODEC_CONFIG` debe seguir preservándose.

### Evidencia RED observada

`H264EncoderBoundaryTest` falló en compilación porque `H264CodecOutput.FormatChanged` no aceptaba `codecConfigBytes`.

### Evidencia GREEN

- Focused `H264EncoderBoundaryTest` — PASS.
- `FormatChanged` con CSD válido emite chunk de configuración PTS `0` antes del keyframe y copia defensivamente los bytes.
- `maxOutputs=1` entrega sólo CSD; el keyframe queda para un drain posterior y el backpressure pendiente bloquea antes de emitir más.
- `FormatChanged` sin CSD o con CSD vacío sólo registra formato y no inventa configuración.
- Los buffers directos `BUFFER_FLAG_CODEC_CONFIG` se siguen preservando.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `:android:usb-probe:lintDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier — PASS.
