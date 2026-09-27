# T15e1 — Android fake egress sustained transport adapter

## Scope

Add an explicit adapter from `EncodedVideoSessionFrameTransport` to `UsbSessionFrameSustainedFakeTransport` for current type-8 `VideoChunkV2` fake egress only.

## Allowed edit surfaces

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoSustainedFakeTransportAdapter.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/EncodedVideoSustainedFakeTransportAdapterTest.kt`
- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/EncodedVideoSessionFrameSink.kt`
- `android/usb-probe/src/test/java/dev/chinchillacam/usbprobe/EncodedVideoSessionFrameSinkTest.kt`
- `odd/tasks/android-fake-egress-adapter.md`

## Requirements

- Adapter owns the fake video sequence, starting at `initialSequence`, independently of `VideoChunkV2.chunkIndex`.
- Adapter accepts a real `sessionId`; reject empty session id, UTF-8 session ids larger than `u16`, and negative `initialSequence`.
- Sequence increments only after `UsbSessionFrameSustainedWriteResult.Sent`.
- `Int.MAX_VALUE` is the last valid sent sequence; a later write fails closed instead of wrapping.
- Adapter preflights `SessionFrameCodec.encode(frame).size <= 65528` before delegating, even if the injected fake transport is configured with a larger max.
- Map `Sent` to `Written`, `Backpressure` to `BackpressureExceeded`, `Closed` to `Closed`, and adapter/fake oversize to typed `Oversized`.
- Preserve close fail-closed behavior and return `Closed` after close.
- Do not use type 9/fragmenter in this unit.

## Non-goals

- No USB physical transport.
- No Activity, service, manifest, controller, or sink wiring.
- No real LAN/hardware/video readiness claims.

## Evidence

- RED: focused adapter and sink oversize tests failed to compile before `EncodedVideoSustainedFakeTransportAdapter` and typed `EncodedVideoSessionFrameWriteResult.Oversized` existed.
- GREEN focused tests: sequence ownership, 65,528-byte preflight against permissive fake, backpressure/closed/oversize mappings, sequence overflow fail-closed with delegate close, malformed payload encode exception fail-closed, close delegation, and sink typed oversize mapping — PASS.
- Full verification pending.
