# Plan integral del producto ChinchillaCam

> Estado (2026-09-26): plan paraguas creado y revisado en `feat/android-camera-catalog` desde HEAD limpio `2958236`; corrección de seguridad de planificación aplicada en commit separado. Este plan coordina el producto completo sin push/PR/merge. Los commits siguen siendo locales. Las pruebas físicas Samsung/Mac/Windows quedan deliberadamente al final y requieren acceso explícito a hardware del usuario.

## Alcance de producto

ChinchillaCam debe permitir usar un teléfono Android Samsung compatible como cámara de video para Windows 11 o macOS 13+ Apple Silicon, con transporte USB y Wi‑Fi local, sin cuentas, sin nube y sin servidores externos.

No objetivos actuales:

- audio;
- grabación;
- fotos;
- nube, cuentas, backend público o analítica remota;
- múltiples computadoras activas a la vez;
- soporte prometido para Windows 10, Mac Intel u otros teléfonos antes de evidencia.

## Documentos vinculados

- Producto: `README.md`, `docs/requisitos.md`, `docs/factibilidad.md`, `docs/uso.md`.
- Definición inicial: `odd/tasks/define-product.md`.
- USB/AOA: `odd/tasks/usb-transport-feasibility.md`.
- Catálogo Camera2 Android: `odd/tasks/android-camera-catalog.md`.

## Estado actual resumido

### USB/AOA

Implementado y revisado localmente:

- host Rust `desktop/usb-probe` con dry-run, live-control, re-enumeración, claim bulk y `--live-bulk-smoke`;
- Android USB accessory smoke APK con permiso explícito, frame bounded y ACK;
- endurecimientos de callback/timeout/identidad de permiso.

Pendiente:

- `T6` de `odd/tasks/usb-transport-feasibility.md` sigue pendiente hasta pruebas físicas reales en Samsung + Windows + macOS. No se cierra con documentación.

### Camera2 Android

Implementado y revisado localmente:

- dominio `CameraCapabilityCatalog` con gateway inyectable;
- clasificación de IDs `DirectOpenCandidate` vs `PhysicalOnlyChild`;
- capacidades `Known`/`Unknown`/`Unavailable`;
- hardening T1b ante fallo parcial y orden determinista.

Pendiente inmediato:

- T2 adapter real `CameraManager`/`CameraCharacteristics` sin abrir cámara;
- T3 UI española para listar/seleccionar solo candidatos directos.

## Incertidumbres técnicas nombradas

Estas no son bloqueos de planificación, pero sí puertas de evidencia antes de declarar soporte real:

1. **USB-AOA-Samsung** — AOA y bulk smoke pueden fallar o comportarse distinto en Samsung reales.
2. **WinUSB-libusb-Windows11** — `rusb` puede requerir asociación manual WinUSB/libusb; no se automatiza driver/Zadig.
3. **MediaFoundation-WindowsCamera** — exponer una cámara seleccionable en Windows 11 debe validarse con apps reales.
4. **OBS-macOS-binding** — el flujo macOS depende de OBS Studio o integración aceptable; no prometer dispositivo nativo `ChinchillaCam` sin prueba.
5. **Lifecycle-Camera-FGS** — comportamiento en estado no visible y foreground service depende de Android/dispositivo.
6. **Samsung-physical-camera-IDs** — IDs físicos Camera2 pueden ser consultables pero no abribles ni seleccionables directamente.
7. **Android-min-version** — `minSdk` técnico no equivale a versión mínima de producto validada.
8. **LAN-discovery-firewall** — Wi‑Fi local puede fallar por red invitada, aislamiento de clientes o firewall.
9. **Latency-quality-budget** — calidad real depende de captura, encoder, transporte, decode y publicación como cámara.
10. **Unsigned-installers** — distribución gratuita desde GitHub puede mostrar advertencias de seguridad en Android/Windows/macOS.

## Reglas de ejecución

Para cada unidad de trabajo:

