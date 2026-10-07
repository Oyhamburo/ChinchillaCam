# Auditoría de privacidad y operación local (T28, desktop)

## 1. Objetivo

Comprobar con evidencia y dejar protegido por tests que la app de escritorio no usa red externa, telemetría ni actualizaciones remotas, que no escucha conexiones en producción, que guarda sus datos privados con permisos restrictivos y que no imprime secretos; documentar qué se guarda, dónde y cómo borrarlo.

## 2. Hallazgos (auditoría del 2026-10-07)

- Dependencias: `eframe` sin features por defecto (`glow`, `default_fonts`), `qrcode`, `rusb`, `rustls` (ring), `rcgen`, `sha2`, `base64`, `getrandom`, `objc2-*` en macOS. Sin clientes HTTP, telemetría ni crash reporting. `tokio` aparece en `Cargo.lock` sólo vía `wasm-bindgen-futures` (objetivo wasm, no se compila en macOS/Windows).
- Red: `loopback_lan_listener.rs` y `loopback_pairing_proof_server.rs` sólo enlazan `127.0.0.1`; ninguno se usa desde el binario `chinchillacam` ni desde `usb-probe/src/main.rs` (el servidor de prueba sólo desde un binario auxiliar de interop).
- Archivos: identidad en `identity/` (0700) con archivo 0600; `trusted-phones.txt` sin modo propio (depende de la carpeta 0700).
- Sin impresión de claves, nonces, QR ni códigos cortos.

## 3. Decisiones (orquestador, dentro de los no-objetivos del producto)

1. Lista de crates prohibidos sobre `Cargo.lock` (HTTP, telemetría, autoactualización, TLS del sistema); `tokio` se admite sólo como dependencia de `wasm-bindgen-futures`.
2. Las APIs de red (`TcpListener`, `TcpStream`, `UdpSocket`) sólo pueden aparecer en los módulos `loopback_*` y binarios auxiliares, nunca en `desktop/app/src` ni en `usb-probe/src/main.rs`.
3. `trusted-phones.txt` se escribe con modo 0600 explícito.

## 4. Contrato

- `desktop/app/tests/privacy_contract_test.rs` con las aserciones de 3.1 y 3.2.
- `FileTrustedPhoneStore` crea el archivo (y su temporal) con 0600 en Unix; test.
- `docs/uso.md` §8: datos guardados en la computadora, dónde y cómo borrarlos.

## 5. Riesgos

- Una dependencia nueva legítima puede requerir actualizar la lista; el test debe explicar cómo.

## 6. Reglas de ejecución

TDD estricto donde aplique (los tests de contrato se escriben primero y deben fallar ante una violación simulada o un caso real); commits de work unit ≤400 líneas con tests y docs; writers delegados acotados. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runners: `cd desktop/usb-probe` y `cd desktop/app` con `PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && cargo test --offline && cargo clippy --offline --all-targets -- -D warnings` (líneas de base 336/0 y 40/0).

1. [x] d1 — test de contrato de privacidad (dependencias y red) y `trusted-phones.txt` con 0600. RED: `trusted_phone_file_is_private`. ~250 líneas.
   - Evidencia d1: RED observado (0644 ≠ 0600); GREEN 337/0 en usb-probe y 45/0 en app; fmt y clippy sin advertencias en ambos crates. Contrato sin infracciones detectadas; `tokio` sólo depende de `wasm-bindgen-futures`.
2. [ ] d2 — `docs/uso.md` §8 con datos guardados; renumerar la guía de mensajes del desktop a §9.7 para no chocar con la del teléfono al integrar; cierre, plan general y push.

## 8. Evidencia
