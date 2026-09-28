# Identidad mTLS del teléfono

## 1. Objetivo

Que el desktop autentique al teléfono con TLS mutuo estándar, cerrando el ítem pendiente del Gate M3 ("threat model/plan testeable con TLS estándar e identidad de par pinneada/esperada") antes de componer el pairing de punta a punta y antes de T17/T19.

## 2. Problema

Hoy el teléfono pinnea el SPKI del desktop recibido por QR (`PinnedDesktopTlsTrustManager`), pero el desktop no autentica al teléfono: los servidores rustls usan `.with_no_client_auth()` (`desktop/usb-probe/src/usb_tls_pairing_proof.rs:117`, `desktop/usb-probe/src/loopback_pairing_proof_server.rs:284`) y `TrustedPhoneIdentity` (T13c) espera una clave pública que nada produce. Sin identidad del teléfono no se puede rechazar tráfico no autenticado (T17) ni reconectar sin QR (T19).

## 3. Decisión

Decisión del usuario del 2026-09-28 (opción 1): mTLS con clave del teléfono en Android Keystore. Alternativas descartadas: firma de desafío a nivel aplicación dentro de TLS (protocolo propio; cualquier dispositivo de la LAN completa el handshake antes de ser rechazado) y token secreto entregado en el pairing (una filtración permite suplantar al teléfono).

## 4. Contrato compartido Android ↔ desktop

1. Clave del teléfono: EC P-256 no exportable en Android Keystore, alias `chinchillacam-phone-tls-v1`, `PURPOSE_SIGN`, digests `DIGEST_NONE` y `DIGEST_SHA256` (`DIGEST_NONE` es necesario para autenticación TLS de cliente; fuente: javadoc de AOSP `KeyGenParameterSpec`). Certificado autofirmado generado por Keystore. En tests JVM se usa una identidad PKCS12 creada con `keytool` detrás del mismo seam.
2. El teléfono presenta su certificado de cliente en todo handshake TLS con desktops: pairing y reconexión; hoy por USB AOA stream `0x01020305`, luego Wi-Fi.
3. El desktop exige autenticación de cliente en el camino USB real. Acepta sólo un SPKI P-256 canónico (91 bytes DER) y verifica la firma TLS del cliente (`CertificateVerify`) con los helpers de rustls (`rustls::crypto::verify_tls12_signature` / `verify_tls13_signature`). No valida fechas ni emisor del certificado: la identidad es el SPKI.
4. `phone_id` = SHA-256 del SPKI DER en hex minúscula (64 caracteres), el mismo algoritmo de huella que `PairingTrustFingerprint` en Android.
5. Política por conexión, sin estado compartido con el emisor de QR:
   - Handshake de pairing (`complete_handshake_and_pairing_proof`, con QR vigente): acepta cualquier SPKI P-256 canónico; el resultado sólo sirve si `CCP1` presenta un nonce de QR vigente, que se consume. Devuelve el stream TLS vivo y el candidato `{phone_id, spki}` ligado a esa misma sesión TLS.
   - Handshake de reconexión (sin QR): acepta sólo teléfonos confiables y no revocados, buscando por el `phone_id` derivado y exigiendo igualdad exacta de `public_key`; rechaza en el handshake a desconocidos (`CertificateError::UnknownIssuer`) y revocados (`CertificateError::Revoked`). Errores del store rechazan (fail closed).
6. Confirmación explícita en cada lado, sin frame `CCP1` nuevo: el desktop persiste `TrustedPhoneIdentity{phone_id, label, public_key=spki}` sólo por una llamada explícita de confirmación (la UI llega en T25); el teléfono persiste el desktop con `PendingPairingCoordinator.confirm` como hoy. Si sólo un lado confirmó, la reconexión falla cerrada.
7. `CCP1` v1 no cambia.
8. Fuera de alcance: UI, LAN/listener, wiring de Activity, integración del canal vivo con `SessionFrame`, comparación de código corto (SAS) en UI, client auth en `loopback_pairing_proof_server.rs` (sigue siendo test/interop), pruebas físicas.

## 5. Threat model (Gate M3)

