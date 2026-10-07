# UX de recuperación de errores en el teléfono (T27, Android)

## 1. Objetivo

Que cada falla que ve el usuario en el teléfono tenga un mensaje en español sin texto técnico y una acción clara para salir de ella, y que una sesión que termina por error quede avisada también en una notificación.

## 2. Problema (mapa del 2026-10-07)

- Los errores de cámara, encoder, captura, drenado y envío muestran el `reason` crudo de la plataforma o `error.message` (`VisibleCameraPipelineController.kt:59-88`, `AndroidVisibleCameraPipelineLauncher.kt:30-63`); aparece la palabra "Backpressure".
- El fin de sesión pierde la causa (PeerDead, Backpressure, WriteFailed, ReadFailed, ProtocolViolation) al convertirse en texto (`SessionEgressBinding.kt:88,119`).
- `Failed` siempre ofrece `[START_PAIRING, DIAGNOSTICS]`, sea cual sea la causa; no hay "Reintentar", "Abrir ajustes" ni "Vincular de nuevo". Con sesión activa y error de cámara sólo se ofrece "Desconectar".
- Al fallar, el servicio quita la notificación y no publica ninguna de error, en contra de la decisión del 2026-10-01.

## 3. Decisiones

1. **Usuario, 2026-10-01 (vigente):** ante un fin de sesión el teléfono detiene cámara y encoder, publica el error en el estado visible **y en una notificación**, y el usuario reinicia. Sin reconexión automática (T19).
2. **Orquestador (delegación de UI):**
   - Catálogo tipado de fallas → mensaje en español + acción de recuperación. El detalle técnico va sólo a `Log`, nunca a la UI.
   - Acciones: **Reintentar** (vuelve a conectar con la última PC usada, manual), **Abrir ajustes** (permiso de cámara negado → `ACTION_APPLICATION_DETAILS_SETTINGS`), **Vincular de nuevo** (PC no confiable o identidad cambiada), **Reintentar cámara** con sesión activa (usa la reconfiguración de T26) además de **Desconectar**.
   - Notificación de error no persistente que abre la pantalla de conexión.
   - Wi-Fi queda fuera de alcance (sólo existe el transporte de loopback de T16).

## 4. Contrato

1. `UserFailure` (o equivalente) con causa tipada, mensaje y `RecoveryAction`; la causa de fin de sesión se conserva hasta la UI.
2. `ConnectionScreenPlanner` elige las acciones según la causa; `ConnectionActivity` las ejecuta.
3. El servicio publica una notificación de error al terminar la sesión por falla.

## 5. Riesgos

- Abrir los ajustes del sistema y las notificaciones (permiso `POST_NOTIFICATIONS` en Android 13+) no se prueban sin dispositivo.
- "Reintentar" depende de recordar la última PC; si fue olvidada, cae a "Vincular".

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 530/2/0).

1. [x] r1 — catálogo tipado de fallas de cámara/pipeline y de fin de sesión, sin texto técnico en la UI. RED: `camera_open_failure_shows_spanish_message_without_platform_reason`. ~300 líneas.
   - Evidencia r1: RED observado al exponer el motivo de plataforma en la apertura; el catálogo en español separa mensaje y causa, y la causa llega al estado visible de cámara y al fin de sesión sin mostrar el detalle técnico. Una infracción de protocolo indica incompatibilidad o falla, no pérdida de confianza: se recomienda actualizar y reintentar, sin volver a vincular.
2. [x] r2 — acciones de recuperación en la pantalla de conexión (Reintentar, Abrir ajustes, Vincular de nuevo, Reintentar cámara). RED: `failed_connection_offers_retry_for_last_desktop`. ~350 líneas.
   - Evidencia r2: RED observado por referencias sin implementar; fallas tipadas, última computadora en memoria y acciones de recuperación conectadas a la pantalla. Dos RED adicionales confirmaron que, sin sesión, reintentar cámara no tenía efecto y que volver a vincular duplicaba el botón de vinculación. Ahora la falla de cámara sin sesión ofrece reconexión manual sólo si hay última computadora; volver a vincular conserva únicamente Diagnóstico como acción secundaria. Pruebas focalizadas y suite completa con ensamblado y lint correctos (546 pruebas, 2 omitidas, 0 fallas).
3. [x] r3 — notificación de error al terminar la sesión por falla. RED: `session_failure_posts_error_notification`. ~200 líneas.
   - Evidencia r3: RED observado por el notifier aún inexistente y GREEN focalizado; el cierre por falla conserva el mensaje en el estado visible y publica una notificación descartable tras retirar la de cámara. La alerta abre la pantalla de conexión y se cancela al iniciar otra sesión; el cierre voluntario o local no alerta. En Android 13+ se solicita el permiso una vez sin detener la conexión si se deniega. Canal «Errores» de importancia normal separado del canal de cámara de importancia baja; pruebas focalizadas, suite completa, ensamblado y lint correctos (553 pruebas, 2 omitidas, 0 fallas).
4. [ ] r4 — cierre: suite completa, guía de problemas en `docs/uso.md`, plan general, push.

## 8. Evidencia
