# UX de recuperación de errores en el teléfono (T27, Android) (Android y desktop)

Este documento reúne los registros de las dos ramas, integradas en `main` el 2026-10-07 (`odd/tasks/branch-integration.md`). Cada parte conserva su evidencia original sin cambios.

## Parte Android (rama `feat/t15c-fake-usb-sustained`)

## UX de recuperación de errores en el teléfono (T27, Android)

### 1. Objetivo

Que cada falla que ve el usuario en el teléfono tenga un mensaje en español sin texto técnico y una acción clara para salir de ella, y que una sesión que termina por error quede avisada también en una notificación.

### 2. Problema (mapa del 2026-10-07)

- Los errores de cámara, encoder, captura, drenado y envío muestran el `reason` crudo de la plataforma o `error.message` (`VisibleCameraPipelineController.kt:59-88`, `AndroidVisibleCameraPipelineLauncher.kt:30-63`); aparece la palabra "Backpressure".
- El fin de sesión pierde la causa (PeerDead, Backpressure, WriteFailed, ReadFailed, ProtocolViolation) al convertirse en texto (`SessionEgressBinding.kt:88,119`).
- `Failed` siempre ofrece `[START_PAIRING, DIAGNOSTICS]`, sea cual sea la causa; no hay "Reintentar", "Abrir ajustes" ni "Vincular de nuevo". Con sesión activa y error de cámara sólo se ofrece "Desconectar".
- Al fallar, el servicio quita la notificación y no publica ninguna de error, en contra de la decisión del 2026-10-01.

### 3. Decisiones

1. **Usuario, 2026-10-01 (vigente):** ante un fin de sesión el teléfono detiene cámara y encoder, publica el error en el estado visible **y en una notificación**, y el usuario reinicia. Sin reconexión automática (T19).
2. **Orquestador (delegación de UI):**
   - Catálogo tipado de fallas → mensaje en español + acción de recuperación. El detalle técnico va sólo a `Log`, nunca a la UI.
   - Acciones: **Reintentar** (vuelve a conectar con la última PC usada, manual), **Abrir ajustes** (permiso de cámara negado → `ACTION_APPLICATION_DETAILS_SETTINGS`), **Vincular de nuevo** (PC no confiable o identidad cambiada), **Reintentar cámara** con sesión activa (usa la reconfiguración de T26) además de **Desconectar**.
   - Notificación de error no persistente que abre la pantalla de conexión.
   - Wi-Fi queda fuera de alcance (sólo existe el transporte de loopback de T16).

### 4. Contrato

1. `UserFailure` (o equivalente) con causa tipada, mensaje y `RecoveryAction`; la causa de fin de sesión se conserva hasta la UI.
2. `ConnectionScreenPlanner` elige las acciones según la causa; `ConnectionActivity` las ejecuta.
3. El servicio publica una notificación de error al terminar la sesión por falla.

### 5. Riesgos

- Abrir los ajustes del sistema y las notificaciones (permiso `POST_NOTIFICATIONS` en Android 13+) no se prueban sin dispositivo.
- "Reintentar" depende de recordar la última PC; si fue olvidada, cae a "Vincular".

### 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

### 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 530/2/0).

1. [x] r1 — catálogo tipado de fallas de cámara/pipeline y de fin de sesión, sin texto técnico en la UI. RED: `camera_open_failure_shows_spanish_message_without_platform_reason`. ~300 líneas.
   - Evidencia r1: RED observado al exponer el motivo de plataforma en la apertura; el catálogo en español separa mensaje y causa, y la causa llega al estado visible de cámara y al fin de sesión sin mostrar el detalle técnico. Una infracción de protocolo indica incompatibilidad o falla, no pérdida de confianza: se recomienda actualizar y reintentar, sin volver a vincular.
