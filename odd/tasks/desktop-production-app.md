# App de escritorio con ventana + composición de producción

## 1. Objetivo

Que la computadora (macOS primero) ofrezca una app con ventana en español que muestre el QR de vinculación, el código corto a confirmar, los teléfonos confiables, el estado y las métricas, y que componga en producción USB AOA → TLS → pairing o reconexión → sesión → decoder VideoToolbox en hilo propio.

## 2. Problema

Las piezas de dominio existen pero nadie las compone (mapa del 2026-10-03):

- no hay SAS: el candidato de pairing no expone `qr_nonce` ni `challenge_nonce`;
- tras `confirm` el desktop no lee `HANDSHAKE_HELLO` ni responde `HANDSHAKE_ACCEPT` (sesión `None` → `SessionRuntime` rechaza); el teléfono ya lo envía (Android `PairedSessionStarter`);
- `FileTrustedPhoneStore` no tiene `list` ni `forget`; el emisor de QR no poda nonces vencidos (tope 64);
- no hay orquestación USB (detección, AOA, claim, stream, reintento) ni selección pairing/reconexión por conexión;
- no hay app con ventana, QR renderizado ni directorio de configuración.

## 3. Decisiones

Fuente: `odd/tasks/complete-webcam-product.md`, actualización 2026-10-03 (app con ventana en Rust, UI liviana en español; dependencias de UI permitidas si compilan `--offline` o se verifican). Decisiones del orquestador dentro de esa delegación:

1. **UI:** `eframe`/`egui` 0.36.2 con backend `glow` y `default_fonts` (sin `wgpu`), y `qrcode` 0.14.1 sin features por defecto. Verificado: compilan con rustc 1.98 y quedan en la caché para `--offline`. Viven en un crate nuevo `desktop/app` (binario `chinchillacam`) que depende de `usb-probe` por path; la librería sigue sin dependencias de UI.
2. **SAS v1** idéntico al de Android (`odd/tasks/android-production-connection.md` §3.1 en el worktree Android): mismo vector (`841 406`; SPKIs invertidos `418 534`).
3. **Modo por conexión elegido por la UI del desktop:** mientras la pantalla de vinculación muestra un QR vigente, la próxima conexión se acepta en modo pairing; si no, en modo reconexión (sólo teléfonos confiables). Sin cambio del modelo de seguridad (el verificador de pairing sigue aceptando cualquier certificado sólo con un QR emitido; la reconexión sigue rechazando desconocidos en el handshake). Un desajuste (el teléfono intenta reconectar mientras se muestra el QR) falla con mensaje claro y se reintenta.
4. **Sesión tras el pairing:** después de `confirm` en el desktop, leer `HANDSHAKE_HELLO` y responder `HANDSHAKE_ACCEPT` en el mismo canal (lógica extraída de la reconexión), con plazo de 120 s como el teléfono; recién después `set_idle_read_timeout`.
5. **Detección USB:** dispositivo ya en modo accesorio (VID `0x18D1`, PID `0x2D00`/`0x2D01`) o, si no hay, el primer dispositivo Samsung (VID `0x04E8`) al que se le pide el modo accesorio con la identidad existente (`ChinchillaCam`/`USB Probe`). Un VID:PID explícito por variable de entorno queda como escape para diagnóstico. No se sondean otros dispositivos.
6. **Directorio de configuración:** `~/Library/Application Support/ChinchillaCam/` (`identity/` 0700 y `trusted-phones.txt`). `desktop_id` = SHA-256 hex del SPKI del desktop (estable); nombre = nombre del equipo, acotado a 64 bytes.
7. **Política ya registrada:** ante saturación se descarta hasta el próximo keyframe; decoder nativo (VideoToolbox); fin de sesión → estado visible y vuelta a esperar conexión.

## 4. Contrato

