# Salida de video hacia OBS en macOS (paso 3)

## 1. Objetivo

Que los fotogramas que VideoToolbox ya decodifica (NV12) lleguen a OBS Studio en macOS para que el usuario los publique con **OBS Virtual Camera** (decisión del 2026-10-03, T24), sin prometer una cámara nativa propia.

## 2. Problema

`desktop/app/src/bootstrap.rs` crea por sesión un `ChannelDecodedFrameSink` (cola de 4) y un hilo `decoded-frame-drain` que descarta todo. No hay conversión de color, ni último fotograma compartido, ni ventana de video, ni guía de OBS.

## 3. Decisiones

1. **Camino a OBS (decisión del usuario, 2026-10-07):** ventana de video propia + fuente "Captura de pantalla de macOS" (modo ventana) en OBS → "Iniciar cámara virtual". Sin dependencias nuevas, todo en Rust y offline. Syphon, el stream local y el plugin propio quedan descartados por ahora; el pipeline queda listo para cambiar de salida sin tocar el decoder.
2. **Conversión:** NV12 empaquetado (Y `w×h`, luego CbCr intercalado `ceil(w/2)×ceil(h/2)` pares, sin stride; formato que entrega `videotoolbox_decoder::copy_locked_planes`) → RGBA opaco, rango de video (limitado). Matriz BT.709 para alto ≥ 720 y BT.601 por debajo (convención habitual de reproductores cuando no hay VUI). La conversión corre en un hilo propio, fuera del hilo de la UI.
3. **Último fotograma gana:** el consumidor vacía la cola y convierte sólo el más nuevo (nunca bloquea al decoder y evita `Backpressure` por conversión lenta). Se publica en una ranura compartida que sobrevive a las sesiones, con número de secuencia; al terminar la sesión (cola desconectada) la ranura se limpia sólo si la última publicación es de esa sesión.
4. **Ventana de video:** viewport aparte de egui titulado `ChinchillaCam — Video`, sin controles, fondo negro, imagen ajustada manteniendo proporción y tamaño inicial igual al del fotograma (acotado a la pantalla). La ventana principal tiene un botón para mostrarla/ocultarla. Sin video: texto "Esperando video…".
5. **Guía en español** en `docs/uso.md`: permisos de grabación de pantalla para OBS, fuente de captura de ventana, recorte, cámara virtual y la limitación de no minimizar la ventana.

## 4. Contrato

1. `nv12_to_rgba(width, height, data) -> Result<Vec<u8>, Nv12Error>`: valida dimensiones no nulas y largo exacto; RGBA con alfa 255.
2. `LatestVideoFrame` (clonable, `Send + Sync`): `publish`/`snapshot` con secuencia, `clear_if_owner`.
3. `spawn_frame_presenter(receiver, slot, session, on_frame)`: hilo que vacía la cola, convierte el más nuevo, publica, avisa a la UI (`on_frame`) y limpia al desconectarse; descarta formatos que no sean NV12 sin caerse.
4. `start_production_worker` recibe la ranura y el aviso de repintado; ya no hay hilo de descarte.

## 5. Riesgos

- Captura de ventana: OBS pide permiso de grabación de pantalla y no captura ventanas minimizadas; la calidad depende del tamaño de la ventana.
- Conversión escalar en CPU: con 1080p puede ser lenta en debug; "último gana" evita frenar al decoder.
- Matriz de color sin leer VUI: posible leve corrimiento de color hasta leer la VUI del SPS (seguimiento).
- Sin OBS instalado ni hardware: la validación real queda para M9 (T36).

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner app: `cd desktop/app && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline && PATH=$HOME/.cargo/bin:$PATH cargo clippy --offline --all-targets -- -D warnings`.

1. [x] o1 — `nv12_to_rgba` (BT.601/BT.709, rango de video, dimensiones impares, validación de largo). RED: `nv12_converts_reference_colors`. ~180 líneas.
   - Evidencia o1: RED observado: `nv12_converts_reference_colors` falló al devolver `EmptyDimensions` para una entrada válida (0 aprobadas, 1 fallida). GREEN: 4 pruebas NV12 y 20 pruebas de la aplicación aprobadas. Comprobaciones: `cargo fmt -- --check`, `cargo test --offline` y `cargo clippy --offline --all-targets -- -D warnings` aprobados.
2. [ ] o2 — `LatestVideoFrame` + `spawn_frame_presenter` (último gana, limpieza por sesión, aviso de repintado, formatos ajenos). RED: `presenter_publishes_only_the_newest_frame`. ~250 líneas.
3. [ ] o3 — cableado en `bootstrap` (sin hilo de descarte), viewport de video en la ventana, botón y guía OBS en `docs/uso.md`. RED: `production_frames_reach_the_shared_slot` (factory con decoder falso). ~250 líneas.
4. [ ] o4 — cierre: evidencia, `complete-webcam-product.md`, push.

## 8. Evidencia