2. [x] r2 — acciones de recuperación en la pantalla de conexión (Reintentar, Abrir ajustes, Vincular de nuevo, Reintentar cámara). RED: `failed_connection_offers_retry_for_last_desktop`. ~350 líneas.
   - Evidencia r2: RED observado por referencias sin implementar; fallas tipadas, última computadora en memoria y acciones de recuperación conectadas a la pantalla. Dos RED adicionales confirmaron que, sin sesión, reintentar cámara no tenía efecto y que volver a vincular duplicaba el botón de vinculación. Ahora la falla de cámara sin sesión ofrece reconexión manual sólo si hay última computadora; volver a vincular conserva únicamente Diagnóstico como acción secundaria. Pruebas focalizadas y suite completa con ensamblado y lint correctos (546 pruebas, 2 omitidas, 0 fallas).
3. [x] r3 — notificación de error al terminar la sesión por falla. RED: `session_failure_posts_error_notification`. ~200 líneas.
   - Evidencia r3: RED observado por el notifier aún inexistente y GREEN focalizado; el cierre por falla conserva el mensaje en el estado visible y publica una notificación descartable tras retirar la de cámara. La alerta abre la pantalla de conexión y se cancela al iniciar otra sesión; el cierre voluntario o local no alerta. En Android 13+ se solicita el permiso una vez sin detener la conexión si se deniega. Canal «Errores» de importancia normal separado del canal de cámara de importancia baja; pruebas focalizadas, suite completa, ensamblado y lint correctos (553 pruebas, 2 omitidas, 0 fallas).
4. [x] r4 — cierre: suite completa, guía de problemas en `docs/uso.md`, plan general, push.
   - Evidencia r4: verificación independiente sobre `dd9a6b9`: 553 tests, 2 omitidos, 0 fallas; `assembleDebug` y `lintDebug` aprobados; revisión estática sin defectos (sin texto técnico en la UI, las cuatro acciones despachadas y con efecto en su estado, notificación sólo en fallas y cancelada al iniciar sesión, permiso pedido una vez y sólo en API 33+). Guía en `docs/uso.md` §6 y §9.6.

### 8. Evidencia

- Commits: `d01205d` (r1, catálogo tipado), `183e6a9` (r2, acciones de recuperación), `dd9a6b9` (r3, notificación de error) y el commit de cierre.
- Readback del orquestador: `SessionProtocolViolation` pasó de "Vincular de nuevo" a "Reintentar" (datos inesperados indican versiones distintas o un bug, no un problema de confianza); fuera de sesión, una falla de cámara ofrece "Reintentar" (reconectar) en lugar de "Reintentar cámara", que no hace nada sin sesión; "Vincular de nuevo" ya no se duplica con "Vincular una computadora".
- Sin ejecutar: notificaciones, apertura de ajustes y permisos en un dispositivo real (M9).

### 9. Seguimientos

- "Sin cable" sigue siendo la espera `AwaitingAccessory` con Cancelar, no una falla con Reintentar.
- Los rechazos TLS de reconexión no tienen subcausa tipada: todos ofrecen "Vincular de nuevo", incluso si fueran transitorios.

## Parte desktop (rama `feat/desktop-video-sink`)

## UX de recuperación de errores en el desktop (T27, desktop)

### 1. Objetivo

Que cada falla que ve el usuario en la app de escritorio diga qué pasó y qué hacer, sin texto técnico, y que el usuario note cuando hay conexión pero no llega video.

### 2. Problema (mapa del 2026-10-07)

- `messages.rs` colapsa todo `SessionEnded(Failed(_))` en "La sesión terminó por un error." y `Pipeline`/`PairingQr`/`TrustedPhoneStore` en "Ocurrió un error. Intentá de nuevo."; `Link` no distingue "no hay teléfono" de "error del dispositivo" o "no se pudo cambiar a modo accesorio".
- El enlace USB reintenta cada 2 s y emite un `ConnectionFailed` por intento: el aviso se repite sin cambiar.
- No hay aviso cuando la sesión está conectada pero no llegan fotogramas decodificados; la falla al iniciar el presentador sólo hace `eprintln!` en inglés.
- Los errores de arranque no ofrecen acción.

### 3. Decisiones