- escribir/actualizar ODD antes de source cuando cambie el plan;
- TDD estricto: RED observado antes de producción, GREEN después;
- incluir tests y docs/evidencia en el mismo work unit;
- commits locales solamente;
- revisión nativa RDD por candidato cuando aplique;
- sin push, PR ni merge;
- sin code-golf para esconder tamaño;
- rollback ordinario por commit local y `git revert` en la rama local si se abandona el candidato; cualquier operación destructiva como `git reset` requiere autorización humana explícita;
- UI y Markdown orientados a usuario en español; código en inglés;
- no afirmar soporte hardware sin evidencia física.

## Milestones y work units

### M1 — Camera2 catalog real, selección segura y UX mínima Android

1. **T2 — Adapter real CameraManager sin abrir cámara.** Implementar gateway `CameraManager`/`CameraCharacteristics` con guards de API para IDs lógicos/físicos, tamaños, FPS y controles; excepciones como `Unknown`; sin `CameraDevice.open`.
2. **T3 — UI española de catálogo de cámaras.** Listar `DirectOpenCandidate` seleccionables y `PhysicalOnlyChild` no abribles; explicar que un candidato puede fallar al abrir.
3. **T4 — Permiso de cámara Android.** Modelar flujo de permiso cámara visible, denegado/otorgado/stale, sin captura aún.
4. **T5 — Persistencia de preferencia de cámara.** Guardar selección local por ID directo; invalidar si desaparece del catálogo.

### M2 — Captura Android y encoder local

5. **T6 — Apertura CameraDevice acotada.** Abrir solo ID directo seleccionado con fakes/adapter seam; errores tipados; sin streaming externo.
6. **T7 — Sesión de captura preview/frame source.** Crear boundary para sesión Camera2 repeating hacia `Surface` inyectada; cerrar recursos; no encoder aún.
7. **T8 — MediaCodec encoder H.264/AVC MVP.** Encapsular encoder con tests de state machine/fakes; producir chunks codificados o errores tipados.
8. **T8b — Orquestador local cámara→encoder.** Conectar seams T6/T7/T8 con selección directa actual, permiso `CAMERA` actual y acción explícita Start/Stop; drenar chunks acotados/tipados y cerrar ante fallos/lifecycle; sin Activity real si se separa en T8c; sin USB/Wi‑Fi/network/storage. Corregir antes de captura prolongada el advisory T7 `R3-stop-failure-cleanup`.
9. **T8c — Cableado Activity visible del pipeline local.** Integrar Start/Stop español en Android visible, cerrar en lifecycle y mantener cero transporte externo/FGS/claims; separar de T8b si el lote sería demasiado grande.
10. **T9 — Métricas Android de captura/encode.** FPS reales, chunks drenados/dropped por backpressure, encoder latency y estado visible desde el pipeline local, no métricas sintéticas.
11. **T10a — Shell seguro de foreground service de cámara.** Declarar permisos/tipo FGS cámara y notificación honesta desde acción visible, pero mantener la Activity como dueña del pipeline y detener en `onStop`; sin claim de estado no visible.
12. **T10b — Transferir ownership del pipeline al service no exportado.** Iniciar solo mientras Activity está visible con permiso `CAMERA` fresco y selección directa actual; el service posee cámara→encoder, mantiene ownership del pipeline bajo notificación persistente después de `onStop`; el comportamiento en estados no visibles queda pendiente de validación física, cancela arranque pendiente, no retiene Activity, usa `START_NOT_STICKY`, se detiene por notificación/usuario/revocación/destrucción; sin background cold-start ni claim de compatibilidad hasta M9.

### M3 — Pairing, autoridad local y framing de sesión

