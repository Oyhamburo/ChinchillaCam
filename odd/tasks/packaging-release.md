# Empaquetado y release reproducible (M8: T31 y T32, macOS)

## 1. Objetivo

Producir `ChinchillaCam.app` para macOS Apple Silicon (13+) con identificador y versión públicos, firmado ad-hoc, comprimido y con checksum, mediante un comando reproducible, listo para subir a GitHub Releases junto con la guía de OBS y la advertencia de Gatekeeper.

## 2. Problema

- No hay bundle `.app`, `Info.plist`, versión pública ni script de empaquetado; el binario `chinchillacam` sólo se construye con `cargo build`.

## 3. Decisiones

1. **Usuario, 2026-10-07:** identificador público `io.github.oyhamburo.chinchillacam` (Android) e `io.github.oyhamburo.chinchillacam.desktop` (macOS).
2. **Usuario, 2026-10-07:** el APK se firma con una clave release local fuera del repo (`~/.chinchillacam/release.jks`, EC P-256, alias `chinchillacam`; contraseñas en `~/.chinchillacam/release-signing.env`, ambos 0600). La build la toma de las variables `CHINCHILLACAM_KEYSTORE`, `CHINCHILLACAM_KEYSTORE_PASSWORD`, `CHINCHILLACAM_KEY_ALIAS` y `CHINCHILLACAM_KEY_PASSWORD`. El usuario debe respaldar la clave.
3. **Usuario, 2026-10-07:** primera versión `0.1.0`, marcada como preliminar (sin validación física).
4. **Producto (docs/uso.md §2.3):** presupuesto cero; en macOS sin Developer ID ni notarización: firma ad-hoc y advertencia de Gatekeeper documentada.
5. **Orquestador:** el `namespace`/paquete Kotlin (`dev.chinchillacam.usbprobe`) y la identidad AOA no cambian (no son públicos); Windows (T30) queda para el final con M9.

## 4. Contrato

- `desktop/app/Cargo.toml` versión `0.1.0`; la ventana muestra la versión ("ChinchillaCam 0.1.0 (preliminar)").
- `packaging/macos/Info.plist` (plantilla): `CFBundleIdentifier` `io.github.oyhamburo.chinchillacam.desktop`, `CFBundleName`/`CFBundleDisplayName` "ChinchillaCam", `CFBundleExecutable` `chinchillacam`, `CFBundleShortVersionString` `0.1.0`, `CFBundleVersion` `1`, `LSMinimumSystemVersion` `13.0`, `NSHighResolutionCapable`; sin claves de cámara/micrófono (la app no los usa).
- `scripts/package-macos.sh`: `cargo build --release --offline` (aarch64-apple-darwin), arma el bundle, firma ad-hoc (`codesign --force --sign -`) y verifica (`codesign --verify`), genera `dist/ChinchillaCam-0.1.0-macos-arm64.zip` con `ditto` y `dist/SHA256SUMS-macos.txt`. Nunca usa identidades de firma del llavero. `dist/` ignorado por git.
- Test que comprueba que la versión del `Info.plist` coincide con `Cargo.toml` y que el identificador es el decidido.
- `docs/release.md`: pasos reproducibles, advertencia de Gatekeeper (abrir con clic derecho → Abrir o desde Privacidad y seguridad) y referencia a la guía de OBS.

## 5. Riesgos

- Sin notarización, Gatekeeper bloquea la primera apertura; se documenta el desbloqueo manual.
- El acceso USB desde una app ad-hoc no se validó en hardware (M9).

## 6. Reglas de ejecución

TDD donde aplique (tests de configuración/contrato antes del cambio); para scripts de empaquetado, verificación funcional ejecutándolos de punta a punta. Commits de work unit ≤400 líneas con tests y docs. Push autorizado tras la unidad; sin PR, sin merge y **sin publicar releases en GitHub** (decisión del usuario pendiente para publicar).

## 7. Tareas

Runner app: `export PATH=$HOME/.cargo/bin:$PATH && cd desktop/app && cargo fmt -- --check && cargo test --offline && cargo clippy --offline --all-targets -- -D warnings` (línea de base 50/0).

1. [x] m1 — versión 0.1.0 visible, `Info.plist` + test de coherencia, `scripts/package-macos.sh` y ejecución real (bundle firmado ad-hoc, zip y checksum). RED: `info_plist_matches_cargo_version_and_bundle_id`. ~200 líneas.
   - Evidencia m1: RED por ausencia de `Info.plist`; GREEN 52 pruebas, 0 fallos; fmt y clippy sin errores. Script ejecutado: firma ad-hoc verificada, ZIP arm64 y SHA-256 generados; `plutil -lint` correcto. No se inició la interfaz.
2. [ ] m2 — `docs/release.md` y `docs/uso.md` §2.3 actualizados; cierre y push.

## 8. Evidencia
