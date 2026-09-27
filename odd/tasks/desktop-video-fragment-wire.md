# ODD: Desktop SessionFrame video fragment wire

## Status

- Branch: `feat/desktop-video-sink`
- Base before source edits: `a9bf9f9 fix(desktop): cap receiver session packet size`
- Slice: T11g1a Rust codec-only `VIDEO_CHUNK_FRAGMENT_V1` core wire.
- Native review: blocked on consent; do not START here.

## Goal

Add transport-agnostic SessionFrame payload type id `9` without changing type id `5` or `8`, without receiver consumption/reassembly, and without imposing USB `65,528` globally in the codec.

## Wire contract

Payload offsets are big-endian:

- `0..4 i32 chunk_index >= 0`
- `4..12 i64 presentation_time_us >= 0`
- `12 u8 kind`: `0 Delta`, `1 Key`, `2 CodecConfig`; unknown typed error/no fallback
- `13..17 i32 fragment_index >= 0` and `< fragment_count`
- `17..21 i32 fragment_count`, `1..=1024`
- `21..25 i32 total_h264_bytes`, `1..=4,194,304`
- `25..27 u16 fragment_byte_length`, `1..=65,535` and `<= total_h264_bytes`
- `27.. fragment_h264_bytes`

`SessionFrameCodec` keeps the existing 1 MiB cap. USB packet sizing stays in receiver/transport because it depends on outer `BulkFrame` and session id overhead.

## Golden fixture

Session id `s`, sequence `0`, Key, chunk `0`, PTS `0`, fragment `0/2`, total `70,000`, bytes `[0x65]`:

```text
43 43 53 46 01 09 00 00 00 00 00 01 73 00 00 00 1c 00 00 00 00 00 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 00 01 11 70 00 01 65
```

## Split plan

- T11g1a: codec type 9 plus golden and Delta/CodecConfig roundtrip core tests.
- T11g1b: invalidity/security tests for kind, indices, counts, total size, empty bytes, and len > total.

## Evidence

- ODD/Engram mirror created before source edits.
- RED focused test failed on missing `video_chunk_fragment_v1_*` constructors.
- GREEN focused test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml session_frame` passed with 18 SessionFrame tests.
- GREEN full crate test: `PATH=$HOME/.cargo/bin:$PATH cargo test --manifest-path desktop/usb-probe/Cargo.toml` passed with 44 AOA, 12 desktop receiver, 6 encoded video sink, 18 session frame, 7 trusted-phone store tests, and doctests.
- Format: `PATH=$HOME/.cargo/bin:$PATH cargo fmt --manifest-path desktop/usb-probe/Cargo.toml -- --check` passed.
- `git diff --check`: passed with no output.
- Diff budget: G1a core tracked code/test diff plus task doc totals 393 changed lines, within <=400.
- Independent verification: `gentle-ai-verify` PASS; confirmed type 9 core codec, golden/roundtrip tests, type 5/type 8 preservation, no codec USB cap, and receiver helper arm only.
