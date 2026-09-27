# ODD: Desktop trusted-phone store seam T13c

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base before this slice: `595202fa7ff2f314b4fd91a65751e8d9cc3db5ca`.
- Scope: one bounded Rust slice for desktop-side persistent trusted-phone identity storage and revocation seam.

## Goal

Add a small desktop trust-store boundary that can persist trusted phone identities and local revocation state for later QR pairing/session work. This slice stores local metadata only; it does not implement pairing, transport, authentication, key exchange, TLS, proof-of-possession, network listeners, or secure storage.

## Slice T13c scope

- Model a trusted phone identity with stable phone id, user-visible label, and opaque public-key bytes/fingerprint input.
- Reject empty/oversized ids, labels, and key material before persistence.
- Persist and load records through a file-backed store seam with atomic-ish temp-file replacement where supported by `std::fs::rename`.
- Support local revocation by id and make revoked identities unavailable to normal trust lookup.
- Tests must use temporary test files/fakes only and must not touch real user config paths.
- Keep diff under the ~400 changed-line review budget.

## Non-goals

- No private-key plaintext storage.
- No crypto verification or secure-pairing claims.
- No OS keychain/DPAPI/Secure Enclave integration.
- No QR payload, network, USB transport, TLS, decoder, virtual camera, or hardware claims.

## Evidence

- RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone` failed on unresolved `FileTrustedPhoneStore`, `TrustedPhoneIdentity`, and `TrustedPhoneStoreError` imports.
- GREEN focused: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone` passed with 4 trusted-phone tests.
- GREEN full crate: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 12 session frame + 4 trusted-phone tests + doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Independent verification: `gentle-ai-verify` PASS; confirmed scoped seam only, temp-file tests, no private-key storage, no crypto/secure-pairing/transport/hardware claims, and 388 changed lines within <=400 budget.
- Commit local: this commit, `feat(desktop): add trusted phone store seam`.
