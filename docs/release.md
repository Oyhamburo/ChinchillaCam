# Release de ChinchillaCam (Android)

Guía para quien mantiene el proyecto. Describe cómo producir el APK de Android de forma reproducible. El paquete de macOS se produce con `scripts/package-macos.sh` en la rama del desktop, y Windows todavía no tiene paquete (T30).

## Requisitos

- Android SDK en `~/Library/Android/sdk` (o `ANDROID_HOME`) con `build-tools` que incluyan `apksigner` y `aapt2`.
- Gradle 8.0.1 de la distribución del wrapper (o `GRADLE` apuntando a un binario equivalente) y dependencias ya en caché, porque la build usa `--offline`.
- La clave de release en `~/.chinchillacam/release.jks` y sus contraseñas en `~/.chinchillacam/release-signing.env` (ambos con permisos 0600).

## Producir el APK

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

## Verificar

```sh
(cd dist && shasum -a 256 -c SHA256SUMS-android.txt)
"$HOME/Library/Android/sdk/build-tools/<versión>/apksigner" verify --print-certs dist/ChinchillaCam-0.1.0-android.apk
```

El certificado de release esperado tiene la huella SHA-256 `b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c`.

## Respaldo de la clave (obligatorio)

Android sólo acepta una actualización si está firmada con la misma clave que la versión instalada. Si se pierden `release.jks` o sus contraseñas, no se pueden publicar más actualizaciones: los usuarios tendrían que desinstalar la app y volver a vincular sus computadoras.

- Guardá una copia de `~/.chinchillacam/release.jks` y `~/.chinchillacam/release-signing.env` en un gestor de contraseñas o en un respaldo cifrado fuera de la computadora.
- Nunca los subas al repositorio ni los compartas.

## Antes de publicar

- [ ] Suite completa en verde (`testDebugUnitTest`, `assembleDebug`, `lintDebug`).
- [ ] `versionCode` y `versionName` actualizados en `android/usb-probe/build.gradle.kts` y en la documentación.
- [ ] `scripts/release-android.sh` ejecutado con `EXPECTED_CERT_SHA256` y checksums verificados.
- [ ] Release marcado como **preliminar** mientras las pruebas físicas (M9) estén pendientes.
- [ ] Publicar en GitHub Releases es una decisión manual. Ejemplo (no se ejecuta automáticamente):

```sh
gh release create v0.1.0 --prerelease --title "ChinchillaCam 0.1.0 (preliminar)" \
  dist/ChinchillaCam-0.1.0-android.apk dist/SHA256SUMS-android.txt
```
