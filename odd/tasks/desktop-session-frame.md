# ODD: Desktop SessionFrame v1 Slice A

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Android reference source: immutable sibling commit `f8504a91c27efb1be0004470b1ee30334d5ddc8b` in `/Users/jele/Desktop/codes/ChinchillaCam-usb-bulk-tdd`.
- Current scope: reviewable Slice A only.
- Autonomous implementation authorized; local commit allowed after verification. Native review must wait for review-slot coordination.

## Goal

Add a small pure Rust `SessionFrame v1` companion for desktop tests that matches the Android byte contract only for the Slice A payloads needed now. Do not claim or implement transport, networking, pairing, crypto, local authority, video decoding, or virtual camera behavior.

## Slice A scope

- `SessionFrame` envelope with `CCSF` magic, version 1, payload type, sequence, session id, and payload length.
- Big-endian encoding/decoding for the inner `SessionFrame`.
- `HandshakeAccept` encode/decode against Android golden bytes.
- `VideoChunk` encode/decode roundtrip.
- `CameraControlCommand` decode path only as needed to reject duplicate argument keys.
- Nesting evidence that the existing outer `BulkFrame` remains little-endian while the inner `SessionFrame` is big-endian.
- Rejections for frame too large, truncated header/payload, trailing bytes, unsupported version, unknown type, negative sequence, empty session id, malformed UTF-8, and duplicate camera-control argument keys.

## Deferred slices

- `HandshakeHello`
- `HandshakeReject`
- `StreamMetadata`
- `MetricsSnapshot`
- Broader payload validation beyond Slice A tests
- Any transport/network/crypto/auth/decoder/virtual-camera work

## Authorized edit surfaces for this shrink pass

- `desktop/usb-probe/src/session_frame.rs`
- `desktop/usb-probe/tests/session_frame_test.rs`
- `odd/tasks/desktop-session-frame.md`

## Slice A1 hardening scope

- Close advisory `R3-encode-decode-size-asymmetry` without rewriting `f1de9db` or adding deferred payload types.
- Ensure `SessionFrameCodec::encode` rejects frames whose encoded size would exceed the same negotiated/default 1 MiB frame cap used by decode.
- Add RED test for an oversized `CameraControlCommand` payload where encode returns a typed `FrameTooLarge` error before avoidable final frame copy/allocation.
- Preserve normal roundtrip under the cap and existing Kotlin-compatible golden bytes.
- Coordinate later with Android Kotlin encoder for the same cap; this Rust slice records the expected cap but does not edit Android.
- No transport/network/crypto/auth/decoder/virtual-camera work.

## Evidence

- Android source read: `SessionFrame.kt` and `SessionFrameTest.kt` from sibling commit `f8504a91c27efb1be0004470b1ee30334d5ddc8b`.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` failed on unresolved `SessionFrame` imports before implementation.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 7 session frame tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 7 session frame tests + doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Independent verification: `gentle-ai-verify` PASS; confirmed Android golden bytes, big-endian inner frame, little-endian BulkFrame nesting, UTF-8/duplicate-key rejection, and no transport/network/crypto/auth/decoder/virtual-camera work.
- Commit local: `f1de9db feat(desktop): add session frame slice`.
- Native review: `review-e20ab89368bc203a` for committed range `caf25f9..f1de9db` approved and acknowledged; authority burned. Target `sha256:e149c5f5b39d2ebb8c1bb75d4fa4a455270829feea73a537b760877fd51c4646`; consumed revision `sha256:0e77c803470eb7fa7c087a42a2ab642ad040d4b2728f961647d59239e16343a2`.
- Advisory informativo no bloqueante: `R3-encode-decode-size-asymmetry`; tratar como trabajo futuro separado, no como razón para reabrir este candidato.
- Slice A1 RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` failed on missing `SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE` and `SessionFrameEncodeError::FrameTooLarge`.
- Slice A1 GREEN: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 8 session frame tests.
- Slice A1 full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 8 session frame tests + doctests.
- Slice A1 format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- Slice A1 `git diff --check`: passed with no output.
- Slice A1 independent verification: `gentle-ai-verify` PASS; confirmed pre-encode frame-size check before `encode_payload`/final allocation, existing golden/roundtrip behavior, and no deferred payload or transport/network/crypto/auth/decoder/virtual-camera work.
- Slice A1 commit local: pending.

## Review workload note

- Review-size exception recorded after local commit: `f1de9db` adds 676 lines, above the ~400 advisory review budget.
- Rationale: the feature was already committed with tests and ODD evidence as one coherent work unit; splitting after the fact would require amend/reset/rebase or an artificial split that separates tests/docs from behavior.
- Decision: do not code-golf, amend, reset, rebase, or fake-split this candidate. Native review should inspect only committed range `caf25f9..f1de9db` with `committedOnly: true`.
- Future payload slices must be genuinely smaller and target <=400 changed lines each: remaining `HandshakeHello`, `HandshakeReject`, `StreamMetadata`, `MetricsSnapshot`, and broader payload validation should not be stacked onto this candidate before review closes.
