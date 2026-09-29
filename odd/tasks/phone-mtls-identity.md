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
2. [x] m2 — Client auth obligatorio en `usb_tls_pairing_proof`: `with_client_cert_verifier` con política `Pairing` en lugar de `.with_no_client_auth()`; `complete_handshake_and_pairing_proof` devuelve el stream vivo y el candidato `{phone_id, spki}` leído con `peer_certificates()`; el helper stdio se adapta al nuevo tipo de retorno y, al terminar, reporta `phone_id=<hex>` por stderr. RED: `pairing_proof_returns_client_spki_with_live_stream`, `pairing_handshake_without_client_certificate_does_not_consume_qr_nonce` (nombre final; el plan original decía `pairing_handshake_rejects_client_without_certificate`). Estimado ~300 líneas.

   ### Evidencia m2

   - **RED observado** (error de compilación: el test ya asume el nuevo tipo de retorno de `complete_handshake_and_pairing_proof`, que todavía era `StreamOwned<...>` directo):

     ```
     error[E0609]: no field `tls` on type `StreamOwned<rustls::ServerConnection, UsbTlsCiphertextStream<CrossedBulkIo>>`
       --> tests/usb_tls_pairing_proof_test.rs:93:18
        |
     93 |                 .tls;
        |                  ^^^ unknown field
        |
        = note: available fields are: `conn`, `sock`
     ```

     (4 errores E0609 en total: 2 por `.tls` y 2 por `.candidate`, uno de cada uno en `pairing_proof_returns_client_spki_with_live_stream` y en el test existente adaptado). Mismo patrón que el RED de m1 (error de compilación como evidencia válida, no un test que corre y falla en runtime).

   - **GREEN**: `server_config` usa `.with_client_cert_verifier(PhoneClientCertVerifier::pairing())` en lugar de `.with_no_client_auth()`. `complete_handshake_and_pairing_proof` devuelve `CompletedPairingProof<I> { tls, candidate }` (struct nombrado, no tupla); `candidate: PairedPhoneCandidate { phone_id, spki }` se lee con `paired_phone_candidate(&connection)` desde la MISMA `ServerConnection` vía `peer_certificates()`, reutilizando `phone_client_cert_verifier::accepted_client_spki` (ahora `pub(crate)`) para la misma extracción/validación de SPKI canónico P-256 que ya usó el verifier durante el handshake; un certificado de par ausente falla cerrado (`UsbTlsPairingProofError::Tls`, nunca un candidato por defecto), antes de tocar `CCP1`. `lib.rs` reexporta `CompletedPairingProof` y `PairedPhoneCandidate`. El helper stdio (`usb_pairing_proof_stdio_helper.rs`) escribe `phone_id=<64 hex minúscula>` por stderr (con `flush`) después de un proof exitoso; stdout sigue siendo sólo el stream binario. Los tests existentes cuyo cliente usaba `with_no_client_auth()` ahora presentan un certificado de teléfono real vía el helper compartido `tls_client`/`client_config` (ahora piden un `&DesktopTlsIdentity`), sin tocar sus assertions originales; se agregó `tls_client_without_certificate` sólo para el nuevo test negativo. `loopback_pairing_proof_server.rs` no se tocó (sigue en `.with_no_client_auth()`, test/interop-only, según contrato).
   - **GREEN focused**: `cargo test --offline --test usb_tls_pairing_proof_test` → `test result: ok. 8 passed; 0 failed` (6 previas adaptadas + 2 nuevas). `cargo test --offline --test phone_client_cert_verifier_test` → sigue en `9 passed; 0 failed` (sin regresión).
   - `cargo fmt -- --check`: sin diffs (tras `cargo fmt` sobre `phone_client_cert_verifier.rs` y `usb_tls_pairing_proof_test.rs`).
   - **Full**: `cargo test` → 185 tests en total (183 + 2 nuevos), 0 fallos. Incluye `usb_pairing_proof_stdio_helper_test` sin cambios (sigue verde: sólo cubre las líneas de prelude, no llega a completar un handshake).
   - **Líneas cambiadas**: 208 -- `usb_tls_pairing_proof.rs` +45/-5, `usb_tls_pairing_proof_test.rs` +126/-11, `phone_client_cert_verifier.rs` +6/-2, `usb_pairing_proof_stdio_helper.rs` +8/-1, `lib.rs` +3/-1. Dentro de la heurística de ~400.
   - **Tipo de retorno elegido**: struct nombrado `CompletedPairingProof<I: UsbBulkIo> { pub tls: StreamOwned<ServerConnection, UsbTlsCiphertextStream<I>>, pub candidate: PairedPhoneCandidate }`, con `PairedPhoneCandidate { pub phone_id: String, pub spki: Vec<u8> }` (`Debug, Clone, PartialEq, Eq`) -- struct en lugar de tupla, según lo pedido.
   - **Interop Android pendiente**: el test de interop opcional de Android contra este helper (aún no existe en este repo -- no se encontró ninguna referencia a `usb_pairing_proof_stdio_helper` ni a `phone_id=` bajo `android/`) va a fallar contra el helper reconstruido hasta la tarea m3 de Android (necesita autorización nueva del usuario); no se tocó el worktree de Android.
   - Commit: `feat(desktop): require phone client certificates for USB pairing`.

   ### Revisión nativa m1b+m2

   Lineage `review-3060bba2cff7b65a`, 1 lente (reliability). Resultado: **aprobada**, sin corrección, acknowledgement con autoridad `burned`. Hallazgos no bloqueantes:

   - WARNING `R3-corrupt-store-branch-untested` — la rama `CorruptStore` de `phone_trust_snapshot` (duplicados no revocados que difieren en `public_key`) no tenía test directo. Atendido en m2b.
   - WARNING `R3-stdio-phone-id-report-untested` — el contrato `phone_id=<hex>` por stderr del helper stdio no tenía test; el único test del helper cubre sólo el prelude. Atendido en m2b.
   - SUGGESTION `R3-no-cert-rejection-weak-assertion` — el test negativo de mTLS (`pairing_handshake_without_client_certificate_does_not_consume_qr_nonce`) sólo afirmaba `is_err()`. Atendido en m2b.
   - SUGGESTION `R3-temp-guard-cleanup-untested` — `TempFileGuard` (agregado en m1b) no tenía test directo de su `Drop`. Atendido en m2b.

   ### Evidencia m2b

   - **`R3-corrupt-store-branch-untested`**: test nuevo `trusted_only_rejects_client_when_store_has_conflicting_non_revoked_duplicate_keys` en `phone_client_cert_verifier_test.rs`, con un store escrito directamente en formato real (dos registros `trusted` para el mismo `phone_id`, distinta clave pública) -- a través de la API pública (`PhoneClientCertVerifier::trusted_only(...).verify_client_cert(...)`). Characterization: pasó sin necesitar fix (`phone_trust_snapshot` ya cubría este caso desde m1b); documentado así en el test.
   - **`R3-stdio-phone-id-report-untested`**: test nuevo `stdio_helper_completes_trusted_phone_handshake_and_reports_phone_id_on_stderr` en `usb_pairing_proof_stdio_helper_test.rs`. Levanta el binario real (`CARGO_BIN_EXE_usb_pairing_proof_stdio_helper`), lee las dos líneas de prelude byte a byte (sin `BufReader`, para no perder bytes binarios del stream AOA que sigue inmediatamente después), decodifica el QR (`nonce` y `trustMaterial` en base64 URL-safe sin padding), y actúa de teléfono: un `rustls::ClientConnection` pinneado por SPKI al `trustMaterial` del QR (verificador propio `PinnedSpkiServerCertVerifier`, equivalente de test al `PinnedDesktopTlsTrustManager` de Android) que presenta un certificado de cliente P-256, sobre frames AOA en el stdin/stdout del hijo (adaptador `ChildStdioBulkIo: UsbBulkIo` + `FramedUsbStream`/`UsbTlsCiphertextStream` reales de la crate). Envía un `CCP1` válido con el nonce del QR, lee la respuesta `status=0`, espera la salida del proceso con timeout acotado (mata el proceso si excede) y verifica que stderr sea exactamente una línea `phone_id=<phone_id_for_spki(spki del cliente)>`. RED observado: error de compilación propio del test (`E0716`, valor temporal de `subject_public_key_info()` liberado mientras seguía prestado) -- bug del test, no de producción; corregido introduciendo un `let` intermedio (mismo patrón que ya usa `phone_client_cert_verifier.rs`). GREEN al primer intento tras el fix.
   - **`R3-no-cert-rejection-weak-assertion`**: `pairing_handshake_without_client_certificate_does_not_consume_qr_nonce` en `usb_tls_pairing_proof_test.rs` ahora hace match sobre `UsbTlsPairingProofError::Tls(message)` y afirma que el mensaje contiene "no certificates". Mensaje exacto observado empíricamente (rustls 0.23.45, servidor, sin certificado de cliente): `Tls("peer sent no certificates")` (el `Display` de `rustls::Error::NoCertificatesPresented`, envuelto por `map_complete_io_error`).
   - **`R3-temp-guard-cleanup-untested`**: dos tests unitarios nuevos dentro de `trusted_phone_store.rs` (`#[cfg(test)] mod tests`, ya que `TempFileGuard` es privado): `temp_file_guard_removes_file_on_drop_unless_disarmed` y `temp_file_guard_leaves_file_when_disarmed`. Se testea el guard directamente (crear archivo, dropear el guard armado/desarmado) en vez de forzar un fallo real de `fs::rename` entre `write` y `rename` -- no hay seam de test entre esas dos llamadas, y simular el fallo a nivel de SO sería no determinístico o específico de plataforma. Characterization: ambos pasaron sin fix.
   - **GREEN focused**: `cargo test --offline --test phone_client_cert_verifier_test` → `10 passed; 0 failed` (9 + 1 nueva). `cargo test --offline --test usb_tls_pairing_proof_test` → sigue en `8 passed; 0 failed` (test existente fortalecido, no agregado). `cargo test --offline --test usb_pairing_proof_stdio_helper_test` → `2 passed; 0 failed` (1 + 1 nueva). `cargo test --offline --lib` → `2 passed; 0 failed` (los dos unitarios de `TempFileGuard`).
   - `cargo fmt -- --check`: sin diffs (tras `cargo fmt`, que reformateó `usb_pairing_proof_stdio_helper_test.rs`).
   - **Full**: `cargo test --offline` → 189 tests en total (185 + 4 nuevos: CorruptStore, helper end-to-end, 2 unitarios de `TempFileGuard`; el fortalecimiento del test negativo de mTLS no suma un test nuevo), 0 fallos.
   - **Líneas cambiadas**: 403 (+395/-8) -- `trusted_phone_store.rs` +55/-0, `phone_client_cert_verifier_test.rs` +58/-0, `usb_pairing_proof_stdio_helper_test.rs` +264/-4, `usb_tls_pairing_proof_test.rs` +18/-4. Supera la heurística de ~400 por 3 líneas: no se recortó nada para entrar en el límite; el grueso es el test end-to-end del helper (implementación completa de un cliente mTLS de test: verificador SPKI-pinned + adaptador `UsbBulkIo` sobre pipes de un proceso hijo), que atiende por sí solo la advertencia más severa (`R3-stdio-phone-id-report-untested`).
   - Sin cambios de producción: los cuatro hallazgos eran de cobertura de test, no de comportamiento; ningún archivo bajo `src/` (fuera del nuevo módulo de test dentro de `trusted_phone_store.rs`) cambió su lógica.
   - Commit: `test(desktop): cover phone trust and helper identity contracts`.
