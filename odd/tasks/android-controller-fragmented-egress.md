# T15e3 — Controller fake fragmented egress seam

## Scope

Wire the visible camera controller to a normalized fake-egress seam so tests and future fake starts can use either the legacy type-8 sink or the fragmenting type-8/type-9 sink without duplicate controller drain/error logic.

## Allowed edit surfaces

- NEW `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoEgressSink.kt`
- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineController.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineControllerTest.kt`
- `odd/tasks/android-controller-fragmented-egress.md`

## Requirements

- Use one nullable factory: `() -> EncodedVideoEgressSink`.
- Preserve default no-sink local discard behavior.
- Provide wrappers for legacy `EncodedVideoSessionFrameSink` and `EncodedVideoFragmentingSessionFrameSink`.
- Controller drain/error logic must stay unified over normalized results and stats.
- Legacy type-8 tests and behavior remain unchanged through the wrapper.
- Fragmenting sink success delivers honest FPS plus accepted/drop counters.
- Partial type-9 fragment failure must fail closed with accepted=0, dropped=1, no consume, no false FPS acceptance.
- Restart after fake-egress failure must create a fresh sink/session.

## Non-goals

- No Activity/service/manifest wiring.
- No physical USB, hardware, LAN, or auth changes.
- No native review start.

## Evidence

- RED: fragmenting controller tests failed to compile before normalized egress sink wrappers existed.
- RED follow-up: partial fragment backpressure initially left FPS unknown; controller now records an observed zero-accepted delivery window without false accepted chunks.
- GREEN focused tests: fragmenting 65,496 type8 + 65,497 type9 success, partial first-fragment backpressure accepted=0/dropped=1/no consume, restart fresh session sequence 0, and legacy type8 wrapper path — PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