1. **Usuario, 2026-10-01 (vigente):** sin reconexión automática del lado del teléfono (T19); el desktop sigue sondeando el USB como hoy.
2. **Orquestador (delegación de UI):**
   - Catálogo tipado de fallas del desktop → mensaje en español + sugerencia de acción (por ejemplo "Conectá el teléfono con el cable y abrí ChinchillaCam en el teléfono", "Desbloqueá el teléfono y aceptá el permiso USB", "Volvé a vincular el teléfono").
   - Fallas repetidas de enlace con la misma causa no reemiten el aviso.
   - Vigía de video: conectado y sin fotogramas decodificados durante 5 s → aviso "No llega video del teléfono" con sugerencia; se limpia al llegar un fotograma.
   - La falla al iniciar el presentador se muestra en la UI.

### 4. Contrato

1. `messages.rs`: mapeo exhaustivo por causa (`DesktopConnectionFailure`, `DesktopSessionEndReason::Failed(SessionEnd)`, errores USB) a mensaje + sugerencia, con tests.
2. Deduplicación de avisos repetidos en el modelo de vista.
3. Vigía de video puro y testeable sobre la secuencia de la ranura de video y el reloj.

### 5. Riesgos

- Los errores USB reales (permisos de macOS, cables, AOA) sólo se ven con hardware (M9).

### 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

### 7. Tareas

Runner app: `cd desktop/app && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline && PATH=$HOME/.cargo/bin:$PATH cargo clippy --offline --all-targets -- -D warnings`; lib: lo mismo en `desktop/usb-probe` (líneas de base 28/0 y 334/0).

1. [x] e1 — catálogo de fallas por causa con sugerencias y deduplicación de avisos repetidos. RED: `link_failures_distinguish_missing_phone_from_device_error`. ~300 líneas.
   - Evidencia e1: RED observado por ausencia de sugerencia en la vista y por el sondeo demorado tras no encontrar un teléfono; se clasificaron las etapas del enlace, se comprobaron los avisos, la deduplicación y los cierres de sesión y se conservó el sondeo inmediato sin teléfono. La reconexión mantiene un aviso genérico de identidad.
2. [x] e2 — vigía de video (5 s) y falla del presentador visibles en la ventana. RED: `connected_without_frames_for_five_seconds_warns`. ~250 líneas.
   - Evidencia e2: RED observado por falta de `observe_video`; el vigía usa el contador de fotogramas publicados para no confundir el vaciado de la ranura con una llegada, reinicia al reconectar y muestra avisos y sugerencias en la ventana sin reemplazar fallas específicas. Se comprobaron los límites de tiempo, la llegada, la desconexión y la señal de falla del presentador con pruebas sin hardware.
3. [x] e3 — cierre: guía de problemas en `docs/uso.md`, plan general, push.
   - Evidencia e3: verificación independiente sobre `b57d075`: `usb-probe` 336/0 y `desktop/app` 40/0; fmt, clippy y build `--offline` limpios; revisión estática sin defectos en el mapeo exhaustivo, el sondeo sin espera cuando no hay teléfono, la deduplicación, el vigía y la precedencia de avisos. La guía de `docs/uso.md` §9.6 se alineó con los textos exactos de la app.

### 8. Evidencia

### 9. Seguimientos

- Exponer una causa tipada de reconexión para distinguir un teléfono desconocido o revocado de otras fallas de identidad; el error actual de la negociación sólo expone texto. Por ahora se muestra «No se pudo comprobar la identidad del teléfono.» con la sugerencia «Si no está vinculado, vinculalo con el QR.».
- Separar el puerto USB ocupado del acceso denegado: el error de reclamo actual no aporta una categoría tipada fiable. Mantener mientras tanto la sugerencia de cerrar la otra app o permitir el acceso.

- Commits: `fd125b1` (clasificación de fallas del enlace USB), `8a7060e` (e1, catálogo con sugerencias y deduplicación), `b57d075` (e2, vigía de video y falla del presentador) y el commit de cierre.
- Readback del orquestador: «no hay teléfono» no debe activar la espera de 2 s entre reintentos (habría demorado la detección al enchufar); se corrigió con un test RED.
- Sin ejecutar: errores USB reales de macOS, ventana gráfica ni hardware (M9).
