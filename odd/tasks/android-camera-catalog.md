# Android camera catalog capability task

> Estado (2026-09-26): T1 y T1b completos; T2 adapter real `CameraManager` en progreso desde HEAD limpio `4db5cb9`. Rama local `feat/android-camera-catalog` creada desde el HEAD limpio real `0586792` de `proto/usb-bulk-tdd-retry`. Commits locales autorizados; sin push, PR ni merge.

## Alcance

Construir el primer dominio Kotlin/JVM para catalogar capacidades Camera2 con un gateway inyectable y pruebas fake, sin abrir `CameraDevice`, sin iniciar captura, sin UI de cámara y sin prometer soporte de cámaras físicas específicas.

## Restricciones

- TDD estricto: RED antes de producción, luego GREEN y refactor si cabe.
- Edit surfaces previstos para T1:
  - `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalog.kt`
  - `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalogTest.kt`
  - `odd/tasks/android-camera-catalog.md`
- No editar `AndroidManifest.xml` en T1 salvo que una necesidad real aparezca; no se espera.
- No abrir `CameraDevice`, no crear sesión de captura, no tocar permisos runtime/cámara UI.
- No hardcodear Samsung Note10/S24+ ni afirmar cámaras físicas seleccionables sin soporte de API.
- Documentación visible en español; código y símbolos en inglés.
- T6 USB sigue pendiente de validación física; no marcarlo completo por documentación.

## Evidencia de API usada como hipótesis

Fuente indicada por el usuario: Android Developers `CameraManager` / Camera2.

Hechos a reflejar en el dominio:

- `CameraManager.getCameraIdList()` lista cámaras directamente direccionables por ID y puede excluir lentes que existen solo como cámaras físicas dentro de una cámara lógica.
- La presencia de un ID en `getCameraIdList()` no garantiza que `openCamera` vaya a tener éxito: puede fallar por desconexión, uso por otra app, permisos u otra condición operativa. El catálogo solo puede marcar un ID como candidato direccionable/listado, no como apertura garantizada.
- `CameraCharacteristics.getPhysicalCameraIds()` puede revelar IDs físicos agrupados bajo una cámara lógica.
- Desde API 29, `getCameraCharacteristics(String)` puede consultar características de IDs físicos, pero esos IDs físicos no necesariamente se pueden abrir directamente.

## T1 — Dominio de catálogo Camera2 con gateway fake

Objetivo: producir una función de catálogo que reciba un gateway inyectable y devuelva:

- IDs standalone/direct-open-candidate reportados por `getCameraIdList()`, con disponibilidad operativa desconocida;
- IDs physical-only child asociados a un logical camera parent cuando aparecen en `physicalCameraIds` pero no en la lista standalone;
- facing;
- tamaños disponibles;
- rangos FPS disponibles;
- disponibilidad de controles como estado `Known`, `Unknown` o `Unavailable`.

Estados esperados:

- `Known(value)` cuando el gateway entrega valor concreto.
- `Unknown(reason)` cuando la API no entrega dato o no se pudo consultar.
- `Unavailable(reason)` cuando el capability no aplica.

## Verificación requerida T1

- RED: pruebas JVM fallan antes de `CameraCapabilityCatalog.kt` o sus APIs de producción.
- GREEN:
  - `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`
  - `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`
  - `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`
  - `git diff --check`
- Verificación independiente read-only.
- Revisión nativa RDD en boundary de commit.

## Riesgos conocidos heredados antes de pruebas físicas

Estos advisories de T5d3 son conocidos y no deben iniciar un bucle automático infinito mientras se avanza en el catálogo; quedan como hardening pre-físico si se decide retomarlos:

- `R3-fingerprint-length-overflow`
- `R3-recreation-restarts-timeout`
- `R3-waiting-state-without-request`

## Plan secuencial