3. [x] m3 — Handshake de reconexión confiable sin QR: API nueva que acepta sólo teléfonos confiables. RED: `trusted_handshake_accepts_trusted_phone_without_qr`, `trusted_handshake_rejects_unknown_phone`, `trusted_handshake_rejects_revoked_phone`. Estimado ~250 líneas.

   ### Evidencia m3

   - **API elegida**: función libre `complete_trusted_phone_handshake<I: UsbBulkIo>(identity: &DesktopTlsIdentity, lookup: Arc<dyn TrustedPhoneLookup + Send + Sync>, stream: UsbTlsCiphertextStream<I>, timeout: Duration) -> Result<CompletedTrustedHandshake<I>, UsbTlsPairingProofError>`, agregada a `usb_tls_pairing_proof.rs` (no un módulo nuevo) para reutilizar sin fricción los helpers privados existentes `ensure_before_deadline`, `map_complete_io_error` y, sobre todo, `paired_phone_candidate` -- la MISMA función que m2 usa para leer `phone_id`/`spki` desde `peer_certificates()` de la conexión recién completada, así que el `phone_id` devuelto está re-derivado del certificado de esta conexión, nunca de un valor provisto por el llamador. `server_config` (política `Pairing`) se generalizó a `build_server_config(identity, verifier)`, reutilizado por ambas rutas con distinta política (`Pairing` para pairing, `PhoneClientCertVerifier::trusted_only(lookup)` para esta). `CompletedTrustedHandshake<I> { pub tls, pub phone_id: String }` -- sin `spki`, según lo pedido (sólo stream vivo + phone_id autenticado). Sin `CCP1` ni `PairingQrIssuer` en esta ruta.
   - **RED observado** (error de compilación, API aún no existía):

     ```
     error[E0432]: unresolved import `usb_probe::complete_trusted_phone_handshake`
       --> tests/usb_tls_trusted_session_test.rs:20:5
     error[E0425]: cannot find type `CompletedTrustedHandshake` in crate `usb_probe`
       --> tests/usb_tls_trusted_session_test.rs:163:24
     ```

   - **Desviación de proceso (no de producción)**: al implementar y correr los 4 tests por primera vez, 3 pasaron al primer intento (`trusted_handshake_rejects_unknown_phone`, `trusted_handshake_rejects_revoked_phone`, `trusted_handshake_rejects_trusted_phone_id_with_different_key`) pero `trusted_handshake_accepts_trusted_phone_without_qr` falló en runtime (`bulk read timed out`) por un bug del test, no de producción: el intercambio de datos de aplicación del lado servidor estaba escrito DESPUÉS de `server.join()` en vez de DENTRO del closure del hilo servidor, así que nadie leía/respondía mientras el teléfono esperaba (mismo patrón concurrente que ya usan los tests de m2, que este test no había seguido). Corregido moviendo la lectura+eco al closure del servidor; GREEN al reintentar.
   - **Mensajes de rechazo observados empíricamente** (rustls 0.23.45, vía `PhoneClientCertVerifier::trusted_only`, envueltos por `map_complete_io_error` en `UsbTlsPairingProofError::Tls`): desconocido y clave distinta con el mismo `phone_id` → `Tls("invalid peer certificate: UnknownIssuer")`; revocado → `Tls("invalid peer certificate: Revoked")`. Los tests hacen match sobre `UsbTlsPairingProofError::Tls(_)` (variante estructural) en vez de sobre el texto, ya que el texto es un detalle de implementación de rustls; los mensajes exactos quedan documentados aquí como evidencia observada.
   - `trusted_handshake_rejects_trusted_phone_id_with_different_key` (bonus explícitamente opcional en el plan): entra bien porque `phone_id` es sólo un string que `TrustedPhoneIdentity::new`/`store.trust` no ligan criptográficamente a `public_key` -- esa ligadura la hace el verificador en el handshake (`if public_key == spki`), no el store. El test confía un `phone_id` real con una clave pública DISTINTA (vía la API pública del store) y confirma que el handshake completo (no sólo `verify_client_cert` aislado, ya cubierto por `trusted_only_rejects_trusted_phone_with_different_public_key` en m1) también rechaza.
   - **GREEN focused**: `cargo test --offline --test usb_tls_trusted_session_test` → `test result: ok. 4 passed; 0 failed`. `cargo test --offline --test usb_tls_pairing_proof_test` → sigue en `8 passed; 0 failed` (sin regresión; comparte módulo de producción).
   - `cargo fmt -- --check`: sin diffs (tras `cargo fmt`).
   - **Full**: `cargo test --offline` → 193 tests en total (189 + 4 nuevos), 0 fallos.
   - **Líneas cambiadas**: 391 -- `lib.rs` +2/-1, `usb_tls_pairing_proof.rs` +56/-3, `usb_tls_trusted_session_test.rs` +329 (nuevo). Dentro de la heurística de ~400.
   - Commit: `feat(desktop): accept trusted phones on USB reconnection`.
