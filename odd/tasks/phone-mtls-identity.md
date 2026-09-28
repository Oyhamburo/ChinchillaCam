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

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` (focused: `--tests <FQCN>`). Último límite revisado nativamente: `11b05ea`.

### [ ] m1 — Certificado de cliente del teléfono en el canal USB TLS

Nuevo seam `PhoneTlsIdentity` (`fun keyManagers(): Array<KeyManager>`, `val subjectPublicKeyInfoDer: ByteArray`); parámetro de constructor `phoneTlsIdentity: PhoneTlsIdentity? = null` en `SslEngineUsbTlsChannel` (hoy `init(null, …)` en `SslEngineUsbTlsChannel.kt:274`); identidad PKCS12 de test con la receta `keytool` existente (`TlsFixture.create`). RED: `presentsPhoneClientCertificateWhenServerRequiresClientAuth` (servidor JSSE con `needClientAuth=true` y trust manager que captura el SPKI). Test de caracterización: sin identidad y con servidor que exige client auth, el handshake termina `Rejected` y cierra la sesión. Estimado ~250 líneas.

### [ ] m2 — Adaptador delgado `AndroidKeyStorePhoneTlsIdentity`

Genera o carga el alias, spec del contrato §4.1, `KeyManagerFactory.init(AndroidKeyStore, null)`. No es testeable en JVM: desvío TDD declarado; validación en dispositivo pendiente (M9); sin wiring de Activity. Estimado ~100 líneas.

### [ ] m3 — Interoperabilidad JSSE ↔ rustls con client auth obligatorio

Requiere autorización fresca del usuario para runtime entre worktrees. `SocketlessUsbPairingProofInteropTest` presenta identidad PKCS12 contra el helper desktop actualizado y compara el `phone_id` que el helper reporta por stderr con la huella local del SPKI. Estimado ~120 líneas.

Criterios: el canal presenta el certificado sólo si recibe identidad; sin identidad falla cerrado contra un desktop que exige client auth; el SPKI recibido por el servidor es el del teléfono; los tests existentes siguen verdes.

## Progreso

Plan creado el 2026-09-28; ninguna tarea iniciada.
