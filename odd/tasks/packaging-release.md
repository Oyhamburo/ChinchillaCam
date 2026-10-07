# Empaquetado y release reproducible (M8: T29 y T32, Android)

## 1. Objetivo

Producir un APK de release firmado, con identificador y versión públicos, y un comando reproducible que lo construye, verifica la firma y genera su checksum, listo para subir a GitHub Releases.

## 2. Problema

- `applicationId` sigue siendo `dev.chinchillacam.usbprobe` y la app se llama "ChinchillaCam USB Probe"; no hay `versionCode`/`versionName`.
- No hay configuración de firma release ni script de release.

## 3. Decisiones

1. **Usuario, 2026-10-07:** identificador público `io.github.oyhamburo.chinchillacam` (Android) e `io.github.oyhamburo.chinchillacam.desktop` (macOS).
2. **Usuario, 2026-10-07:** el APK se firma con una clave release local fuera del repo (`~/.chinchillacam/release.jks`, EC P-256, alias `chinchillacam`; contraseñas en `~/.chinchillacam/release-signing.env`, ambos 0600). La build la toma de las variables `CHINCHILLACAM_KEYSTORE`, `CHINCHILLACAM_KEYSTORE_PASSWORD`, `CHINCHILLACAM_KEY_ALIAS` y `CHINCHILLACAM_KEY_PASSWORD`. El usuario debe respaldar la clave.
3. **Usuario, 2026-10-07:** primera versión `0.1.0`, marcada como preliminar (sin validación física).
4. **Producto (docs/uso.md §2.3):** presupuesto cero; en macOS sin Developer ID ni notarización: firma ad-hoc y advertencia de Gatekeeper documentada.
5. **Orquestador:** el `namespace`/paquete Kotlin (`dev.chinchillacam.usbprobe`) y la identidad AOA no cambian (no son públicos); Windows (T30) queda para el final con M9.

## 4. Contrato

- `applicationId = "io.github.oyhamburo.chinchillacam"`, `versionCode = 1`, `versionName = "0.1.0"`, etiqueta de la app "ChinchillaCam".
- `buildTypes.release` firma con las variables de entorno; sin variables, `assembleRelease` falla con un mensaje claro (nunca firma con la clave debug).
- `scripts/release-android.sh`: construye `assembleRelease` `--offline`, verifica con `apksigner verify --print-certs`, copia a `dist/ChinchillaCam-0.1.0-android.apk` y escribe `dist/SHA256SUMS-android.txt`. `dist/` queda ignorado por git.
- `docs/release.md`: pasos reproducibles, respaldo de la clave y checklist previa a publicar.

## 5. Riesgos

- Perder la clave impide publicar actualizaciones sobre instalaciones existentes.
- Cambiar el `applicationId` hace que una instalación del prototipo quede como otra app (aceptable: no hubo releases).

## 6. Reglas de ejecución

TDD donde aplique (tests de configuración/contrato antes del cambio); para scripts de empaquetado, verificación funcional ejecutándolos de punta a punta. Commits de work unit ≤400 líneas con tests y docs. Push autorizado tras la unidad; sin PR, sin merge y **sin publicar releases en GitHub** (decisión del usuario pendiente para publicar).

## 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 574/2/0).

1. [x] k1 — identificador, versión, etiqueta y firma release por variables de entorno, con test de configuración. RED: `release_build_uses_public_identity_and_version`. ~150 líneas.
   - Evidencia k1: RED de contrato observado; pruebas unitarias, `assembleDebug` y `lintDebug` correctos; `assembleRelease` sin variables falla con el mensaje previsto y con variables produce un APK firmado cuyo certificado SHA-256 es `b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c`; paquete público y versión `0.1.0` verificados con `aapt2`.
2. [ ] k2 — `scripts/release-android.sh` + `docs/release.md` + ejecución real del script (APK firmado y checksum); cierre y push.

## 8. Evidencia
