# T15e2 — Android fake fragmented egress

## Scope

Extend the fake sustained egress path in two bounded cuts. T15e2a widens the existing `EncodedVideoSustainedFakeTransportAdapter` so the same object can write both type-8 `VideoChunkV2` and type-9 `VideoChunkFragmentV1` payloads while owning the single fake `sessionId`, sequence, and `UsbSessionFrameSustainedFakeTransport`. T15e2b will add a fragmenting sink that uses this widened adapter.

## T15e2a allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoSustainedFakeTransportAdapter.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/EncodedVideoSustainedFakeTransportAdapterTest.kt`
- `odd/tasks/android-fake-fragmented-egress.md`

## T15e2a requirements

- Add a read-only session id accessor for later capacity planning.
- Add `maxType8H264Bytes()` using the adapter-owned session id and the 65,528-byte SessionFrame payload cap.
- Add `writeFragment(SessionPayload.VideoChunkFragmentV1)`.
- `write(VideoChunkV2)` and `writeFragment(VideoChunkFragmentV1)` must delegate to one synchronized private `writePayload` path so both share the same sequence and fail-closed rules.
- Preserve T15e1 type-8 behavior exactly.
- Do not introduce a second session/sequence owner.

## T15e2b requirements (later cut)

- Add a new fragmenting sink that chooses type 8 when H.264 bytes fit `maxType8H264Bytes()`, otherwise uses `EncodedVideoChunkFragmenter` and type 9.
- Advance logical `chunkIndex` only after the whole access unit is sent.
- Partial Sent followed by Backpressure/Closed/Oversize/Failed closes and reports failure; no retry, no accepted metrics, no logical chunk index advance.

## Non-goals

- No USB physical transport.
- No Activity, service, manifest, controller, or UI wiring.
- No real hardware/LAN/decoder/playback claims.
- No native review start in this coding unit.

## Evidence

- RED: focused adapter tests failed to compile before `sessionId`, `maxType8H264Bytes()`, and `writeFragment(VideoChunkFragmentV1)` existed.
- GREEN focused tests: type8 capacity planning, same sequence owner for type8/type9, and fragment fail-closed backpressure — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
