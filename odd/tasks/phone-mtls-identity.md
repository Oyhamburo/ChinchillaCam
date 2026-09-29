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

### [x] m1 — Certificado de cliente del teléfono en el canal USB TLS

Nuevo seam `PhoneTlsIdentity` (`fun keyManagers(): Array<KeyManager>`, `val subjectPublicKeyInfoDer: ByteArray`); parámetro de constructor `phoneTlsIdentity: PhoneTlsIdentity? = null` en `SslEngineUsbTlsChannel` (hoy `init(null, …)` en `SslEngineUsbTlsChannel.kt:274`); identidad PKCS12 de test con la receta `keytool` existente (`TlsFixture.create`). RED: `presentsPhoneClientCertificateWhenServerRequiresClientAuth` (servidor JSSE con `needClientAuth=true` y trust manager que captura el SPKI). Test de caracterización: sin identidad y con servidor que exige client auth, el handshake termina `Rejected` y cierra la sesión. Estimado ~250 líneas.

#### Evidencia m1

- **RED observado** (falla de compilación, no de ejecución, porque el seam todavía no existía): al agregar sólo el test y el harness de soporte (sin tocar producción), `:android:usb-probe:compileDebugUnitTestKotlin` falló con, entre otras:
  ```
  e: .../SslEngineUsbTlsChannelTest.kt:252:45 Cannot find a parameter with this name: phoneTlsIdentity
  e: .../SslEngineUsbTlsChannelTest.kt:449:75 Unresolved reference: PhoneTlsIdentity
  e: .../SslEngineUsbTlsChannelTest.kt:450:9 'keyManagers' overrides nothing
  e: .../SslEngineUsbTlsChannelTest.kt:451:9 'subjectPublicKeyInfoDer' overrides nothing
  BUILD FAILED
  ```
- **GREEN focused** (tras crear `PhoneTlsIdentity.kt` y cablear el parámetro/ctor en `SslEngineUsbTlsChannel`): `:android:usb-probe:testDebugUnitTest --tests dev.chinchillacam.usbprobe.SslEngineUsbTlsChannelTest --rerun-tasks` → `BUILD SUCCESSFUL`, 14 tests, 0 fallas (11 preexistentes + 3 nuevos). Repetido 5 veces consecutivas sin flakiness.
  - Se descubrió y corrigió una carrera real de TLS 1.3 durante el GREEN: el cliente termina su propio handshake al enviar su `Finished`, sin esperar al servidor; el servidor todavía puede hacer un `wrap` adicional (p. ej. `NewSessionTicket` post-handshake) y escribirlo en el pipe justo cuando el test cierra el lado cliente. Se resolvió en el test ordenando `server.join(...)` antes de `result.close()`. El mismo síntoma (`IOException: Pipe closed`, no capturado) aparece de fondo en `completesPinnedClientHandshakeOverUsbCiphertextFrames` (test preexistente, no modificado) en la misma corrida — no bloquea (esa prueba sólo verifica `!isAlive`), se deja constancia para una futura limpieza fuera de alcance de m1.
- **Caracterización** (`rejectsHandshakeWhenServerRequiresClientAuthWithoutPhoneIdentity`): no pasó "gratis"; requirió investigación. Con TLS 1.3 (el que negocian por defecto ambos peers), JSSE deja el rechazo de un certificado de cliente vacío a discreción del servidor (RFC 8446 §4.4.2.4) y el `SSLEngine` sigue adelante sin nunca invocar el trust manager — verificado empíricamente (el cliente terminaba `Authenticated`, no `Rejected`). Además, aunque el servidor rechazara post-handshake, la asimetría de TLS 1.3 hace que el cliente ya haya retornado antes de que el servidor pueda reaccionar. La prueba fuerza `TLSv1.2` sólo del lado servidor de este test: ahí JSSE aborta él mismo con `SSLHandshakeException: Empty client certificate chain` en cuanto ve la cadena vacía, sin necesidad de lógica extra en el trust manager, y el cliente sí observa `Rejected` (cierra la sesión) porque en TLS 1.2 el `Finished` del servidor es el último mensaje del flujo. Pasó tras ese ajuste, estable en 5 corridas.
- **Test opcional agregado** (`keyManagersFailureRejectsHandshakeFailClosed`, barato: sin hilo servidor): confirma que si `PhoneTlsIdentity.keyManagers()` lanza, `handshake()` retorna `Rejected` y cierra la sesión (ya cubierto por el `runCatching` existente alrededor de `newEngine()`, sin cambios adicionales de producción).
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo (agregando todos los XML de `testDebugUnitTest`): 364 tests, 2 skipped (los interop opt-in), 0 failures, 0 errors (baseline previo: 361/2/0).
- **Líneas cambiadas**: 163 (additions+deletions) — `PhoneTlsIdentity.kt` nuevo (16), `SslEngineUsbTlsChannel.kt` (+2/-1), `SslEngineUsbTlsChannelTest.kt` (+140/-4).
- **Commit**: `feat(android): present phone TLS client certificate`.