- [x] T1: agregar dominio `CameraCapabilityCatalog` con gateway fake y pruebas JVM. Commit `d75495b` (`feat: add Android camera capability catalog`) implementó `CameraCapabilityCatalog.kt` y `CameraCapabilityCatalogTest.kt`; revisión nativa RDD aprobada y reconocida en lineage `review-ff86e4f59c053d11`.
- [x] T1b: endurecer catálogo ante fallos parciales y orden no determinista. Commit `9558ca3` (`fix: harden Android camera catalog snapshots`) resolvió `R3-characteristics-failure-aborts-snapshot` y `R3-nondeterministic-physical-order`: una excepción/fallo de características de una cámara produce entrada parcial `Unknown` sin abortar otras cámaras; los IDs direct-open-candidate y físicos child salen en orden determinista estable. Incorporó la nuance de `getCameraIdList()`: un ID listado es candidato direccionable, no garantía de apertura exitosa. Sin `CameraDevice.open`, sin captura, sin UI y sin claims hardware. Revisión nativa RDD aprobada y reconocida en lineage `review-2237faf927784644`.
- [x] T2: adapter real `CameraManager`/`CameraCharacteristics` con guards de API para IDs lógicos/físicos, tamaños/FPS/controles y estados `Unknown`/`Unavailable` claros; compile/build tests, sin abrir cámara. Commit `3b902bf` (`feat: add Android CameraManager catalog adapter`); revisión nativa RDD aprobada y reconocida en lineage `review-086573489e2dd009`. Conserva la nuance de `getCameraIdList()`: ID listado es `DirectOpenCandidate`, no apertura garantizada. Los IDs físicos solo se consultan directamente con guard API 29+; si no, quedan `Unknown` sin crash. `SecurityException`, `CameraAccessException` u omisiones de claves restringidas se degradan a `Unknown`/`Unavailable`, nunca a crash del catálogo completo.
- [x] T2b: endurecer adapter antes de UI por advisories `R3-missing-control-metadata-reported-false` y `R3-overbroad-run-catching`: controles sin metadata quedan `Unknown` en vez de `Known(false)`; valores conocidos `false` siguen siendo `Known(false)`; el gateway/adapter atrapa solo fallos esperados de API/permisos/cámara, no cualquier excepción inesperada. Sin abrir cámara, sin captura, sin UI y sin pruebas físicas.
- [x] T3: UI española para listar/seleccionar solo IDs direccionables; físicos-only se muestran como no abribles. Sin prometer selección de lentes Samsung sin soporte de API. Alcance: planner JVM puro para texto/estado de catálogo, integración Activity pasiva que solo enumera catálogo con `CameraManager`; selección inicial en memoria de UI si hay candidatos directos, sin persistencia (T5), sin permiso de cámara (T4), sin `CameraDevice.open`, sin captura ni pruebas físicas.
- [x] T4: gate de permiso de cámara en UI española: declarar permiso Android y solicitarlo solo por acción explícita del usuario; mostrar estados concedido/denegado/no solicitado. Sin `CameraDevice.open`, sin captura, sin stream, sin persistencia de selección (T5) y sin pruebas físicas.
- [x] T5: persistir preferencia de cámara seleccionada de forma segura: guardar solo IDs `DirectOpenCandidate`, ignorar físicos-only o stale al reconstruir catálogo, releer el permiso real de Android en recreación/reanudación, sin persistir un booleano stale de permiso. Sin abrir cámara, sin captura, sin prueba física y sin prometer selección de lentes físicos Samsung.

## Verificación T1

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` por símbolos inexistentes agregados en tests: `CapabilityState`, `CameraFacing`, `CameraOutputSize`, `CameraFpsRange`, `CameraControlAvailability`, `CameraCapabilityCatalog`, `CameraIdRole`, `CameraCatalogSnapshot`, `CameraCatalogEntry`, `CameraCapabilityGateway` y `CameraCapabilityCharacteristics`.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó con `BUILD SUCCESSFUL`.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only: PASS; confirmó gateway inyectable, pruebas fake, separación standalone/openable vs physical-only child, estados Known/Unknown/Unavailable y ausencia de CameraDevice open/capture, UI integration, manifest edits, hardcoding Samsung o claims de soporte hardware.

### Revisión nativa RDD T1

- Candidato: `d75495b` contra base `0586792`.
- Lineage: `review-ff86e4f59c053d11`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-characteristics-failure-aborts-snapshot` y `R3-nondeterministic-physical-order`. No abrieron corrección para T1; quedan como hardening previo a integración si se decide antes de UI/uso real.

