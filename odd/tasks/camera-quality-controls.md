# Controles de cámara y calidad en el teléfono (T26)

## 1. Objetivo

Que el usuario elija desde el teléfono la cámara, la resolución y los FPS (o el modo automático) y que el cambio se aplique en vivo durante la sesión USB, sin reconectar. Las opciones que la cámara no soporta, o que no se pueden conocer, se muestran deshabilitadas con el motivo.

## 2. Problema

- `VisibleCameraForegroundService.kt:39` fija `H264EncoderConfig(1280, 720, 2_000_000, 30, 2)`; el catálogo de capacidades (`CameraCapabilityCatalog`) no se consulta para elegir tamaño ni FPS.
- Los FPS sólo llegan a `KEY_FRAME_RATE` del encoder: la captura no fija `CONTROL_AE_TARGET_FPS_RANGE` (`CameraCaptureSessionBoundary.kt:164`).
- No hay forma de reiniciar cámara y encoder sin terminar la sesión: `prepareStart` devuelve null con un handle vivo y todos los caminos de parada del servicio (`ACTION_STOP`, `onDestroy`, falla) terminan la sesión.
- La pantalla de conexión no tiene controles de cámara ni de calidad; `onCameraControlCommand = { }` sigue vacío (`PhoneConnectionRuntime.kt:59`).

## 3. Decisiones

1. **Usuario, 2026-10-07:** los controles viven en el teléfono. El control remoto desde la PC (frame 7 `CAMERA_CONTROL_COMMAND` con capacidades anunciadas) queda para una unidad posterior.
2. **Usuario, 2026-10-07:** el cambio se aplica en vivo, reiniciando sólo cámara y encoder dentro de la sesión abierta. El encoder nuevo emite un SPS/PPS nuevo y un keyframe; el desktop recrea VideoToolbox con cada `CodecConfig` (`videotoolbox_decoder.rs:173`), el reensamblador no exige índices de chunk crecientes y los keepalive (2 s, muerto a los 6 s) sostienen la sesión durante el corte.
3. **Orquestador (dentro de la delegación de UI):**
   - Resoluciones ofrecidas: 1920×1080, 1280×720, 960×540 y 640×480, filtradas por los tamaños `Known` de la cámara. FPS ofrecidos: 30, 24 y 15, filtrados por los rangos `Known` (un rango sirve si `max >= f` y `min <= f`).
   - Modo automático: 1280×720 a 30 FPS si se soporta; si no, la mayor resolución ofrecida que no supere 1280×720 y los mayores FPS ofrecidos.
   - Capacidad `Unknown` o `Unavailable`: se usa 1280×720 a 30 FPS (el comportamiento actual) y el control se muestra deshabilitado con el motivo.
   - Bitrate: `2_000_000 × (ancho × alto × fps) / (1280 × 720 × 30)`, acotado entre 500 kbps y 8 Mbps. Intervalo de I-frame: 2 s.
   - Preferencia guardada en el mismo archivo de `SharedPreferences` que la cámara (`dev.chinchillacam.usbprobe.camera`) bajo una clave nueva `quality_preference`, como un único valor codificado (`auto` o `manual:1280x720@30`) sobre la interfaz existente `StringPreferenceStore`; una preferencia que la cámara actual no soporta cae al modo automático.
   - Reconfiguración por un intent nuevo `ACTION_RECONFIGURE` al servicio, ejecutado en el `startExecutor` (nunca en el hilo principal) y serializado. Si el reinicio falla, se intenta volver a la configuración anterior; si eso también falla, la sesión termina por el camino de falla existente.

## 4. Contrato