| Amenaza | Mitigación | Riesgo residual |
| --- | --- | --- |
| Desktop falso o MITM en USB/LAN | el teléfono pinnea el SPKI del QR o del trust store | — |
| Teléfono no emparejado que se conecta (LAN, T17) | el desktop exige certificado de cliente de un teléfono confiable y rechaza en el handshake | durante la ventana de pairing se acepta cualquier clave hasta `CCP1` (QR 60–120 s, nonce de un solo uso) |
| Un tercero captura el QR (foto) y empareja antes que el teléfono legítimo | nonce de un solo uso con expiración y confirmación explícita en el desktop | falta comparación de código corto (huella) en las UIs de desktop y teléfono; pendiente para T25 y la UI del teléfono |
| Robo del archivo de confianza del desktop | sólo contiene claves públicas | integridad del archivo: hardening T13c diferido (permisos, symlink, fsync) |
| Extracción de la clave del teléfono | Keystore no exportable | teléfono rooteado o malware con acceso al Keystore; validación del Keystore en dispositivo pendiente (M9) |
| Revocación | el desktop marca revocado y rechaza en el handshake; el teléfono revoca el desktop en su store | — |
| Replay de `CCP1` | nonce de QR de un solo uso, challenge nonce de 32 B y sesión TLS | — |
| Downgrade | sólo TLS 1.2/1.3 y sólo P-256 | TLS 1.3 en Android sólo desde API 29 (no verificado con fuente primaria); por debajo queda TLS 1.2 |

## 6. Reglas de ejecución

- TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución: "TDD estricto: RED observado antes de producción, GREEN después"). Si una tarea no puede tener RED observable, se declara el desvío explícitamente; no se inventa evidencia.
- Commits locales por tarea, ≤400 líneas cambiadas como heurística; sin push, PR ni merge (regla del usuario); la estrategia de cadena de PR no aplica.
- Revisión nativa RDD (on, global) por commit de trabajo con `gentle-ai review assess`.
- Ruta de cada tarea: delegada a un writer acotado (trigger: 2+ archivos no triviales o lectura previa de 4+ archivos), con verificación independiente para candidatos de riesgo alto.

## 7. Tareas

Runner: `cd desktop/usb-probe && PATH=$HOME/.cargo/bin:$PATH cargo fmt -- --check && PATH=$HOME/.cargo/bin:$PATH cargo test` (focused: `cargo test --test <name>`). Revisión nativa por commit con base = commit padre: la deuda previa sin revisión (`92b04c0..ec22232` y T13c `93ab9d0`, `a9771c5`, `ba32d62`, `989a200`) queda fuera de este feature y requiere decisión aparte.