#### Revisión nativa m1

- Lineage `review-37abf23d14631701`, 4 lentes (R1 riesgo, R2 legibilidad, R3 confiabilidad, R4 resiliencia). Resultado: **aprobada sin corrección**; acknowledgement con autoridad `burned`.
- Hallazgos no bloqueantes y el commit que los resuelve:
  - WARNING `R3-tls13-no-identity-unproved` (el caso sin identidad sólo está probado con TLS 1.2) — resuelto en **m1b**.
  - SUGGESTION `R4-001` (bajo TLS 1.3 por defecto el cliente retorna `Authenticated` aunque el servidor exija client auth; el rechazo debe surgir fail-closed en la primera lectura — no probado) — resuelto en **m1b**, mismo test que el anterior.
  - WARNING `R2-server-error-optional-assertion` (`serverError.get()?.let { ... }` pasa si `serverError` es null, es decir cuando el servidor NO rechazó) — resuelto en **m1b**.
  - SUGGESTION `R2-throwing-identity-desktop-spki` (`ThrowingPhoneTlsIdentity` reutilizaba el SPKI del desktop como SPKI del teléfono) — resuelto en **m1b**.
  - SUGGESTION `R3-test-session-cleanup` (los dos tests nuevos con hilo servidor no cerraban `pair` en `finally`) — resuelto en **m1b**.

#### Evidencia m1b

- **Caracterización con investigación** (`tls13ServerRequiringClientAuthRejectsMissingPhoneIdentityOnFirstRead`): no pasó a la primera. La hipótesis inicial —calcada del texto de m1 ("el SSLEngine sigue adelante sin nunca invocar el trust manager" bajo TLS 1.3)— resultó **falsa** para este JDK. Primera corrida:
  ```
  javax.net.ssl.SSLHandshakeException: Empty client certificate chain
      at sun.security.ssl.CertificateMessage$T13CertificateConsumer.onConsumeCertificate(...)
      ...
      at SslEngineUsbTlsChannelTest.serverHandshake(...)
  ```
  La excepción no salió del chequeo especulativo `engine.session.peerCertificates` (nunca se alcanzó); salió directo de `serverHandshake(...)`. Esto confirma dos cosas: (1) el cliente sí termina `Authenticated` (la asimetría de m1 es real, la aserción sobre el resultado del handshake pasó antes de que la excepción llegara al hilo principal); (2) el lado servidor no "sigue en silencio" — lanza el mismo `SSLHandshakeException: Empty client certificate chain` que TLS 1.2, sólo que TLS 1.3 lo hace vía `T13CertificateConsumer`/delegated task, demasiado tarde para que el cliente lo vea dentro de su propio `handshake()`. Se reescribió el test para afirmar exactamente eso (se descartó el chequeo especulativo de `peerCertificates`) y se dejó constancia en comentarios. Segunda corrida: `BUILD SUCCESSFUL`.
