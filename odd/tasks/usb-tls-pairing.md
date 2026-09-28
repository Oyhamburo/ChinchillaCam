# USB TLS pairing

## Objetivo

Llevar los bytes TLS de rustls sobre el transporte USB AOA existente sin abrir LAN ni reclamar hardware en este corte.

## Tareas

1. [x] M3e1: implementar un adaptador `Read`/`Write` acotado para ciphertext TLS sobre `FramedUsbStream`, usando `stream_id` `0x01020305`, chunks no vacíos de hasta 32768 bytes y buffer pendiente máximo de 65536 bytes.
2. [x] M3e2: ejecutar CCP1 proof dentro de rustls sobre el stream USB en memoria, sin listener TCP ni fake proof-of-possession. M3e2c devuelve el stream TLS vivo después de status0 y refuerza deadline absoluto post-read/post-write.
3. [ ] M3e3: preparar prueba cross-language in-memory con Android, pendiente de autoridad fresca del usuario.

## Restricciones

- No LAN, no hardware claims, no merge/cherry-pick/push.
- `.codegraph/` preexistente queda intacto.
- `0x01020304` queda reservado para SessionFrame claro; `0x01020305` queda reservado para TLS-over-AOA.
- El stream TLS es opaco y no implica un TLS record por BulkFrame.

## Evidencia M3e1

- Commit: `e835b53358941b190a590b148867bca89cc6ec7c` (`feat(desktop): add USB TLS ciphertext stream`).
- RED/GREEN: pruebas focused del adaptador guiaron la implementación y los readbacks de seguridad; resultado final `cargo test --test usb_tls_ciphertext_stream_test` PASS, 8 tests.
- Formato y suite completa: `cargo fmt -- --check && cargo test` PASS.
- Verificación independiente: PASS; confirmó stream id `0x01020305`, chunks no vacíos `<=32768`, pending read `<=65536`, lectura corta sin bloqueo, poison fail-closed para read/write/flush, sin `Clone`, sin API de extracción, sin TCP/LAN/hardware.
- Tamaño: 351 inserciones, dentro del límite de 400 líneas cambiadas.

## Evidencia M3e2

- Handshake commit: `d2c5749d86a782f4a3a360f43a925f7fc95896c2` (`feat(desktop): handshake USB TLS pairing proof`). Focused `cargo test --test usb_tls_pairing_proof_test` PASS (2 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS.
- CCP1 proof commit: `c58c384029b3b131e1a090c5eeeb26a41ef198d2` (`feat(desktop): prove pairing over USB TLS`). Focused `cargo test --test usb_tls_pairing_proof_test` PASS (4 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS. TDD caveat: este corte no conserva evidencia histórica RED registrada; no debe inventarse.
- Cobertura parcial: status0 exact response dentro del mismo stream rustls, nonce QR de un solo uso, replay/expired/wrong nonce denegados, raw CCP1 sin TLS denegado.
- Recuperación M3e2c: el usuario autorizó restaurar exactamente `/tmp/m3e2c-usb_tls_pairing_proof-source.patch` con sha256 `4942b5dc4bd8c3680bb093b6d1b4abbf6c98b6a2179db3873941a1507f197c18`; se verificó que tocaba sólo `desktop/usb-probe/src/usb_tls_pairing_proof.rs`, que `git apply --check` pasaba y se aplicó una vez con `git apply`.
- RED M3e2c test-only observado contra HEAD: `cd desktop/usb-probe && ~/.cargo/bin/cargo test --test usb_tls_pairing_proof_test` falló con `E0599` porque el valor devuelto era `()` y no tenía `read_exact`, `write_all` ni `flush` (`tests/usb_tls_pairing_proof_test.rs:92/95/96`), exit code 101. La late-final-bytes test estaba agregada, pero la compilación se cortó primero por el RED de live stream.
- GREEN M3e2c: `complete_handshake_and_pairing_proof` devuelve el `StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>` vivo tras CCP1 status0, permitiendo tráfico de aplicación cifrado posterior en la misma conexión TLS. También comprueba deadline absoluto después de `complete_io`, después de cada read, antes de consumir nonce, y antes/después de write/flush.
- Verificación M3e2c: focused `cargo test --test usb_tls_pairing_proof_test` PASS (6 tests), full `cargo fmt -- --check && cargo test` PASS, verificación independiente PASS.
- Alcance: sin TCP/listener, sin LAN, sin hardware claims, sin persistencia de confianza de teléfono.
- Tamaño M3e2c: 135 inserciones / 15 borrados en 3 archivos, dentro del límite de 400 líneas cambiadas.
