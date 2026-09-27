# T15d — Android fake encoded egress to SessionFrame V2

## Scope

Add an injectable fake egress sink for the visible local camera pipeline so drained `EncodedVideoChunk` values can be converted to reviewed `SessionPayload.VideoChunkV2` frames without USB hardware, real session transport, Activity wiring, permission changes, service changes, or manifest changes.

## Allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineController.kt`
- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoSessionFrameSink.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/VisibleCameraPipelineControllerTest.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/EncodedVideoSessionFrameSinkTest.kt`
- `odd/tasks/android-video-egress.md`

## Requirements

- Default local visible-camera behavior remains no-sink: encoded chunks are discarded in memory and foreground-service ownership is unchanged.
- An injectable sink maps existing `EncodedVideoChunk` bytes, presentation timestamp, and codec/key flags to `SessionPayload.VideoChunkV2`.
- Chunk index is deterministic and monotonic for an egress sink instance.
- Frame-kind precedence is `CodecConfig` over `Key`; frames with neither flag are Delta.
- Modeled fake egress transport outcomes are fail-closed:
  - backpressure closes the active handle and prevents later writes;
  - closed sink closes the active handle and prevents later writes;
  - oversized encoded chunks close the active handle and prevent later writes;
  - empty bytes, negative presentation timestamps, and transport runtime exceptions fail closed before false acceptance.
- `EncodedVideoSessionFrameTransport` is an independent injected fake interface in this unit; this does not claim integration with `UsbSessionFrameSustainedFakeTransport` or any T15c adapter until an adapter test exists.
- Explicit stop owns lifecycle and closes the sink without claiming hardware or decoder readiness.
- Metrics honestly distinguish accepted and dropped chunks.

## Non-goals

- No USB hardware transport.
- No `UsbProbeActivity`, `UsbManager`, manifest, permission, or foreground-service wiring.
- No real session negotiation or decoder/playback readiness claim.

## Verification plan

Use TDD: RED focused tests for sink mapping/failure behavior and controller sink integration, then GREEN implementation. Run focused unit tests, full `:android:usb-probe:testDebugUnitTest`, `:android:usb-probe:assembleDebug`, `git diff --check`, and independent verifier before review.

## Evidence

### T15d1 — fake sink only

- RED: focused sink/controller tests initially failed to compile because `EncodedVideoSessionFrameSink` and related typed results did not exist.
- RED follow-up: empty H.264 payload and negative presentation timestamp tests failed to compile before typed invalid-payload handling existed.
- GREEN focused sink path: `:android:usb-probe:testDebugUnitTest` with `EncodedVideoSessionFrameSinkTest` — PASS in final focused run.
- Scope note: the sink enforces the reviewed `u16` H.264 byte-field bound (`<= 65535`) for fake frame construction only; this does not claim that 65,535 bytes always fits a complete outer USB/transport packet with headers.
- Fake-only caveat: no USB hardware, LAN, Activity, permission, service, manifest, real session, or decoder/playback readiness.
- Native review: approved under lineage `review-3845d6cf80b1997f`; exact ACK recorded and authority burned.
- Final reviewed T15d1 range: `a192cbf6d5131246e5e48e886dc6eca81bcbdc1a..60f9ca69d0050df6d7ecee16fda8a3c571c54b66`.
- Reviewer advisory: `R3-close-exception-leaks` at `EncodedVideoSessionFrameSink.kt:110`; informational for this reviewed slice and reserved for a separate bounded hardening unit.

### T15d2 — controller integration

- T15d1 commit: `60f9ca69d0050df6d7ecee16fda8a3c571c54b66`.
- Controller integration wires the sink through a per-start factory so stop/failure closes the active sink and restart creates a fresh fake transport session instead of reopening a closed transport silently.
- Controller metrics text reports fake-egress accepted/dropped counts without recording accepted chunks as local discarded bytes; FPS remains explicitly unavailable for fake egress until a separate metrics API can track delivered chunks honestly.
- Default no-sink local discard behavior remains unchanged.
- Factory failure is typed as visible Error and stops the newly launched handle before publishing it.
- Focused controller restart/failure/factory/metrics tests: PASS.
- Full `:android:usb-probe:testDebugUnitTest --rerun-tasks`, `:android:usb-probe:assembleDebug`, `git diff --check`, and independent verify: PASS.
- T15d2 commit id: `1a045fa244c626c0c9f491ac4e836179b6c137d9`.
- T15d1 closure doc commit pending.