- **`readApplicationData` reporta con excepciones, no con un resultado tipado**: a diferencia de `handshake()` (que retorna el sealed `SslEngineUsbTlsHandshakeResult`), el canal establecido señala fallas lanzando (`IllegalStateException`, p. ej. `"USB TLS ciphertext read failed: EofEmpty"` o `"TLS engine closed during application read"`); nunca retorna bytes de aplicación en el camino de falla. El test nuevo cierra el lado servidor de la sesión cruda en su `finally` (no hay alerta TLS explícita: la excepción del servidor aborta su propio loop de handshake antes de poder volver a hacer `wrap()`), lo que hace que la primera lectura del cliente observe fin de stream y lance.
- **Focused** (`--tests dev.chinchillacam.usbprobe.SslEngineUsbTlsChannelTest --rerun-tasks`), 3 corridas consecutivas tras la corrección: `BUILD SUCCESSFUL`, 15 tests, 0 fallas cada vez (14 preexistentes + 1 nuevo).
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo (agregando todos los XML de `testDebugUnitTest`): 365 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (baseline m1: 364/2/0).
- **Líneas cambiadas**: 143 (113 adiciones + 30 eliminaciones), un solo archivo — `SslEngineUsbTlsChannelTest.kt`: imports (+2), dos tests existentes envueltos en `try/finally` con `pair.close()` + aserción de error del servidor reforzada (no vacuously-true), un test nuevo, `ThrowingPhoneTlsIdentity` con SPKI propio (fixture `phone-key-manager-failure` separada de la del desktop), `serverHandshake` ahora retorna el `SSLEngine` (sin cambio de comportamiento para los 4 call sites existentes, que ya ignoraban el valor de retorno).
- **Commit**: `test(android): prove phone client auth rejection fails closed`.

### [x] m2 — Adaptador delgado `AndroidKeyStorePhoneTlsIdentity`

Genera o carga el alias, spec del contrato §4.1, `KeyManagerFactory.init(AndroidKeyStore, null)`. No es testeable en JVM: desvío TDD declarado; validación en dispositivo pendiente (M9); sin wiring de Activity. Estimado ~100 líneas.

#### Evidencia m2

- **Desvío TDD declarado explícitamente** (no se inventa RED/GREEN): `AndroidKeyStorePhoneTlsIdentity` usa `KeyStore.getInstance("AndroidKeyStore")`, un provider de plataforma que no existe en una JVM de escritorio — no lo puede ejercitar `testDebugUnitTest`. La única prueba disponible en este entorno es que el módulo compile, empaquete y pase lint; el comportamiento real (generación de la clave, autenticación de cliente TLS con `DIGEST_NONE`, disponibilidad de TLS 1.3 sólo desde API 29+) queda **pendiente de validación física (M9)**.
- **Implementación**: adaptador delgado que implementa `PhoneTlsIdentity`. Carga `KeyStore.getInstance("AndroidKeyStore").apply { load(null) }`; si el alias `chinchillacam-phone-tls-v1` no existe, lo genera con `KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")` + `KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(DIGEST_NONE, DIGEST_SHA256).setCertificateSubject(X500Principal("CN=ChinchillaCam Phone"))`. `keyManagers()` delega en `KeyManagerFactory.getInstance(getDefaultAlgorithm()).apply { init(keyStore, null) }.keyManagers`. `subjectPublicKeyInfoDer` toma `publicKey.encoded` del certificado del alias y lo valida como P-256 canónico con `DesktopTlsIdentityMaterial.validate(...)`, lanzando una excepción clara (fail closed) si no lo es. Sin exportar la clave privada, sin loguear material de clave, sin wiring de Activity, sin dependencias nuevas.
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. `compileDebugKotlin` compila el archivo nuevo contra los stubs del SDK de Android sin errores ni warnings propios; `lintDebug` no reporta ningún hallazgo sobre `AndroidKeyStorePhoneTlsIdentity.kt` (0 coincidencias en `lint-results-debug.xml`). Total de tests unitarios del módulo sin cambios respecto a m1b (365 tests, 2 skipped, 0 failures, 0 errors) porque ningún test de JVM ejercita este archivo.
- **Líneas cambiadas**: 65 (archivo nuevo) — `AndroidKeyStorePhoneTlsIdentity.kt`.
- **Commit**: `feat(android): add Android Keystore phone TLS identity`.

#### Revisión nativa m1b+m2