### Límites y decisiones T1

- `CameraCapabilityGateway` encapsula las lecturas tipo Camera2 para mantener el dominio testeable con JVM fakes.
- `CameraIdRole.StandaloneOpenable` representa IDs listados por el gateway como abribles; `CameraIdRole.PhysicalOnlyChild(parentId)` representa IDs físicos vistos dentro de una cámara lógica pero ausentes de la lista abrible.
- Si un ID físico también aparece en la lista standalone, conserva rol `StandaloneOpenable`; no se degrada a físico-only.
- `CapabilityState.Known`, `Unknown` y `Unavailable` mantienen separados datos conocidos, datos omitidos/no consultables y capacidades no aplicables.
- T1 no abre cámara, no crea captura, no integra Activity, no toca manifest/permisos y no promete soporte en dispositivos reales.


## Verificación T1b

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` por el cambio de API esperado (`CameraIdRole.DirectOpenCandidate`, `canAttemptOpenDirectly`) todavía inexistente.
- Después de implementar el rename, una corrida intermedia falló en `CameraCapabilityCatalogTest.kt:42` porque el orden físico aún no coincidía con la expectativa determinista (`0-tele` antes de `0-wide`); se ajustó el test para fijar el orden estable esperado.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó con `BUILD SUCCESSFUL`.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only: PASS; confirmó fallos parciales como `Unknown`, orden determinista, wording `DirectOpenCandidate`, físicos-only no abribles y ausencia de open/capture/UI/hardware claims.

### Límites y decisiones T1b

- `CameraIdRole.DirectOpenCandidate` reemplaza el nombre anterior que podía sugerir apertura garantizada. Significa ID listado/direccionable por CameraManager, pero `openCamera` puede fallar por permisos, desconexión, uso concurrente u otra condición operativa.
- `CameraCapabilityCatalog` captura excepciones al consultar características por ID y conserva una entrada parcial con capabilities `Unknown`, en vez de abortar el snapshot completo.
- El snapshot ordena IDs directos con `distinct().sorted()` y acumula físicos-only en `sortedMapOf`, para estabilizar salida y pruebas.
- T1b sigue sin abrir cámara, sin captura, sin UI, sin manifest/permisos y sin prometer selección de lentes físicos Samsung.


### Revisión nativa RDD T1b

- Candidato: `9558ca3` contra base `9378559`.
- Lineage: `review-2237faf927784644`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-overbroad-throwable-recovery`. No abrió corrección para T1b; queda como hardening futuro si se acota la recuperación de errores del adapter/gateway.

## Verificación T2

