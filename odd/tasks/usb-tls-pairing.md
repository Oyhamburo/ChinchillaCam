# USB TLS pairing

## Objetivo

Llevar los bytes TLS de rustls sobre el transporte USB AOA existente sin abrir LAN ni reclamar hardware en este corte.

## Tareas

1. [ ] M3e1: implementar un adaptador `Read`/`Write` acotado para ciphertext TLS sobre `FramedUsbStream`, usando `stream_id` `0x01020305`, chunks no vacíos de hasta 32768 bytes y buffer pendiente máximo de 65536 bytes.
2. [ ] M3e2: ejecutar CCP1 proof dentro de rustls sobre el stream USB en memoria, sin listener TCP ni fake proof-of-possession.
3. [ ] M3e3: preparar prueba cross-language in-memory con Android, pendiente de autoridad fresca del usuario.

## Restricciones

- No LAN, no hardware claims, no merge/cherry-pick/push.
- `.codegraph/` preexistente queda intacto.
- `0x01020304` queda reservado para SessionFrame claro; `0x01020305` queda reservado para TLS-over-AOA.
- El stream TLS es opaco y no implica un TLS record por BulkFrame.

## Evidencia esperada M3e1

- RED/GREEN focused tests del adaptador.
- `cargo fmt -- --check`.
- `cargo test`.
- Verificación independiente.
- Commit <=400 líneas cambiadas.