10. **T11 — Protocolo de sesión y framing de video.** Extender framing para handshake, stream metadata, video chunks, metrics y control messages.
11. **T12 — QR pairing payload.** Diseñar payload local con claves/identificador de PC, expiración y tests; el checksum sin clave solo detecta corrupción accidental, no autentica.
12. **T13 — Persistencia de confianza local.** Guardar confianza en Android y desktop; revocación local. Estado (2026-09-28): T13a Android in-memory seam hecho; T13b Android persistente hecho en esta rama como port manual C6 (`6ff5575`, `b5deec4`, `ab02bab`, `2c3ae01`, `75c1ec9`) desde `feat/t13b-android-persistent-trust`; T13c desktop persistente/revocación hecho como MVP en `feat/desktop-video-sink` (`93ab9d0`, `a9771c5`, `ba32d62`, `989a200`), con hardening diferido (symlink/ownership, permisos owner-only, fsync, aliasing y recuperación de lock). Ninguno de los dos tiene registro de revisión nativa.
13. **T14 — One-active-computer enforcement.** Rechazar segunda sesión activa con mensaje claro; handoff explícito. Plan: autoridad local pura antes de transporte, sin fallback silencioso ni LAN.
- **M3e — TLS sobre USB AOA para la prueba de posesión.** Hecho en ambos lados: Android `odd/tasks/usb-tls-pairing.md` (e1–e3) y desktop `odd/tasks/usb-tls-pairing.md` en `feat/desktop-video-sink` (M3e1–M3e3); interop JSSE↔rustls socketless probada en `4263e6d`. Sin wiring productivo: la prueba todavía no está compuesta con `PendingPairingCoordinator` ni con el store de teléfonos del desktop.

Gate M3 para LAN/T17: no abrir LAN sin protección. Antes de cualquier LAN autenticado debe existir T13b Android persistente, T13c desktop persistente/revocación, prueba de posesión de la clave privada correspondiente al `trustMaterial` del QR, nonce de uso único con expiración, confirmación explícita de confianza y threat model/plan testeable con TLS estándar e identidad de par pinneada/esperada.

Estado del gate (2026-09-28): T13b hecho; T13c hecho como MVP; prueba de posesión hecha (M3c por loopback y M3e por USB, ambas sólo en tests); nonce de uso único con expiración hecho; confirmación explícita hecha en la capa de dominio (`PendingPairingCoordinator.confirm`). Falta el threat model consolidado y, sobre todo, la identidad esperada del teléfono ante el desktop: hoy el teléfono pinnea al desktop, pero el desktop no autentica al teléfono (`TrustedPhoneIdentity` prevé una clave pública que nada produce todavía). Esto requiere una decisión de diseño de seguridad antes de componer el pairing de punta a punta y antes de T17/T19.

Actualización del gate (2026-09-28, feature phone-mtls-identity): la identidad esperada del teléfono ya existe a nivel de dominio con TLS mutuo estándar en ambos lados — Android presenta un certificado de cliente P-256 respaldado por Android Keystore; el desktop lo exige, liga su SPKI al nonce de QR consumido durante el pairing, sólo persiste tras confirmación explícita que rechaza teléfonos revocados, y en la reconexión sólo acepta teléfonos confiables y no revocados. El threat model consolidado vive en `odd/tasks/phone-mtls-identity.md` §5 (ambos worktrees). Con esto, todos los ítems del gate existen a nivel de dominio; sigue faltando antes de T17: composición/wiring de producción del flujo, confirmación en UI con comparación de código corto (T25 y la UI del teléfono), y validación en dispositivo (M9).

### M4 — Transportes autenticados y cambio de modo