La verificación local de T2 cubre adapter real `CameraManager`/`CameraCharacteristics` compilable y pruebas JVM con facade fake. No se abrió `CameraDevice`, no se inició captura, no se ejecutó en dispositivo físico, no se editó el manifest de cámara, no se prometió soporte Samsung ni selección de lentes físicos.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` por símbolos inexistentes agregados en tests: `AndroidCameraManagerGateway`, `AndroidCameraManagerFacade` y `AndroidCameraCharacteristicField`.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó con `BUILD SUCCESSFUL`; 19 tareas accionables, 5 ejecutadas y 14 up-to-date en la última corrida local.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la última corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only: PASS; confirmó adapter/facade real, tests fake, guard API29 para físicos-only, claves restringidas como `Unknown`/`Unavailable`, ausencia de open/capture/UI y header paraguas en español.

### Límites y decisiones T2

- `AndroidCameraManagerFacade` separa el adapter testeable de Android framework; `AndroidCameraManagerFacadeImpl` envuelve `CameraManager` y `CameraCharacteristics` reales.
- `AndroidCameraManagerGateway` adapta ese facade al dominio `CameraCapabilityGateway` sin abrir cámaras.
- `CameraManager.cameraIdList` se interpreta como IDs candidatos direccionables (`DirectOpenCandidate`), nunca como garantía de apertura.
- Características de IDs físicos-only se consultan directamente solo con guard API 29+; debajo de API 29 se devuelven capabilities `Unknown` con razón explícita.
- Tamaños se leen desde `SCALER_STREAM_CONFIGURATION_MAP` para `ImageFormat.YUV_420_888`; FPS desde `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`; controles desde AF/exposición/zoom disponibles cuando las claves existen.
- Excepciones o claves restringidas se degradan a `Unknown`; ausencia de tamaños de salida se modela como `Unavailable`.

### Revisión nativa RDD T2

- Candidato: `3b902bf` contra base `4db5cb9`.
- Lineage: `review-086573489e2dd009`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-missing-control-metadata-reported-false` y `R3-overbroad-run-catching`. No abrieron corrección para T2; quedan como hardening futuro antes de UI/captura si se decide.

## Verificación T2b

T2b endureció el adapter antes de UI para resolver los advisories no bloqueantes de T2.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `AndroidCameraAccessFailure` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only: PASS; confirmó tests de metadata de controles, propagación de fallos inesperados, catches acotados, ausencia de open/capture/UI/manifest y sin claims hardware.

### Límites y decisiones T2b

- `AndroidCameraAccessFailure` representa fallos recuperables de acceso/cámara dentro del dominio testeable.
- `CameraCapabilityCatalog` y `AndroidCameraManagerGateway` degradan solo `SecurityException`, `CameraAccessException` y `AndroidCameraAccessFailure`. Fallos inesperados, como errores de programación, se propagan para no ocultar bugs.
- `AndroidCameraManagerFacadeImpl.getControls()` devuelve `null` si falta metadata requerida; el catálogo lo muestra como `CapabilityState.Unknown`. Cuando la metadata existe pero indica que un control no está disponible, se preserva `Known(false)`.
- No se agregó apertura de cámara, captura, frame stream, UI, permiso de cámara ni prueba física.

### Revisión nativa RDD T2b

- Candidato: `c779c49` contra base `1df8e91`.
- Lineage: `review-9cf10dfe34dfadbd`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-missing-control-facade-coverage`. No abrió corrección para T2b; queda como posible hardening futuro del wrapper real si se prioriza antes de captura.

## Verificación T3

T3 agrega presentación UI española del catálogo de cámaras sin cambiar permisos ni abrir cámara.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraCatalogUiPlanner` y `CameraCatalogUiRow` antes de existir.
- Una corrida intermedia falló en `:android:usb-probe:compileDebugKotlin` por exhaustividad del `when` de `CapabilityState.Known`; se corrigió antes de la verificación GREEN.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only inicial: FAIL solo porque esta sección de RED aún no estaba registrada; confirmó por inspección y pruebas que el alcance funcional T3 estaba satisfecho, sin `openCamera`, captura, manifest CAMERA, persistencia, QR/Wi-Fi ni claims hardware.

### Límites y decisiones T3

- `CameraCatalogUiPlanner` es JVM puro y produce texto español testeable para título, resumen, selección y filas.
- Solo `CameraIdRole.DirectOpenCandidate` es seleccionable; si se solicita seleccionar un físico-only, la selección cae al primer candidato directo disponible.
- `CameraIdRole.PhysicalOnlyChild` se muestra como información del grupo lógico y explícitamente no abrible directamente.
- `UsbProbeActivity` integra el catálogo de forma pasiva mediante el adapter `CameraManager` existente y muestra el texto; no persiste la selección, no solicita permiso de cámara y no abre la cámara.

### Revisión nativa RDD T3