1. `CameraQualityPlanner.plan(entry: CameraCatalogEntry?, preference: QualityPreference): QualityPlan`, puro: `encoderConfig`, `fpsRange`, opciones de resolución/FPS con estado habilitado o motivo, y si se aplicó un fallback.
2. `QualityPreferenceStore` sobre `StringPreferenceStore` (modo automático o resolución + FPS explícitos).
3. La captura aplica `CONTROL_AE_TARGET_FPS_RANGE` del plan; el servicio arma el encoder desde el plan en lugar del literal.
4. `VisibleCameraPipelineController.restart(config)` y `VisibleCameraForegroundServicePipelineOwner.reconfigure(...)`: paran sólo el handle, sin cerrar la sesión, y vuelven a arrancar con la configuración nueva.
5. `ConnectionScreenPlanner` agrega la sección de calidad (cámara, resolución, FPS, automático) con opciones deshabilitadas y motivo; `ConnectionActivity` la dibuja, guarda la preferencia y, si hay sesión, envía la reconfiguración.

## 5. Riesgos

- Corte de video de hasta unos 4 s al reiniciar (apertura de cámara y arranque de captura con plazos de 2 s cada uno).
- Cambiar de cámara cierra y reabre el dispositivo; puede fallar si otra app la tiene.
- Si una parada cortara un chunk a medio enviar, el reensamblador del desktop fallaría y terminaría la sesión: la parada debe ocurrir entre chunks (el drain está serializado con el controlador).
- Los tamaños del catálogo son de `YUV_420_888`, no necesariamente del encoder: el encoder puede rechazar un tamaño; en ese caso aplica el fallback del punto 3.
- Sin validación física (M9): codecs reales, cambio de cámara en Samsung, cambio de tamaño en la ventana de video del desktop.

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` (línea de base 496/2/0).

1. [x] q1 — `QualityPreference` + store + `CameraQualityPlanner` puro (opciones, automático, fallback, bitrate). RED: `automatic_prefers_720p30_when_supported`. ~300 líneas.
   - Evidencia q1: RED observado por referencias no resueltas a `CameraQualityPlanner` y `QualityPreference` en `automatic_prefers_720p30_when_supported`; GREEN focalizado 13/0/0; runner completo 509 pruebas, 2 omitidas, 0 fallas (base 496/2/0); `assembleDebug` y `lintDebug` correctos; `git diff --check` correcto.
2. [x] q2 — la captura aplica el rango de FPS y el servicio arma el encoder desde el plan. RED: `capture_request_targets_planned_fps_range`. ~250 líneas.
   - Evidencia q2: RED observado por argumento excedente en `startRepeating` para `capture_request_targets_planned_fps_range`; GREEN focalizado para captura y propietario del servicio; runner completo 513 pruebas, 2 omitidas, 0 fallas; `assembleDebug` y `lintDebug` correctos. El plan se resuelve en el ejecutor de inicio; si falla la resolución, se usa el plan automático sin rango de FPS.
3. [x] q3 — reconfiguración en vivo (`restart` del controlador, `reconfigure` del owner, `ACTION_RECONFIGURE`, vuelta atrás si falla). RED: `reconfigure_restarts_pipeline_without_ending_session`. ~350 líneas.
   - Evidencia q3: RED observado por referencias no resueltas a `Reconfigured` y `handleReconfigureCommand`; GREEN focalizado; runner completo 523 pruebas, 2 omitidas, 0 fallas; `assembleDebug`, `lintDebug` y `git diff --check` correctos. La parada y el reinicio afectan sólo al pipeline; si falla el nuevo arranque, se intenta recuperar el plan anterior.
4. [x] q4 — UI de calidad en la pantalla de conexión (planner + activity + preferencia + envío de la reconfiguración). RED: `connected_screen_offers_supported_quality_options`. ~350 líneas.
   - Evidencia q4: RED observado por referencias no resueltas al planificador y al campo de calidad; GREEN focalizado; runner completo sin conexión 530 pruebas, 2 omitidas, 0 fallas; `assembleDebug` y `lintDebug` correctos. El catálogo se actualiza fuera del hilo principal al reanudar; las capacidades incompletas deshabilitan las opciones manuales y la reconfiguración sólo se envía durante una sesión conectada.
5. [ ] q5 — cierre: suite completa, evidencia, `complete-webcam-product.md`, push.

## 8. Evidencia