- Lineage `review-48052d68d919f710`, 4 lentes (R1 riesgo, R2 legibilidad, R3 confiabilidad, R4 resiliencia). Resultado: **aprobada sin corrección**; acknowledgement con autoridad `burned`.
- Hallazgos no bloqueantes y dónde se resuelven (todos en **m2b**, este cambio):
  - WARNING `R4-keystore-alias-no-recovery` + WARNING `R3-keystore-alias-unrecoverable-state` (si el alias existe pero es inservible — sin certificado por una generación interrumpida, no es una entrada de clave privada, o una clave no canónica/no P-256 — la construcción fallaba para siempre, sin recuperación) — resuelto en el commit `fix(android): recover unusable phone TLS keystore alias`: `KeyStorePhoneTlsIdentity.ensure()` borra la entrada y regenera una vez; si sigue inservible, falla cerrado con una excepción clara en lugar de reintentar indefinidamente.
  - SUGGESTION `R4-keystore-check-then-generate-race` (`containsAlias` + `generate` no es atómico) — resuelto en el mismo commit: `ensure()` corre bajo un lock de proceso compartido por todas las instancias de la clase.
  - SUGGESTION `R1-001` (el `KeyManager` sobre todo el keystore podía presentar una clave distinta a `subjectPublicKeyInfoDer` si se guardara otra clave de firma) — resuelto en el mismo commit: `AliasPinnedX509KeyManager` sólo ofrece el alias pinneado (chain/clave privada sólo para ese alias; `chooseClientAlias`/`chooseEngineClientAlias` sólo cuando se pide tipo `"EC"`; métodos de servidor devuelven `null`).
  - SUGGESTION `R2-init-order-dependency` (orden implícito de declaración entre el `init` que asegura el alias y la propiedad que lee el certificado) — resuelto en el mismo commit: `regenerated`, `subjectPublicKeyInfoDer` y el `KeyManager` se asignan juntos desde un único resultado de `ensure()` computado dentro de un solo bloque `init`.
  - SUGGESTION `R2-desktop-validator-for-phone-spki` (aclarar que el validador es agnóstico del par) — resuelto en el mismo commit: KDoc nuevo en `DesktopTlsIdentityMaterial` sin renombrar el tipo público.
  - WARNING `R2-dead-serverhandshake-return` (retorno de `serverHandshake` sin uso en los 4 call sites) — resuelto en el commit `test(android): tighten TLS 1.3 client auth assertions` (ver Evidencia m2b, continuación).
  - WARNING `R2-tls13-name-unasserted-protocol` + SUGGESTION `R3-tls13-test-protocol-and-exception-unasserted` (el test de TLS 1.3 nunca fuerza ni afirma el protocolo negociado) — resuelto en el mismo commit de continuación.
  - SUGGESTION `R2-broad-catch-first-read` (catch de `Exception` genérico en la primera lectura) — resuelto en el mismo commit de continuación.
  - SUGGESTION `R2-evidence-import-count` (la evidencia de m1b dice "imports (+2)"; el diff real de `8e47482` agregó 1 import, `SSLHandshakeException`, verificado con `git show 8e47482 -- .../SslEngineUsbTlsChannelTest.kt`) — corregido en el mismo commit de continuación (ver también la corrección de texto más abajo).

#### Evidencia m2b

- **RED observado** (falla de compilación, no de ejecución, porque `KeyStorePhoneTlsIdentity` todavía no existía): al agregar sólo `KeyStorePhoneTlsIdentityTest.kt` (sin tocar producción), `:android:usb-probe:compileDebugUnitTestKotlin` falló con, entre otras:
  ```
  e: .../KeyStorePhoneTlsIdentityTest.kt:25:24 Unresolved reference: KeyStorePhoneTlsIdentity
  e: .../KeyStorePhoneTlsIdentityTest.kt:47:24 Unresolved reference: KeyStorePhoneTlsIdentity
  e: .../KeyStorePhoneTlsIdentityTest.kt:64:24 Unresolved reference: KeyStorePhoneTlsIdentity
  e: .../KeyStorePhoneTlsIdentityTest.kt:82:24 Unresolved reference: KeyStorePhoneTlsIdentity
  e: .../KeyStorePhoneTlsIdentityTest.kt:101:24 Unresolved reference: KeyStorePhoneTlsIdentity
  BUILD FAILED
  ```
