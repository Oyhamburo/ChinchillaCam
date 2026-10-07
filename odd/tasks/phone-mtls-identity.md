# Identidad mTLS del teléfono (Android y desktop)

Este documento reúne los registros de las dos ramas, integradas en `main` el 2026-10-07 (`odd/tasks/branch-integration.md`). Cada parte conserva su evidencia original sin cambios.

## Parte Android (rama `feat/t15c-fake-usb-sustained`)

## Identidad mTLS del teléfono

### 1. Objetivo

Que el desktop autentique al teléfono con TLS mutuo estándar, cerrando el ítem pendiente del Gate M3 ("threat model/plan testeable con TLS estándar e identidad de par pinneada/esperada") antes de componer el pairing de punta a punta y antes de T17/T19.

### 2. Problema

Hoy el teléfono pinnea el SPKI del desktop recibido por QR (`PinnedDesktopTlsTrustManager`), pero el desktop no autentica al teléfono: los servidores rustls usan `.with_no_client_auth()` (`desktop/usb-probe/src/usb_tls_pairing_proof.rs:117`, `desktop/usb-probe/src/loopback_pairing_proof_server.rs:284`) y `TrustedPhoneIdentity` (T13c) espera una clave pública que nada produce. Sin identidad del teléfono no se puede rechazar tráfico no autenticado (T17) ni reconectar sin QR (T19).

### 3. Decisión

Decisión del usuario del 2026-09-28 (opción 1): mTLS con clave del teléfono en Android Keystore. Alternativas descartadas: firma de desafío a nivel aplicación dentro de TLS (protocolo propio; cualquier dispositivo de la LAN completa el handshake antes de ser rechazado) y token secreto entregado en el pairing (una filtración permite suplantar al teléfono).

### 4. Contrato compartido Android ↔ desktop

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

### 5. Threat model (Gate M3)

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

### 6. Reglas de ejecución

- TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución: "TDD estricto: RED observado antes de producción, GREEN después"). Si una tarea no puede tener RED observable, se declara el desvío explícitamente; no se inventa evidencia.
- Commits locales por tarea, ≤400 líneas cambiadas como heurística; sin push, PR ni merge (regla del usuario); la estrategia de cadena de PR no aplica.
- Revisión nativa RDD (on, global) por commit de trabajo con `gentle-ai review assess`.
- Ruta de cada tarea: delegada a un writer acotado (trigger: 2+ archivos no triviales o lectura previa de 4+ archivos), con verificación independiente para candidatos de riesgo alto.

