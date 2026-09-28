# Desktop TLS pairing (M3)

## Alcance

Implementar el camino real de escritorio para emparejamiento local sin abrir un listener LAN externo:

1. Productor Rust de QR v1 byte-compatible con el contrato Android canónico.
2. Identidad persistente de escritorio con clave EC P-256 y certificado local apto para TLS.
3. Prueba local loopback con rustls que demuestre posesión de la SPKI anunciada en el QR mediante desafío.

## Restricciones

- TDD estricto: cada slice empieza con prueba RED y termina GREEN.
- Commits locales Conventional Commits, <=400 líneas de diff por commit incluyendo Cargo.lock/tests/docs.
- No push, merge, cherry-pick, integración de ramas, ni native START.
- No trabajar en decoder ni VideoToolbox.
- No reclamar soporte Windows hasta verificarlo; Windows queda platform-gated/fail-closed.
- No listener externo LAN antes de autenticación.
- `.codegraph/` preexistente sin dueño: no stage/delete/ignore.

## Plan de slices

### Slice 1 — QR v1 producer

- Añadir dependencias mínimas para SHA-256/base64url si Cargo.lock no las contiene.
- Generar bytes canónicos deterministas con campos `desktopId`, `name`, `expiry`, `nonce`, `spkiDerP256` y checksum.
- Exponer wire exacto y fixture SPKI para coordinar con Android.

### Slice 2 — identidad TLS persistente

- Generar/cargar clave EC P-256 persistente y certificado self-signed local.
- Fallar cerrado si el archivo de clave no tiene permisos restrictivos en macOS/Unix.
- Gating de Windows sin claim de soporte.

### Slice 3 — loopback proof-of-possession

- Handshake rustls en loopback únicamente.
- Validar que la SPKI del certificado coincide con la SPKI del QR.
- Intercambiar challenge/response para demostrar posesión.

## Evidencia esperada

- `cargo fmt --check`
- `cargo test`
- `git diff --stat` / revisión de diff por slice
- Reporte READY con SHA, checks, fixture SPKI y QR wire exacto
