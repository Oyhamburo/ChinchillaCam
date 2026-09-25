# Prototipo de factibilidad USB

## Contexto y contrato

El usuario aprobó avanzar desde la definición documental hacia prototipos técnicos. Esta tarea mantiene como contrato vigente los documentos en español existentes: `README.md`, `docs/requisitos.md`, `docs/uso.md` y `docs/factibilidad.md`.

El primer foco es validar de forma acotada el transporte USB entre Android Kotlin y escritorio Rust. No es un MVP Wi‑Fi-only y no implementa todavía una app completa de webcam.

## Alcance autorizado

- Prototipos de código para explorar transporte USB.
- Android en Kotlin como extremo de teléfono.
- Escritorio en Rust como host/accesorio USB.
- Evidencia local de compilación o bloqueo en macOS cuando sea posible.
- Documentación en español de qué prueba el prototipo y qué queda pendiente.

## No objetivos de esta tarea

- No implementar cámara completa, codificación de video final ni cámara virtual.
- No implementar OBS, Wi‑Fi ni emparejamiento QR.
- No prometer compatibilidad real con Samsung Galaxy Note10, Samsung Galaxy S24+ ni Windows 11.
- No instalar, reemplazar ni modificar drivers USB.
- No usar Zadig ni alterar asociaciones de WinUSB automáticamente.
- No usar ADB como transporte del producto.
- No afirmar pruebas reales en Windows, Samsung o hardware USB hasta ejecutarlas.

## Evidencia técnica de partida

- AOSP Android Open Accessory indica que AOA no requiere USB debugging para conexiones de accesorio, pero el dispositivo debe soportar AOA y eso debe validarse por hardware.
- `rusb` es un wrapper Rust sobre libusb para comunicación USB desde host.
- La documentación de libusb para Windows indica que puede requerirse vincular el dispositivo a WinUSB; esa acción puede cambiar drivers del sistema y no debe automatizarse.

## Estado de herramientas local observado en macOS

- Java/Javac disponible: OpenJDK 17.0.16.
- Rust disponible con `PATH=$HOME/.cargo/bin:$PATH`: `cargo 1.98.0`, `rustc 1.98.0`, toolchain `stable-aarch64-apple-darwin`.
- Android SDK disponible con `ANDROID_HOME=$HOME/Library/Android/sdk`; plataformas `android-33`, `android-34`, `android-35`, `android-36`; build-tools `34.0.0`, `35.0.0`, `36.0.0`, `36.1.0`.
- Android platform-tools/ADB disponible: 37.0.1; ADB no se usa como transporte de producto.
- `pkg-config` disponible.
- `libusb` disponible vía Homebrew en `/opt/homebrew/opt/libusb`, versión 1.0.30.
- No hay `gradle` global en `PATH`, pero hay distribuciones Gradle wrapper ya cacheadas: `8.0.1-all` y `9.3.1-bin`. Antes de elegir wrapper o AGP hay que revisar compatibilidad de versiones.

## Modo TDD efectivo

- **Modo:** TDD estricto activado para prototipos de código nuevos.
- **Fuente:** elección explícita del usuario relayed por el orquestador: “Sí TDD estricto”. No existe configuración TDD previa del repositorio ni runner heredado; `odd/tasks/define-product.md` solo declaró que TDD no aplicaba a la fase documental.
- **Historia de recuperación:** hubo WIP no commiteado en el worktree original `/Users/jele/Desktop/codes/ChinchillaCam` escrito antes de la autorización explícita de TDD estricto y antes de un RED observado. Ese WIP queda preservado e intacto, no se considera evidencia TDD y no debe commitearse como TDD-compliant. Este worktree aislado `/Users/jele/Desktop/codes/ChinchillaCam-usb-tdd` parte del commit limpio `9066ff1` para obtener evidencia real RED→GREEN.
- **Runner Rust T1:** `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`.
- **Runner Android T1:** `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`.
- **Regla de evidencia:** observar y registrar RED antes del código de producción correspondiente, luego GREEN con el mínimo código necesario y una refactorización si procede. Si una herramienta impide ejecutar el runner, registrar el comando exacto y el bloqueo; no reemplazarlo por afirmaciones manuales.

## División incremental

- [x] **T1 — Prototipo USB mínimo buildable con handshake simulado.** Commit `394d2d1` (`feat: add strict TDD USB probe prototype`) agregó una unidad coherente de Android Kotlin y escritorio Rust que expresa límites de transporte USB, parsing AOA mínimo y validaciones sin hardware real. Incluye pruebas observables y documentación de evidencia en el mismo work unit. No afirma compatibilidad de dispositivo, Windows ni Samsung. Revisión nativa RDD aprobada y reconocida: lineage `review-3d704aca5d159a2f`.
- [ ] **T2 — Handshake AOA host test-first.** Extender el prototipo Rust con un modelo de handshake host AOA más real: solicitudes de control, orden de envío de identidad de accesorio y transición esperada a modo accessory, usando un transporte fake en pruebas. Debe iniciar con RED observado, terminar con pruebas y documentación del alcance; no debe enumerar hardware ni afirmar soporte real.
- [ ] **T3 — Datos/lado Android accessory test-first.** Extender el lado Android con el contrato de recepción/validación de identidad o datos iniciales desde accessory, usando pruebas JVM y sin cámara/video. Debe mantener ADB fuera del transporte de producto.
- [ ] **T4 — Evidencia y límites por plataforma.** Documentar qué prueba el Mac sin hardware y qué queda pendiente para Windows 11, macOS host real y Samsung reales. Debe incluir cualquier advertencia de drivers WinUSB/libusb como decisión manual, nunca automatizada.

