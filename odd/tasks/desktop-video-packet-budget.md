# ODD: Desktop receiver USB packet budget

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base before source edits: `5ffb63a docs(desktop): record receiver v2 review closure`
- Prior dependency: T20b desktop receiver v2 consumption approved and acknowledged in native review `review-dc6a180765a603ce`.
- Slice: T11f1 desktop receiver total USB packet budget.

## Goal

Enforce the fake receiver's USB total packet budget before sink push. The current `BulkFrame` payload can carry up to the SessionFrame codec's 1 MiB default, but the USB packet target is `65,536` bytes including the 8-byte `BulkFrame` header, so the inner SessionFrame bytes must be `<= 65,528`.

## Scope

- Reject `BulkFrame` payloads larger than `65,528` bytes before `SessionFrame` decode and before sink push.
- Accept the exact `65,528`-byte boundary.
- Cover both `VIDEO_CHUNK_V2` and legacy `VIDEO_CHUNK` type id `5` behavior.
- Use actual SessionFrame encoding with session id and metadata overhead accounted for in tests.
- Preserve no-truncation behavior: oversize packets fail closed rather than being shortened.
- Use existing codec boundaries where possible; do not edit `session_frame.rs` unless unavoidable after read-only mapping and explicit approval.
- No real USB, hardware, LAN, decoder, virtual camera, crypto, or cross-branch integration claims.

## Authorized edit surfaces

- `desktop/usb-probe/src/desktop_receiver.rs`
- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `odd/tasks/desktop-video-packet-budget.md`

## Verification plan

- RED tests before implementation for:
  - v2 session frame payload length `65,528` accepted and enqueued
  - v2 session frame payload length `65,529` rejected before sink push
  - legacy type 5 session frame payload length `65,528` accepted
  - legacy type 5 session frame payload length `65,529` rejected before sink push
- GREEN focused desktop receiver tests.
- Full `desktop/usb-probe` cargo test.
- `cargo fmt --check`.
- `git diff --check`.
- Independent `gentle-ai-verify`.
- Keep diff under 400 changed lines; if not, stop and split.

## Evidence

- ODD/Engram mirror created before source edits.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver -- --nocapture` failed because a `65,529` byte SessionFrame payload was accepted instead of `MalformedSessionFrame(FrameTooLarge { actual_size: 65529, max_size: 65528 })`.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver` passed with 12 desktop receiver tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA, 12 desktop receiver, 6 encoded video sink, 16 session frame, 7 trusted-phone store tests, and doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Diff budget: code/test diff is 82 lines and final ODD doc is 54 lines = 136 total, within <=400.
- Independent verification: `gentle-ai-verify` PASS; confirmed `65,528` exact boundary accepted for legacy and v2, `65,529` rejected before sink push via `decode_with_limit`, no truncation, and no real USB/hardware/LAN/decoder/cross-branch claims.
