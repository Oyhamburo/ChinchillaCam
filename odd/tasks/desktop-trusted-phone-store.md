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


## T13c race-fix follow-up scope

Grant withheld after independent scout found high-severity stale read-modify-write behavior: concurrent `trust()` and `revoke()` instances can race, and the deterministic shared temp path can let a stale trust overwrite a completed revoke.

Fix scope in a separate local commit, without amending `93ab9d0`:

- Add a deterministic RED two-writer stale-revocation test using an injectable write coordinator.
- Cover the full read + mutate + write critical section with a per-store-path lock.
- Use a unique temp file per writer instead of one shared `.tmp` path.
- Fail closed on lock/write errors.
- Verify revocation remains durable across concurrent store instances.

Deferred gates before real app use remain: symlink/path ownership checks, owner-only permissions, fsync, and whole-file size cap. No LAN, secure-pairing, OS keychain, QR, transport, or hardware claim is added here.

## T13c race-fix evidence

- RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone_store_prevents_stale` failed on unresolved `TrustedPhoneStoreWriteCoordinator` import and missing injectable coordinator seam.
- GREEN focused: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone` passed with 5 trusted-phone tests.
- GREEN full crate: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 12 session frame + 5 trusted-phone tests + doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Race-fix diff before commit: independently verified 193 insertions / 5 deletions = 198 changed lines.
- Independent verification: `gentle-ai-verify` PASS; confirmed deterministic stale-revocation coverage, full read+mutate+write lock, unique temp files, fail-closed lock/write errors, no expanded claims.
- Commit local: this commit, `fix(desktop): serialize trusted phone store writes`.


## T13c fresh-install lock correction scope

Second withheld-grant readback found a fresh-install blocker: lock acquisition attempted to create `.lock` in the store parent before the parent directory exists. First-run trust in a new per-user config directory must initialize successfully.

Correction scope in a separate local commit, without amending prior T13c commits:

- Add RED test for `FileTrustedPhoneStore::new(tempdir/new/subdir/store).trust(identity)` succeeding when parents are absent.
- Create/validate the parent directory before lock acquisition, while keeping later owner-only permissions/symlink/path-hardening as deferred gates before product use.
- Preserve fail-closed lock/write errors and the stale-revocation race fix.
- No secure-pairing, QR, transport, TLS, OS keychain, or hardware claim.

## T13c fresh-install correction evidence

- RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone_store_initializes_missing_parent_directory` failed with `Io("No such file or directory (os error 2)")` during first `trust()`.
- GREEN focused: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml trusted_phone` passed with 6 trusted-phone tests.
- GREEN full crate: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 12 session frame + 6 trusted-phone tests + doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Correction diff before commit: independently verified 56 insertions / 7 deletions = 63 changed lines.
- Independent verification: `gentle-ai-verify` PASS; confirmed first trust initializes absent parents, parent is created before lock acquisition, lock/write errors still fail closed, stale-revocation coverage remains, and no expanded claims.
- Commit local: this commit, `fix(desktop): initialize trust store directory before locking`.
