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

1. [ ] m1 — Verificador de certificado de cliente. `rustls-webpki = "0.103"` explícito en `Cargo.toml` (hoy transitivo, `Cargo.lock` 0.103.15) para extraer el SPKI con `EndEntityCert::try_from(&cert)` + `subject_public_key_info()` (rustls-webpki `src/cert.rs:234`); validación de SPKI P-256 canónico; `phone_id`; `PhoneClientCertVerifier` (trait `rustls::server::danger::ClientCertVerifier`, client auth obligatorio) con política `Pairing` y `TrustedOnly` sobre un trait de lookup (`Trusted(spki)` / `Revoked` / `Unknown`) implementado por `FileTrustedPhoneStore` y por un fake. RED: `pairing_policy_accepts_canonical_p256_client_spki`, `rejects_non_p256_client_certificate` (cert P-384 de rcgen), `trusted_only_rejects_unknown_phone`, `trusted_only_rejects_revoked_phone`, `trusted_only_accepts_trusted_phone`. Estimado ~350 líneas.
2. [ ] m2 — Client auth obligatorio en `usb_tls_pairing_proof`: `with_client_cert_verifier` con política `Pairing` en lugar de `.with_no_client_auth()`; `complete_handshake_and_pairing_proof` devuelve el stream vivo y el candidato `{phone_id, spki}` leído con `peer_certificates()`; el helper stdio se adapta al nuevo tipo de retorno y, al terminar, reporta `phone_id=<hex>` por stderr. RED: `pairing_proof_returns_client_spki_with_live_stream`, `pairing_handshake_rejects_client_without_certificate`. Estimado ~300 líneas.
3. [ ] m3 — Handshake de reconexión confiable sin QR: API nueva que acepta sólo teléfonos confiables. RED: `trusted_handshake_accepts_trusted_phone_without_qr`, `trusted_handshake_rejects_unknown_phone`, `trusted_handshake_rejects_revoked_phone`. Estimado ~250 líneas.
4. [ ] m4 — Confirmación explícita: el candidato de pairing no se persiste solo; `confirm(label, store)` persiste `TrustedPhoneIdentity`. RED: `pairing_candidate_is_not_persisted_without_confirmation`, `confirm_persists_trusted_phone_identity`. Estimado ~150 líneas.

Criterios: ningún camino USB real acepta clientes sin certificado; pairing liga el SPKI a la sesión que consumió el nonce; reconexión rechaza desconocidos y revocados en el handshake; nada se persiste sin confirmación explícita; `cargo fmt -- --check` y `cargo test` verdes.

## Progreso

Plan creado el 2026-09-28; ninguna tarea iniciada.