14. **T15 — USB video transport sobre AOA bulk.** Reusar claim/framing existente para stream sostenido con backpressure/timeouts; sin prueba física todavía. T15a inicia con seam fake `UsbSessionFrameTransport` que anida `SessionFrame` como payload opaco dentro de `AccessoryFrame`.
15. **T16 — Wi‑Fi LAN transport fake/loopback.** Modelar transporte Wi‑Fi sin listener externo no autenticado; fakes o loopback local hasta tener pairing/trust.

    Estado (2026-09-30): T16 hecho a nivel de dominio/test en ambos lados, sin listener externo, revisión nativa pendiente. Android (rama `feat/t15c-fake-usb-sustained`): seam `TlsCiphertextTransport` + `StreamTlsCiphertextTransport` en memoria (TLS crudo sobre stream, fail-closed, sólo `java.io`), sin permiso de red y con guardia `NoNetworkListenerContractTest` que prohíbe permisos de red y sockets de servidor en `src/main`. Desktop (rama `feat/desktop-video-sink`): stack TLS genérico `S: Read + Write` + `LoopbackLanListener` que sólo enlaza `127.0.0.1`, commits `02206e1`, `4108428`. La interop cruzada entre worktrees sigue pospuesta.
16. **T17 — Wi‑Fi LAN transport autenticado.** Habilitar listener/red local solo después de QR pairing + confianza local + one-active-computer; rechazar tráfico no autenticado.
17. **T18 — Transport switch model.** Cambiar USB/Wi‑Fi sin reemparejar; interrupción explícita y estado recuperable.
18. **T19 — Reconnection and session resume.** Reconectar cable/red sin nuevo QR cuando confianza local siga válida.

### M5 — Desktop receive/decode core

19. **T20 — Desktop receiver session core.** Separar transporte, protocolo, decode y métricas; fakes para USB/Wi‑Fi.
20. **T21 — Video decode pipeline.** Decodificar frames H.264 con boundary testeable; errores tipados.
21. **T22 — Desktop metrics.** FPS, latency, dropped frames, active transport quality y causa probable.

### M6 — Cámara seleccionable por plataforma

22. **T23 — Windows 11 virtual camera prototype.** Media Foundation boundary, registro/publicación, pruebas automatizables donde sea posible; validación con app real queda para fase física/manual.
23. **T24 — macOS OBS integration prototype.** Fuente/flujo OBS documentado o adapter si viable; no prometer cámara nativa propia.
24. **T25 — Desktop app UX shell.** Pantallas españolas de pairing, transporte, cámara seleccionada, métricas y fallos.

### M7 — Calidad, controles y recuperación

25. **T26 — Controles de cámara/calidad.** Resolución, FPS, cámara, modo automático; degradar si capability `Unavailable`.
26. **T27 — Error recovery UX.** USB/Wi‑Fi/cámara/encoder/desktop output con acciones claras.
27. **T28 — Privacy and local-only audit.** Confirmar sin cuentas/nube/backend/audio/recording; docs y tests de configuración.

### M8 — Build, packaging y publicación gratuita

28. **T29 — Android APK product build.** Separar o renombrar probe/product si procede; APK GitHub-ready.
29. **T30 — Windows package.** Build/install local, advertencias de firma si aplica, sin prometer fricción cero.
30. **T31 — macOS package.** Build Apple Silicon, OBS dependency docs, advertencias de firma/notarización.
31. **T32 — CI/local release automation.** Comandos reproducibles para APK/Windows/macOS, checksums y docs.

### M9 — Validación física final

32. **T33 — USB physical matrix.** Requiere acceso del usuario a Samsung referencia + Windows 11 + macOS 13+; WinUSB/libusb manual solo si el usuario decide; registrar evidencia.
33. **T34 — Wi‑Fi LAN matrix.** Requiere redes reales del usuario; firewall/guest isolation, reconnect, latency.
34. **T35 — Camera and lifecycle no visible matrix.** Requiere Note10/S24+ u otros disponibles; cámaras, resolución/FPS, estado no visible.
35. **T36 — External app matrix.** Requiere apps reales del usuario; Windows apps seleccionan `ChinchillaCam`; macOS apps seleccionan `OBS Virtual Camera`; documentar fallos.
36. **T37 — Support declaration pass.** Solo aquí mover hipótesis a soporte real o limitaciones por plataforma/dispositivo, basado en evidencia de T33–T36.

## Próxima unidad autorizada

