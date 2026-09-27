# ODD: Desktop receiver VIDEO_CHUNK_V2 consumption

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base before source edits: `3ee9aee docs(desktop): record video kind wire review closure`
- Prior dependency: T11e Rust SessionFrame `VIDEO_CHUNK_V2` type id `8` approved and acknowledged in native review `review-5e0382ad18aea6d4`.
- Slice: T20b desktop receiver consumes validated v2 frame kind without changing legacy v1 behavior.

## Goal

Teach the desktop receiver to accept `SessionFramePayload::VideoChunkV2` and map its validated wire kind directly to `EncodedVideoFrameKind`, while preserving the legacy `VIDEO_CHUNK` type id `5` injected-classifier path.

## Scope

- Accept `VideoChunkV2` type id `8` in `receive_desktop_video_frame`.
- Map v2 `VideoFrameKind::Key` to `EncodedVideoFrameKind::Key`, `CodecConfig` to `CodecConfig`, and `Delta` to `Delta`.
- Do not call the legacy `VideoFrameKindClassifier` for v2 payloads.
- Preserve legacy type id `5` behavior: still requires injected classifier result, including unknown-kind error path.
- Preserve malformed frame rejection and queue backpressure behavior.
- Use existing fake `BulkFrame` stream id in tests.
- No real USB, decoder, LAN, hardware, crypto, or virtual camera claims.

## Authorized edit surfaces

- `desktop/usb-probe/src/desktop_receiver.rs`
- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `odd/tasks/desktop-receiver-v2.md`

## Verification plan

- RED tests before implementation for:
  - v2 key/config/delta accepted and enqueued with direct mapped kind
  - v2 path does not call the legacy classifier
  - legacy type 5 behavior remains injected-classifier based
  - malformed/unknown frame behavior remains closed
  - queue-full behavior remains closed for v2
- GREEN focused desktop receiver tests.
- Full `desktop/usb-probe` cargo test.
- `cargo fmt --check`.
- `git diff --check`.
- Independent `gentle-ai-verify`.
- Keep diff under 400 changed lines; if not, stop and split.

## Evidence

- ODD/Engram mirror created before source edits.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver -- --nocapture` failed with v2 still rejected as `UnexpectedSessionPayload { type_id: 8 }` and v2 queue-full/direct-kind tests failing before implementation.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml desktop_receiver` passed with 10 desktop receiver tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA, 10 desktop receiver, 6 encoded video sink, 16 session frame, 7 trusted-phone store tests, and doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Diff budget: code diff is 185 lines and final ODD doc is 55 lines = 240 total, within <=400.
- Independent verification: `gentle-ai-verify` PASS; confirmed v2 direct kind mapping, no v2 classifier call, legacy type 5 classifier path preserved, v2 unknown kind malformed, queue-full behavior, and no real USB/decode/LAN/hardware/crypto claims.
