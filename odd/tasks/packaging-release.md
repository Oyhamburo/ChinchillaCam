# Empaquetado y release reproducible (M8: T29 y T32, Android) (Android y desktop)

Este documento reúne los registros de las dos ramas, integradas en `main` el 2026-10-07 (`odd/tasks/branch-integration.md`). Cada parte conserva su evidencia original sin cambios.

## Parte Android (rama `feat/t15c-fake-usb-sustained`)

## Empaquetado y release reproducible (M8: T29 y T32, Android)

### 1. Objetivo

Producir un APK de release firmado, con identificador y versión públicos, y un comando reproducible que lo construye, verifica la firma y genera su checksum, listo para subir a GitHub Releases.

### 2. Problema

- `applicationId` sigue siendo `dev.chinchillacam.usbprobe` y la app se llama "ChinchillaCam USB Probe"; no hay `versionCode`/`versionName`.
- No hay configuración de firma release ni script de release.

### 3. Decisiones

1. **Usuario, 2026-10-07:** identificador público `io.github.oyhamburo.chinchillacam` (Android) e `io.github.oyhamburo.chinchillacam.desktop` (macOS).
2. **Usuario, 2026-10-07:** el APK se firma con una clave release local fuera del repo (`~/.chinchillacam/release.jks`, EC P-256, alias `chinchillacam`; contraseñas en `~/.chinchillacam/release-signing.env`, ambos 0600). La build la toma de las variables `CHINCHILLACAM_KEYSTORE`, `CHINCHILLACAM_KEYSTORE_PASSWORD`, `CHINCHILLACAM_KEY_ALIAS` y `CHINCHILLACAM_KEY_PASSWORD`. El usuario debe respaldar la clave.
3. **Usuario, 2026-10-07:** primera versión `0.1.0`, marcada como preliminar (sin validación física).
4. **Producto (docs/uso.md §2.3):** presupuesto cero; en macOS sin Developer ID ni notarización: firma ad-hoc y advertencia de Gatekeeper documentada.
5. **Orquestador:** el `namespace`/paquete Kotlin (`dev.chinchillacam.usbprobe`) y la identidad AOA no cambian (no son públicos); Windows (T30) queda para el final con M9.

### 4. Contrato

- `applicationId = "io.github.oyhamburo.chinchillacam"`, `versionCode = 1`, `versionName = "0.1.0"`, etiqueta de la app "ChinchillaCam".
- `buildTypes.release` firma con las variables de entorno; sin variables, `assembleRelease` falla con un mensaje claro (nunca firma con la clave debug).
- `scripts/release-android.sh`: construye `assembleRelease` `--offline`, verifica con `apksigner verify --print-certs`, copia a `dist/ChinchillaCam-0.1.0-android.apk` y escribe `dist/SHA256SUMS-android.txt`. `dist/` queda ignorado por git.
- `docs/release.md`: pasos reproducibles, respaldo de la clave y checklist previa a publicar.

### 5. Riesgos

- Perder la clave impide publicar actualizaciones sobre instalaciones existentes.
- Cambiar el `applicationId` hace que una instalación del prototipo quede como otra app (aceptable: no hubo releases).

### 6. Reglas de ejecución

TDD donde aplique (tests de configuración/contrato antes del cambio); para scripts de empaquetado, verificación funcional ejecutándolos de punta a punta. Commits de work unit ≤400 líneas con tests y docs. Push autorizado tras la unidad; sin PR, sin merge y **sin publicar releases en GitHub** (decisión del usuario pendiente para publicar).

### 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 574/2/0).

1. [x] k1 — identificador, versión, etiqueta y firma release por variables de entorno, con test de configuración. RED: `release_build_uses_public_identity_and_version`. ~150 líneas.
   - Evidencia k1: RED de contrato observado; pruebas unitarias, `assembleDebug` y `lintDebug` correctos; `assembleRelease` sin variables falla con el mensaje previsto y con variables produce un APK firmado cuyo certificado SHA-256 es `b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c`; paquete público y versión `0.1.0` verificados con `aapt2`.
2. [x] k2 — `scripts/release-android.sh` + `docs/release.md` + ejecución real del script (APK firmado y checksum); cierre y push.
   - Evidencia k2: `scripts/release-android.sh` ejecutado de punta a punta: sin variables falla con «Falta CHINCHILLACAM_KEYSTORE…» (salida 1); con variables y `EXPECTED_CERT_SHA256` produce `dist/ChinchillaCam-0.1.0-android.apk` (1,1 MB) firmado con el certificado `b31c0838…240c`, paquete `io.github.oyhamburo.chinchillacam` 0.1.0, y `SHA256SUMS-android.txt` (`8845e6e0b62e1eea12a98a955c757acd2905e8725b6e494f1b6b36cd822ec538`, verificado con `shasum -c`). `docs/release.md` (pasos, verificación, respaldo obligatorio de la clave y checklist) y `docs/uso.md` §2.1 con el artefacto real y sin permisos de red. Desvío: el writer delegado se colgó tras escribir el script; el orquestador completó permisos, `.gitignore`, documentación y la ejecución en línea.

### 8. Evidencia

- Commits: `bf2d330` (k1) y el commit de k2/cierre.
- Incidente: dos subagentes (writer de k2 y verificador de macOS) quedaron colgados 4 minutos y el harness los cortó; no dejaron cambios a medias más allá del script completo.
- Sin ejecutar: instalación del APK en un teléfono (M9).

## Parte desktop (rama `feat/desktop-video-sink`)

## Empaquetado y release reproducible (M8: T31 y T32, macOS)

### 1. Objetivo

