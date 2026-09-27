# T11g4 — Android encoded video chunk fragmenter

## Scope

Add a pure Android helper that fragments one `EncodedVideoChunk` into `SessionPayload.VideoChunkFragmentV1` payloads. This unit does not wire the helper into sinks, USB adapters, controllers, UI, services, or real transport.

## Allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoChunkFragmenter.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/EncodedVideoChunkFragmenterTest.kt`
- `odd/tasks/android-video-fragmenter.md`

## Requirements

- API accepts `chunkIndex`, real `sessionId`, and `EncodedVideoChunk`.
- Result is typed: `Fragments`, `InvalidPayload`, or `Oversized`.
- Frame kind uses `SessionVideoFrameKind.fromCodecFlags`, so `CodecConfig` has priority over `Key`.
- Reject empty session id, UTF-8 session id longer than `u16`, negative chunk index, negative PTS, empty bytes, and logical H.264 payloads larger than 4 MiB.
- Compute per-fragment byte capacity from the exact total packet cap: `65_536 - 8 accessory bytes - 16 SessionFrame base bytes - sessionIdUtf8Bytes - 27 type9 fixed payload bytes`, i.e. `65_485 - sessionIdUtf8Bytes`; session `s` has capacity `65_484` bytes.
- Reject non-positive capacity.
- Split without truncation. Fragment count must be `1..1024`; reject payloads that would require more fragments.
- Copy bounded fragment bytes into each payload.
- Each emitted fragment must serialize through `SessionFrameCodec` such that `8 + encodedFrame.size <= 65_536`.

## Non-goals

- No USB adapter changes.
- No sink/controller/activity/service/manifest wiring.
- No reassembly implementation.
- No real hardware, LAN, decoder, or playback readiness claim.

## Evidence

- RED: focused `EncodedVideoChunkFragmenterTest` failed to compile before the fragmenter and typed result existed.
- GREEN focused tests: session `s` boundary 65,484/65,485, UTF-8 session id capacity, invalid inputs, oversized/too many fragments, and frame-kind priority — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
