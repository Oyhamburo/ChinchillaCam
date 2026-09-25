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
- [x] **T2 — Modelo AOA host control test-first.** Commit `bc4bb27` (`feat: model AOA host control handshake`) extendió el prototipo Rust con un modelo de handshake de control AOA: solicitud GET_PROTOCOL simulada, envío de identidad de accesorio en orden AOA, solicitud START_ACCESSORY simulada, validación de versión de protocolo y log ordenado de operaciones mediante `FakeAoaTransport`. Inició con RED observado y terminó con pruebas Rust en verde. No enumera hardware, no demuestra un handshake USB real y no afirma soporte real. Revisión nativa RDD aprobada y reconocida: lineage `review-2c36083eccc1a5f2`.
- [x] **T3 — Adapter/CLI rusb para AOA host test-first.** Commit `dbf261c` (`feat: add rusb AOA host adapter`) agregó un límite host `rusb` testeado con mocks/fakes para mapear solicitudes de control AOA (`GET_PROTOCOL`, seis `SEND_STRING` y `START_ACCESSORY`) y devolver un estado seguro de re-enumeración esperada. No se ejecutó contra hardware, no enumera dispositivos en pruebas, no instala ni cambia drivers y no afirma endpoints bulk reales ni compatibilidad de plataforma/dispositivo. Revisión nativa RDD aprobada y reconocida: lineage `review-2eac537778fbc2c7`.
- [x] **T4 — Android UsbManager accessory open/read/write test-first.** Commit `b6f8a85` (`feat: model Android accessory IO boundary`) agregó un límite Android pequeño alrededor de `UsbManager`/`UsbAccessory` mediante interfaces mockeables y streams inyectables. Las pruebas JVM cubren permiso denegado sin intento de apertura, apertura autorizada con sesión de lectura/escritura, lectura corta explícita, EOF explícito, cierre idempotente y escritura con `flush`. No usa hardware, no inicia cámara, no agrega Activity ni servicio, no usa ADB como transporte y no afirma compatibilidad real. Revisión nativa RDD aprobada y reconocida: lineage `review-a092b4fb9a1066ba`.
- [x] **T5a — Host CLI Rust seguro con selección explícita de dispositivo.** Commit `6c55126` (`feat: add safe AOA host CLI boundary`) agregó una CLI host Rust dry-run que requiere `--device VID:PID` explícito antes de producir el plan AOA, modela espera de re-enumeración y agrega un límite bulk fake para endpoints reclamados y frames mínimos. No envía solicitudes de control a dispositivos arbitrarios, no abre hardware en dry-run, no instala ni cambia drivers y no afirma ejecución real en hardware. Revisión nativa RDD aprobada y reconocida: lineage `review-0413e095afba842b`.
- [x] **T5b — Host live AOA control path test-first.** Commit `d1485a1` (`feat: add live AOA host control path`) agregó una ruta no-dry-run de host para ejecutar `GET_PROTOCOL`, seis `SEND_STRING` y `START_ACCESSORY` sobre `rusb` únicamente contra un `VID:PID` explícito, con timeout configurable, resultado acotado de re-enumeración y CLI `--live-control`. Usa fake device/control boundaries en pruebas, no modifica drivers, no usa Zadig y no afirma éxito con dispositivo real. Revisión nativa RDD aprobada y reconocida: lineage `review-0355a50420ca2b05`.
  - RED observado T5b: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .` falló con código 101 por imports inexistentes en `usb_probe`: `HostAoaControlOptions`, `LiveAoaControlRunner`, `RecordingUsbDeviceRegistry`, `ReenumerationWait` y variante `UsbProbeError::SelectedDeviceNotFound`.
- [x] **T5c1 — Host live AOA re-enumeration poll test-first.** Commit `25cacc4` (`feat: poll for AOA re-enumeration`) agregó una sub-unidad separada para corregir el límite de T5b: después de `START_ACCESSORY`, la ruta live-control ahora hace un poll acotado buscando re-enumeración a Google AOA `18d1:2d00` o `18d1:2d01`, con error distinto si no aparece. Usa fakes en pruebas, no reclama interfaces bulk, no lee/escribe frames, no ejecuta hardware físico y no afirma compatibilidad real. Revisión nativa RDD aprobada y reconocida: lineage `review-3b74a320767c5602`.
- [x] **T5c1b — Guard de identidad física post-AOA test-first.** Commit `7a53e0e` (`feat: bind AOA re-enumeration identity`) agregó el guard previo a cualquier reclamo bulk: la ruta live-control captura la identidad física estable del dispositivo pre-START mediante `rusb` (`bus_number` + `port_numbers`) y el poll post-AOA solo acepta `18d1:2d00`/`18d1:2d01` si aparece en la misma ubicación física. Falla cerrado con errores distintos cuando la identidad física no está disponible, cuando hay ambigüedad o cuando vence el poll. También endurece el cálculo de intentos para timeout cero/saturación y mantiene T5c2 como bulk futuro. Sin hardware real ni afirmación de misma identidad física probada en dispositivo físico. Revisión nativa RDD aprobada y reconocida: lineage `review-7f646ad8c675d1c1`.
- [x] **T5c2a — Host safe AOA handle binding test-first.** Commit `6d3b4fc` (`feat: bind opened AOA handle identity`) agregó la apertura segura del handle AOA post-reenumeración solo si el handle abierto conserva la misma identidad física `bus_number + port_path` seleccionada antes de `START_ACCESSORY`. Falla cerrado si la identidad del handle no está disponible, si hay más de un candidato AOA en la misma ubicación física o si el handle abierto corresponde a otra ubicación. Incluye pruebas de handle equivocado con fakes; no lee ni escribe frames, no reclama interfaz bulk y no afirma hardware real. Revisión nativa RDD aprobada y reconocida: lineage `review-3fe355b7919ce6cf`.
- [x] **T5c2b — Host framed USB stream boundary test-first.** Commit `1867f33` (`feat: add bounded USB frame stream`) agregó un framing USB acotado sobre un límite fake/inyectable: header de longitud explícito, límite máximo de frame, lecturas/escrituras incrementales, header fragmentado, payload fragmentado, EOF/errores propagados, short writes detectados y sin truncación/overflow. No reclama interfaz real, no integra CLI live y no afirma hardware real. Revisión nativa RDD aprobada y reconocida: lineage `review-767bae86a1094abf`.
- [x] **T5c2b1 — Hardening del frame stream antes de bulk live test-first.** Commit `150bfe4` (`fix: harden bounded USB frame stream`) conserva bytes residuales cuando una lectura fake devuelve datos coalescidos de frames adyacentes y rechaza con error tipado cualquier backend que reporte más bytes leídos/escritos que el slice entregado. Cubre dos frames coalescidos y conteos inválidos de read/write sin pánico. No cambia el alcance aprobado de T5c2b, no integra CLI live, no reclama interfaz real y no afirma hardware real. Revisión nativa RDD aprobada y reconocida: lineage `review-7d3db44c1cec19c2`.
- [x] **T5c2c1 — Host live bulk descriptor/claim boundary test-first.** Commit `90d4438` (`feat: claim validated AOA bulk interface`) agregó la sub-unidad previa de T5c2c para descubrir/validar descriptores de interfaz con un par bulk IN/OUT explícito y reclamar exactamente esa interfaz. Incluye fake deterministic y wrapper `rusb` compilado sobre `DeviceHandle`, falla cerrado si no hay par bulk o si hay candidatos ambiguos, no integra CLI live, no ejecuta frame I/O real y no afirma hardware real. Revisión nativa RDD aprobada y reconocida: lineage `review-9f552f6854ea0a30`.
- [x] **T5c2c1b — Corrección selector AOA PID/interfaz/alt-setting test-first.** Commit `b95fe3f` (`fix: select AOA accessory bulk interface`) corrige la selección de interfaz bulk antes de la CLI live según la documentación AOA: PID `18d1:2d00` usa interfaz `0`; PID `18d1:2d01` usa interfaz `0` para accesorio e interfaz `1` para ADB, que se ignora. Falla cerrado si la configuración activa no puede verificarse, si hay interfaz extra no esperada, si hay endpoints duplicados de la misma dirección o si el par bulk está en alternate setting distinto de `0`. No integra CLI live, no ejecuta frame I/O real, no cambia drivers/configuración y no afirma hardware real. Revisión nativa RDD aprobada y reconocida: lineage `review-6b6ba648ae04ade0`.
- [x] **T5c2c2 — Host live bulk CLI frame integration test-first.** Commit `143445f` (`feat: wire live AOA bulk frame CLI`) conecta la ruta CLI `--live-bulk-smoke` después de T5c2c1b: reusa el control AOA y poll físico, abre de nuevo solo el dispositivo AOA físicamente vinculado, valida/claim la interfaz bulk de accesorio, selecciona alternate setting `0`, escribe un frame de prueba pequeño y lee un frame de respuesta mediante `FramedUsbStream`. Incluye fakes para alt-setting, timeouts, endpoints y errores; no ejecuta hardware físico, no cambia drivers/configuración, no usa ADB como transporte y no afirma compatibilidad real. Revisión nativa RDD aprobada y reconocida: lineage `review-0c15605242c3fdcb`.
- [ ] **T5d — APK Android de prueba instalable.** Agregar un artefacto Android mínimo de prueba con UI/entrada visible del usuario para permiso/accesorio, apertura de accessory y lectura/escritura de payload framed acotado. Debe incluir pruebas, no iniciar cámara, no iniciar trabajo silencioso en segundo plano y no usar ADB como transporte de producto.
- [ ] **T5e — Framing y guía smoke host↔phone.** Documentar comandos host↔teléfono reproducibles, salida esperada, ruta de cross-build Windows si es factible y hardware real pendiente. Debe mantener el dry-run como planificación, no como soporte de hardware.
- [ ] **T6 — Validación plataforma/dispositivo pendiente.** Registrar qué queda pendiente para Windows 11, macOS host real y Samsung reales. Debe incluir advertencias de WinUSB/libusb como decisión manual del usuario, nunca automatizada. Mantener no-ADB como default; cualquier fallback de depuración USB solo se documenta después de pruebas específicas.

## Estrategia de entrega y slicing

- **Decisión del usuario:** “lo que sea más rápido”.
- **Estrategia elegida por el orquestador:** `chain_strategy=stacked-to-main`, con work units secuenciales y autocontenidos que podrían encadenarse hacia la rama principal solo si un PR se autoriza explícitamente más adelante.
- **Delivery strategy:** `ask-on-risk` ya resuelto para este umbral; no se agrega overhead de PR/tracker ahora.
- **Riesgo observado:** T1 fue un work unit coherente de ~436 líneas authored, por encima del umbral orientativo de ~400. No se debe hacer code-golf ni separar tests/docs del comportamiento para bajar el número.
- **Regla futura:** cuando existan PRs explícitamente autorizados, si un slicing honesto y cohesivo no puede mantener cada slice en <=400 líneas, reportar la necesidad de `size:exception` explícito de maintainer en vez de asumir que esta respuesta lo concede.

## Plan de commits de unidad de trabajo

1. `chore: track USB transport feasibility prototype` — esta tarea ODD y espejo de memoria. Commit `9066ff1`.
2. `feat: add strict TDD USB probe prototype` — código mínimo, pruebas y evidencia del primer prototipo. Commit `394d2d1`; revisión nativa aprobada y reconocida en lineage `review-3d704aca5d159a2f`.
3. `feat: model AOA host control handshake` — modelo host AOA bajo TDD estricto con `FakeAoaTransport` y pruebas RED/GREEN; commit `bc4bb27`; revisión nativa aprobada y reconocida en lineage `review-2c36083eccc1a5f2`.
4. `feat: add rusb AOA host adapter` — adapter host con solicitudes de control AOA sobre `rusb`, re-enumeración esperada y límites explícitos bajo TDD estricto; commit `dbf261c`; revisión nativa aprobada y reconocida en lineage `review-2eac537778fbc2c7`.
5. `feat: model Android accessory IO boundary` — contrato Android `UsbManager`/`UsbAccessory` open/read/write bajo TDD estricto; commit `b6f8a85`; revisión nativa aprobada y reconocida en lineage `review-a092b4fb9a1066ba`.
6. `feat: add safe AOA host CLI boundary` — CLI Rust con selección explícita de dispositivo, dry-run AOA y límite bulk bajo TDD estricto; commit `6c55126`; revisión nativa aprobada y reconocida en lineage `review-0413e095afba842b`.
7. T5b previsto: `feat: add live AOA host control path` — ruta host no-dry-run para control AOA con `rusb` contra `VID:PID` explícito, timeouts y re-enumeración acotada; pruebas con fakes y docs/evidencia en el mismo work unit.
8. `feat: poll for AOA re-enumeration` — poll acotado post-START hacia `18d1:2d00`/`18d1:2d01`, error distinto de timeout y pruebas fake. Commit `25cacc4`; revisión nativa aprobada y reconocida en lineage `review-3b74a320767c5602`.
9. `feat: bind AOA re-enumeration identity` — guard de identidad física pre/post START usando `rusb` bus/puertos, fallo cerrado en ambigüedad/no disponibilidad/timeout y pruebas fake. Commit `7a53e0e`; revisión nativa aprobada y reconocida en lineage `review-7f646ad8c675d1c1`.
10. `feat: bind opened AOA handle identity` — apertura segura del handle AOA post-reenumeración solo si conserva la ubicación física pre-START; fallos cerrados para metadata ausente, ambigüedad o handle equivocado; pruebas/fakes/docs en el mismo work unit. Commit `6d3b4fc`; revisión nativa aprobada y reconocida en lineage `review-3fe355b7919ce6cf`.
11. `feat: add bounded USB frame stream` — framing incremental con límite de longitud, header/payload fragmentados, EOF/errores y short writes sin truncación/overflow; pruebas/fakes/docs en el mismo work unit. Commit `1867f33`; revisión nativa aprobada y reconocida en lineage `review-767bae86a1094abf`.
12. `fix: harden bounded USB frame stream` — preservar residual coalescido entre lecturas y rechazar conteos de I/O mayores que el slice entregado; pruebas/fakes/docs en el mismo work unit. Commit `150bfe4`; revisión nativa aprobada y reconocida en lineage `review-7d3db44c1cec19c2`.
13. `feat: claim validated AOA bulk interface` — descriptor/interfaz/endpoints bulk reales con wrapper `rusb`, fake deterministic y claim de interfaz validado; sin CLI live ni frame I/O real. Commit `90d4438`; revisión nativa aprobada y reconocida en lineage `review-9f552f6854ea0a30`.
14. `fix: select AOA accessory bulk interface` — corregir selector por PID/interfaz AOA, ignorar ADB interface 1 solo en `18d1:2d01`, fallar cerrado sin configuración activa verificada y mantener alt setting 0. Commit `b95fe3f`; revisión nativa aprobada y reconocida en lineage `review-6b6ba648ae04ade0`.
15. `feat: wire live AOA bulk frame CLI` — CLI live conectada al handle AOA vinculado, claim bulk validado y `FramedUsbStream` acotado para un frame mínimo; pruebas/fakes/docs en el mismo work unit. Commit `143445f`; revisión nativa aprobada y reconocida en lineage `review-0c15605242c3fdcb`.
16. T5d previsto: `feat: add Android accessory smoke APK` — APK Android de prueba instalable con permiso visible y payload framed acotado bajo TDD estricto; docs/evidencia en el mismo work unit.
17. T5e previsto: `docs: define host phone USB smoke procedure` — comandos host↔phone, ruta Windows si es factible y validación hardware marcada pendiente.

## Verificación T5c2c2

La verificación local de T5c2c2 cubre la integración de código host para I/O bulk live, pero solo mediante compilación y fakes. No se ejecutó contra hardware USB físico, no se instaló ni modificó ningún driver, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existían `BulkInterfaceClaimState`, `RecordingClaimedBulkInterface`, `UsbProbeError::BulkAlternateSettingFailed` ni `UsbProbeError::BulkInterfaceNotClaimed`.
- Después de crear el primer fake, el test `claimed_bulk_interface_selects_alt_zero_then_exchanges_fixed_frame` falló porque esperaba una sola lectura con timeout, pero el frame reader hace una lectura para header y otra para payload; se ajustó la expectativa a dos lecturas acotadas.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 44 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Revisión nativa RDD T5c2c2

- Candidato: `143445f` contra base `ce1c12e`.
- Lineage: `review-0c15605242c3fdcb`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-live-bulk-cli-coverage` en `desktop/usb-probe/src/main.rs:36`. No abrió corrección para T5c2c2; se trata como trabajo futuro si se decide agregar cobertura de proceso/CLI más allá de los fakes de librería.

