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
- Verification to record after implementation: focused unit tests, assemble, diff check, independent verify, and commit id.
