# Android TLS identity validation

## M3c1 — QR trust material

- Validate `trustMaterial` as canonical DER `SubjectPublicKeyInfo` for EC P-256 using JCA APIs available on minSdk 23.
- Derive pairing fingerprints from the validated SPKI bytes with SHA-256.
- Reject typed invalid inputs before pairing proof verification: RSA keys, P-384 keys, X.509 certificates, random bytes, oversized material, and alternate encodings/aliases.
- Keep QR codec wire format stable; validation belongs in Android trust material handling and coordinator admission.

## M3c2 — coordinator gate

- `PendingPairingCoordinator.start` must validate trust material before building the proof challenge.
- Arbitrary QR trust material must not reach `PairingProofVerifier`.
- `confirm` must never persist a fingerprint derived from invalid material.
- Fixtures should use real P-256 SPKI bytes.

## M3c3 — JSSE pinned desktop trust manager

- Add a fail-closed Android `X509TrustManager` that accepts only a non-empty desktop leaf certificate whose DER SubjectPublicKeyInfo exactly matches validated P-256 QR trust material.
- Validate the leaf certificate time bounds with JSSE/JCA certificate APIs and reject expired, invalid, empty, and SPKI-mismatched chains without permissive trust managers or hostname bypasses.
- Keep this slice limited to TLS identity pinning; TLS sockets, pairing proof channel binding, decoder paths, USB fake transport, LAN transport, and hardware remain out of scope.
- Use real local X.509 fixtures generated outside production code; do not depend on `sun.security` internals.

## M3c4a1 — códec de endpoint `CCPB`

- Congelar y probar el descriptor binario `proofBytes` antes de abrir sockets reales.
- Codificar y parsear `CCPB` con límite de 256 bytes: host literal solo `127.0.0.1` o `::1`, puerto `1..65535` y timeout `250..5000` ms.
- Rechazar fail-closed TLVs desconocidos, duplicados, faltantes, fuera de orden, bytes extra, UTF-8 inválido y largos inválidos.
- No abrir sockets, no LAN real y no inventar otro protocolo en este slice.

## M3c4a2 — códec de frames `CCP1`

- Codificar y parsear requests y responses `CCP1` con header de 10 bytes: magic, versión, tipo y largo `u32` big-endian.
- Limitar payload a 1024 bytes y campos: `desktopId` ASCII con regex `[A-Za-z0-9._-]+` hasta 64 bytes, `sessionId` UTF-8 no vacío hasta 64 bytes, y nonces no vacíos hasta 64 bytes.
- Exigir TLVs en orden ascendente estricto; response usa status TLV `0x00` primero y luego `0x01..0x04`.
- Rechazar fail-closed TLVs desconocidos, duplicados, faltantes, fuera de orden, bytes extra, UTF-8 inválido y largos inválidos.
- Mantener los goldens de nonce corto solo como fixture de códec; el verificador real posterior exigirá QR nonce >=16 bytes y challenge nonce >=32 bytes CSPRNG.
- No abrir sockets, no LAN real y no inventar otro protocolo en este slice.

## M3c4b — cliente real de prueba TLS en loopback

- Enlazar en `PairingProofChallenge` el SPKI DER canónico validado desde el QR; el verificador no debe reconstruir identidad desde el fingerprint.
- Abrir un cliente `SSLSocket` solo contra endpoints `CCPB` de loopback y exigir TLS 1.2+ con `PinnedDesktopTlsTrustManager`.
- Enviar/recibir frames `CCP1` acotados, cerrar recursos y rechazar timeouts, IO/TLS, status distinto de cero, ecos que no coinciden, QR/challenge expirados y nonces bajo mínimos del verificador (QR >=16 bytes, challenge >=32 bytes).
- Usar tests JVM con servidor TLS local real y certificado P-256 de fixture; no conectar LAN externa, USB, decoder ni wiring de producción en este slice.