### Límites y decisiones T5c2c2

- `RecordingClaimedBulkInterface` exige `select_alt_zero()` antes de exponer un `UsbBulkIo`; si la selección falla, marca el claim como liberado y devuelve `BulkInterfaceNotClaimed` ante intentos de usarlo.
- `RusbClaimedBulkIo::claim_accessory` consume el `DeviceHandle` AOA ya vinculado, reclama la interfaz de accesorio validada, llama `set_alternate_setting(interface, 0)`, libera la interfaz si esa selección falla y recién entonces permite `read_bulk`/`write_bulk` por los endpoints validados.
- La CLI agrega `--live-bulk-smoke`, que primero ejecuta el control AOA y poll físico existente, luego reabre solo el AOA observado en la misma ubicación física, valida/claim bulk, escribe un frame pequeño fijo y lee un frame de respuesta. La salida dice que la compatibilidad de hardware todavía requiere evidencia smoke documentada.
- No se ejecutó la CLI contra hardware y no se automatiza detach de kernel driver, cambio de driver, Zadig, ADB ni cambios de configuración USB.

## Verificación T5c2c1b

La verificación local de T5c2c1b corrige el selector de interfaz antes de integrar I/O live. La regla se basa en la documentación AOA: `18d1:2d00` expone una interfaz de accesorio con endpoints bulk IN/OUT; `18d1:2d01` expone dos interfaces con endpoints bulk IN/OUT, donde la primera es comunicación normal de accesorio y la segunda es ADB. No se ejecutó contra hardware USB físico, no se integró la CLI live, no se envió ni recibió ningún frame real, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existían `RecordingBulkInterfaceClaimer::with_active_device_descriptors`, `without_active_configuration`, `BulkInterfaceClaim::candidate_descriptor_with_alt` ni `UsbProbeError::ActiveConfigurationUnavailable`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 42 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Revisión nativa RDD T5c2c1b

