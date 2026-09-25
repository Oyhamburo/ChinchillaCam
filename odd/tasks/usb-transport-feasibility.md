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

- **Modo:** TDD activado para prototipos de código nuevos.
- **Fuente:** no existe configuración TDD previa del repositorio ni runner heredado; `odd/tasks/define-product.md` solo declaró que TDD no aplicaba a la fase documental. Para esta fase, la instrucción vigente del usuario y del orquestador exige resolver runner antes de escribir código y mantener código, pruebas y documentación del comportamiento en el mismo work unit.
- **Runner Rust T1:** `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`.
- **Runner Android T1:** comando Gradle enfocado del módulo `:android:usb-probe` con `ANDROID_HOME=$HOME/Library/Android/sdk` y una distribución Gradle cacheada elegida después de revisar compatibilidad AGP/Kotlin. Si la compatibilidad no puede resolverse sin descargar o instalar dependencias nuevas, T1 debe detenerse o registrar ese bloqueo antes de presentar soporte Android como verificado.
- **Regla de evidencia:** primero debe existir una prueba o chequeo observable que falle o no exista por la ausencia del comportamiento, luego el código mínimo para pasarla. Si una herramienta impide ejecutar el runner, se registra el comando exacto y el bloqueo; no se reemplaza por afirmaciones manuales.

## División incremental

- [ ] **T1 — Prototipo USB mínimo buildable con handshake simulado.** Agregar una unidad coherente de Android Kotlin y escritorio Rust que exprese el límite de transporte USB y un handshake AOA simulado o modelado sin hardware real. Debe incluir pruebas/chequeos observables y documentación de evidencia en este mismo work unit. No afirmar compatibilidad de dispositivo, Windows ni Samsung.
- [ ] **T2 — Evidencia y límites posteriores.** Si T1 deja preguntas de documentación fuera del commit de código, actualizar documentación en español con lo que el Mac puede verificar y lo que queda pendiente para Windows 11 y Samsung reales.

## Plan de commits de unidad de trabajo

1. `chore: track USB transport feasibility prototype` — esta tarea ODD y espejo de memoria.
2. `feat: add bounded USB transport prototype skeleton` — código mínimo y verificación del primer prototipo.
3. `docs: record USB prototype evidence and hardware limits` — solo si la evidencia documental merece un cierre separado; de lo contrario se mantiene con el commit del prototipo.

## Verificación inicial

Pendiente: crear los prototipos y registrar comandos exactos. La verificación local no equivale a prueba de Windows 11 ni de dispositivos Samsung.
