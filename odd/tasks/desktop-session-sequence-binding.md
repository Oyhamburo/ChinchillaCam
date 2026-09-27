# ODD: Enlace de secuencia de sesión del receptor desktop

## Estado

- Rama: `feat/desktop-video-sink`
- Base para M3a: `d5223f7 test(desktop): cover receiver fragment failures`
- M3a: `33e4baa test(desktop): prepare receiver sequence fixtures`
- M3b1: `2eb67bb feat(desktop): bind receiver session sequence`
- Revisión nativa: no iniciar mientras el consentimiento/review esté bloqueado.

## Objetivo

Preparar y proteger el `DesktopVideoSessionReceiver` fake-only con enlace de `session_id` y `SessionFrame.sequence`, sin cambiar el contrato legacy stateless de `receive_desktop_video_frame`.

## Mapeo read-only

- `SessionFrame.sequence` ya existe como campo wire `i32` no negativo, con constructor/getter y validación encode/decode.
- Los helpers antiguos del receiver desktop usaban valores fijos por familia de payload (`7`, `8`, `9`), incluido `9` repetido para múltiples fragmentos type9.
- M3a migró sólo los fixtures stateful a secuencias explícitas y monótonas; los fixtures legacy stateless conservan sus valores fijos.
- El fake adapter Android trata `Int.MAX_VALUE` como una escritura final válida: el receiver debe aceptar y empujar ese frame una vez y después marcarse `Closed`, porque no hay siguiente secuencia representable.

## Alcance M3a

- Migración de fixtures de test únicamente.
- Agregar helpers con secuencia explícita para tests del wrapper stateful.
- Migrar tests stateful de `DesktopVideoSessionReceiver` a secuencias monótonas entre type5/type8/type9 y flujos con reset.
- Mantener sin cambios los fixtures y comportamiento legacy stateless.
- Sin cambios de comportamiento en `desktop_receiver.rs`.

## Alcance M3b1

- El wrapper enlaza el primer `session_id` decodificado y la primera `SessionFrame.sequence` no negativa.
- Cada frame type5/type8/type9 posterior debe usar el mismo `session_id` y la siguiente secuencia exacta.
- Replay, gap y mezcla de `session_id` fallan cerrado, limpian estado y no empujan al sink.
- La validación ocurre antes de classifier, reassembler y sink push.
- `reset_for_new_session()` limpia el enlace de sesión/secuencia.
- `i32::MAX` queda soportado en source para paridad con el fake Android; las pruebas dedicadas quedan diferidas a M3b2.
- `receive_desktop_video_frame` permanece stateless y sin enlace.

## Superficies autorizadas usadas

- `desktop/usb-probe/tests/desktop_receiver_test.rs`
- `desktop/usb-probe/src/desktop_receiver.rs`
- `odd/tasks/desktop-session-sequence-binding.md`

## Fuera de alcance

- USB real, LAN, decoder, crypto, auth/PoP, hardware, merge, push, PR o revisión nativa.

## Evidencia

- M3a ODD/Engram mirror creado antes de editar fixtures.
- M3b ODD/Engram transition registrada antes de editar source.
- M3b1 quedó dentro del presupuesto de revisión: 366 inserciones y 17 eliminaciones.
