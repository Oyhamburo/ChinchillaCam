# UX de recuperación de errores en el desktop (T27, desktop)

## 1. Objetivo

Que cada falla que ve el usuario en la app de escritorio diga qué pasó y qué hacer, sin texto técnico, y que el usuario note cuando hay conexión pero no llega video.

## 2. Problema (mapa del 2026-10-07)

- `messages.rs` colapsa todo `SessionEnded(Failed(_))` en "La sesión terminó por un error." y `Pipeline`/`PairingQr`/`TrustedPhoneStore` en "Ocurrió un error. Intentá de nuevo."; `Link` no distingue "no hay teléfono" de "error del dispositivo" o "no se pudo cambiar a modo accesorio".
- El enlace USB reintenta cada 2 s y emite un `ConnectionFailed` por intento: el aviso se repite sin cambiar.
- No hay aviso cuando la sesión está conectada pero no llegan fotogramas decodificados; la falla al iniciar el presentador sólo hace `eprintln!` en inglés.
- Los errores de arranque no ofrecen acción.

## 3. Decisiones

1. **Usuario, 2026-10-01 (vigente):** sin reconexión automática del lado del teléfono (T19); el desktop sigue sondeando el USB como hoy.
2. **Orquestador (delegación de UI):**
   - Catálogo tipado de fallas del desktop → mensaje en español + sugerencia de acción (por ejemplo "Conectá el teléfono con el cable y abrí ChinchillaCam en el teléfono", "Desbloqueá el teléfono y aceptá el permiso USB", "Volvé a vincular el teléfono").
   - Fallas repetidas de enlace con la misma causa no reemiten el aviso.
   - Vigía de video: conectado y sin fotogramas decodificados durante 5 s → aviso "No llega video del teléfono" con sugerencia; se limpia al llegar un fotograma.
   - La falla al iniciar el presentador se muestra en la UI.

## 4. Contrato

1. `messages.rs`: mapeo exhaustivo por causa (`DesktopConnectionFailure`, `DesktopSessionEndReason::Failed(SessionEnd)`, errores USB) a mensaje + sugerencia, con tests.
2. Deduplicación de avisos repetidos en el modelo de vista.
3. Vigía de video puro y testeable sobre la secuencia de la ranura de video y el reloj.

## 5. Riesgos

- Los errores USB reales (permisos de macOS, cables, AOA) sólo se ven con hardware (M9).

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner app: `cd desktop/app && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline && PATH=$HOME/.cargo/bin:$PATH cargo clippy --offline --all-targets -- -D warnings`; lib: lo mismo en `desktop/usb-probe` (líneas de base 28/0 y 334/0).

1. [x] e1 — catálogo de fallas por causa con sugerencias y deduplicación de avisos repetidos. RED: `link_failures_distinguish_missing_phone_from_device_error`. ~300 líneas.
   - Evidencia e1: RED observado por ausencia de sugerencia en la vista y por el sondeo demorado tras no encontrar un teléfono; se clasificaron las etapas del enlace, se comprobaron los avisos, la deduplicación y los cierres de sesión y se conservó el sondeo inmediato sin teléfono. La reconexión mantiene un aviso genérico de identidad.
2. [x] e2 — vigía de video (5 s) y falla del presentador visibles en la ventana. RED: `connected_without_frames_for_five_seconds_warns`. ~250 líneas.
   - Evidencia e2: RED observado por falta de `observe_video`; el vigía usa el contador de fotogramas publicados para no confundir el vaciado de la ranura con una llegada, reinicia al reconectar y muestra avisos y sugerencias en la ventana sin reemplazar fallas específicas. Se comprobaron los límites de tiempo, la llegada, la desconexión y la señal de falla del presentador con pruebas sin hardware.
3. [ ] e3 — cierre: guía de problemas en `docs/uso.md`, plan general, push.

## 8. Evidencia

## 9. Seguimientos

- Exponer una causa tipada de reconexión para distinguir un teléfono desconocido o revocado de otras fallas de identidad; el error actual de la negociación sólo expone texto. Por ahora se muestra «No se pudo comprobar la identidad del teléfono.» con la sugerencia «Si no está vinculado, vinculalo con el QR.».
- Separar el puerto USB ocupado del acceso denegado: el error de reclamo actual no aporta una categoría tipada fiable. Mantener mientras tanto la sugerencia de cerrar la otra app o permitir el acceso.