Actualización 2026-10-07 (tras packaging-release, T29/T32 del lado Android): `odd/tasks/packaging-release.md`. Decisiones del usuario: identificador público `io.github.oyhamburo.chinchillacam`, firma con clave release local fuera del repo (`~/.chinchillacam/`, certificado `b31c0838…240c`, respaldo a cargo del usuario) y versión 0.1.0 preliminar. `scripts/release-android.sh` produce `dist/ChinchillaCam-0.1.0-android.apk` firmado y verificado con su checksum; `docs/release.md` describe pasos, respaldo y checklist. Publicar en GitHub Releases queda como decisión manual. El paquete de macOS (T31) está en `feat/desktop-video-sink`; Windows (T30) y M9 al final.

Actualización 2026-10-07 (tras remote-quality-control, lado teléfono): `odd/tasks/remote-quality-control.md`, commits `48a85fa`, `e41325d`, `604bbfe` y el cierre, 574 tests/2 omitidos/0 fallas. Decisiones del usuario: la PC vinculada puede cambiar cámara, resolución y FPS; una sola preferencia compartida, gana el último cambio. Protocolo `quality-control-v1` sobre el frame 7 (capacidad en HELLO, `quality_subscribe`, `quality_state`, `set_quality`); el teléfono valida contra su plan, guarda la preferencia, reconfigura en vivo y responde con el estado; los cambios locales (pantalla de conexión y de diagnóstico) se informan a la PC suscripta. Lado desktop en `feat/desktop-video-sink`. Siguiente: M8 (empaquetado, T29–T32); Windows y M9 al final.

Actualización 2026-10-07 (tras privacy-local-audit, T28 del lado del teléfono): `odd/tasks/privacy-local-audit.md`, commit `bdaf157`, 560 tests/2 omitidos/0 fallas. Tests de contrato: lista cerrada de permisos (cámara, notificaciones, servicio en primer plano de cámara), backup desactivado, sólo `ConnectionActivity` exportada, sin APIs de audio/grabación/HTTP/WebView/almacenamiento externo/logging ni sockets salientes en `src/main`, y única dependencia de runtime ZXing. `TlsPairingProofVerifier` (cliente de socket del prototipo) pasó a tests. Guía de datos y permisos en `docs/uso.md` §8.1. La parte desktop de T28 está en `feat/desktop-video-sink`. Siguiente: control remoto de calidad desde la PC, luego M8 (empaquetado); Windows y M9 al final.

Actualización 2026-10-07 (tras error-recovery-ux, T27 del lado del teléfono): `odd/tasks/error-recovery-ux.md`, commits `d01205d..dd9a6b9`, 553 tests/2 omitidos/0 fallas, assemble y lint OK. Catálogo tipado de fallas (mensajes en español sin texto técnico, causa de fin de sesión conservada), acciones de recuperación en la pantalla de conexión (Reintentar con la última PC, Reintentar cámara en sesión, Abrir ajustes, Vincular de nuevo) y notificación «ChinchillaCam se detuvo» en fallas (decisión del 2026-10-01). Sin reconexión automática (T19). La parte desktop de T27 sigue en `feat/desktop-video-sink`.

Actualización 2026-10-07 (tras camera-quality-controls, T26 del lado del teléfono): `odd/tasks/camera-quality-controls.md`, commits `04ac6be..7f5aab5`, 530 tests/2 omitidos/0 fallas, assemble y lint OK. Decisiones del usuario: los controles viven en el teléfono (el control remoto desde la PC por el frame 7 queda para una unidad posterior) y el cambio se aplica en vivo reiniciando sólo cámara y encoder dentro de la sesión. Planificador de calidad desde el catálogo (1080p/720p/540p/480p × 30/24/15 FPS, automático 720p30, fallback 720p30 con controles deshabilitados y motivo cuando las capacidades son `Unknown`/`Unavailable`), `CONTROL_AE_TARGET_FPS_RANGE` en la captura, `ACTION_RECONFIGURE` con vuelta atrás si falla, y UI en la pantalla de conexión. En paralelo, en el desktop (rama `feat/desktop-video-sink`): paso 2 (app con ventana + composición de producción) y paso 3 (salida a OBS con ventana de video propia, `odd/tasks/macos-obs-output.md`). Siguiente: T27 (UX de recuperación de errores), T28 (auditoría local), control remoto de calidad desde la PC y luego empaquetado (M8); Windows y M9 al final.

