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

## Next TLS verifier slice

- Introduce a real `PairingProofVerifier` backed by JSSE TLS sockets over local loopback tests.
- Pin the desktop SPKI during handshake with `PinnedDesktopTlsTrustManager` and bind the pairing challenge over the frozen `CCPB`/`CCP1` protocol.
- Do not accept forged `Verified` results as a production verifier and do not enable LAN transport in this slice.