### 7. Tareas

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` (focused: `--tests <FQCN>`). Último límite revisado nativamente: `11b05ea`.

#### [x] m1 — Certificado de cliente del teléfono en el canal USB TLS

Nuevo seam `PhoneTlsIdentity` (`fun keyManagers(): Array<KeyManager>`, `val subjectPublicKeyInfoDer: ByteArray`); parámetro de constructor `phoneTlsIdentity: PhoneTlsIdentity? = null` en `SslEngineUsbTlsChannel` (hoy `init(null, …)` en `SslEngineUsbTlsChannel.kt:274`); identidad PKCS12 de test con la receta `keytool` existente (`TlsFixture.create`). RED: `presentsPhoneClientCertificateWhenServerRequiresClientAuth` (servidor JSSE con `needClientAuth=true` y trust manager que captura el SPKI). Test de caracterización: sin identidad y con servidor que exige client auth, el handshake termina `Rejected` y cierra la sesión. Estimado ~250 líneas.

##### Evidencia m1

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

##### Revisión nativa m1

- Lineage `review-37abf23d14631701`, 4 lentes (R1 riesgo, R2 legibilidad, R3 confiabilidad, R4 resiliencia). Resultado: **aprobada sin corrección**; acknowledgement con autoridad `burned`.
- Hallazgos no bloqueantes y el commit que los resuelve:
  - WARNING `R3-tls13-no-identity-unproved` (el caso sin identidad sólo está probado con TLS 1.2) — resuelto en **m1b**.
  - SUGGESTION `R4-001` (bajo TLS 1.3 por defecto el cliente retorna `Authenticated` aunque el servidor exija client auth; el rechazo debe surgir fail-closed en la primera lectura — no probado) — resuelto en **m1b**, mismo test que el anterior.
  - WARNING `R2-server-error-optional-assertion` (`serverError.get()?.let { ... }` pasa si `serverError` es null, es decir cuando el servidor NO rechazó) — resuelto en **m1b**.
  - SUGGESTION `R2-throwing-identity-desktop-spki` (`ThrowingPhoneTlsIdentity` reutilizaba el SPKI del desktop como SPKI del teléfono) — resuelto en **m1b**.
  - SUGGESTION `R3-test-session-cleanup` (los dos tests nuevos con hilo servidor no cerraban `pair` en `finally`) — resuelto en **m1b**.

##### Evidencia m1b

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

#### [x] m2 — Adaptador delgado `AndroidKeyStorePhoneTlsIdentity`

Genera o carga el alias, spec del contrato §4.1, `KeyManagerFactory.init(AndroidKeyStore, null)`. No es testeable en JVM: desvío TDD declarado; validación en dispositivo pendiente (M9); sin wiring de Activity. Estimado ~100 líneas.

##### Evidencia m2

- **Desvío TDD declarado explícitamente** (no se inventa RED/GREEN): `AndroidKeyStorePhoneTlsIdentity` usa `KeyStore.getInstance("AndroidKeyStore")`, un provider de plataforma que no existe en una JVM de escritorio — no lo puede ejercitar `testDebugUnitTest`. La única prueba disponible en este entorno es que el módulo compile, empaquete y pase lint; el comportamiento real (generación de la clave, autenticación de cliente TLS con `DIGEST_NONE`, disponibilidad de TLS 1.3 sólo desde API 29+) queda **pendiente de validación física (M9)**.
- **Implementación**: adaptador delgado que implementa `PhoneTlsIdentity`. Carga `KeyStore.getInstance("AndroidKeyStore").apply { load(null) }`; si el alias `chinchillacam-phone-tls-v1` no existe, lo genera con `KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")` + `KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(DIGEST_NONE, DIGEST_SHA256).setCertificateSubject(X500Principal("CN=ChinchillaCam Phone"))`. `keyManagers()` delega en `KeyManagerFactory.getInstance(getDefaultAlgorithm()).apply { init(keyStore, null) }.keyManagers`. `subjectPublicKeyInfoDer` toma `publicKey.encoded` del certificado del alias y lo valida como P-256 canónico con `DesktopTlsIdentityMaterial.validate(...)`, lanzando una excepción clara (fail closed) si no lo es. Sin exportar la clave privada, sin loguear material de clave, sin wiring de Activity, sin dependencias nuevas.
- **Full**: `testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug` → `BUILD SUCCESSFUL`. `compileDebugKotlin` compila el archivo nuevo contra los stubs del SDK de Android sin errores ni warnings propios; `lintDebug` no reporta ningún hallazgo sobre `AndroidKeyStorePhoneTlsIdentity.kt` (0 coincidencias en `lint-results-debug.xml`). Total de tests unitarios del módulo sin cambios respecto a m1b (365 tests, 2 skipped, 0 failures, 0 errors) porque ningún test de JVM ejercita este archivo.
- **Líneas cambiadas**: 65 (archivo nuevo) — `AndroidKeyStorePhoneTlsIdentity.kt`.
- **Commit**: `feat(android): add Android Keystore phone TLS identity`.

##### Revisión nativa m1b+m2

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

##### Evidencia m2b

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

##### Evidencia m2b (continuación: calidad de tests)

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

##### Evidencia m2c

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

#### [x] m3 — Interoperabilidad JSSE ↔ rustls con client auth obligatorio

Requiere autorización fresca del usuario para runtime entre worktrees. `SocketlessUsbPairingProofInteropTest` presenta identidad PKCS12 contra el helper desktop actualizado y compara el `phone_id` que el helper reporta por stderr con la huella local del SPKI. Estimado ~120 líneas.

Criterios: el canal presenta el certificado sólo si recibe identidad; sin identidad falla cerrado contra un desktop que exige client auth; el SPKI recibido por el servidor es el del teléfono; los tests existentes siguen verdes.

##### Evidencia m3

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

### Revisiones nativas finales