- Candidato: `b95fe3f` contra base `d40a040`.
- Lineage: `review-6b6ba648ae04ade0`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-adb-interface-cardinality` y `R3-alt-setting-not-verified`. No abrieron corrección para T5c2c1b; deben considerarse antes de T5c2c2 si se quiere aceptar variantes de descriptor o alternate settings no default.

### Límites y decisiones T5c2c1b

- `BulkInterfaceClaim::from_accessory_descriptors` valida el PID AOA y selecciona interfaz `0` para `18d1:2d00` y `18d1:2d01`; en `18d1:2d01` ignora interfaz `1` como ADB y rechaza cualquier interfaz bulk extra.
- La selección exige configuración activa verificada; el wrapper `rusb` dejó de inspeccionar `config_descriptor(0)` como fallback cuando `active_config_descriptor()` falla.
- Los descriptores con par bulk en alternate setting distinto de `0` se ignoran/fallan cerrados; esta tarea no cambia alternate settings ni configuración USB.
- Los tests cubren ambos PIDs AOA, interfaz extra maliciosa, endpoints duplicados de la misma dirección, alternate setting no activo/default y configuración activa no verificable.

## Verificación T5c2c1

La verificación local de T5c2c1 cubre solo descubrimiento/validación de descriptor de interfaz bulk y claim de interfaz sobre límites fake y `rusb` compilado. No se ejecutó contra hardware USB físico, no se integró la CLI live, no se envió ni recibió ningún frame real, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existían `BulkEndpointDescriptor`, `BulkInterfaceClaim`, `BulkTransferKind`, `RecordingBulkInterfaceClaimer` ni las variantes `UsbProbeError::BulkInterfaceNotFound` y `BulkInterfaceAmbiguous`.
- Después de una verificación independiente fallida, se agregó un RED específico: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml bulk_interface_claim_rejects_missing_wrong_kind_or_ambiguous_bulk_pairs` falló porque una interfaz con múltiples endpoints bulk IN y un bulk OUT se aceptaba en vez de rechazarse como ambigua.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 39 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Revisión nativa RDD T5c2c1

