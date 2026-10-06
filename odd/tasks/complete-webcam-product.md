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
11. **T12 — QR pairing payload.** Diseñar payload local con claves/identificador de PC, expiración y tests.
12. **T13 — Persistencia de confianza local.** Guardar confianza en Android y desktop; revocación local.
13. **T14 — One-active-computer enforcement.** Rechazar segunda sesión activa con mensaje claro; handoff explícito.

### M4 — Transportes autenticados y cambio de modo

14. **T15 — USB video transport sobre AOA bulk.** Reusar claim/framing existente para stream sostenido con backpressure/timeouts; sin prueba física todavía.
15. **T16 — Wi‑Fi LAN transport fake/loopback.** Modelar transporte Wi‑Fi sin listener externo no autenticado; fakes o loopback local hasta tener pairing/trust. Estado (2026-09-30): hecho a nivel de dominio/tests en ambos lados (`odd/tasks/wifi-loopback-transport.md`); desktop: stack TLS genérico `S: Read + Write` (`02206e1`) y `LoopbackLanListener` sólo `127.0.0.1` (`4108428`); Android en `feat/t15c-fake-usb-sustained`: seam `TlsCiphertextTransport` + `StreamTlsCiphertextTransport` en memoria, sin permisos de red. Sin listener externo; revisión nativa pendiente.
16. **T17 — Wi‑Fi LAN transport autenticado.** Habilitar listener/red local solo después de QR pairing + confianza local + one-active-computer; rechazar tráfico no autenticado.
17. **T18 — Transport switch model.** Cambiar USB/Wi‑Fi sin reemparejar; interrupción explícita y estado recuperable.
18. **T19 — Reconnection and session resume.** Reconectar cable/red sin nuevo QR cuando confianza local siga válida.

### M5 — Desktop receive/decode core

19. **T20 — Desktop receiver session core.** Separar transporte, protocolo, decode y métricas; fakes para USB/Wi‑Fi.
20. **T21 — Video decode pipeline.** Decodificar frames H.264 con boundary testeable; errores tipados.
    - 2026-10-01: decodificación H.264 real en macOS vía VideoToolbox completada (`odd/tasks/videotoolbox-decoder.md`) y probada con un fixture real, también de punta a punta en `DesktopSessionPipeline` sobre loopback TCP. Windows (Media Foundation) pendiente.
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

Actualización 2026-10-03 (tras desktop-production-app): paso 2 hecho en el desktop (`odd/tasks/desktop-production-app.md`, commits `8470cc5..68a88e8`, `usb-probe` 334/0 y `desktop/app` 16/0): SAS v1 con el vector compartido, HELLO/ACCEPT tras el pairing confirmado, `list`/`forget` y poda de nonces, `DesktopConnectionWorker` (pairing y reconexión → pipeline → decoder VideoToolbox en hilo propio, `set_idle_read_timeout` tras el HELLO), enlace USB de producción (modo accesorio o Samsung, espera del primer dato) y app `chinchillacam` con ventana egui en español (QR, código corto, teléfonos confiables, métricas). El paso 1 (Android) está en `feat/t15c-fake-usb-sustained` (`android-production-connection`). Sigue el paso 3: salida de video hacia OBS en macOS a partir de los frames decodificados.

Actualización 2026-10-03 (decisiones del usuario, delegación amplia): las pruebas físicas (M9) quedan para el final y el desarrollo continúa sin ellas. Decisiones: (1) UI del teléfono para pairing y conexión autorizada, con diseño y decisiones de UI delegados al orquestador (mínima, en español, sin cuentas ni nube: escanear QR, confirmar código corto, PCs confiables, conectar/desconectar, estado visible); (2) macOS expone la cámara vía OBS Virtual Camera (T24), sin cámara nativa propia; (3) app de escritorio con ventana en Rust (UI nativa liviana en español: QR de pairing, confirmación, estado, métricas; T25); (4) Windows (decoder Media Foundation y cámara virtual, T21c/T23) al final, cuando se pueda probar. Orden previsto: composición de producción Android (USB → TLS → sesión → cámara) + UI de pairing/conexión; app desktop con ventana + composición de producción (USB AOA → sesión → decoder); salida hacia OBS; luego M7/M8; Windows y M9 al final.

La próxima unidad de código recomendada en la rama actual es **M2/T10b — Transferir ownership del pipeline al foreground service no exportado**. T10a solo agregó el shell seguro; no declarar M2 completo ni comportamiento en estado no visible mientras el código siga deteniendo el pipeline en `onStop`.

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