Revisión nativa por slice de commits, cerrando la deuda de revisión de m3 señalada en Progreso.

#### Slice 1 — `c807f4a..84b87ff` (m2b + endurecimiento de tests TLS 1.3)

- Lineage `review-1919496974936c73`, 4 lentes (R1 riesgo, R2 legibilidad, R3 confiabilidad, R4 resiliencia). Resultado: **aprobada sin corrección**; acknowledgement con autoridad `burned`.
- Hallazgos:
  - WARNING `R1-transient-probe-error-deletes-identity`, `R4-transient-keystore-error-deletes-valid-key`, `R3-transient-keystore-error-deletes-valid-key` — ya corregidos en m2c (`a76b002`); encontrados antes también por el readback del orquestador.
  - WARNING `R4-regenerated-signal-lost-after-failed-regeneration` y `R3-regenerated-signal-lost-on-generation-failure` — si la generación falla después de borrar el alias, la señal `regenerated` se pierde en el siguiente arranque; requiere un marcador durable en la capa de wiring.
  - WARNING `R3-fail-closed-after-regeneration-untested`.
  - WARNING `R2-pinned-keymanager-test-silent-join-timeout`.
  - SUGGESTION `R2-ensure-result-duplicates-usable-entry`, `R2-entryinstanceof-precheck-unexplained`, `R2-review-ids-and-history-in-code-comments`, `R2-duplicated-keytool-fixture`, `R3-certificate-chain-cast-escapes-fail-closed`, `R3-pinned-km-test-timing`.

#### Slice 2 — `84b87ff..fa0c6a4` (m2c + m3)

- Lineage `review-6e9bc43ad84cc0fb`, 4 lentes (R1 riesgo, R2 legibilidad, R3 confiabilidad, R4 resiliencia). Resultado: **aprobada sin corrección**; acknowledgement con autoridad `burned`.
- Hallazgos:
  - WARNING `R4-persistent-read-failure-no-recovery` y `R3-permanent-read-failure-no-recovery` — una clave ilegible de forma permanente deja la construcción fallando siempre; requiere una acción explícita de "restablecer identidad del teléfono" en la capa de wiring/UI, nunca borrado automático.
  - SUGGESTION `R2-duplicated-keytool-recipe`, `R2-reused-diagnostic-constant`, `R2-kdoc-change-history`, `R2-duplicated-hex-digest`.

#### Estado

Feature Android completo (m1–m3, más m1b, m2b y m2c); último límite revisado `fa0c6a4`; validación en dispositivo del Keystore pendiente (M9); los hallazgos anteriores quedan como seguimientos no bloqueantes.

### Progreso

Plan creado el 2026-09-28. m1 completada el 2026-09-28 (ver Evidencia m1); revisión nativa m1 aprobada (lineage `review-37abf23d14631701`). m1b (endurecimiento post-revisión) completada el 2026-09-28 (ver Evidencia m1b). m2 completada el 2026-09-28 (ver Evidencia m2; desvío TDD declarado, validación física pendiente para M9). Revisión nativa m1b+m2 aprobada sin corrección (lineage `review-48052d68d919f710`). m2b (endurecimiento post-revisión: alias inservible recuperable, lock de proceso, KeyManager pinneado al alias, y calidad de tests en `SslEngineUsbTlsChannelTest`) completada el 2026-09-28 (ver Revisión nativa m1b+m2 y Evidencia m2b); los once hallazgos no bloqueantes quedan resueltos. m2c (defecto encontrado en readback del orquestador sobre `8ca23c4`: un error transitorio de lectura del keystore borraba y regeneraba la clave del teléfono en vez de propagar y fallar cerrado; corregido para que sólo la invalidez de contenido dispare borrar+regenerar) completada el 2026-09-28 (ver Evidencia m2c). m3 completada el 2026-09-28 (ver Evidencia m3): con autorización fresca del usuario para runtime entre worktrees, `SocketlessUsbPairingProofInteropTest` confirma que el desktop del commit `1a99938` exige y verifica el certificado de cliente del teléfono, y que el `phone_id` que reporta coincide con la huella SPKI local. Revisión nativa final ejecutada por slices el 2026-09-28 (ver Revisiones nativas finales): slice `c807f4a..84b87ff` (lineage `review-1919496974936c73`) y slice `84b87ff..fa0c6a4` (lineage `review-6e9bc43ad84cc0fb`), ambas aprobadas sin corrección con autoridad `burned`; feature Android completo (m1-m3, más m1b, m2b y m2c), último límite revisado `fa0c6a4`; validación en dispositivo del Keystore pendiente (M9).

