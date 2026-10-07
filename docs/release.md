# Release de ChinchillaCam

Guía para quien mantiene el proyecto: cómo producir los artefactos de cada plataforma de forma reproducible. Windows todavía no tiene paquete (T30). Publicar en GitHub Releases es siempre una decisión manual.

## Android

El APK se produce con `scripts/release-android.sh`.

### Requisitos

- Android SDK en `~/Library/Android/sdk` (o `ANDROID_HOME`) con `build-tools` que incluyan `apksigner` y `aapt2`.
- Gradle 8.0.1 de la distribución del wrapper (o `GRADLE` apuntando a un binario equivalente) y dependencias ya en caché, porque la build usa `--offline`.
- La clave de release en `~/.chinchillacam/release.jks` y sus contraseñas en `~/.chinchillacam/release-signing.env` (ambos con permisos 0600).

### Producir el APK

```sh
set -a; . ~/.chinchillacam/release-signing.env; set +a
EXPECTED_CERT_SHA256=b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c \
  scripts/release-android.sh
```

El script:

1. falla con un mensaje claro si falta alguna variable `CHINCHILLACAM_*`;
2. compila `assembleRelease` (firmado con la clave de release; nunca con la clave debug);
3. verifica la firma con `apksigner` y el paquete y la versión con `aapt2`;
4. si se define `EXPECTED_CERT_SHA256`, comprueba que el certificado coincida;
5. deja `dist/ChinchillaCam-<versión>-android.apk` y `dist/SHA256SUMS-android.txt`.

`dist/` está ignorado por git. El script no imprime contraseñas.

### Verificar

```sh
(cd dist && shasum -a 256 -c SHA256SUMS-android.txt)
"$HOME/Library/Android/sdk/build-tools/<versión>/apksigner" verify --print-certs dist/ChinchillaCam-0.1.0-android.apk
```

El certificado de release esperado tiene la huella SHA-256 `b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c`.

### Respaldo de la clave (obligatorio)

Android sólo acepta una actualización si está firmada con la misma clave que la versión instalada. Si se pierden `release.jks` o sus contraseñas, no se pueden publicar más actualizaciones: los usuarios tendrían que desinstalar la app y volver a vincular sus computadoras.

- Guardá una copia de `~/.chinchillacam/release.jks` y `~/.chinchillacam/release-signing.env` en un gestor de contraseñas o en un respaldo cifrado fuera de la computadora.
- Nunca los subas al repositorio ni los compartas.

### Antes de publicar

- [ ] Suite completa en verde (`testDebugUnitTest`, `assembleDebug`, `lintDebug`).
- [ ] `versionCode` y `versionName` actualizados en `android/usb-probe/build.gradle.kts` y en la documentación.
- [ ] `scripts/release-android.sh` ejecutado con `EXPECTED_CERT_SHA256` y checksums verificados.
- [ ] Release marcado como **preliminar** mientras las pruebas físicas (M9) estén pendientes.
- [ ] Publicar en GitHub Releases es una decisión manual. Ejemplo (no se ejecuta automáticamente):

```sh
gh release create v0.1.0 --prerelease --title "ChinchillaCam 0.1.0 (preliminar)" \
  dist/ChinchillaCam-0.1.0-android.apk dist/SHA256SUMS-android.txt
```

## macOS

El paquete para Apple Silicon se produce con `scripts/package-macos.sh`.

### Requisitos

- Mac con Apple Silicon (arm64) y macOS 13 o posterior.
- Rust instalado con `rustup` (`~/.cargo/bin` en el `PATH`) y las dependencias ya descargadas (`cargo fetch` en `desktop/app` si hace falta), porque el script compila con `--offline`.
- Herramientas de línea de comandos de Xcode (`codesign`, `ditto`, `plutil`).

### Producir el paquete

```sh
export PATH="$HOME/.cargo/bin:$PATH"
scripts/package-macos.sh
```

El script:

1. comprueba que la versión de `desktop/app/Cargo.toml` coincide con `packaging/macos/Info.plist`;
2. compila `chinchillacam` en modo release;
3. arma `dist/ChinchillaCam.app` desde cero;
4. lo firma **ad-hoc** (`codesign --sign -`), sin usar identidades del llavero, y verifica la firma;
5. genera `dist/ChinchillaCam-<versión>-macos-arm64.zip` y `dist/SHA256SUMS-macos.txt`.

`dist/` está ignorado por git.

### Verificar

```sh
codesign -dv dist/ChinchillaCam.app        # Identifier=io.github.oyhamburo.chinchillacam.desktop, Signature=adhoc
(cd dist && shasum -a 256 -c SHA256SUMS-macos.txt)
```

### Firma y Gatekeeper

El presupuesto de publicación es cero: no hay Developer ID ni notarización. macOS bloquea la primera apertura de una app descargada sin notarizar. Para abrirla, el usuario hace **clic derecho → Abrir** sobre `ChinchillaCam.app` y confirma, o va a **Configuración del Sistema → Privacidad y seguridad** y elige **Abrir igualmente**. Esto se explica en `docs/uso.md` §2.3.

### Antes de publicar

- [ ] Tests en verde en `desktop/usb-probe` y `desktop/app` (fmt, test, clippy).
- [ ] Versión actualizada en `desktop/app/Cargo.toml`, `packaging/macos/Info.plist` (`CFBundleShortVersionString` y `CFBundleVersion`) y en la documentación.
- [ ] `scripts/package-macos.sh` ejecutado en limpio y checksums verificados.
- [ ] Release marcado como **preliminar** mientras las pruebas físicas (M9) estén pendientes.
- [ ] Publicar en GitHub Releases es una decisión manual. Ejemplo (no se ejecuta automáticamente):

```sh
gh release create v0.1.0 --prerelease --title "ChinchillaCam 0.1.0 (preliminar)" \
  dist/ChinchillaCam-0.1.0-macos-arm64.zip dist/SHA256SUMS-macos.txt
```
