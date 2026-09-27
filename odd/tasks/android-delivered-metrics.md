# T15d3 — Android delivered encoded metrics

## Scope

Add a narrow `LocalPipelineMetricsTracker` API for encoded chunks that were delivered to a fake egress sink, then wire the visible camera controller to use it for fake-egress accepted chunks only.

## Allowed edit surfaces

### T15d3a

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/LocalPipelineMetrics.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/LocalPipelineMetricsTest.kt`
- `odd/tasks/android-delivered-metrics.md`

### T15d3b

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineController.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineControllerTest.kt`
- `odd/tasks/android-delivered-metrics.md`

## Requirements

- Add `recordDeliveredChunks` (or equivalent clear name) for successfully delivered `EncodedVideoChunk` values.
- Delivered chunks contribute to FPS samples using distinct non-codec-config, non-negative presentation timestamps per call.
- Duplicate presentation timestamps within one delivered batch count once.
- Delivered chunks do not increment `encodedChunksDiscarded` or `encodedBytesDiscarded`.
- Existing `recordDrain` discard/backpressure behavior remains unchanged.
- `reset()` clears delivered samples and leaves initial FPS unknown again.
- Preserve current estimated encode latency semantics (`Unknown`).

## T15d3b requirements

- Controller records delivered metrics only for `EncodedVideoSessionFrameSinkResult.Accepted` chunks.
- A successful fake-egress drain shows valid FPS plus fake-egress accepted/dropped counters in Spanish.
- Duplicate presentation timestamps in the accepted batch use the tracker-level per-batch dedupe.
- If a later chunk fails after earlier accepted chunks, only the earlier accepted chunks are recorded as delivered before stopping fail-closed.
- Failed chunks are not recorded as delivered or discarded.
- Existing reset and legacy no-sink discard behavior remain unchanged.

## Non-goals

- No real USB/LAN/session transport claims.
- No merge/push/PR or native review in this unit without separate explicit grant.

## Evidence

### T15d3a — tracker API

- RED: focused `LocalPipelineMetricsTest` delivered metrics tests failed to compile before `recordDeliveredChunks` existed.
- GREEN focused tests: delivered chunks contribute FPS without discard counters; same-PTS delivered chunks deduplicate per batch; reset clears delivered samples; legacy `recordDrain` discard accounting remains unchanged.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.

### T15d3b — controller integration

- RED: controller fake-egress metrics test failed while success path still reported `FPS: no disponible para egreso fake en T15d2`.
- RED follow-up: partial accepted-then-backpressure path lost fake accepted/drop counters on the Error state.
- GREEN focused tests: success path reports valid FPS plus accepted/drop counters; partial success records only accepted chunks as delivered before failing closed and keeps fake counters on the Error state.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