- Candidato inicial: `f19cb3b` contra base `98f68f7`.
- Hallazgo bloqueante corregido: `R3-static-catalog-no-selection`; la UI inicial listaba cámaras en texto estático y no permitía elegir entre múltiples candidatos directos.
- Corrección local: `7fe967a` (`fix: make camera catalog rows selectable`) convierte filas directas en botones que actualizan `selectedCameraId` y re-renderizan; filas físico-only siguen visibles y no seleccionables.
- Lineage: `review-a54c8d59de90f2fb`.
- Resultado: corrección validada, aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.

## Verificación T4

T4 agrega el gate de permiso runtime de cámara sin abrir cámara ni iniciar captura.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraPermissionUiPlanner` y `CameraPermissionUiModel` antes de existir.
- Una corrida intermedia falló en `:android:usb-probe:compileDebugKotlin` por un literal multilinea mal formado en `UsbProbeActivity`; se corrigió antes de la verificación GREEN.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.
- Verificación independiente read-only inicial: FAIL solo porque esta sección de RED aún no estaba registrada; confirmó por inspección y pruebas que el alcance funcional T4 estaba satisfecho, sin apertura/captura/persistencia/QR/Wi‑Fi ni claims hardware.

### Límites y decisiones T4

- `CameraPermissionUiPlanner` modela estados españoles `NotRequested`, `Granted`, `Denied` y `UnknownResult`; todos mantienen `openCameraAllowed = false`.
- `UsbProbeActivity` solicita `android.permission.CAMERA` solo desde el botón explícito de permiso de cámara.
- El callback de permiso actualiza estado visible y re-renderiza la pantalla, por lo que el catálogo se vuelve a consultar después de un grant.
- El flujo de prueba USB queda separado y utilizable aunque el permiso de cámara esté denegado.
- El manifest declara `android.permission.CAMERA` y `android.hardware.camera.any` como opcional (`required=false`).
- No se agregó `CameraDevice.open`, `openCamera`, captura, sesión, `ImageReader`, frame stream, persistencia ni prueba física.

### Revisión nativa RDD T4

- Candidato: `d9f0727` contra base `5e45bec`.
- Lineage: `review-8490f15400a70b47`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-permission-state-lifecycle`. No abrió corrección para T4; queda como hardening futuro si se persiste/restaura estado de permiso junto con la selección en T5.

## Verificación T5

T5 persiste solo la preferencia de ID directo seleccionado y relee el permiso runtime real en recreación/reanudación, sin persistir un booleano stale de permiso.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` porque los tests nuevos referenciaban `CameraSelectionPreference` y `StringPreferenceStore` antes de existir.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks`: pasó con `BUILD SUCCESSFUL`; 19 tareas ejecutadas.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó con `BUILD SUCCESSFUL`; 31 tareas accionables, 3 ejecutadas y 28 up-to-date en la corrida local.
- `test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk`: confirmó el APK en la ruta esperada.
- `git diff --check`: pasó sin salida.

### Límites y decisiones T5

- `CameraSelectionPreference` solo restaura y guarda IDs cuyo rol actual sea `DirectOpenCandidate`.
- Si la preferencia apunta a un físico-only o a un ID stale/removido, se ignora y se limpia.
- `UsbProbeActivity` persiste la selección únicamente cuando el usuario toca una fila directa seleccionable.
- `UsbProbeActivity.onResume()` relee el permiso real `CAMERA`; no se persiste un booleano de permiso runtime.
- El render del catálogo vuelve a consultar CameraManager y valida la preferencia contra el snapshot actual.
- No se agregó `CameraDevice.open`, `openCamera`, captura, sesión, `ImageReader`, frame stream ni prueba física.

### Revisión nativa RDD T5

- Candidato: `444d2b4` contra base `d3b6faf`.
- Lineage: `review-00da3c7774755062`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-permission-denial-reset` y `R3-selection-persistence-integration-unproved`. No abrieron corrección para T5; quedan como hardening futuro antes de captura real si se prioriza.