- Candidato: `90d4438` contra base `1ad9fbf`.
- Lineage: `review-9f552f6854ea0a30`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-inactive-alternate-setting` en `desktop/usb-probe/src/lib.rs:957`. No abrió corrección para T5c2c1; se trata como trabajo futuro si se necesita elegir alternate settings inactivos antes del claim.

### Límites y decisiones T5c2c1

- `BulkInterfaceClaim::from_descriptors` acepta exactamente un descriptor con exactamente un endpoint bulk IN y exactamente un endpoint bulk OUT; devuelve `BulkInterfaceNotFound` si no hay par válido y `BulkInterfaceAmbiguous` si hay más de un candidato o si un candidato tiene múltiples endpoints bulk de la misma dirección.
- `BulkEndpointDescriptor` rechaza endpoint cero y conserva el tipo de transferencia para no confundir endpoints interrupt/control/isochronous con bulk.
- `RecordingBulkInterfaceClaimer` prueba la selección y registra el claim de interfaz sin abrir hardware.
- `RusbBulkInterfaceClaimer` enumera el descriptor activo de `rusb::DeviceHandle`, deriva descriptores de endpoints y llama `claim_interface` solo después de validar un único par bulk IN/OUT. No hace detach de kernel driver, no cambia drivers y no prueba hardware real.
- La integración CLI/frame live queda separada como T5c2c2 para evitar un cambio demasiado grande.

## Verificación T5c2b1

La verificación local de T5c2b1 endurece el stream fake/inyectable antes de integrar bulk real. No se ejecutó contra hardware USB físico, no se reclamó interfaz bulk real, no se integró la CLI live, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existía `UsbProbeError::BulkTransferCountExceeded` para rechazar conteos inválidos. Después de agregar el error y un primer cambio parcial, el test `framed_usb_stream_preserves_coalesced_read_residual_for_next_frame` falló porque los bytes residuales no se exponían como una sola lectura de origen.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 36 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Revisión nativa RDD T5c2b1

- Candidato: `150bfe4` contra base `1867f33`.
- Lineage: `review-7d3db44c1cec19c2`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-001` en `desktop/usb-probe/tests/aoa_boundary_test.rs:904-934`. No abrió corrección para T5c2b1; se trata como trabajo futuro si se necesita fortalecer el fake de contrato inválido.