- **Investigación** (la firma de ejemplo del contrato no alcanzaba): medido empíricamente con un script Java standalone contra el mismo JDK (Homebrew OpenJDK 17.0.16, el que resuelve `java.home` en las corridas de Gradle) que `KeyStore.getKey(alias, null)` y `KeyStore.getEntry(alias, null)` lanzan `UnrecoverableKeyException` sobre un PKCS12 con contraseña real, incluso cuando `-storepass` y `-keypass` coinciden (`getKey(null)` → "Cannot read the array length because password is null"; `getEntry(null)` → "requested entry requires a password"; ambos funcionan con la contraseña real). `"AndroidKeyStore"` en cambio exige protección `null` (no soporta contraseñas; así lo asume el código de m2 ya aprobado). Se agregó un cuarto parámetro opcional `keyProtection: KeyStore.ProtectionParameter? = null` a `KeyStorePhoneTlsIdentity`: `AndroidKeyStorePhoneTlsIdentity` no lo pasa (preserva el `null` de siempre), los tests JVM pasan `KeyStore.PasswordProtection`. Desviación menor respecto a la firma de ejemplo del diseño, documentada acá en vez de asumida sin verificar.
- **GREEN focused** (`--tests dev.chinchillacam.usbprobe.KeyStorePhoneTlsIdentityTest --rerun-tasks`), 3 corridas consecutivas: `BUILD SUCCESSFUL`, 5 tests, 0 fallas cada vez (`generatesKeyWhenAliasMissing`, `reusesValidAliasWithoutRegenerating`, `regeneratesWhenAliasHoldsNonP256Key`, `regeneratesWhenAliasHasNoPrivateKeyEntry`, `pinnedKeyManagerPresentsOnlyPinnedAliasWhenStoreHasOtherEcKeys`), verde ya en el primer intento tras la investigación anterior.
- **Implementación**: `KeyStorePhoneTlsIdentity` (core JVM-testeable) + `AliasPinnedX509KeyManager` (privada, en el mismo archivo) — ver Revisión nativa m1b+m2 arriba para el detalle de qué resuelve cada pieza. `AndroidKeyStorePhoneTlsIdentity` pasa a ser un adaptador delgado que sólo aporta `KeyStore.getInstance("AndroidKeyStore")` y el generador `KeyGenParameterSpec` (sin cambio de spec); expone `regenerated` además de la interfaz `PhoneTlsIdentity`. El comportamiento específico del provider real (generación de clave, TLS 1.3 desde API 29+) sigue pendiente de validación física (M9); lo que antes no era JVM-testeable en absoluto ahora sí lo es en su lógica de ensure/regenerar/pinning.
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo: 370 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (baseline m2: 365/2/0). `lintDebug` no reporta hallazgos sobre ninguno de los archivos tocados.
- **Líneas cambiadas**: 434 (407 adiciones + 27 eliminaciones) — `KeyStorePhoneTlsIdentity.kt` nuevo (178, incluye `AliasPinnedX509KeyManager`), `KeyStorePhoneTlsIdentityTest.kt` nuevo (193), `AndroidKeyStorePhoneTlsIdentity.kt` (+25/-27, ahora delega), `DesktopTlsIdentityMaterial.kt` (+11, sólo KDoc, sin cambio de comportamiento).
- **Commit**: `fix(android): recover unusable phone TLS keystore alias`.

#### Evidencia m2b (continuación: calidad de tests)

- **Correcciones en `SslEngineUsbTlsChannelTest.kt`** (sin tests nuevos; ver Revisión nativa m1b+m2 arriba):
  - `serverHandshake` deja de declarar `: SSLEngine` y de retornar el engine: los 4 call sites ya lo ignoraban (`R2-dead-serverhandshake-return`).
  - `tls13ServerRequiringClientAuthRejectsMissingPhoneIdentityOnFirstRead` ahora fuerza `forceProtocol = "TLSv1.3"` del lado servidor (antes dependía de la negociación por defecto) y afirma `result.protocol == "TLSv1.3"` del lado cliente (`R2-tls13-name-unasserted-protocol`, `R3-tls13-test-protocol-and-exception-unasserted`).
  - El catch de la primera lectura de aplicación en ese mismo test pasa de `catch (Exception)` a `catch (IllegalStateException)`, afirmando que el mensaje contiene `"USB TLS ciphertext read failed: EofEmpty"` (confirmado leyendo `UsbTlsCiphertextIoAdapter.read` → `UsbTlsCiphertextReadResult.EofEmpty` y `SslEngineUsbTlsEstablishedChannel.readCiphertext`, que arma ese mensaje exacto) en vez de aceptar cualquier excepción (`R2-broad-catch-first-read`).
  - Corrección de texto (`R2-evidence-import-count`): la Evidencia m1b de arriba decía "imports (+2)"; `git show 8e47482 -- .../SslEngineUsbTlsChannelTest.kt` muestra un solo import agregado (`SSLHandshakeException`). Se deja esta nota en vez de reescribir la evidencia original de m1b, para no alterar un registro histórico ya commiteado.