## Parte desktop (rama `feat/desktop-video-sink`)

## Identidad mTLS del teléfono

### 1. Objetivo

Que el desktop autentique al teléfono con TLS mutuo estándar, cerrando el ítem pendiente del Gate M3 ("threat model/plan testeable con TLS estándar e identidad de par pinneada/esperada") antes de componer el pairing de punta a punta y antes de T17/T19.

### 2. Problema

Hoy el teléfono pinnea el SPKI del desktop recibido por QR (`PinnedDesktopTlsTrustManager`), pero el desktop no autentica al teléfono: los servidores rustls usan `.with_no_client_auth()` (`desktop/usb-probe/src/usb_tls_pairing_proof.rs:117`, `desktop/usb-probe/src/loopback_pairing_proof_server.rs:284`) y `TrustedPhoneIdentity` (T13c) espera una clave pública que nada produce. Sin identidad del teléfono no se puede rechazar tráfico no autenticado (T17) ni reconectar sin QR (T19).

### 3. Decisión

Decisión del usuario del 2026-09-28 (opción 1): mTLS con clave del teléfono en Android Keystore. Alternativas descartadas: firma de desafío a nivel aplicación dentro de TLS (protocolo propio; cualquier dispositivo de la LAN completa el handshake antes de ser rechazado) y token secreto entregado en el pairing (una filtración permite suplantar al teléfono).

### 4. Contrato compartido Android ↔ desktop

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

### 5. Threat model (Gate M3)

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

### 6. Reglas de ejecución

- TDD estricto (fuente: `odd/tasks/complete-webcam-product.md`, Reglas de ejecución: "TDD estricto: RED observado antes de producción, GREEN después"). Si una tarea no puede tener RED observable, se declara el desvío explícitamente; no se inventa evidencia.
- Commits locales por tarea, ≤400 líneas cambiadas como heurística; sin push, PR ni merge (regla del usuario); la estrategia de cadena de PR no aplica.
- Revisión nativa RDD (on, global) por commit de trabajo con `gentle-ai review assess`.
- Ruta de cada tarea: delegada a un writer acotado (trigger: 2+ archivos no triviales o lectura previa de 4+ archivos), con verificación independiente para candidatos de riesgo alto.

