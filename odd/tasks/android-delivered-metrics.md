# T15d3a — Android delivered encoded metrics tracker API

## Scope

Add a narrow `LocalPipelineMetricsTracker` API for encoded chunks that were delivered to a fake egress sink, without wiring it into the visible camera controller yet.

## Allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/LocalPipelineMetrics.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/LocalPipelineMetricsTest.kt`
- `odd/tasks/android-delivered-metrics.md`

## Requirements

- Add `recordDeliveredChunks` (or equivalent clear name) for successfully delivered `EncodedVideoChunk` values.
- Delivered chunks contribute to FPS samples using distinct non-codec-config, non-negative presentation timestamps per call.
- Duplicate presentation timestamps within one delivered batch count once.
- Delivered chunks do not increment `encodedChunksDiscarded` or `encodedBytesDiscarded`.
- Existing `recordDrain` discard/backpressure behavior remains unchanged.
- `reset()` clears delivered samples and leaves initial FPS unknown again.
- Preserve current estimated encode latency semantics (`Unknown`).

## Non-goals

- No controller wiring in this unit.
- No real USB/LAN/session transport claims.
- No changes to T15d2 reviewed candidate behavior.

## Evidence

- RED: focused `LocalPipelineMetricsTest` delivered metrics tests failed to compile before `recordDeliveredChunks` existed.
- GREEN focused tests: delivered chunks contribute FPS without discard counters; same-PTS delivered chunks deduplicate per batch; reset clears delivered samples; legacy `recordDrain` discard accounting remains unchanged.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks` — PASS.
- `:android:usb-probe:assembleDebug` — PASS.
- `git diff --check` — PASS.
- Independent verifier: PASS.