1. [x] m1 — Verificador de certificado de cliente. `rustls-webpki = "0.103"` explícito en `Cargo.toml` (hoy transitivo, `Cargo.lock` 0.103.15) para extraer el SPKI con `EndEntityCert::try_from(&cert)` + `subject_public_key_info()` (rustls-webpki `src/cert.rs:234`); validación de SPKI P-256 canónico; `phone_id`; `PhoneClientCertVerifier` (trait `rustls::server::danger::ClientCertVerifier`, client auth obligatorio) con política `Pairing` y `TrustedOnly` sobre un trait de lookup (`Trusted(spki)` / `Revoked` / `Unknown`) implementado por `FileTrustedPhoneStore` y por un fake. RED: `pairing_policy_accepts_canonical_p256_client_spki`, `rejects_non_p256_client_certificate` (cert P-384 de rcgen), `trusted_only_rejects_unknown_phone`, `trusted_only_rejects_revoked_phone`, `trusted_only_accepts_trusted_phone`. Estimado ~350 líneas.

   ### Evidencia m1

   - **RED observado** (import sin resolver; el archivo de test ya existía, el módulo aún no estaba declarado en `lib.rs`):

     ```
     error[E0432]: unresolved imports `usb_probe::phone_id_for_spki`, `usb_probe::PhoneClientCertVerifier`, `usb_probe::TrustedPhoneLookup`, `usb_probe::TrustedPhoneLookupError`, `usb_probe::TrustedPhoneStatus`
       --> tests/phone_client_cert_verifier_test.rs:14:5
        |
     14 |     phone_id_for_spki, DesktopTlsIdentity, FileTrustedPhoneStore, PhoneClientCertVerifier,
        |     ^^^^^^^^^^^^^^^^^ no `phone_id_for_spki` in the root          ^^^^^^^^^^^^^^^^^^^^^^^ no `PhoneClientCertVerifier` in the root
     ```

   - **GREEN focused**: `cargo test --test phone_client_cert_verifier_test` → `test result: ok. 7 passed; 0 failed` (las 5 pruebas pedidas del contrato compartido + `phone_id_is_lowercase_sha256_hex_of_spki` + `trusted_only_rejects_when_lookup_fails`, esta última agregada para cubrir "error de lookup falla cerrado" del punto 5 del contrato, no exigida por nombre pero sí por comportamiento).
   - `cargo fmt -- --check`: sin diffs (tras aplicar `cargo fmt` una vez sobre los dos archivos nuevos).
   - **Full**: `cargo test` → 181 tests en total (unit + los 21 binarios de integración de la crate), 0 fallos.
   - **Líneas cambiadas** (sin `Cargo.lock`): 455 — Cargo.toml +1, `lib.rs` +5, `trusted_phone_store.rs` +31, `phone_client_cert_verifier.rs` +240 (nuevo), `phone_client_cert_verifier_test.rs` +178 (nuevo). Supera la estimación (~350) y la heurística de ~400: el trait `ClientCertVerifier` de rustls exige 7 métodos más un trait de lookup propio (con su tipo de error y dos implementaciones: `FileTrustedPhoneStore` real y fakes de test), y la matriz RED pedida cubre ambas políticas y el puente con el store en disco. No se recortaron pruebas, comentarios ni el doc de "por qué se ignoran intermediates/fechas/emisor" para entrar en la heurística.
   - **Cambio adicional no listado explícitamente pero necesario**: se agregó `FileTrustedPhoneStore::phone_trust_snapshot` (una sola lectura bajo un único `load_records()`) porque combinar `trusted_identity` + `is_revoked` en dos llamadas separadas corre una carrera revoke-vs-lectura entre un `trust`/`revoke` concurrente y las dos lecturas independientes.
   - Commit: `feat(desktop): verify phone TLS client certificates`.

   ### Revisión nativa m1

   Lineage `review-162caf1bf8856ada`, 1 lente (reliability). Resultado: **aprobada**, sin corrección, acknowledgement con autoridad `burned`. Hallazgos no bloqueantes y su disposición:

   - WARNING `R3-untested-key-mismatch` — el guard `TrustedOnly` de igualdad exacta `public_key == SPKI` no tenía test directo. Atendido en m1b (`trusted_only_rejects_trusted_phone_with_different_public_key`, characterization).
   - WARNING `R3-snapshot-first-match` — `load_records` no rechaza registros duplicados con el mismo `phone_id`, y `phone_trust_snapshot` usaba `find()` (primer match), por lo que un duplicado no revocado anterior podía ganarle a uno revocado posterior (fail open ante un archivo inconsistente). Atendido en m1b (fix + RED/GREEN).
   - SUGGESTION `R3-weak-error-assertions` — los tests de rechazo no-P256 y de fallo de lookup sólo afirmaban `is_err()`. Atendido en m1b (assertions fortalecidas a `BadEncoding` y `General(_)`).
   - SUGGESTION `R3-lookup-error-discarded` — el mensaje `General` del error de lookup descartaba el texto del error subyacente. Atendido en m1b (`error.to_string()`).
   - SUGGESTION `R3-temp-file-leak-on-panic` — `save_records` podía dejar un `.tmp` huérfano si fallaba entre el `write` y el `rename`. Atendido en m1b (`TempFileGuard`, drop guard).

   Las 5 advertencias quedan resueltas por el commit m1b (ninguna requirió cambios fuera de su alcance).

   ### Evidencia m1b

   - **RED observado** (`trusted_only_rejects_revoked_phone_despite_earlier_duplicate_trusted_record`; store con dos registros para el mismo `phone_id` -- primero `trusted` con la SPKI real, segundo `revoked` con otra clave -- escrito directamente en el formato real en disco, sin pasar por `trust`/`revoke`):

     ```
     thread 'trusted_only_rejects_revoked_phone_despite_earlier_duplicate_trusted_record' panicked at tests/phone_client_cert_verifier_test.rs:144:5:
     expected Err(InvalidCertificate(Revoked)), got Ok(ClientCertVerified(()))
     ```

     En el mismo run, RED observado también en `trusted_only_rejects_when_lookup_fails` tras fortalecer su assertion (el mensaje `General` no incluía el texto del error de lookup):

     ```
     thread 'trusted_only_rejects_when_lookup_fails' panicked at tests/phone_client_cert_verifier_test.rs:115:13:
     expected the lookup error's Display text in the General message, got "trusted phone lookup failed"
     ```

   - **GREEN**: `phone_trust_snapshot` ahora considera TODOS los registros que matchean `phone_id` (antes sólo el primero vía `find()`): si alguno está revocado devuelve `Revoked` sin importar el orden; si ninguno está revocado pero difieren en `public_key`, devuelve `TrustedPhoneStoreError::CorruptStore` (fail closed, no elige uno arbitrariamente); si no, `Trusted`/`Unknown` como antes -- `trust`/`revoke`/`is_revoked`/`trusted_identity` no cambiaron. El error de lookup en `PhoneClientCertVerifier::verify_client_cert` ahora usa `error.to_string()` (el `Display` de `TrustedPhoneLookupError` ya antepone "trusted phone lookup failed: ", sin duplicarlo). `save_records` usa un `TempFileGuard` (drop guard) que borra el `.tmp` si falla entre el `write` y el `rename`.
   - Test agregado `trusted_only_rejects_trusted_phone_with_different_public_key`: characterization, pasó sin necesitar el fix (el guard de igualdad de clave ya existía desde m1); documentado así en el propio test en vez de inventar un RED que no existe.
   - **GREEN focused**: `cargo test --test phone_client_cert_verifier_test` → `test result: ok. 9 passed; 0 failed` (7 previas + 2 nuevas). `cargo test --test trusted_phone_store_test` → `test result: ok. 7 passed; 0 failed` (sin cambios; confirma que el drop guard no rompe el camino feliz de `save_records`).
   - `cargo fmt -- --check`: sin diffs (tras `cargo fmt` sobre `tests/phone_client_cert_verifier_test.rs`).
   - **Full**: `cargo test` → 183 tests en total (181 + 2 nuevos), 0 fallos.
   - **Líneas cambiadas**: 174 -- `trusted_phone_store.rs` +59/-8, `phone_client_cert_verifier.rs` +1/-1, `phone_client_cert_verifier_test.rs` +98/-7. Dentro de la heurística de ~400.
   - Commit: `fix(desktop): fail closed on duplicate phone trust records`.
