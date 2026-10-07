# Control remoto de cámara y calidad desde la PC (Android)

## 1. Objetivo

Que el teléfono informe su cámara y calidad a la PC vinculada que lo pida y acepte cambios desde ella, aplicándolos en vivo con la misma lógica de T26.

## 2. Problema

- HELLO anuncia `capabilities = emptyList()` (`SessionHelloExchange.kt:49`).
- `onCameraControlCommand = { }` está vacío (`PhoneConnectionRuntime.kt:63`) y corre en el hilo lector de la sesión.
- `SessionRuntime.send` existe pero no está expuesto fuera de `SessionEgressBinding`.

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

- La cola de salida (64) es compartida con el video: los `quality_state` deben ser pocos y chicos; una cola llena termina la sesión por saturación.
- El callback de control corre en el hilo lector: todo el trabajo va a un ejecutor propio.

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 560/2/0).

1. [x] a1 — capacidad `quality-control-v1` en HELLO, envío de control expuesto hasta la sesión activa, y codificación/parser del protocolo (`QualityControlProtocol`). RED: `quality_state_encodes_indexed_options`. ~350 líneas.
   - Evidencia a1: RED por referencia no resuelta a `encodeQualityState`; GREEN con el fixture literal (cámaras Automático/0 Trasera 1/1 Frontal 1, resoluciones 1920x1080/1280x720/960x540 deshabilitada/640x480, FPS 30/24/15 deshabilitado, Manual 1280x720@30, `req=7`, resumen «Calidad: Manual (1280 × 720, 30 FPS)»); HELLO, parser, límites y envío/cierre comprobados con pruebas focalizadas.
2. [ ] a2 — `QualityControlHandler` (suscripción, `set_quality` validado, preferencia compartida, reconfiguración, `quality_state` tras cambios locales) cableado en `PhoneConnectionRuntime`. RED: `set_quality_applies_supported_choice_and_replies_with_state`. ~350 líneas.
3. [ ] a3 — cierre: verificación, docs, plan general, push.

## 8. Evidencia