### Límites y decisiones T5c2b1

- `RecordingUsbBulkIo` conserva bytes residuales en una cola cuando un chunk contiene más bytes que el buffer de la llamada actual; esto permite leer dos frames coalescidos sin perder el segundo.
- `FramedUsbStream::read_exact_bounded` rechaza `count > remaining_buffer` con `BulkTransferCountExceeded` antes de sumar o indexar.
- `FramedUsbStream::write_frame` rechaza `count > remaining_bytes` con `BulkTransferCountExceeded` antes de sumar o indexar.
- La corrección sigue siendo fake/inyectable: no abre hardware, no reclama endpoints reales y no modifica el formato de cable de T5c2b.

## Verificación T5c2b

La verificación local de T5c2b cubre solo el formato de frames y el stream de bytes sobre un límite fake/inyectable. No se ejecutó contra hardware USB físico, no se reclamó interfaz bulk real, no se integró la CLI live, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### Formato de cable compartido T5c2b

- Cada frame usa un header fijo de 8 bytes little-endian seguido del payload.
- Bytes `0..3`: `stream_id` como `u32` little-endian.
- Bytes `4..7`: longitud de payload como `u32` little-endian.
- Bytes `8..`: payload exacto de la longitud declarada.
- El lector rechaza longitud mayor que el máximo configurado antes de leer payload. La conversión de longitud se valida y nunca trunca silenciosamente.
- El escritor reutiliza el mismo `BulkFrame::encode()` y rechaza frames cuyo payload excede el máximo configurado.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existían `FramedUsbStream`, `FrameTransferBudget`, `RecordingUsbBulkIo` ni las variantes `UsbProbeError::OversizeBulkFrame`, `BulkFrameHeaderTruncated`, `BulkFramePayloadTruncated`, `UsbBulkTransferFailed` y `BulkShortWrite`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 33 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Límites y decisiones T5c2b

- `FrameTransferBudget` exige máximo de payload no cero, representable como `u32`, y cantidad máxima de intentos de I/O no cero.
- `FramedUsbStream::read_frame` lee header y payload en bucles acotados; acepta fragmentación de header/payload y distingue header truncado, payload truncado, longitud excesiva y errores USB propagados.
- `FramedUsbStream::write_frame` escribe el frame completo con writes parciales acotados y devuelve `BulkShortWrite` si no hay progreso o se agotan los intentos.
- `RecordingUsbBulkIo` solo modela lecturas/escrituras parciales deterministas para pruebas; no abre hardware ni conoce endpoints reales. La integración con descriptor/interfaz/endpoints bulk queda para T5c2c.

## Verificación T5c2a

La verificación local de T5c2a cubre solo el binding seguro del handle AOA abierto contra la identidad física pre-START. No se ejecutó contra hardware USB físico, no se reclamó interfaz bulk, no se leyeron ni escribieron frames, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 porque aún no existían `AoaAccessoryHandleRegistry`, `RecordingAoaAccessoryHandleRegistry` ni la variante `UsbProbeError::AoaHandlePhysicalIdentityMismatch`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 27 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust antes del GREEN final.

### Límites y decisiones T5c2a

- `AoaObservedDevice::physical_location()` ahora devuelve `Option<&UsbPhysicalLocation>` y `required_physical_location()` propaga `PhysicalIdentityUnavailable`; esto elimina el accessor público con `expect` sobre observaciones sin metadata.
- `RecordingAoaAccessoryHandleRegistry` prueba que solo se abre un candidato Google AOA `18d1:2d00`/`18d1:2d01` en la ubicación física vinculada, registra intentos de apertura y falla cerrado por identidad ausente, ambigüedad o handle abierto en otra ubicación.
- `RusbAoaAccessoryHandleRegistry` enumera candidatos AOA reales por ubicación física, detecta ambigüedad antes de abrir, abre el candidato único y vuelve a leer la ubicación desde `handle.device()` antes de devolver el handle. El handle queda sin interfaz bulk reclamada; esa integración queda para T5c2c.

## Verificación T5c1b

La verificación local de T5c1b cubre solamente el guard de identidad física antes del futuro bulk. No se ejecutó contra hardware USB físico, no se observó una re-enumeración real, no se reclamó interfaz bulk, no se leyeron ni escribieron frames, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: falló con código 101 por símbolos/comportamiento inexistentes: imports `AoaObservedDevice` y `UsbPhysicalLocation`, constructor `RecordingUsbDeviceRegistry::with_device_at_location`, constructor `AoaAccessoryReenumerationPoller::fake_with_observed_snapshots`, método `poll_until_bound_to_location` y variantes `UsbProbeError::AoaReenumerationAmbiguous` y `UsbProbeError::PhysicalIdentityUnavailable`. Esta invocación usa filtro `.`; cuando compila, filtra las pruebas y ejecuta 0 tests, por eso se mantuvo como RED de compilación y se corre el runner completo después.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: pasó con código 0. La invocación exacta compila lib/bin/integration test y, por el filtro `.`, reporta 24 pruebas filtradas en `aoa_boundary_test.rs` y 0 ejecutadas.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 24 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: falló inicialmente con código 1 por formato pendiente en `desktop/usb-probe/src/lib.rs` y `desktop/usb-probe/tests/aoa_boundary_test.rs`.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- Después de aplicar formato y actualizar la CLI/documentación: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .` pasó con código 0 y 24 pruebas filtradas por el filtro `.`; `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con código 0 y 24 pruebas Rust; `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó sin salida; `git diff --check` pasó sin salida.

### Revisión nativa RDD T5c1b

- Candidato: `7a53e0e` contra base `6458f46`.
- Lineage: `review-7f646ad8c675d1c1`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-panicking-location-accessor` en `desktop/usb-probe/src/lib.rs:252-255`. No abre corrección para T5c1b; se trata como trabajo futuro si un llamador necesita inspeccionar observaciones no vinculadas.