- **Focused** (`--tests dev.chinchillacam.usbprobe.SslEngineUsbTlsChannelTest --rerun-tasks`), 3 corridas consecutivas: `BUILD SUCCESSFUL`, 15 tests, 0 fallas cada vez (sin cambio en la cantidad de tests respecto a m1b).
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo: 370 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (sin cambio respecto al commit anterior: estos ajustes son sobre aserciones de tests existentes, no tests nuevos). `lintDebug` no reporta hallazgos sobre el archivo tocado.
- **Líneas cambiadas**: 44 (29 adiciones + 15 eliminaciones), un solo archivo — `SslEngineUsbTlsChannelTest.kt`.
- **Commit**: `test(android): tighten TLS 1.3 client auth assertions`.

Con esto, los once hallazgos no bloqueantes de la revisión nativa m1b+m2 (lineage `review-48052d68d919f710`, listados arriba) quedan resueltos.

#### Evidencia m2c

- **Defecto encontrado en readback del orquestador** (revisión estructural de `8ca23c4`): en `usableEntry(...)` (~líneas 115-133 antes del fix), un `catch (_: GeneralSecurityException) { return null }` envolvía toda la lectura de la entrada existente. `ensure(...)` trata `null` como "inservible" y, si el alias ya existía, hace `keyStore.deleteEntry(alias)` y regenera. Un error de lectura TRANSITORIO (p. ej. `UnrecoverableKeyException`/`KeyStoreException` del keystore) caía en la misma rama que una invalidez de contenido genuina: borraba la clave de largo plazo del teléfono e invalidaba en silencio la confianza de todos los desktops, sin que hubiera contenido corrupto que lo justificara.
- **RED observado** (test `readFailureDoesNotDeleteOrRegenerateExistingKey`: keystore PKCS12 con una entrada EC P-256 válida bajo el alias, construcción con un `KeyStore.PasswordProtection` incorrecto — por lo que `getEntry` lanza `UnrecoverableKeyException`, según la Investigación de m2b): sobre el código anterior al fix, falla con:
  ```
  java.lang.AssertionError: expected:<0> but was:<1>
      at dev.chinchillacam.usbprobe.KeyStorePhoneTlsIdentityTest.readFailureDoesNotDeleteOrRegenerateExistingKey(KeyStorePhoneTlsIdentityTest.kt:81)
  ```
  Es decir: el generador SÍ fue invocado (`invocations == 1`) — el código anterior borró la entrada original y regeneró una nueva, y como la contraseña incorrecta también impide leer la clave recién generada, la construcción terminaba lanzando `IllegalStateException("... still unusable after regeneration")` en lugar de propagar el `UnrecoverableKeyException` original. La clave original ya estaba destruida en ese punto.
- **GREEN**: se quitó el `try/catch` de `usableEntry`; ahora cualquier excepción durante la lectura (`entryInstanceOf`/`getEntry`) se propaga sin pasar por el `?: return null`, así que ni el `deleteEntry` ni el `generateKeyPair()` de `ensure(...)` se alcanzan. Sólo siguen devolviendo `null` (inservible, dispara borrar+regenerar) los casos de invalidez de contenido: alias ausente o no es `PrivateKeyEntry`, sin certificado, certificado no-X.509, o SPKI rechazado por `DesktopTlsIdentityMaterial.validate`. Se quitó el import ahora no usado de `GeneralSecurityException` en el archivo de producción. KDoc de la clase y de `usableEntry` actualizado para dejar la regla explícita. Sin cambios en `AndroidKeyStorePhoneTlsIdentity` (no lo requería, confirmado por lectura) ni en el resto de `ensure`/el candado de proceso/`regenerated`/`AliasPinnedX509KeyManager`.
- **Focused** (`--tests dev.chinchillacam.usbprobe.KeyStorePhoneTlsIdentityTest --rerun-tasks`), 3 corridas consecutivas: `BUILD SUCCESSFUL`, 6 tests, 0 fallas cada vez (5 preexistentes + 1 nuevo).
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. Total del módulo: 371 tests, 2 skipped (interop opt-in), 0 failures, 0 errors (baseline m2b: 370/2/0). `lintDebug` no reporta hallazgos sobre ninguno de los dos archivos tocados.
- **Líneas cambiadas**: 82 (53 en `KeyStorePhoneTlsIdentity.kt`: 32 adiciones + 21 eliminaciones; 29 en `KeyStorePhoneTlsIdentityTest.kt`: 29 adiciones + 0 eliminaciones).
- **Commit**: `fix(android): keep phone TLS key on keystore read errors`.