## Estrategia de entrega y slicing

- **Decisión del usuario:** “lo que sea más rápido”.
- **Estrategia elegida por el orquestador:** `chain_strategy=stacked-to-main`, con work units secuenciales y autocontenidos que podrían encadenarse hacia la rama principal solo si un PR se autoriza explícitamente más adelante.
- **Delivery strategy:** `ask-on-risk` ya resuelto para este umbral; no se agrega overhead de PR/tracker ahora.
- **Riesgo observado:** T1 fue un work unit coherente de ~436 líneas authored, por encima del umbral orientativo de ~400. No se debe hacer code-golf ni separar tests/docs del comportamiento para bajar el número.
- **Regla futura:** cuando existan PRs explícitamente autorizados, si un slicing honesto y cohesivo no puede mantener cada slice en <=400 líneas, reportar la necesidad de `size:exception` explícito de maintainer en vez de asumir que esta respuesta lo concede.

## Plan de commits de unidad de trabajo

1. `chore: track USB transport feasibility prototype` — esta tarea ODD y espejo de memoria. Commit `9066ff1`.
2. `feat: add strict TDD USB probe prototype` — código mínimo, pruebas y evidencia del primer prototipo. Commit `394d2d1`; revisión nativa aprobada y reconocida en lineage `review-3d704aca5d159a2f`.
3. T2 previsto: `feat: model AOA host control handshake` — handshake host AOA real bajo TDD estricto con transporte fake y pruebas RED/GREEN; docs/evidencia en el mismo work unit.
4. T3 previsto: `feat: model Android accessory payload boundary` — contrato Android de payload/identidad accessory bajo TDD estricto; docs/evidencia en el mismo work unit.

## Verificación T1

La verificación local de T1 modela límites de transporte USB sin hardware real. No prueba compatibilidad con Windows, macOS como host USB real, Samsung Galaxy Note10, Samsung Galaxy S24+, cámara, OBS, Wi‑Fi ni video.

### Revisión nativa RDD T1

- Candidato: `394d2d1` contra base `9066ff1`.
- Lineage: `review-3d704aca5d159a2f`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida. No volver a consultar STATUS sobre este lineage quemado.
- Hallazgos advisory no bloqueantes del reviewer: longitud de respuesta Android, rango de IDs USB Android y handshake aún no modelado. No abren corrección para T1; se tratan como trabajo futuro, especialmente T2.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló inicialmente con código 101 porque `desktop/usb-probe/Cargo.toml` no existía.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló inicialmente con código 1 porque el directorio no contenía un proyecto Gradle.
- Después de crear el harness mínimo y pruebas, `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` falló con código 101 por imports inexistentes en `usb_probe`: `parse_protocol_version_response`, `AccessoryIdentity`, `AoaProtocolVersion` y `DeviceSummary`.
- Spot RED adicional para asegurar el límite Rust con `rusb`: tras agregar una prueba para `UsbHostBoundary`, `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` falló con código 101 por import inexistente `usb_probe::UsbHostBoundary`.
- Después de crear el harness mínimo y pruebas, `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest` falló con código 1 por referencias Kotlin inexistentes: `AoaProtocolVersion`, `AccessoryIdentity` y `UsbDeviceSummary`. Antes de ese RED útil hubo ajustes de harness para alinear JVM 17 y agregar JUnit local.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó; 5 pruebas de `aoa_boundary_test.rs` en verde, más lib/doc tests sin pruebas. La primera ejecución verde resolvió dependencias de `rusb`/`libusb1-sys` desde Cargo.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó; `BUILD SUCCESSFUL` con 14 tareas, 6 ejecutadas y 8 up-to-date.

### Refactor y límites explícitos

- Refactor/configuración: se fijó `compileSdk = 33` para usar una combinación compatible con Android Gradle Plugin 8.0.2 disponible en caché local y evitar advertencias por SDK no probado.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó nuevamente después del ajuste.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó nuevamente después del ajuste.
- El prototipo solo cubre parsing little-endian de versión AOA, validación mínima de identidad de accesorio, un límite host Rust tipado sobre `rusb` que no enumera hardware en pruebas, y formateo de resumen de dispositivo con texto que aclara que el transporte de hardware sigue no validado.
- No se usó ADB como transporte, no se instaló ni modificó ningún driver, no se usó Zadig, no se tocaron configuraciones del sistema y no se afirmó compatibilidad de hardware.