Actualización 2026-10-03 (tras android-production-connection): paso 1 hecho en Android (`odd/tasks/android-production-connection.md`, commits `edb7d4c..45a6bed`, 496/2/0): SAS v1 de 6 dígitos, sesión sobre el canal del pairing confirmado, `PhoneConnectionController` + `PhoneConnectionRuntime` de proceso (USB AOA → TLS → sesión → cámara/encoder en el foreground service en modo sesión), escáner QR con ZXing core 3.3.3 y `ConnectionActivity` en español como launcher. Sigue el paso 2: app de escritorio con ventana + composición de producción, que además debe implementar SAS v1 con el mismo vector y el HELLO/ACCEPT post-pairing.

Actualización 2026-10-03 (decisiones del usuario, delegación amplia): las pruebas físicas (M9) quedan para el final y el desarrollo continúa sin ellas. Decisiones: (1) UI del teléfono para pairing y conexión autorizada, con diseño y decisiones de UI delegados al orquestador (mínima, en español, sin cuentas ni nube: escanear QR, confirmar código corto, PCs confiables, conectar/desconectar, estado visible); (2) macOS expone la cámara vía OBS Virtual Camera (T24), sin cámara nativa propia; (3) app de escritorio con ventana en Rust (UI nativa liviana en español: QR de pairing, confirmación, estado, métricas; T25); (4) Windows (decoder Media Foundation y cámara virtual, T21c/T23) al final, cuando se pueda probar. Orden previsto: composición de producción Android (USB → TLS → sesión → cámara) + UI de pairing/conexión; app desktop con ventana + composición de producción (USB AOA → sesión → decoder); salida hacia OBS; luego M7/M8; Windows y M9 al final.

Actualización 2026-10-01 (tras session-pipeline-wiring): el cableado de la sesión al pipeline queda hecho a nivel de dominio en ambos lados. Desktop: receptor→decoder con descarte hasta keyframe ante saturación y métricas locales (commits `975a75e`, `9f8645d`, `03acb12`, `0b79ab7` en `feat/desktop-video-sink`). Android: `SessionEgressBinding` (arranca el runtime desde un `Reconnected`, fábrica de sink de video, fin de sesión → detención del pipeline fuera del hilo del runtime con error tipado en español y cierre idempotente) y la composición para el service layer, sin fuente de sesión de producción (§4.6). Próximas candidatas autorizables: la unidad del decoder nativo por plataforma (el usuario eligió nativo: VideoToolbox primero), o la UI de pairing/connect, que todavía necesita autorización humana explícita antes de empezar. Sin fuente de sesión de producción no hay cámara virtual end-to-end todavía.

Actualización 2026-10-01 (tras session-runtime): el runtime de sesión queda hecho a nivel de dominio/test en ambos lados. Desktop: loop por pasos con lectura acotada, keepalive por tracker y secuencia compartida (commits `d6ecb92`, `0c71563`, `d78ebeb` en `feat/desktop-video-sink`). Android: `SessionRuntime` (hilo escritor/lector, keepalive, validación +1/`sessionId`, fin tipado) más el egress de video por el runtime (`SessionRuntimeVideoTransport`). Próximas candidatas autorizables: (a) cablear el runtime en el pipeline visible / foreground service (Android) y el receptor→decoder en el desktop; o (b) T17 — Wi‑Fi LAN transport autenticado, que sigue exigiendo los ítems del gate M3 (wiring de producción, UI de confirmación con código corto, one-active-computer sobre LAN) y una decisión humana explícita antes de abrir cualquier listener no loopback. Recomendación: hacer (a) primero; (b) no empieza sin esa decisión humana.