### [x] m3 — Interoperabilidad JSSE ↔ rustls con client auth obligatorio

Requiere autorización fresca del usuario para runtime entre worktrees. `SocketlessUsbPairingProofInteropTest` presenta identidad PKCS12 contra el helper desktop actualizado y compara el `phone_id` que el helper reporta por stderr con la huella local del SPKI. Estimado ~120 líneas.

Criterios: el canal presenta el certificado sólo si recibe identidad; sin identidad falla cerrado contra un desktop que exige client auth; el SPKI recibido por el servidor es el del teléfono; los tests existentes siguen verdes.

#### Evidencia m3

- **Autorización**: usuario, 2026-09-28, prueba acotada de runtime entre worktrees: sin sockets/TCP/LAN/hardware, sin merge ni cherry-pick entre ramas, sin wiring de producción, sin editar archivos fuera de los permitidos.
- **Helper**: copia única en `/Users/jele/.claude/jobs/8dbadce5/tmp/usb_pairing_proof_stdio_helper-1a99938`, SHA-256 `d23616b099bda8e7246ab302cdade0b66158173384eb5360d41f69617c330c4f`, construida desde el commit del desktop `1a99938` (exige certificado de cliente y emite `phone_id=<64 hex minúscula>` por stderr tras una prueba exitosa). Se copió una sola vez para no correr contra un rebuild concurrente del worktree del desktop (protegido, no se leyó ni se tocó); el hash se verificó antes de correr (coincide) y el propio test lo revalida vía `CHINCHILLA_USB_PAIRING_PROOF_STDIO_HELPER_SHA256`.
- **RED observado** (de ejecución, no de compilación: a diferencia de m1/m2b, acá el seam de identidad — `PhoneTlsIdentity`, `SslEngineUsbTlsChannel(phoneTlsIdentity = ...)`, `KeyStorePhoneTlsIdentity`, `PairingTrustFingerprint` — ya existía desde m1/m2/m2b; lo que faltaba era que este test la usara). Corriendo el test tal cual estaba, sin ninguna modificación, con ambas variables de entorno apuntando al helper actualizado: el desktop exige client auth y el teléfono no presenta certificado (`SslEngineUsbTlsChannel()` con `phoneTlsIdentity = null` por defecto), así que el proof nunca llega a `Verified`:
  ```
  java.util.concurrent.ExecutionException: java.lang.AssertionError: expected status0 verified proof, got Rejected(reason=tls proof failed)
      at dev.chinchillacam.usbprobe.SocketlessUsbPairingProofInteropTest.rustlsHelperCompletesUsbTlsPairingProofOverStdio(SocketlessUsbPairingProofInteropTest.kt:30)
  Caused by: java.lang.AssertionError: expected status0 verified proof, got Rejected(reason=tls proof failed)
      at dev.chinchillacam.usbprobe.SocketlessUsbPairingProofInteropTest.runInterop(SocketlessUsbPairingProofInteropTest.kt:90)
  ```
  XML: `tests="1" skipped="0" failures="1" errors="0"`. El motivo `tls proof failed` es exactamente el string del catch genérico de `UsbTlsPairingProofVerifier.verify()` (no uno de los `reject(...)` internos de `SslEngineUsbTlsChannel`, que llevan su propio motivo), lo que indica que el handshake TLS del lado cliente terminó pero el desktop cortó la conexión durante el intercambio de datos de aplicación del proof — coherente con la asimetría de TLS 1.3 ya documentada en m1/m1b. Esto prueba que el desktop de verdad exige mTLS contra un cliente JSSE real, no sólo contra el peer rustls con el que se probó en desarrollo.