2. [ ] m2 — Client auth obligatorio en `usb_tls_pairing_proof`: `with_client_cert_verifier` con política `Pairing` en lugar de `.with_no_client_auth()`; `complete_handshake_and_pairing_proof` devuelve el stream vivo y el candidato `{phone_id, spki}` leído con `peer_certificates()`; el helper stdio se adapta al nuevo tipo de retorno y, al terminar, reporta `phone_id=<hex>` por stderr. RED: `pairing_proof_returns_client_spki_with_live_stream`, `pairing_handshake_rejects_client_without_certificate`. Estimado ~300 líneas.
3. [ ] m3 — Handshake de reconexión confiable sin QR: API nueva que acepta sólo teléfonos confiables. RED: `trusted_handshake_accepts_trusted_phone_without_qr`, `trusted_handshake_rejects_unknown_phone`, `trusted_handshake_rejects_revoked_phone`. Estimado ~250 líneas.
4. [ ] m4 — Confirmación explícita: el candidato de pairing no se persiste solo; `confirm(label, store)` persiste `TrustedPhoneIdentity`. RED: `pairing_candidate_is_not_persisted_without_confirmation`, `confirm_persists_trusted_phone_identity`. Estimado ~150 líneas.

Criterios: ningún camino USB real acepta clientes sin certificado; pairing liga el SPKI a la sesión que consumió el nonce; reconexión rechaza desconocidos y revocados en el handshake; nada se persiste sin confirmación explícita; `cargo fmt -- --check` y `cargo test` verdes.

## Progreso

Plan creado el 2026-09-28; m1 completada el 2026-09-28 (ver Evidencia m1); m2-m4 sin iniciar.