### 7. Tareas

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

   ### Evidencia m4b

   - **Defecto encontrado en readback del orquestador** (antes de que corriera la revisión nativa de m3+m4, que seguía pendiente): `PairedPhoneCandidate::confirm` llamaba `store.is_revoked(&self.phone_id)?` y, en una llamada separada, `store.trust(identity)?`. Cada llamada toma el lock del store por separado, y `trust` hace upsert incondicional (`retain` + `push`) sin mirar el estado de revocación: un `revoke` que aterriza entre las dos llamadas queda silenciosamente sobrescrito y el teléfono termina confiado de nuevo (time-of-check/time-of-use). El contrato (sección 4.6) exige que confirmar nunca des-revoque un teléfono.
   - **RED observado -- interleaving real a través de `confirm`, no sólo error de compilación**: se evaluó primero si un RED determinista de la carrera completa a través de `confirm` era alcanzable sin agregar un hook de test nuevo dentro de `is_revoked` (que hoy no llama al `TrustedPhoneStoreWriteCoordinator`). Resultó que sí es alcanzable, combinando el hook existente -- pausando `revoke` justo después de su `load_records()` -- con el bloqueo por lock de archivo que ya sufre `trust`: mientras `revoke` está pausado (con el lock tomado), el hilo que llama a `candidate.confirm(...)` hace su `is_revoked` (sin lock, ve "no revocado") y queda bloqueado dentro de `store.trust(...)` esperando el mismo lock; al liberar la pausa, `revoke` termina y suelta el lock, y recién entonces corre el `trust` de `confirm` -- sobre un archivo que ya muestra revocado, pero sin mirarlo -- y sobrescribe la revocación. Test `confirm_refuses_when_revocation_completes_while_confirm_is_pending` en `tests/paired_phone_candidate_confirm_test.rs`, corrido contra el código sin arreglar:

     ```
     thread 'confirm_refuses_when_revocation_completes_while_confirm_is_pending' panicked at tests/paired_phone_candidate_confirm_test.rs:174:5:
     expected Err(PhoneRevoked), got Ok(TrustedPhoneIdentity { phone_id: "phone-confirm-race", label: "Stale Confirm", public_key: [1, 2, 3] })
     ```

   - **RED complementario (observación manual, no dejado como test permanente)**: antes de construir el test anterior se verificó la misma hipótesis de la forma más simple posible -- sin hilos, reproduciendo a mano y en un solo hilo la secuencia exacta de `confirm` (`is_revoked` → `revoke` intercalado → `trust`) directamente sobre el store -- y también falló como se esperaba (`a revoke committed after confirm's stale not-revoked check must not be overwritten`, panic en runtime). No quedó como test commiteado porque `trust`/`revoke`/`is_revoked` no cambian de comportamiento (instrucción explícita de esta tarea): esa secuencia seguiría fallando para siempre después del fix, ya que el fix no toca esos tres métodos, sólo agrega uno nuevo y cambia lo que usa `confirm`.
   - **RED de API** (mismo patrón que m1-m4, error de compilación): los 3 tests nuevos de `trust_unless_revoked` en `tests/trusted_phone_store_test.rs` referencian el método y el tipo antes de existir:

     ```
     error[E0432]: unresolved import `usb_probe::TrustUnlessRevoked`
       --> tests/trusted_phone_store_test.rs:11:40
     error[E0599]: no method named `trust_unless_revoked` found for struct `FileTrustedPhoneStore` in the current scope
     ```

     (3 apariciones de E0599, una por cada test nuevo que ya llamaba `store.trust_unless_revoked(...)`.)

   - **GREEN -- fix elegido**: nueva operación atómica `FileTrustedPhoneStore::trust_unless_revoked(&self, identity) -> Result<TrustUnlessRevoked, TrustedPhoneStoreError>` (enum nuevo `TrustUnlessRevoked { Trusted, Refused }`, exportado en `lib.rs`) que, bajo UN solo lock y UNA sola lectura (`load_records`), revisa si algún registro de ese `phone_id` está revocado: si lo está, devuelve `Refused` sin escribir nada; si no, hace exactamente el mismo upsert que `trust` (`retain` + `push` + `save_records`) y devuelve `Trusted`. `trust`, `revoke`, `is_revoked` y `phone_trust_snapshot` no cambiaron. `PairedPhoneCandidate::confirm` ahora sólo llama `store.trust_unless_revoked(identity)` (elimina el `is_revoked` previo) y mapea `Refused` a `PairedPhoneCandidateConfirmError::PhoneRevoked`; `PairedPhoneCandidateConfirmError` no cambió de forma.
   - **GREEN -- tests**: los 4 tests nuevos pasan. Los 4 tests preexistentes de `paired_phone_candidate_confirm_test.rs` siguen verdes sin modificarlos (`confirm_refuses_revoked_phone` ya cubría el caso secuencial simple -- revocado antes de llamar a `confirm` -- y sigue pasando porque `trust_unless_revoked` ve el registro revocado en su única lectura y rechaza). `trust_unless_revoked_refuses_when_revocation_completes_while_call_is_pending` (en `trusted_phone_store_test.rs`) reproduce la misma carrera directamente contra el store, pausando `revoke` (no `trust_unless_revoked`): pausar el lado que escribe sólo demostraría que un revoke concurrente se aplica después (sin perderse), no que se rechace; pausar `revoke` es lo que fuerza que su commit sea visible en la única lectura de `trust_unless_revoked` "al momento de escribir", tal como pedía la tarea. `trust_unless_revoked_refuses_and_writes_nothing_when_already_revoked` compara el archivo completo (`fs::read_to_string`) antes y después para probar que un rechazo no escribe nada. `trust_unless_revoked_trusts_when_not_currently_revoked` cubre el camino feliz (sin registro previo).
   - **GREEN focused**: `cargo test --offline --test paired_phone_candidate_confirm_test` → `test result: ok. 5 passed; 0 failed` (4 previas + 1 nueva). `cargo test --offline --test trusted_phone_store_test` → `test result: ok. 10 passed; 0 failed` (7 previas + 3 nuevas).
   - `cargo fmt -- --check`: diffs de orden de imports en `lib.rs`, `usb_tls_pairing_proof.rs` y `trusted_phone_store_test.rs` (rustfmt ordena `TrustUnlessRevoked` antes que `TrustedPhone*`, sort case-sensitive: mayúscula antes que minúscula); sin diffs tras `cargo fmt`.
   - **Full**: `cargo test --offline` → 201 tests en total (197 + 4 nuevos), 0 fallos.
   - **Líneas cambiadas**: 250 (+237/-13) -- `lib.rs` +1/-1, `trusted_phone_store.rs` +42/-0, `usb_tls_pairing_proof.rs` +12/-7, `paired_phone_candidate_confirm_test.rs` +91/-4, `trusted_phone_store_test.rs` +91/-1. Dentro de la heurística de ~400.
   - Commit: `fix(desktop): confirm phones atomically against revocation`.

