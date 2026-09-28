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

## Estado M3d1 — identidad efímera

Este corte solo genera una identidad TLS efímera con clave P-256 usando CSPRNG del sistema vía `rcgen`/`ring`, certificado self-signed DER y SPKI DER exacta para `trustMaterial` del QR. No persiste claves todavía y no declara readiness de emparejamiento persistente.

La persistencia segura queda para M3d2: directorio/archivo restrictivos en macOS/Unix, rechazo de symlink/corrupción/estado parcial y gate explícito de Windows sin claim de soporte.

## Estado M3d3 — protocolo CCP1

El wire CCPB/CCP1 queda congelado con Android antes de implementar TLS: este corte solo agrega codec binario CCP1 para request/response de prueba de posesión. No abre listener, no usa rustls todavía y no inventa otro protocolo.

- Frame: magic `CCP1` (`43435031`), versión `1`, tipo request `1` o response `2`, `payloadLen` u32 BE `<=1024`.
- TLVs canónicos ascendentes. Request: `0x01 desktopId`, `0x02 qrNonce`, `0x03 challengeNonce`, `0x04 sessionId`.
- Response: `0x00 status` de un byte primero, luego eco exacto `0x01..0x04`.
- Goldens acordados: request `4343503101010000001f01000470632d3102000201020300041011121304000973657373696f6e2d31`; response `434350310102000000230000010001000470632d3102000201020300041011121304000973657373696f6e2d31`.

## Estado M3d4 — emisor QR local

Este corte emite QR de emparejamiento con nonce de 32 bytes desde CSPRNG del sistema, expiración acotada 60–120s, `trustMaterial` igual a la SPKI DER persistida del escritorio y control de nonces pendientes en memoria. No persiste nonces, no abre listener, no usa rustls todavía y no valida tráfico de red.

## Estado M3d5a — endpoint CCPB

Este corte agrega solo la dependencia `rustls` prevista y el codec CCPB para anunciar un endpoint loopback futuro. CCPB no abre sockets ni declara éxito criptográfico: host fijo `127.0.0.1`, puerto `1..65535`, timeout `250..5000ms`, TLVs ascendentes y tamaño total `<=256`.

## Estado M3d5b — handshake TLS loopback

Este corte agrega solo un servidor rustls de una conexión en `127.0.0.1:0` que expone el endpoint CCPB real y presenta el certificado persistido. La prueba verifica que el certificado TLS observado contiene la SPKI del QR. Todavía no hay frame OK, validación CCP1 ni concesión de confianza.

## Evidencia esperada

- `cargo fmt --check`
- `cargo test`
- `git diff --stat` / revisión de diff por slice
- Reporte READY con SHA, checks, fixture SPKI y QR wire exacto
