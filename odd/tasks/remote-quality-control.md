# Control remoto de cámara y calidad desde la PC (desktop)

## 1. Objetivo

Que la ventana del desktop muestre la cámara y la calidad que transmite el teléfono, con las opciones que admite, y permita cambiarlas; el teléfono aplica el cambio en vivo (T26).

## 2. Problema

- El desktop descarta `HANDSHAKE_HELLO.capabilities` (`phone_connection.rs:313`).
- `SessionRuntime` trata un frame 7 entrante como violación de protocolo (`session_runtime.rs:459-468`); el pipeline y el worker no exponen `send_command`.
- La app no tiene comandos ni vista de calidad.

## 3. Decisiones

1. **Usuario, 2026-10-07 (T26):** los controles viven primero en el teléfono; el control remoto desde la PC es esta unidad.
2. **Usuario, 2026-10-07:** la PC vinculada puede cambiar cámara, resolución y FPS.
3. **Usuario, 2026-10-07:** una sola preferencia compartida; gana el último cambio (teléfono o PC) y los dos muestran el mismo estado.
4. **Orquestador:** negociación por capacidad en HELLO + `quality_subscribe` para no romper desktops ni teléfonos viejos.

## 4. Protocolo `quality-control-v1` (idéntico en los dos lados)

Todo viaja en el frame 7 `CAMERA_CONTROL_COMMAND(command, arguments)` ya existente, con valores de texto.

1. **Negociación.** El teléfono anuncia la capacidad `quality-control-v1` en `HANDSHAKE_HELLO.capabilities`. El desktop la guarda y, sólo si está, envía `quality_subscribe` (`v=1`) después de iniciar la sesión. El teléfono envía frames 7 al desktop **sólo después** de recibir `quality_subscribe` (un desktop viejo trata un frame 7 entrante como violación de protocolo). Cada lado ignora comandos desconocidos.
2. **`quality_state`** (teléfono → desktop), en respuesta a `quality_subscribe`, después de procesar cada `set_quality` y cuando el usuario cambia la calidad en el teléfono mientras hay suscripción:
   - `v=1`; `req=<n>` sólo si responde a un `set_quality`; `error=unsupported|invalid|unavailable` si rechazó el pedido;
   - `mode=auto|manual`; `camera.selected=<id>|auto`;
   - `camera.count=N`, `camera.<i>.id`, `camera.<i>.label` (i = 0..N-1);
   - `res.count=N`, `res.<i>=<W>x<H>`, `res.<i>.enabled=1|0`, `res.<i>.reason` (opcional);
   - `fps.count=N`, `fps.<i>=<F>`, `fps.<i>.enabled=1|0`, `fps.<i>.reason` (opcional);
   - `applied.res=<W>x<H>`, `applied.fps=<F>`, `summary=<texto>`.
3. **`set_quality`** (desktop → teléfono): `v=1`, `req=<n>` (entero creciente), `camera=<id>|auto` (opcional: ausente = mantener), `mode=auto|manual`, y `width`, `height`, `fps` obligatorios si `mode=manual`. El teléfono valida contra su propio plan (opciones habilitadas), guarda la preferencia compartida (gana el último cambio), reconfigura en vivo y responde con `quality_state` con el mismo `req`.
4. **Límites del parser** (ambos lados): a lo sumo 16 cámaras, 16 resoluciones y 16 FPS; cada clave y valor de hasta 256 bytes; números positivos sin signos ni ceros a la izquierda; claves desconocidas ignoradas; un mensaje fuera de límites se descarta sin cortar la sesión.

## 5. Riesgos

- El reinicio de cámara corta el video unos segundos; el vigía de 5 s (T27) puede avisar si tarda más.
- Sin hardware: la interoperabilidad real se valida en M9.

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runners: `export PATH=$HOME/.cargo/bin:$PATH` y en `desktop/usb-probe` y `desktop/app`: `cargo fmt -- --check && cargo test --offline && cargo clippy --offline --all-targets -- -D warnings` (líneas de base 337/0 y 45/0).

1. [x] c1 — capacidades del HELLO guardadas en la sesión + módulo `quality_control` (tipos, codificación y parser con límites). RED: `quality_state_round_trips_with_indexed_options`. ~350 líneas.
   - Evidencia c1: RED por módulo ausente; GREEN de ida y vuelta, mapa literal, límites, capacidades y sesión HELLO; verificación de formato, tests y clippy en usb-probe y tests en app.
2. [x] c2 — `SessionRuntime` acepta frames 7 entrantes (cola de control) y el pipeline expone enviar y recibir control. RED: `inbound_camera_control_is_queued_not_a_violation`. ~250 líneas.
   - Evidencia c2: RED por ausencia de `take_controls`; GREEN de recepción con secuencia, cola FIFO acotada con descarte del más antiguo, envío/recepción por pipeline y mapa literal de Android; verificación de formato, tests y clippy en usb-probe y tests en app.
3. [ ] c3 — worker: `quality_subscribe` automático si hay capacidad, `DesktopCommand::SetQuality`, `DesktopEvent::QualityState`. RED: `worker_subscribes_and_reports_quality_state`. ~300 líneas.
4. [ ] c4 — app: sección de calidad en la ventana (cámara, resolución, FPS, automático; deshabilitadas con motivo). RED: `connected_view_shows_remote_quality_options`. ~300 líneas.
5. [ ] c5 — cierre: verificación, docs, plan general, push.

## 8. Evidencia
