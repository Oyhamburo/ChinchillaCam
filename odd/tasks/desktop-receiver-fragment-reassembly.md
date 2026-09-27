# ODD: Desktop receiver fragment reassembly

## Status

- Branch: `feat/desktop-video-sink`
- Base before source edits: `67c5a8e fix(desktop): bound encoded video queue bytes`
- Slice: T20e1 fake desktop stateful receiver for type9 happy path and parity.
- Native review: do not start while consent/review is blocked.

## Goal

Add a fake-only stateful desktop receiver wrapper that can consume `VIDEO_CHUNK_FRAGMENT_V1` type9 fragments, emit an encoded chunk only after the reassembler completes an access unit, and preserve existing legacy type5 and type8 behavior. Keep the existing stateless `receive_desktop_video_frame` function intact.

## Scope

- Add `DesktopVideoSessionReceiver<C>` with classifier, `VideoFragmentReassembler`, per-chunk reassembled limit, and terminal closed state.
- `receive()` validates stream id and decodes with the existing 65,528 SessionFrame budget.
- Type5 legacy path uses the injected classifier and pushes immediately.
- Type8 path maps validated kind directly and pushes immediately.
- Type9 path calls the reassembler: partial fragments push nothing; final fragment pushes one concatenated `EncodedVideoChunk`.
- Wrong stream id, malformed frame, reassembler error, unexpected payload, `EncodedVideoChunk` rejection, and sink rejection are terminal for the wrapper: clear reassembler state and return `Closed` on later `receive()` calls until explicit `reset_for_new_session()`.
- Wrong stream id while a fragment is in flight is treated as session corruption; it closes the wrapper too.
- No partial retry or dropped-byte retry semantics.
- No real USB, decoder, crypto, auth/PoP, LAN, hardware, merge, push, PR, or native review scope.

## Authorized edit surfaces

- `desktop/usb-probe/src/desktop_receiver.rs`
- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `desktop/usb-probe/src/lib.rs` only for minimal public re-export of `DesktopVideoSessionReceiver` and required new receiver error types
- `odd/tasks/desktop-receiver-fragment-reassembly.md`

## T20e1 tests

- RED then GREEN: type9 first fragment returns `Ok(())` and leaves sink empty.
- RED then GREEN: type9 final fragment emits one concatenated chunk with mapped kind, PTS, stream id, and bytes.
- RED then GREEN: wrapper preserves legacy type5 injected-classifier behavior.
- RED then GREEN: wrapper preserves type8 direct-kind behavior.
- RED then GREEN: wrong stream id while partial fragment is active is terminal; a later valid receive returns `Closed` until `reset_for_new_session()`.

## Deferred T20e2

- Duplicate/out-of-order/mismatch reassembler errors.
- Sink byte/full backpressure terminal close.
- Malformed frame terminal close.
- Unexpected payload terminal close.
- Reset-focused recovery tests.

## Evidence

- ODD/Engram mirror created before source edits.
