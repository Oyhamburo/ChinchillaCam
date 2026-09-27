# ODD: Desktop video fragment reassembler

## Status

- Branch: `feat/desktop-video-sink`
- Base before source edits: `574c548 test(desktop): cover video fragment invalidity`
- Slice: T11g3a pure Rust reassembler core.
- Native review: do not start while consent review is blocked.

## Goal

Add a pure Rust reassembler for decoded `SessionFramePayload::VideoChunkFragmentV1` frames. It emits an owned `ReassembledVideoChunk` only after a full access unit arrives in strict order.

## Scope

- One in-flight access unit globally, bound to one `session_id`.
- Strict `fragment_index` order: `0..fragment_count - 1`.
- Constant metadata: `session_id`, `chunk_index`, `presentation_time_us`, `kind`, `fragment_count`, `total_h264_bytes`.
- Cumulative bytes `<= total_h264_bytes <= 4 MiB`; total cannot be reached before the last fragment; last must equal total.
- Duplicate, out-of-order, metadata mismatch, overflow, early-complete, incomplete-final, non-fragment payload, and second-session-while-active errors fail closed and clear state.
- Explicit `reset_session` and `reset_all`; no timeout claim.
- No `EncodedVideoChunk`, receiver, sink, USB, decoder, or hardware integration.

## Split plan

- G3a: module/API plus happy strict completion test.
- G3b: fail-closed edge tests and reset/sequential-session tests.

## Evidence

- ODD/Engram mirror created before source edits.
- RED focused test failed on missing reassembler exports.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml reassembler_emits_chunk_only_after_strict_completion` passed.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Independent verification: `gentle-ai-verify` PASS after bounds-before-allocation fix.