- **Cambio**: se construye una identidad de teléfono JVM-testeable con `KeyStorePhoneTlsIdentity` sobre un keystore PKCS12 en memoria (clave EC P-256 generada con el mismo `keytool` que `KeyStorePhoneTlsIdentityTest`), creada antes de arrancar el watchdog para no cargar la generación de la clave dentro del timeout de la interop. Se pasa por `SslEngineUsbTlsChannel(phoneTlsIdentity = identity)` a un `UsbTlsPairingProofVerifier(epochSecondsSource, tlsChannel)`. Tras `Verified` y salida limpia (`exitValue() == 0`), se lee el stderr completo del helper acotado en tamaño (4096 bytes, reutilizando `MAX_STDERR_DIAGNOSTIC_BYTES`) y en tiempo (5 s, `STDERR_READ_TIMEOUT_SECONDS` nuevo), se afirma que es exactamente una línea `phone_id=<hex>`, y que ese hex coincide con un SHA-256(`subjectPublicKeyInfoDer`) calculado explícitamente en el test y, por separado, con `PairingTrustFingerprint.fromTrustMaterial(...).hex`. Se mantuvo sin cambios: el lector crudo del preludio (sin `BufferedReader`), el watchdog y los timeouts existentes, la limpieza del proceso en `finally` en todas las rutas, la semántica opt-in/skip (`assumeTrue`), el chequeo opcional de SHA-256 del binario del helper, y que los fallos del lado servidor sigan llegando a JUnit (`fail(process.stderrDiagnostic())`). No se creó un archivo de soporte nuevo: el recetario de `keytool` se duplicó (adaptado) dentro del propio test, porque ningún otro archivo permitido lo necesita todavía.
- **GREEN focused** (`--tests dev.chinchillacam.usbprobe.SocketlessUsbPairingProofInteropTest --rerun-tasks`), verde ya en el primer intento (las piezas de producción ya existían desde m1/m2/m2b), 3 corridas consecutivas: XML `tests="1" skipped="0" failures="0" errors="0"` cada vez (0.388 s, 0.388 s, 0.409 s).
- **Full** (env sin las variables del helper, con `assembleDebug` + `lintDebug`): `BUILD SUCCESSFUL`. Total del módulo (agregando los 41 XML de `testDebugUnitTest`): 371 tests, 2 skipped (los dos interop opt-in, incluido este), 0 failures, 0 errors — sin cambio respecto al baseline de m2c (371/2/0): confirma que el test sigue saltando sin las variables de entorno y que ningún otro test se rompió. `lintDebug`: 0 coincidencias de `SocketlessUsbPairingProofInteropTest` en `lint-results-debug.xml`.
- **Líneas cambiadas**: 105 (102 adiciones + 3 eliminaciones), un solo archivo — `SocketlessUsbPairingProofInteropTest.kt`.
- **Commit**: `test(android): prove mutual TLS interop with desktop helper`.

## Progreso

Plan creado el 2026-09-28. m1 completada el 2026-09-28 (ver Evidencia m1); revisión nativa m1 aprobada (lineage `review-37abf23d14631701`). m1b (endurecimiento post-revisión) completada el 2026-09-28 (ver Evidencia m1b). m2 completada el 2026-09-28 (ver Evidencia m2; desvío TDD declarado, validación física pendiente para M9). Revisión nativa m1b+m2 aprobada sin corrección (lineage `review-48052d68d919f710`). m2b (endurecimiento post-revisión: alias inservible recuperable, lock de proceso, KeyManager pinneado al alias, y calidad de tests en `SslEngineUsbTlsChannelTest`) completada el 2026-09-28 (ver Revisión nativa m1b+m2 y Evidencia m2b); los once hallazgos no bloqueantes quedan resueltos. m2c (defecto encontrado en readback del orquestador sobre `8ca23c4`: un error transitorio de lectura del keystore borraba y regeneraba la clave del teléfono en vez de propagar y fallar cerrado; corregido para que sólo la invalidez de contenido dispare borrar+regenerar) completada el 2026-09-28 (ver Evidencia m2c). m3 completada el 2026-09-28 (ver Evidencia m3): con autorización fresca del usuario para runtime entre worktrees, `SocketlessUsbPairingProofInteropTest` confirma que el desktop del commit `1a99938` exige y verifica el certificado de cliente del teléfono, y que el `phone_id` que reporta coincide con la huella SPKI local. Revisión nativa m3 pendiente.