### Límites y decisiones T5c1b

- `rusb` expone una identidad física utilizable para esta frontera mediante `Device::bus_number()` y `Device::port_numbers()`. No se usa `address` porque puede cambiar durante re-enumeración.
- La ruta real falla cerrado con `PhysicalIdentityUnavailable` si `port_numbers()` falla o devuelve una ruta vacía; no degrada silenciosamente a `VID:PID` ni a address.
- El poll post-AOA solo acepta Google AOA `18d1:2d00`/`18d1:2d01` en la misma ubicación física capturada pre-START; si hay más de una coincidencia en esa ubicación devuelve `AoaReenumerationAmbiguous`; si no aparece devuelve `AoaReenumerationTimedOut`.
- Los errores aislados de descriptor durante enumeración real se saltean para no abortar todo el poll si otros snapshots/dispositivos todavía pueden evaluarse.
- La CLI mantiene el guard explícito `--device VID:PID` para el control inicial y reporta el dispositivo AOA observado como físicamente matcheado; el reclamo de interfaz bulk y el I/O de frames quedan para T5c2.

## Verificación T5c1

La verificación local de T5c1 cubre solamente el poll acotado de re-enumeración AOA después de `START_ACCESSORY`. No se ejecutó contra hardware USB físico, no se observó una re-enumeración real, no se reclamó interfaz bulk, no se leyeron ni escribieron frames, no se instalaron ni modificaron drivers, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: falló con código 101 por imports/símbolos inexistentes: `AoaAccessoryReenumerationPoller`, `AOA_ACCESSORY_DEVICE_IDS`, variante `UsbProbeError::AoaReenumerationTimedOut`, método `ReenumerationWait::max_attempts` y método `LiveAoaControlRunner::start_accessory_and_poll`. Esta invocación usa filtro `.`; cuando compila, filtra las pruebas y ejecuta 0 tests, por eso se mantuvo como RED de compilación y se corrió el runner completo después.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: pasó con código 0. La invocación exacta compila lib/bin/integration test y, por el filtro `.`, reporta 20 pruebas filtradas en `aoa_boundary_test.rs` y 0 ejecutadas.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó con código 0; 20 pruebas Rust en verde, más lib/bin/doc tests sin pruebas.

### Revisión nativa RDD T5c1

- Candidato: `25cacc4` contra base `fea053d`.
- Lineage: `review-3b74a320767c5602`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgos advisory no bloqueantes del reviewer: `R3-001`, `R3-002` y `R3-003` en `desktop/usb-probe/src/lib.rs`. No abren corrección para T5c1; se tratan como trabajo futuro junto con T5c2 si aplican.

