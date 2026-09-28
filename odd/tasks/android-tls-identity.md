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

## Next TLS verifier slice

- Introduce a real `PairingProofVerifier` backed by JSSE TLS.
- Pin the desktop SPKI during handshake and bind the pairing challenge over the TLS channel.
- Do not accept forged `Verified` results as a production verifier and do not enable LAN transport in this slice.
