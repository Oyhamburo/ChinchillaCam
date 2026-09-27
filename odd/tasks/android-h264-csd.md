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

## T21b0b2 — captura real de CSD desde MediaFormat

### Alcance permitido

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/H264EncoderBoundary.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/H264EncoderBoundaryTest.kt`
- `odd/tasks/android-h264-csd.md`

### Objetivo

Extraer `csd-0` y `csd-1` desde `MediaFormat` en `INFO_OUTPUT_FORMAT_CHANGED`, validar que son CSD AVC con start codes ya presentes, pasarlos por el seam de T21b0b1 y suprimir el buffer `BUFFER_FLAG_CODEC_CONFIG` duplicado cuando sea byte a byte idéntico.

### Reglas

- Copiar sólo `position..limit` de cada `ByteBuffer`; no mutar buffers originales.
- No agregar start codes: cada parameter set debe traer `00 00 00 01`.
- Aceptar sólo SPS tipo NAL 7 en `csd-0` y PPS tipo NAL 8 en `csd-1`.
- Rechazar CSD faltante, vacío, sin start code, con tipo NAL incorrecto o cada parte mayor que 128 KiB + start code o combinado mayor a 256 KiB.
- Si el formato vendor no cumple estas reglas, no se emite configuración falsa.
- Suprimir sólo un buffer directo `BUFFER_FLAG_CODEC_CONFIG` idéntico al último CSD emitido por `FormatChanged`; buffers distintos y keyframes nunca se suprimen.

### Evidencia RED prevista

- `extractAvcCsd(csd0, csd1)` debe copiar defensivamente `position..limit` y concatenar bytes exactos.
- CSD faltante o malformado debe devolver `null`.
- Un buffer `BUFFER_FLAG_CODEC_CONFIG` idéntico al CSD de `FormatChanged` debe liberarse una vez, no incrementar pending y no aparecer como chunk.
- Un buffer config distinto debe preservarse.
- Un keyframe con bytes iguales no debe suprimirse.

### Evidencia RED observada

`H264EncoderBoundaryTest` falló en compilación porque no existía `extractAvcCsd(csd0, csd1)`.

### Evidencia GREEN

- Focused `H264EncoderBoundaryTest` — PASS.
- `extractAvcCsd` copia `position..limit`, no muta los buffers originales y concatena bytes exactos ya prefijados.
- CSD faltante, vacío, sin start code, con tipo NAL incorrecto o con parte mayor que 128 KiB + start code o combinado mayor que 256 KiB devuelve `null`.
- `AndroidH264CodecSession` pasa `csd-0`/`csd-1` validados desde `MediaFormat` hacia `FormatChanged` sin agregar start codes.
- Un buffer directo `BUFFER_FLAG_CODEC_CONFIG` idéntico al último CSD emitido por `FormatChanged` se libera una vez, no incrementa pending y no aparece como chunk.
- Un buffer config distinto se preserva y un keyframe nunca se suprime por bytes iguales, incluso si también trae flag codec-config.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `:android:usb-probe:lintDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier — PASS.
