# ODD: Desktop encoded video queue byte budget

## Status

- Branch: `feat/desktop-video-sink`
- Base before source edits: `ade533a fix(desktop): validate fragment reassembler input`
- Slice: T20d encoded video queue aggregate byte budget.
- Native review: do not start while consent/review is blocked.

## Goal

Bound total retained encoded-video payload bytes in `BoundedEncodedVideoQueue` before any future reassembler-to-sink integration. This preserves the existing chunk-count cap while adding a finite byte cap.

## Scope

- `BoundedEncodedVideoQueue::new(capacity)` remains compatible and uses a default aggregate byte cap of 16 MiB.
- Add `BoundedEncodedVideoQueue::with_limits(capacity, max_queued_bytes)`.
- Keep `MAX_CAPACITY = 4096` unchanged.
- Add `MAX_QUEUED_BYTES = 64 MiB`.
- Reject zero byte limit and byte limits greater than `MAX_QUEUED_BYTES` with typed errors.
- Track `queued_bytes` with checked addition on push.
- Reject push when `queued_bytes + incoming > max_queued_bytes` without mutating queue state.
- `pop_front` subtracts the exact popped payload length.
- Preserve count-cap and per-chunk payload-limit behavior.
- No receiver, reassembler hookup, decoder, USB, auth, hardware, merge, push, or PR scope.

## Verification plan

- RED tests before implementation for:
  - default `new` exposes the 16 MiB byte cap
  - configurable invalid zero and too-large byte limits
  - push increments queued bytes
  - byte overflow fails with `QueueBytesFull` and does not mutate queue state
  - pop decrements exact bytes
  - count cap remains enforced independently of byte headroom
  - per-chunk payload limit remains enforced before queue push
- GREEN focused encoded video sink tests.
- Full `desktop/usb-probe` cargo test.
- `cargo fmt --check`.
- `git diff --check`.
- Independent `gentle-ai-verify`.
- Keep diff under 400 changed lines; split instead of code-golf if needed.

## Evidence

- ODD/Engram mirror created before source edits.
- RED focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml encoded_video_sink -- --nocapture` failed on missing queue byte API/errors.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml encoded_video_sink` passed with 11 encoded video sink tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Diff budget: code/test diff plus task doc totals 207 changed lines, within <=400.
- Independent verification: `gentle-ai-verify` PASS; confirmed default/configurable byte limits, typed errors, checked non-mutating push rejection, exact pop decrement, count/per-chunk caps preserved, and no receiver/reassembler/decoder/USB/auth integration claims.
