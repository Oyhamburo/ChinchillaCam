# ODD: Desktop session sequence binding

## Status

- Branch: `feat/desktop-video-sink`
- Base for M3a: `d5223f7 test(desktop): cover receiver fragment failures`
- Native review: do not start while consent/review is blocked.

## Goal

Prepare the fake-only `DesktopVideoSessionReceiver` tests for future session id and `SessionFrame.sequence` binding without changing source behavior in M3a.

## Read-only mapping

- `SessionFrame.sequence` is an existing nonnegative `i32` wire field with constructor/getter and encode/decode validation.
- Current desktop receiver helpers used fixed sequence values by payload family (`7`, `8`, `9`), including repeated `9` for multiple type9 fragments.
- Future strict monotonic receiver binding would fail those fixtures for replay before it reaches the intended behavior under test.
- Android fake adapter behavior treats `Int.MAX_VALUE` as a valid final write: the receiver must accept and push that frame once, then mark the state closed because no next sequence is representable.

## M3a scope

- Test fixture migration only.
- Add explicit-sequence helper variants for stateful-wrapper tests.
- Migrate stateful `DesktopVideoSessionReceiver` tests to monotonic sequences across type5/type8/type9 and reset flows.
- Keep legacy stateless receiver fixtures and behavior unchanged.
- No `desktop_receiver.rs` source behavior change.
- No real USB, LAN, decoder, crypto, auth/PoP, hardware, merge, push, PR, or native review scope.

## Authorized edit surfaces

- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `odd/tasks/desktop-session-sequence-binding.md`

## Future M3b semantics, pending fresh grant

- Wrapper binds first accepted `session_id` and the next strict sequence.
- Later type5/type8/type9 frames must use the same session id and exact next sequence; replay, gap, and mixed session id fail closed without sink push.
- `i32::MAX` is accepted and pushed once when otherwise valid, then the wrapper enters `Closed` until `reset_for_new_session()`.
- `reset_for_new_session()` clears sequence/session binding and permits a new nonnegative sequence start.
- Existing stateless `receive_desktop_video_frame` remains unbound.

## Evidence

- M3a ODD/Engram mirror created before test fixture edits.
- M3b ODD/Engram transition recorded before source edits.