### Formato y límites explícitos

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: falló inicialmente con código 1 por formato pendiente en `desktop/usb-probe/src/lib.rs` y `desktop/usb-probe/tests/aoa_boundary_test.rs`.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- Después de aplicar formato y actualizar este documento: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .` pasó con código 0 y 20 pruebas filtradas por el filtro `.`; `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con código 0 y 20 pruebas Rust; `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó sin salida; `git diff --check` pasó sin salida.
- La ruta live-control conserva el guard de `--device VID:PID` para abrir solo el dispositivo inicial explícito. El poll posterior busca únicamente Google AOA `18d1:2d00` o `18d1:2d01` dentro del límite configurado por `--reenumeration-wait-ms`; si no aparece, devuelve `AoaReenumerationTimedOut` en vez de éxito descriptivo.
- La CLI ahora comunica si la re-enumeración AOA fue observada por el comando y deja explícito que el reclamo de interfaz bulk y el I/O de frames quedan para T5c2.

## Verificación T5b

La verificación local de T5b cubre una ruta host AOA de control en Rust con límites fake para pruebas y una CLI runnable para modo real. No se ejecutó contra hardware USB físico, no se observó re-enumeración real, no se reclamaron endpoints bulk, no se instaló ni modificó ningún driver, no se usó Zadig, no se tocó Windows, Samsung, cámara, OBS ni Wi‑Fi, y no se usó ADB como transporte.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: falló con código 101 por imports inexistentes en `usb_probe`: `HostAoaControlOptions`, `LiveAoaControlRunner`, `RecordingUsbDeviceRegistry`, `ReenumerationWait` y variante `UsbProbeError::SelectedDeviceNotFound`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: pasó con código 0. La invocación exacta compila lib/bin/integration test y, por el filtro `.`, reporta 16 pruebas filtradas en `aoa_boundary_test.rs` y 0 ejecutadas.
- Spot check adicional sin filtro para confirmar ejecución de pruebas: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 16 pruebas Rust en verde.

### Revisión nativa RDD T5b

- Candidato: `d1485a1` contra base `33e196f`.
- Lineage: `review-0355a50420ca2b05`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida.
- Hallazgo advisory no bloqueante del reviewer: `R3-reenumeration-wait-ignored` en `desktop/usb-probe/src/main.rs:43-51`. No abre corrección para T5b; se trata como trabajo futuro o precisión de T5c/smoke si se requiere observar re-enumeración real.

### Refactor, formato y límites explícitos

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: falló inicialmente con código 1 por formato pendiente en `desktop/usb-probe/src/lib.rs` y `desktop/usb-probe/tests/aoa_boundary_test.rs`.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml .`: pasó nuevamente después del formato con el mismo filtro `.` y 16 pruebas filtradas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: pasó sin salida después del formato.
- `git diff --check`: pasó sin salida.
- Spot check del parent local después del writer/verifier: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 16 pruebas Rust; `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó; `git diff --check` pasó sin salida.
- Verificación independiente read-only: `gentle-ai-verify` reportó PASS, ejecutó `git diff --check`, el runner Rust completo y `cargo fmt --check`, y no encontró bloqueantes.
- `LiveAoaControlRunner` abre únicamente el `VID:PID` explícito recibido en `HostAoaControlOptions`; el fake registra un solo intento de apertura y cero intentos de enumeración fallback.
- La ruta de control reutiliza `AoaHostController` y `RusbAoaControlTransport`: `GET_PROTOCOL`, seis `SEND_STRING` con strings NUL-terminated y `START_ACCESSORY`.
- La CLI mantiene `--dry-run --device VID:PID` y agrega `--live-control --device VID:PID [--control-timeout-ms N] [--reenumeration-wait-ms N]`; elegir modo real sin `--device` devuelve error antes de abrir hardware.
- El resultado de re-enumeración es acotado y descriptivo: registra una espera post-START limitada, pero no afirma que el dispositivo físico se haya desconectado, reconectado ni aparecido como Google AOA.

## Verificación T5a

La verificación local de T5a cubre solamente el límite host Rust ejecutable en modo dry-run/offline y pruebas fake deterministas. No prueba hardware USB real, re-enumeración real, endpoints bulk reales, Windows, Samsung Galaxy Note10, Samsung Galaxy S24+, cámara, OBS, Wi‑Fi, video, drivers, Zadig ni configuraciones del sistema. ADB no se usa como transporte de producto.

### Revisión nativa RDD T5a

- Candidato: `6c55126` contra base `0b429a0`.
- Lineage: `review-0413e095afba842b`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida. No volver a consultar STATUS sobre este lineage quemado.
- Hallazgos advisory no bloqueantes del reviewer: `R3-cli-process-coverage`, `R3-frame-length-truncation` y `R3-invalid-endpoint-addresses`. No abren corrección para T5a; se tratan como trabajo futuro.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 por imports inexistentes en `usb_probe`: `BulkEndpointClaim`, `BulkFrame`, `BulkTransportBoundary`, `DeviceIdentifier` y `DryRunAoaPlanner`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó; 14 pruebas Rust en `aoa_boundary_test.rs` en verde, más lib/bin/doc tests sin pruebas.

### Refactor, spot check y límites explícitos

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: falló inicialmente con código 1 por formato pendiente en `desktop/usb-probe/src/main.rs` y `desktop/usb-probe/tests/aoa_boundary_test.rs`.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó nuevamente después del formateo; 14 pruebas Rust en `aoa_boundary_test.rs`, más lib/bin/doc tests sin pruebas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: pasó sin salida después del formateo.
- `git diff --check`: pasó sin salida.
- Spot check del parent local después del writer/verifier: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 14 pruebas Rust; `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó; `git diff --check` pasó sin salida.
- La CLI host solo implementa `--dry-run --device VID:PID`; sin `--dry-run` devuelve error porque abrir hardware sigue separado y no está habilitado.
- `DeviceIdentifier` acepta únicamente el formato explícito `VID:PID` hexadecimal de cuatro dígitos por lado; entrada ausente o inválida es error.
- `DryRunAoaPlanner` no produce plan sin dispositivo explícito y enumera pasos deterministas: selección explícita, `GET_PROTOCOL`, `SEND_STRING` 0..5, `START_ACCESSORY`, espera de re-enumeración y no reclamo de bulk en dry-run.
- `BulkTransportBoundary` modela endpoints bulk reclamados sin abrir hardware; `BulkFrame` serializa `stream_id` y longitud little-endian antes del payload y rechaza payload vacío.
- No se ejecutó contra hardware, no se tocó Windows, Samsung, cámara, OBS, Wi‑Fi, drivers, Zadig ni configuraciones del sistema, y no se usó ADB como transporte.

## Verificación T4

La verificación local de T4 cubre solo un límite Android de I/O accesorio con pruebas JVM y streams mockeables. No prueba hardware USB real, permisos Android reales mostrados por el sistema, re-enumeración AOA real, endpoints bulk reales, Windows, Samsung Galaxy Note10, Samsung Galaxy S24+, cámara, OBS, Wi‑Fi, video, drivers, Zadig ni configuraciones del sistema. ADB no se usa como transporte de producto.

### Revisión nativa RDD T4

- Candidato: `b6f8a85` contra base `f62e0ab`.
- Lineage: `review-a092b4fb9a1066ba`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida. No volver a consultar STATUS sobre este lineage quemado.
- Hallazgo advisory no bloqueante del reviewer: `R3-001` en `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/UsbAccessoryBoundary.kt:123-126`. No abre corrección para T4; se trata como trabajo futuro.

### RED observado antes del código de producción

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: falló con código 1 en `:android:usb-probe:compileDebugUnitTestKotlin` por símbolos Kotlin inexistentes: `UsbAccessoryBoundary`, `AccessoryOpenResult`, `AccessoryStreams`, `AccessoryIoSession`, `AccessoryReadResult` y `UsbAccessoryGateway`.

### GREEN observado

- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest`: pasó; `BUILD SUCCESSFUL` con 14 tareas, 5 ejecutadas y 9 up-to-date. Antes del verde final hubo una falla de implementación por usar `data object`, no compatible con la versión Kotlin efectiva; se corrigió a `object`.

### Refactor, assemble, spot check y límites explícitos

- Refactor/compatibilidad: `AccessoryReadResult.Eof` quedó como `object` para mantener compatibilidad con el lenguaje Kotlin efectivo del módulo.
- `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug`: pasó; `BUILD SUCCESSFUL` con 21 tareas, 14 ejecutadas y 7 up-to-date.
- `git diff --check`: pasó sin salida.
- Spot check del parent local después del writer: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest` pasó con `BUILD SUCCESSFUL`; `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:assembleDebug` pasó con `BUILD SUCCESSFUL`; `git diff --check` pasó sin salida.
- El modelo Android no concede permisos: recibe `hasPermission` o consulta `UsbManager.hasPermission` y si falta permiso devuelve `PermissionDenied` sin abrir.
- La apertura autorizada usa un `UsbAccessoryGateway` inyectable; `AndroidUsbAccessoryGateway` encapsula `UsbManager.openAccessory` y expone `FileInputStream`/`FileOutputStream` sobre el descriptor devuelto.
- `AccessoryIoSession.readExactly` distingue lectura completa, lectura corta y EOF; una lectura corta no se trata como frame completo.
- `AccessoryIoSession.write` escribe bytes y llama `flush`.
- `AccessoryIoSession.close` es idempotente y cierra input, output y el recurso de sesión exactamente una vez en las pruebas JVM.
- No se ejecutó contra hardware, no se tocó Windows, Samsung, cámara, OBS, Wi‑Fi, drivers, Zadig ni configuraciones del sistema, y no se usó ADB como transporte.

## Verificación T3

La verificación local de T3 cubre el mapeo de solicitudes de control AOA hacia un límite host `rusb` usando mocks/fakes. No prueba hardware USB real, re-enumeración real, Google AOA VID/PID observado, endpoints bulk reales, Windows, macOS como host real, Samsung Galaxy Note10, Samsung Galaxy S24+, cámara, OBS, Wi‑Fi, video, drivers ni configuración del sistema. ADB no se usa como transporte de producto.

### Revisión nativa RDD T3

- Candidato: `dbf261c` contra base `43634da`.
- Lineage: `review-2eac537778fbc2c7`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida. No volver a consultar STATUS sobre este lineage quemado.
- Hallazgo advisory no bloqueante del reviewer: `R3-short-transfer` en `desktop/usb-probe/src/lib.rs:332`. No abre corrección para T3; se trata como trabajo futuro.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 por imports inexistentes en `usb_probe`: `AoaControlRequest`, `AoaStartOutcome`, `RecordingUsbControlIo` y `RusbAoaControlTransport`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó; 11 pruebas de `aoa_boundary_test.rs` en verde, más lib/doc tests sin pruebas.

### Refactor, spot check y límites explícitos

- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: falló inicialmente con código 1 por formato pendiente en `desktop/usb-probe/tests/aoa_boundary_test.rs`.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml`: pasó sin salida y aplicó formato Rust.
- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó nuevamente después del formateo; 11 pruebas Rust, lib/doc tests sin pruebas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: pasó sin salida después del formateo.
- Spot check del parent local después del writer: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 11 pruebas Rust, lib/doc tests sin pruebas; `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` pasó; `git diff --check` pasó sin salida.
- El adapter modela `GET_PROTOCOL` como control read `bmRequestType=0xC0`, request `51`, value `0`, index `0`, length `2`.
- El adapter modela `SEND_STRING` como control write `bmRequestType=0x40`, request `52`, value `0`, index `0..5`, payload UTF-8 terminado en `NUL`.
- El adapter modela `START_ACCESSORY` como control write `bmRequestType=0x40`, request `53`, value `0`, index `0`, payload vacío.
- Después de `START_ACCESSORY`, el resultado solo informa que el llamador debe esperar desconexión/reconexión y luego buscar Google AOA VID/PID o endpoints bulk reclamados; no afirma que esa re-enumeración haya ocurrido.
- No se ejecutó contra hardware, no se tocó Windows, Samsung, cámara, OBS, Wi‑Fi, drivers, Zadig ni configuraciones del sistema, y no se usó ADB como transporte.

## Verificación T2

La verificación local de T2 modela el handshake de control AOA del lado host sin hardware real. No prueba compatibilidad con Windows, macOS como host USB real, Samsung Galaxy Note10, Samsung Galaxy S24+, cámara, OBS, Wi‑Fi, video, drivers ni configuración del sistema. ADB no se usa como transporte de producto.

### Revisión nativa RDD T2

- Candidato: `bc4bb27` contra base `47add6c`.
- Lineage: `review-2c36083eccc1a5f2`.
- Resultado: aprobado y reconocido mediante `acknowledge-approved`; la autoridad quedó consumida. No volver a consultar STATUS sobre este lineage quemado.
- Hallazgo advisory no bloqueante del reviewer: `R3-transport-error-loss` en `desktop/usb-probe/src/lib.rs:128-130`. No abre corrección para T2; se trata como trabajo futuro.

### RED observado antes del código de producción

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: falló con código 101 por imports inexistentes en `usb_probe`: `AoaHostController`, `AoaOperation` y `FakeAoaTransport`.

### GREEN observado

- `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml`: pasó; 8 pruebas de `aoa_boundary_test.rs` en verde, más lib/doc tests sin pruebas.

### Refactor, spot check y límites explícitos

- Refactor: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml` formateó las pruebas Rust; luego `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` volvió a pasar con 8 pruebas.
- `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check`: pasó sin salida después del formateo.
- `git diff --check`: pasó sin salida.
- Spot check del parent local después del writer/verifier: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` pasó con 8 pruebas Rust, lib/doc tests sin pruebas.
- El modelo solo registra operaciones de control AOA sobre un transporte fake: `GetProtocol`, seis strings de identidad en orden índice 0..5 y `StartAccessory`.
- La ruta negativa de versión de protocolo rechaza `0` después de `GetProtocol` y antes de enviar identidad.
- La ruta negativa de identidad incompleta falla antes de cualquier solicitud USB simulada.
- No se enumeró hardware USB en pruebas, no se tocó Android, no se usó ADB como transporte, no se instaló ni modificó ningún driver, no se usó Zadig, no se tocaron configuraciones del sistema y no se afirmó compatibilidad de hardware, Windows, Samsung, cámara, OBS ni Wi‑Fi.

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