1. `pairing_short_code_v1(desktop_spki, phone_spki, qr_nonce, challenge_nonce)` → 6 dígitos, vector fijo compartido; el candidato pendiente expone los nonces y su código.
2. `PendingPairedPhoneSession::confirm_and_start(label, store, identity, timeout)` → `AuthenticatedPhoneSession` con `session: Some(..)`; HELLO inválido/plazo → error tipado y cierre. La reconexión usa la misma función de HELLO sin cambio de comportamiento.
3. `FileTrustedPhoneStore::list()` / `forget(phone_id)`; el emisor poda nonces vencidos al emitir.
4. `DesktopConnectionWorker` (librería, testeable con transportes en memoria): comandos (`StartPairing`, `CancelPairing`, `ConfirmPairing`, `RejectPairing`, `Disconnect`, `ForgetPhone`, `Shutdown`) y eventos (`Status`, `PairingQr`, `ConfirmCode`, `Connected`, `Metrics`, `SessionEnded`, `TrustedPhones`); un hilo propio; decoder inyectado por fábrica (VideoToolbox en producción, falso en tests); `set_idle_read_timeout` tras el HELLO.
5. Enlace USB de producción: detección §3.5, AOA, claim y `UsbTlsCiphertextStream`, con reintento tras desconexión.
6. App `chinchillacam`: modelo de vista puro (testeado) + render egui; textos en español.

## 5. Riesgos

- Árbol de dependencias de UI grande (~250 crates) sólo en `desktop/app`.
- Lecturas bloqueantes durante el pairing (hasta 120 s): la cancelación cierra el stream.
- Sin validación física (M9) de AOA real, VideoToolbox con cámara real ni interop con el teléfono.
- Windows: el store de identidad no soporta Windows todavía (paso 5).

## 6. Reglas de ejecución

TDD estricto con RED observado; commits de work unit ≤400 líneas con tests y docs; writers delegados acotados; el orquestador lee el diff y verifica que todo callback nuevo esté cableado en producción. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test --offline && PATH=$HOME/.cargo/bin:$PATH cargo clippy --offline --all-targets -- -D warnings`. Baseline: 298 tests, 0 fallos (HEAD `b9eb3ab`).

1. [x] d1 — SAS v1 + nonces en el candidato. RED: `short_code_matches_android_vector`. ~200 líneas.
   - Evidencia d1: módulo `pairing_short_code` (`PairingShortCode`, `pairing_short_code_v1`, `ShortCodeError`); `CompletedPairingProof` y `PendingPairedPhoneSession` llevan `qr_nonce`/`challenge_nonce` capturados antes de consumir el pedido CCP1 (la reconexión no cambia); `PendingPairedPhoneSession::short_code(identity)`. RED observado: primero imports sin resolver, luego con stub `left: "000000" right: "841406"`; RED de `pending_pairing_exposes_short_code_matching_phone_inputs`: `no method named short_code`. GREEN: vector `841 406`, SPKIs invertidos `418 534`, ceros a la izquierda, entradas vacías rechazadas. Suite 303/0 (298 + 5), fmt y clippy limpios.
2. [ ] d2 — HELLO/ACCEPT tras `confirm` (función compartida con la reconexión). RED: `confirmed_pairing_accepts_phone_hello`. ~300 líneas.
3. [ ] d3 — `list`/`forget` del store y poda de nonces vencidos. RED: `forgotten_phone_is_not_listed_or_trusted`. ~250 líneas.
4. [ ] d4 — `DesktopConnectionWorker` (pairing, reconexión, pipeline, eventos). RED: `pairing_then_session_reports_connected_and_metrics`. ~400 líneas (partir si excede).
5. [ ] d5 — Enlace USB de producción (detección, AOA, claim, stream, reintento) + rutas de configuración. RED: `detection_prefers_accessory_mode_then_samsung`. ~300 líneas.
6. [ ] d6 — Crate `desktop/app`: modelo de vista + ventana egui + QR. RED: `pairing_view_shows_qr_and_code`. ~400 líneas (partir si excede).

## Progreso

Plan creado el 2026-10-03 (HEAD `b9eb3ab`).
- d1 completa: SAS v1 con vector compartido y código expuesto por el pairing pendiente; 303 tests, 0 fallos.
