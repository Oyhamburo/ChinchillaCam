# ODD: Video frame kind wire parity

## Status

- Branch: `feat/desktop-video-sink`
- Worktree: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink`
- Base before source edits: `997b41f docs(desktop): record fake receiver review closure`
- Slice: T11e companion Rust SessionFrame additive `VIDEO_CHUNK_V2` payload.
- Writer constraint: single desktop writer in isolated worktree; no merge/cherry-pick/push/PR/rebase/reset/amend.

## Goal

Add a Rust SessionFrame companion payload type id `8` for video chunks that preserves the frame kind produced by Android MediaCodec while keeping legacy `VIDEO_CHUNK` type id `5` behavior and golden fixtures unchanged.

## Wire contract

`VIDEO_CHUNK_V2` payload fields are encoded big-endian in this order:

1. `i32 chunk_index`, must be `>= 0`
2. `i64 presentation_time_us`, must be `>= 0`
3. `u8 kind`: `0 = Delta`, `1 = Key`, `2 = CodecConfig`
4. `u16 byteLength`
5. non-empty H264 bytes

Additional constraints:

- Total frame size remains capped at `<= 1 MiB`.
- H264 bytes length must be `1..=65535`.
- Unknown `kind` values must fail with a typed error and must not fall back to a legacy kind.
- Legacy `VIDEO_CHUNK` type id `5` decode/encode remains unchanged.
- No desktop receiver edits in this slice; later work may consume the new frame kind.
- Additional authorized surface: `desktop/usb-probe/src/lib.rs` for the narrow one-line public re-export needed by Rust API/golden tests for the new frame-kind type.
- Additional authorized surface: `desktop/usb-probe/src/desktop_receiver.rs` only for the exhaustiveness helper arm `SessionFramePayload::VideoChunkV2 { .. } => 8` in `session_payload_type_id`; no v2 consumption/decoding or receiver behavior change in this slice.
- Forward T20b/T21 gate: Android current AOA outer payload is bounded at 64 KiB, and MediaCodec key/access units can exceed `65535`; v2 has no fragment boundaries yet, so this slice rejects oversize data instead of truncating it or claiming decoder readiness. Later work needs bounded fragmentation/reassembly or a bitrate/resolution gate before consuming larger units.

## Golden fixture

Session id `s`, sequence `0`, key frame kind `1`, chunk index `0`, PTS `0`, bytes `[0x65]`:

```text
43 43 53 46 01 08 00 00 00 00 00 01 73 00 00 00 10 00 00 00 00 00 00 00 00 00 00 00 00 01 00 01 65
```

## Verification plan

- RED tests before implementation for:
  - v1 legacy `VIDEO_CHUNK` compatibility/golden unchanged
  - v2 encode/decode golden fixture
  - unknown kind typed decode error
  - negative PTS/index rejected
  - byte length and frame-size bounds
- GREEN focused SessionFrame tests.
- Full `desktop/usb-probe` cargo test.
- `cargo fmt --check`.
- `git diff --check`.
- Independent `gentle-ai-verify`.
- Keep diff under 400 changed lines; if not, stop and split.

## Evidence

- ODD/Engram mirror created before source edits; renamed to desktop-specific path before GREEN to avoid Android doc integration conflict.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame -- --nocapture` failed on missing `VideoFrameKind`/`SessionFramePayload::VideoChunkV2`.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 16 SessionFrame tests plus filtered crate tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA, 7 desktop receiver, 6 encoded video sink, 16 session frame, 7 trusted-phone store tests, and doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Diff budget: independent verifier counted 287 tracked changed lines plus 61-line ODD doc = 346 total, within <=400.
- Independent verification: `gentle-ai-verify` PASS; confirmed type 8 wire order/kind mapping, typed unknown-kind error with no fallback, legacy type 5 golden preserved, v2 receiver not consumed, and no hardware/LAN/crypto claims.
