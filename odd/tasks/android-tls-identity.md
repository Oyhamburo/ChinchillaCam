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

## Next TLS verifier slice

- Introduce a real `PairingProofVerifier` backed by JSSE TLS sockets.
- Pin the desktop SPKI during handshake with `PinnedDesktopTlsTrustManager` and bind the pairing challenge over the TLS channel.
- Do not accept forged `Verified` results as a production verifier and do not enable LAN transport in this slice.
