# T11g2 — Android VIDEO_CHUNK_FRAGMENT_V1 wire codec

## Scope

Add Android SessionFrame codec support for additive `VIDEO_CHUNK_FRAGMENT_V1` type id `9`, compatible with the Rust G1a golden fixture.

## Allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/SessionFrame.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/SessionFrameTest.kt`
- `odd/tasks/android-video-fragment-wire.md`

## Wire payload

`VIDEO_CHUNK_FRAGMENT_V1` payload fields are encoded big-endian:

1. `i32 chunkIndex` — non-negative.
2. `i64 presentationTimeUs` — non-negative.
3. `u8 frameKind`:
   - `0` = Delta
   - `1` = Key
   - `2` = CodecConfig
4. `i32 fragmentIndex` — non-negative.
5. `i32 fragmentCount` — `1..1024`, and `fragmentIndex < fragmentCount`.
6. `i32 totalH264Bytes` — `1..4194304`.
7. `u16 fragmentByteLength` followed by non-empty fragment bytes, with `fragmentByteLength <= totalH264Bytes`.

The existing generic 1 MiB SessionFrame maximum still applies to each encoded frame. `totalH264Bytes` describes the reassembled H.264 payload and may be larger than one frame's fragment bytes. Type `5` and type `8` payloads remain unchanged.

## Non-goals

- No USB transport changes.
- No reassembly implementation.
- No decoder/playback readiness claim.
- No UI, service, manifest, or permission changes.

## Evidence

- Rust G1a source fixture: `/Users/jele/Desktop/codes/ChinchillaCam-desktop-video-sink/desktop/usb-probe/tests/session_frame_test.rs` lines 60-63, copied verbatim into Android golden expectation.
- RED: focused Android `SessionFrameTest` type9 golden/invalid tests failed to compile before `VideoChunkFragmentV1` existed.
- GREEN focused tests: Rust golden fixture encodes/decodes, invalid payloads reject, invalid encode rejects — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
