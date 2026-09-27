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

## Evidence

- Android source read: `SessionFrame.kt` and `SessionFrameTest.kt` from sibling commit `f8504a91c27efb1be0004470b1ee30334d5ddc8b`.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` failed on unresolved `SessionFrame` imports before implementation.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 7 session frame tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA + 6 encoded video sink + 7 session frame tests + doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Independent verification: `gentle-ai-verify` PASS; confirmed Android golden bytes, big-endian inner frame, little-endian BulkFrame nesting, UTF-8/duplicate-key rejection, and no transport/network/crypto/auth/decoder/virtual-camera work.
- Commit local: pending.
- Native review: pending; do not start without review-slot coordination.
