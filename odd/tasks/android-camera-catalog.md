# Android camera catalog capability task

> Estado: T1 en preparación. Rama local `feat/android-camera-catalog` creada desde el HEAD limpio real `0586792` de `proto/usb-bulk-tdd-retry`. Commits locales autorizados; sin push, PR ni merge.

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

- `CameraManager.getCameraIdList()` lista cámaras standalone/openable y puede excluir lentes que existen solo como cámaras físicas dentro de una cámara lógica.
- `CameraCharacteristics.getPhysicalCameraIds()` puede revelar IDs físicos agrupados bajo una cámara lógica.
- Desde API 29, `getCameraCharacteristics(String)` puede consultar características de IDs físicos, pero esos IDs físicos no necesariamente se pueden abrir directamente.

## T1 — Dominio de catálogo Camera2 con gateway fake

Objetivo: producir una función de catálogo que reciba un gateway inyectable y devuelva:

- IDs standalone/openable reportados por `getCameraIdList()`;
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

## Progreso

- [x] T1: agregar dominio `CameraCapabilityCatalog` con gateway fake y pruebas JVM. Commit `d75495b` (`feat: add Android camera capability catalog`) implementó `CameraCapabilityCatalog.kt` y `CameraCapabilityCatalogTest.kt`; revisión nativa RDD aprobada y reconocida en lineage `review-ff86e4f59c053d11`.

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