Producir `ChinchillaCam.app` para macOS Apple Silicon (13+) con identificador y versión públicos, firmado ad-hoc, comprimido y con checksum, mediante un comando reproducible, listo para subir a GitHub Releases junto con la guía de OBS y la advertencia de Gatekeeper.

### 2. Problema

- No hay bundle `.app`, `Info.plist`, versión pública ni script de empaquetado; el binario `chinchillacam` sólo se construye con `cargo build`.

### 3. Decisiones

1. **Usuario, 2026-10-07:** identificador público `io.github.oyhamburo.chinchillacam` (Android) e `io.github.oyhamburo.chinchillacam.desktop` (macOS).
2. **Usuario, 2026-10-07:** el APK se firma con una clave release local fuera del repo (`~/.chinchillacam/release.jks`, EC P-256, alias `chinchillacam`; contraseñas en `~/.chinchillacam/release-signing.env`, ambos 0600). La build la toma de las variables `CHINCHILLACAM_KEYSTORE`, `CHINCHILLACAM_KEYSTORE_PASSWORD`, `CHINCHILLACAM_KEY_ALIAS` y `CHINCHILLACAM_KEY_PASSWORD`. El usuario debe respaldar la clave.
3. **Usuario, 2026-10-07:** primera versión `0.1.0`, marcada como preliminar (sin validación física).
4. **Producto (docs/uso.md §2.3):** presupuesto cero; en macOS sin Developer ID ni notarización: firma ad-hoc y advertencia de Gatekeeper documentada.
5. **Orquestador:** el `namespace`/paquete Kotlin (`dev.chinchillacam.usbprobe`) y la identidad AOA no cambian (no son públicos); Windows (T30) queda para el final con M9.

### 4. Contrato

- `desktop/app/Cargo.toml` versión `0.1.0`; la ventana muestra la versión ("ChinchillaCam 0.1.0 (preliminar)").
- `packaging/macos/Info.plist` (plantilla): `CFBundleIdentifier` `io.github.oyhamburo.chinchillacam.desktop`, `CFBundleName`/`CFBundleDisplayName` "ChinchillaCam", `CFBundleExecutable` `chinchillacam`, `CFBundleShortVersionString` `0.1.0`, `CFBundleVersion` `1`, `LSMinimumSystemVersion` `13.0`, `NSHighResolutionCapable`; sin claves de cámara/micrófono (la app no los usa).
- `scripts/package-macos.sh`: `cargo build --release --offline` (aarch64-apple-darwin), arma el bundle, firma ad-hoc (`codesign --force --sign -`) y verifica (`codesign --verify`), genera `dist/ChinchillaCam-0.1.0-macos-arm64.zip` con `ditto` y `dist/SHA256SUMS-macos.txt`. Nunca usa identidades de firma del llavero. `dist/` ignorado por git.
- Test que comprueba que la versión del `Info.plist` coincide con `Cargo.toml` y que el identificador es el decidido.
- `docs/release.md`: pasos reproducibles, advertencia de Gatekeeper (abrir con clic derecho → Abrir o desde Privacidad y seguridad) y referencia a la guía de OBS.

### 5. Riesgos

- Sin notarización, Gatekeeper bloquea la primera apertura; se documenta el desbloqueo manual.
- El acceso USB desde una app ad-hoc no se validó en hardware (M9).

### 6. Reglas de ejecución

TDD donde aplique (tests de configuración/contrato antes del cambio); para scripts de empaquetado, verificación funcional ejecutándolos de punta a punta. Commits de work unit ≤400 líneas con tests y docs. Push autorizado tras la unidad; sin PR, sin merge y **sin publicar releases en GitHub** (decisión del usuario pendiente para publicar).

### 7. Tareas

Runner app: `export PATH=$HOME/.cargo/bin:$PATH && cd desktop/app && cargo fmt -- --check && cargo test --offline && cargo clippy --offline --all-targets -- -D warnings` (línea de base 50/0).

1. [x] m1 — versión 0.1.0 visible, `Info.plist` + test de coherencia, `scripts/package-macos.sh` y ejecución real (bundle firmado ad-hoc, zip y checksum). RED: `info_plist_matches_cargo_version_and_bundle_id`. ~200 líneas.
   - Evidencia m1: RED por ausencia de `Info.plist`; GREEN 52 pruebas, 0 fallos; fmt y clippy sin errores. Script ejecutado: firma ad-hoc verificada, ZIP arm64 y SHA-256 generados; `plutil -lint` correcto. No se inició la interfaz.
2. [x] m2 — `docs/release.md` y `docs/uso.md` §2.3 actualizados; cierre y push.
   - Evidencia m2: `docs/release.md` (pasos reproducibles, verificación, Gatekeeper y checklist previa a publicar) y `docs/uso.md` §2.3 con el artefacto real. El script ahora borra el bundle y el zip anteriores antes de armar los nuevos (ajuste del orquestador tras la lectura).

### 8. Evidencia

- Commits: `69556f0` (m1) y el commit de m2/cierre.
- Verificación final (en línea, porque el verificador delegado se colgó): `usb-probe` 349/0 y `desktop/app` 52/0, fmt y clippy limpios; `scripts/package-macos.sh` ejecutado dos veces seguidas desde cero: `Identifier=io.github.oyhamburo.chinchillacam.desktop`, `Signature=adhoc`, `TeamIdentifier=not set`, `plutil -lint` OK, checksum verificado (`b71ed4be6c17e60066250e9458e264f6d1debb58efbe8b21fbb9c7b2d1c08052  ChinchillaCam-0.1.0-macos-arm64.zip`), y `dist/` no aparece en `git status`.
- Sin ejecutar: abrir la app empaquetada en otra Mac (Gatekeeper) y con hardware (M9).
