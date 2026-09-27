# T11e — Android VIDEO_CHUNK_V2 frame-kind wire format

## Scope

Extend SessionFrame version 1 additively with `VIDEO_CHUNK_V2` type id `8`.

## Wire payload

`VIDEO_CHUNK_V2` payload fields are encoded big-endian:

1. `i32 chunkIndex` — non-negative.
2. `i64 presentationTimeUs` — non-negative.
3. `u8 frameKind`:
   - `0` = Delta
   - `1` = Key
   - `2` = CodecConfig
4. `u16 byteLength` followed by non-empty H.264 bytes.

The encoded frame remains bounded by the existing 1 MiB SessionFrame limit, and the H.264 byte field remains bounded by `u16` (`<= 65535`). Oversized H.264 output is rejected deterministically; it is never truncated.

## Compatibility

- Existing type `5` `VIDEO_CHUNK` is unchanged.
- SessionFrame version remains `1`.
- Existing v1 golden compatibility must continue to pass.
- Unknown `frameKind` values decode as `SessionFrameDecodeError.InvalidPayload`; they do not fall back to Delta.

## MediaCodec mapping

When mapping flags delivered by MediaCodec/`EncodedVideoChunk`, `CodecConfig` has priority over `Key` if both are present. Otherwise `Key` maps to key frames, and frames with neither flag map to Delta.

## Follow-up gate

Type 8 carries frame kind but does not by itself make sustained large-frame video transport-safe or decode-ready. A later bounded unit must add fragmentation/reassembly or negotiate smaller encoder bitrate/resolution before real sustained video claims.

## Evidence

- Engram mirror: observation `7731` (`odd/android-video-frame-kind-wire/tasks`).
- Source commit: `a20ecdc508b1c2c6771f4c87abc09f91877cf47d`.
- Comparison base: `aaba87c`.
- Local verification:
  - `./gradlew :android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
  - `./gradlew :android:usb-probe:assembleDebug` — PASS.
  - `git diff --check` — PASS.
- Independent verification: `gentle-ai-verify` — PASS.
- Coverage note: `VIDEO_CHUNK_V2` golden encoding is 33 bytes and validates frame-kind flag priority. Legacy type `5` keeps its existing type id and round trips unchanged; there is no exact new legacy type-5 golden claim in this unit.
- Native review: approved under lineage `review-b6560c4fb7d97a2f`; exact ACK recorded.
- Final reviewed range: `aaba87cb7386200cd4a488bac6ca557c5188fef3..7d79d64e97b1b876f1bf0682ad54727c955a5267`.

This evidence is limited to Kotlin fake wire-format source, unit/build checks, and review status. It does not claim hardware-camera readiness or decoder/playback readiness.
