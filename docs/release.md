# Release de ChinchillaCam (macOS)

Guía para quien mantiene el proyecto. Describe cómo producir el paquete de macOS de forma reproducible. El APK de Android se produce con `scripts/release-android.sh` en la rama de Android, y Windows todavía no tiene paquete (T30).

## Requisitos

- Mac con Apple Silicon (arm64) y macOS 13 o posterior.
- Rust instalado con `rustup` (`~/.cargo/bin` en el `PATH`) y las dependencias ya descargadas (`cargo fetch` en `desktop/app` si hace falta), porque el script compila con `--offline`.
- Herramientas de línea de comandos de Xcode (`codesign`, `ditto`, `plutil`).

## Producir el paquete

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

## Verificar

```sh
codesign -dv dist/ChinchillaCam.app        # Identifier=io.github.oyhamburo.chinchillacam.desktop, Signature=adhoc
(cd dist && shasum -a 256 -c SHA256SUMS-macos.txt)
```

## Firma y Gatekeeper

El presupuesto de publicación es cero: no hay Developer ID ni notarización. macOS bloquea la primera apertura de una app descargada sin notarizar. Para abrirla, el usuario hace **clic derecho → Abrir** sobre `ChinchillaCam.app` y confirma, o va a **Configuración del Sistema → Privacidad y seguridad** y elige **Abrir igualmente**. Esto se explica en `docs/uso.md` §2.3.

## Antes de publicar

- [ ] Tests en verde en `desktop/usb-probe` y `desktop/app` (fmt, test, clippy).
- [ ] Versión actualizada en `desktop/app/Cargo.toml`, `packaging/macos/Info.plist` (`CFBundleShortVersionString` y `CFBundleVersion`) y en la documentación.
- [ ] `scripts/package-macos.sh` ejecutado en limpio y checksums verificados.
- [ ] Release marcado como **preliminar** mientras las pruebas físicas (M9) estén pendientes.
- [ ] Publicar en GitHub Releases es una decisión manual. Ejemplo (no se ejecuta automáticamente):

```sh
gh release create v0.1.0 --prerelease --title "ChinchillaCam 0.1.0 (preliminar)" \
  dist/ChinchillaCam-0.1.0-macos-arm64.zip dist/SHA256SUMS-macos.txt
```