4. [x] m4 — Confirmación explícita: el candidato de pairing no se persiste solo; `confirm(label, store)` persiste `TrustedPhoneIdentity`. RED: `pairing_candidate_is_not_persisted_without_confirmation`, `confirm_persists_trusted_phone_identity`. Estimado ~150 líneas.

   ### Evidencia m4

   - **API elegida**: método `PairedPhoneCandidate::confirm(&self, label: &str, store: &FileTrustedPhoneStore) -> Result<TrustedPhoneIdentity, PairedPhoneCandidateConfirmError>` en `usb_tls_pairing_proof.rs`. Construye `TrustedPhoneIdentity::new(phone_id, label, spki)` (validación del store) y llama `store.trust(...)`. Ningún paso de la ruta de pairing (m1-m2) ni de reconexión (m3) llama `store.trust` por su cuenta -- `confirm` es la ÚNICA vía de persistencia.
   - **Regla de revocación (conservadora, según el contrato §4.6 y el store de Android)**: `confirm` primero llama `store.is_revoked(phone_id)`; si es `true`, devuelve `Err(PairedPhoneCandidateConfirmError::PhoneRevoked)` SIN llamar `store.trust`, así que un `phone_id` revocado nunca se "des-revoca" con sólo confirmar de nuevo. Des-revocar queda fuera de alcance como acción explícita separada (documentado en el doc del tipo de error y aquí).
   - `PairedPhoneCandidateConfirmError` tiene dos variantes: `PhoneRevoked` y `Store(TrustedPhoneStoreError)` (con `From<TrustedPhoneStoreError>` para poder usar `?` limpio contra `is_revoked`/`TrustedPhoneIdentity::new`/`trust`, los tres ya devuelven `TrustedPhoneStoreError`).
   - **RED observado** (error de compilación, API aún no existía):

     ```
     error[E0432]: unresolved import `usb_probe::PairedPhoneCandidateConfirmError`
       --> tests/paired_phone_candidate_confirm_test.rs:21:65
     error[E0599]: no method named `confirm` found for struct `PairedPhoneCandidate` in the current scope
       --> tests/paired_phone_candidate_confirm_test.rs:52:31
     ```

     (3 apariciones de E0599 -- una por cada test que ya llamaba `candidate.confirm(...)`.)

   - **GREEN**: los 4 tests pasaron en el primer intento tras implementar (sin bugs de test esta vez, a diferencia de m3). `pairing_candidate_is_not_persisted_without_confirmation` hace pairing real (m1-m2) y verifica que un `FileTrustedPhoneStore` nuevo, jamás mencionado durante el pairing, ni tiene el `phone_id` ni su archivo llegó a crearse -- confirma por la negativa que ningún paso previo persiste nada. `confirm_persists_trusted_phone_identity` compara el resultado y el estado persistido contra un `TrustedPhoneIdentity` construido independientemente (mismo patrón de igualdad que usa el resto de la suite, ya que el tipo no expone getters). `confirm_refuses_revoked_phone` confía+revoca antes de confirmar y verifica que sigue revocado y sin registro confiable después del intento fallido. `confirmed_phone_reconnects_and_revoked_phone_is_rejected` encadena las tres tareas: pairing (m1-m2) → `confirm` (m4) → `complete_trusted_phone_handshake` (m3) acepta → `store.revoke` → `complete_trusted_phone_handshake` vuelve a intentarse y rechaza en el handshake (mismo patrón `Err(Tls(_))` de m3).
   - **GREEN focused**: `cargo test --offline --test paired_phone_candidate_confirm_test` → `test result: ok. 4 passed; 0 failed`. `cargo test --offline --test usb_tls_trusted_session_test` → sigue en `4 passed; 0 failed` (sin regresión). `cargo test --offline --test usb_tls_pairing_proof_test` → sigue en `8 passed; 0 failed` (sin regresión).
   - `cargo fmt -- --check`: sin diffs (tras `cargo fmt`).
   - **Full**: `cargo test --offline` → 197 tests en total (193 + 4 nuevos), 0 fallos.
   - **Líneas cambiadas**: 437 -- `lib.rs` +2/-1, `usb_tls_pairing_proof.rs` +56/-3, `paired_phone_candidate_confirm_test.rs` +375 (nuevo). Supera la heurística de ~400 por 37 líneas: no se recortó nada para entrar en el límite. El archivo de test es nuevo y, como cada test de integración es una unidad de compilación independiente, no puede reutilizar los helpers ya escritos en `usb_tls_pairing_proof_test.rs`/`usb_tls_trusted_session_test.rs` (transporte en memoria `CrossedBulkIo`/`BulkPipe`, cliente TLS de teléfono, handshake de pairing completo) -- duplicarlos ahí es el patrón ya establecido en esta crate, y los 4 tests pedidos (incluido el end-to-end que encadena m1-m2-m3-m4) exigen tenerlos completos.
   - Commit: `feat(desktop): persist phones only after explicit confirmation`.

Criterios: ningún camino USB real acepta clientes sin certificado; pairing liga el SPKI a la sesión que consumió el nonce; reconexión rechaza desconocidos y revocados en el handshake; nada se persiste sin confirmación explícita; `cargo fmt -- --check` y `cargo test` verdes.

## Progreso

Plan creado el 2026-09-28; m1 completada el 2026-09-28 (ver Evidencia m1); revisión nativa m1 aprobada y sus 5 hallazgos atendidos en m1b el 2026-09-28 (ver Revisión nativa m1 y Evidencia m1b); m2 completada el 2026-09-28 (ver Evidencia m2); revisión nativa m1b+m2 aprobada y sus 4 hallazgos atendidos en m2b el 2026-09-28 (ver Revisión nativa m1b+m2 y Evidencia m2b); m3 completada el 2026-09-28 (ver Evidencia m3); m4 completada el 2026-09-28 (ver Evidencia m4). Las 4 tareas del plan (m1-m4) están completas; queda pendiente la revisión nativa de m3+m4 (aún no ejecutada en esta sesión).