Actualización 2026-09-30 (tras wifi-loopback-transport / T16): T16 queda cerrado a nivel de dominio/test en ambos lados (ver la nota de estado en M4/T16), sin listener externo y con revisión nativa pendiente. Las próximas candidatas autorizables son: (a) el loop de runtime de sesión (lectura dúplex + envío de keepalive, diferido desde session-liveness) y (b) T17 — Wi‑Fi LAN transport autenticado, que todavía exige los ítems del gate M3 (wiring de producción, UI de confirmación con código corto, one-active-computer sobre LAN) y una decisión humana explícita antes de abrir cualquier listener no loopback. Recomendación: hacer (a) primero; (b) no empieza sin esa decisión humana.

Actualización 2026-09-29 (tras usb-authenticated-session): M3 queda compuesto de punta a punta a nivel de dominio por USB (pairing con canal vivo, confirmación explícita en ambos lados, reconexión confiable con `HANDSHAKE_HELLO`/`HANDSHAKE_ACCEPT`, `SessionFrame` sobre TLS); la prueba cruzada de la sesión completa quedó pospuesta por decisión del usuario. Próxima unidad: vitalidad de la sesión (lecturas tolerantes a inactividad y keepalive en ambos lados) junto con los tests de seguridad pendientes de la reconexión; después, T16.

Actualización 2026-09-28 (tras phone-mtls-identity): la próxima unidad autorizada es componer el pairing USB de punta a punta a nivel de aplicación/dominio en ambos lados, sin UI ni LAN: en Android, `PendingPairingCoordinator` con `UsbTlsPairingProofVerifier` sobre un canal que presenta `PhoneTlsIdentity`; en el desktop, emisor de QR → handshake de pairing → candidato → confirmación explícita → reconexión confiable. Después, T16.

Actualización 2026-09-28: M4/T15 (transporte fake de `SessionFrame` sobre `0x01020304`) y M3e están hechos. La próxima unidad depende de una decisión humana de seguridad: cómo se autentica el teléfono ante el desktop (identidad de par esperada del gate M3). Con esa decisión, cerrar M3 componiendo de punta a punta QR → TLS USB pinneado → `CCP1` → confirmación → confianza persistente en ambos lados; después seguir con T16 (Wi‑Fi fake/loopback, sin listener). La migración de `SessionFrame` al canal TLS vivo forma parte de los transportes autenticados de M4.

Texto anterior, conservado como historial: la próxima unidad recomendada podía seguir siendo **M4/T15 — USB video transport sobre AOA bulk** porque no abre LAN ni depende de persistencia completa; M3 permanecía parcial hasta T13b/T13c. Empezar con seams/fakes y frames de sesión anidados; sin prueba física todavía y sin reinterpretar endianess del `SessionFrame`.

Antes de escribir source para T8b se debe:

- actualizar `odd/tasks/android-camera-capture.md` con el alcance T8b/T8c;
- espejar en Engram;
- escribir tests RED del orquestador cámara→encoder;
- mantener separado el cableado `UsbProbeActivity` si el lote excede tamaño reviewable;
- mantener sin USB/Wi‑Fi/network/storage/FGS/pruebas físicas ni claims.

## Decisiones humanas inevitables conocidas

No se requiere decisión humana rutinaria para continuar con T2. Las decisiones que probablemente sí serán inevitables más adelante son:

- aceptar o rechazar pasos manuales de driver/WinUSB/libusb en Windows si la validación USB lo exige;
- elegir si macOS se queda en OBS-only o se invierte en una cámara virtual nativa si OBS UX no alcanza;
- elegir identificadores/nombres públicos de app/paquetes antes de releases;
- ejecutar pruebas físicas finales con los dispositivos/plataformas disponibles.
