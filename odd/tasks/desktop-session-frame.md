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

## Slice B1 handshake payload scope

- Add `HandshakeHello` and `HandshakeReject` payload encode/decode only.
- Include exact Android Kotlin v1 golden fixtures for both payloads, derived from sibling `SessionFrame.kt` at commit `f8504a91c27efb1be0004470b1ee30334d5ddc8b`.
- Preserve existing Slice A/A1 behavior and 1 MiB encode/decode cap.
- Keep the diff under the ~400 changed-line review budget.
- No `StreamMetadata`, `MetricsSnapshot`, transport/network/crypto/auth/decoder/virtual-camera work.

## Slice B2 metadata/metrics payload scope

- Add `StreamMetadata` and `MetricsSnapshot` payload encode/decode only.
- Include exact Android Kotlin v1 golden fixtures for both payloads, derived from sibling `SessionFrame.kt` at commit `f8504a91c27efb1be0004470b1ee30334d5ddc8b`.
- Preserve existing Slice A/A1/B1 behavior and 1 MiB encode/decode cap.
- Keep diff under the ~400 changed-line review budget.
- If naturally touching parser allocation around counted fields, prefer lazy allocation to address advisory `R3-untrusted-capability-preallocation`; otherwise leave it as a separate finite hardening task before externally reachable LAN.
- No transport/network/crypto/auth/decoder/virtual-camera work.

## Deferred slices

- Broader payload validation beyond current slices
- Persistent trusted-phone identity store/revocation planning after payload slices
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
- Slice A1 commit: `460b65c fix(desktop): cap session frame encode size`.
- Slice A1 native review: `review-1eda5d845fc64a60` approved and acknowledged; authority burned. Advisory informativo no bloqueante: `R3-generic-field-error`; parser diagnostic improvement is future work only if it materially helps debugging.
- Slice A1 RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` failed on missing `SessionFrameCodec::DEFAULT_MAX_FRAME_SIZE` and `SessionFrameEncodeError::FrameTooLarge`.
- Slice A1 GREEN: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 8 session frame tests.
- Slice A1 full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 8 session frame tests + doctests.
- Slice A1 format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- Slice A1 `git diff --check`: passed with no output.
- Slice A1 independent verification: `gentle-ai-verify` PASS; confirmed pre-encode frame-size check before `encode_payload`/final allocation, existing golden/roundtrip behavior, and no deferred payload or transport/network/crypto/auth/decoder/virtual-camera work.
- Slice A1 commit local: `460b65c fix(desktop): cap session frame encode size`.
- Slice A1 native review: `review-1eda5d845fc64a60` approved and acknowledged; authority burned.
- Slice B1 RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame_encodes_android_golden_handshake` failed on missing `SessionFramePayload::HandshakeHello` and `SessionFramePayload::HandshakeReject` variants.
- Slice B1 GREEN focused: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 10 session frame tests.
- Slice B1 GREEN full crate: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 10 session frame tests + doctests.
- Slice B1 format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- Slice B1 `git diff --check`: passed with no output.
- Slice B1 diff budget: verified independently at 155 insertions / 5 deletions (160 changed lines) across 3 files, within <=400 advisory budget.
- Slice B1 independent verification: initial `gentle-ai-verify` functionally PASSed focused/full tests and scope checks but returned FAIL only because diff-size command was not authorized; follow-up `gentle-ai-verify` PASSed the diff budget with `git diff --numstat`/`--stat`.
- Slice B1 commit local: `bd2a031 feat(desktop): add session handshake payloads`.
- Slice B1 native review: `review-a00478532891dc07` approved and acknowledged; authority burned. Advisory informativo no bloqueante: `R3-untrusted-capability-preallocation`; avoid preallocating from attacker-controlled count before externally reachable LAN.
- Slice B2 RED: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame_encodes_android_golden` failed on missing `SessionFramePayload::StreamMetadata` and `SessionFramePayload::MetricsSnapshot` variants.
- Slice B2 GREEN focused: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 12 session frame tests.
- Slice B2 GREEN full crate: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 12 session frame tests + doctests.
- Slice B2 format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- Slice B2 `git diff --check`: passed with no output.
- Slice B2 diff budget: independently verified 60 + 62 + 16 = 138 changed lines across 3 files, within <=400 advisory budget.
- Slice B2 independent verification: `gentle-ai-verify` PASS.
- Slice B2 commit local: `66598ac feat(desktop): add session metadata metrics payloads`.

## Review workload note

- Review-size exception recorded after local commit: `f1de9db` adds 676 lines, above the ~400 advisory review budget.
- Rationale: the feature was already committed with tests and ODD evidence as one coherent work unit; splitting after the fact would require amend/reset/rebase or an artificial split that separates tests/docs from behavior.
- Decision: do not code-golf, amend, reset, rebase, or fake-split this candidate. Native review should inspect only committed range `caf25f9..f1de9db` with `committedOnly: true`.
- Future payload slices must be genuinely smaller and target <=400 changed lines each: remaining `HandshakeHello`, `HandshakeReject`, `StreamMetadata`, `MetricsSnapshot`, and broader payload validation should not be stacked onto this candidate before review closes.