Criterios: ningún camino USB real acepta clientes sin certificado; pairing liga el SPKI a la sesión que consumió el nonce; reconexión rechaza desconocidos y revocados en el handshake; nada se persiste sin confirmación explícita; `cargo fmt -- --check` y `cargo test` verdes.

### Revisiones nativas finales

Revisión nativa por slice de commits, cerrando la deuda de revisión de m3+m4b señalada en Progreso.

#### Slice 1 — `86cc561..18e57fd` (m2b tests + m3)

Lineage `review-74e4722094ea8a48`, 1 lente (reliability). Resultado: **aprobada**, sin corrección, acknowledgement con autoridad `burned`. Hallazgos no bloqueantes:

- WARNING `R3-trusted-rejection-cause-unproved` — los tests de rechazo de m3 aceptan cualquier `Tls(_)` sin verificar la causa.
- WARNING `R3-stdio-e2e-test-can-hang` — lecturas bloqueantes sin deadline en el test end-to-end del helper antes de la espera final.
- SUGGESTION `R3-trusted-handshake-timeout-untested`.

#### Slice 2 — `18e57fd..1a99938` (m4 + m4b)

Lineage `review-86db2a51558e722a`, 1 lente (reliability). Resultado: **aprobada**, sin corrección, acknowledgement con autoridad `burned`. Hallazgos:

- WARNING `R3-race-test-ordering-unproved` — el test de carrera depende de un sleep de 25 ms para el orden.
- SUGGESTION `R3-confirm-store-error-path-untested`.

#### Estado

Feature desktop completo (m1–m4, más m1b, m2b y m4b); último límite revisado `1a99938`; los hallazgos anteriores quedan como seguimientos no bloqueantes (no reabren la revisión).

### Progreso

Plan creado el 2026-09-28; m1 completada el 2026-09-28 (ver Evidencia m1); revisión nativa m1 aprobada y sus 5 hallazgos atendidos en m1b el 2026-09-28 (ver Revisión nativa m1 y Evidencia m1b); m2 completada el 2026-09-28 (ver Evidencia m2); revisión nativa m1b+m2 aprobada y sus 4 hallazgos atendidos en m2b el 2026-09-28 (ver Revisión nativa m1b+m2 y Evidencia m2b); m3 completada el 2026-09-28 (ver Evidencia m3); m4 completada el 2026-09-28 (ver Evidencia m4); defecto TOCTOU en `PairedPhoneCandidate::confirm` encontrado por readback del orquestador (no por revisión nativa) y corregido el 2026-09-28 en m4b (ver Evidencia m4b). Las 4 tareas del plan (m1-m4) están completas; revisión nativa final ejecutada por slices el 2026-09-28 (ver Revisiones nativas finales): feature desktop completo (m1-m4, más m1b, m2b y m4b), último límite revisado `1a99938`, hallazgos anteriores como seguimientos no bloqueantes que no reabren la revisión.
