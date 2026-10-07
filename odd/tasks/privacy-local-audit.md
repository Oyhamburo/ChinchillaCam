# Auditoría de privacidad y operación local (T28, Android)

## 1. Objetivo

Comprobar con evidencia y dejar protegido por tests que el teléfono no usa cuentas, nube, backend, audio ni grabación, que no abre red, que pide sólo los permisos necesarios y que sus datos privados no salen del dispositivo; documentar qué se guarda, dónde y cómo borrarlo.

## 2. Hallazgos (auditoría del 2026-10-07)

- Permisos: `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`; sin red, audio ni almacenamiento. `allowBackup="false"`. Sólo `ConnectionActivity` es exportada (lanzador + accesorio USB).
- Única dependencia de runtime: `com.google.zxing:core`. Sin `Log` ni `println` en `src/main`.
- `TlsPairingProofVerifier.kt` (cliente `SSLSocket` del prototipo de loopback M3c4b) sigue en `src/main` aunque producción usa `UsbTlsPairingProofVerifier` y no hay permiso de red.
- Los tests actuales (`NoNetworkListenerContractTest`) sólo cubren permisos de red y sockets servidor.

## 3. Decisiones (orquestador, dentro de los no-objetivos del producto)

1. `allowBackup="false"` se mantiene: la lista de PCs confiables y las preferencias no se suben a la nube ni se copian a otro equipo.
2. `TlsPairingProofVerifier` pasa a fuentes de test si sólo lo usan tests; si no se puede sin romper tipos compartidos, queda como excepción explícita y comentada en el test de contrato.
3. Contrato por tests: lista cerrada de permisos, backup desactivado, componentes exportados, APIs prohibidas en `src/main` (audio, grabación, HTTP, WebView, almacenamiento externo, logging) y lista cerrada de dependencias.

## 4. Contrato

- `PrivacyContractTest` (o ampliación de `NoNetworkListenerContractTest`) con las aserciones del punto 3.3.
- `docs/uso.md` §8: qué datos guarda el teléfono, dónde, que no hay backup y cómo borrarlos; permisos y su motivo.

## 5. Riesgos

- Los tests de contrato por texto pueden dar falsos positivos con nombres parecidos; preferir patrones precisos (`import android.media.MediaRecorder`, etc.).

## 6. Reglas de ejecución

TDD estricto donde aplique (los tests de contrato se escriben primero y deben fallar ante una violación simulada o un caso real); commits de work unit ≤400 líneas con tests y docs; writers delegados acotados. Push autorizado tras la unidad; sin PR ni merge.

## 7. Tareas

Runner: `env -u CHINCHILLA_PAIRING_PROOF_HELPER ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug --offline` (línea de base 553/2/0).

1. [x] p1 — tests de contrato de privacidad y resolución de `TlsPairingProofVerifier`. RED: `manifest_requests_only_allowed_permissions` contra una violación simulada. ~250 líneas.
   - Evidencia p1: el test de permisos falló con `INTERNET` añadido sólo a su entrada XML; el guardián de sockets detectó tres usos en el verificador antes de moverlo a fuentes de test. Tras el cambio, el contrato focalizado pasó (7 tests) y el runner completo pasó (560 tests, 2 omitidos, 0 fallos, 0 errores; ensamblado y lint correctos).
2. [x] p2 — `docs/uso.md` §8 con datos guardados y permisos; cierre, plan general y push.
   - Evidencia p2: `docs/uso.md` §8.1 (datos, ubicación, cómo borrarlos, backup desactivado y motivo de cada permiso). Suite completa reportada por el writer de p1: 560 tests, 2 omitidos, 0 fallas; assemble y lint aprobados.

## 8. Evidencia

- Commits: `bdaf157` (p1, tests de contrato y `TlsPairingProofVerifier` movido a tests) y el commit de cierre.
- Readback del orquestador: `TlsPairingProofVerifier` se movió sin cambios (sólo lo usaban tests); el guardia de sockets salientes quedó sin excepciones.
- Límite: los tests de contrato son estáticos; no prueban la ausencia de tráfico en ejecución (eso queda para M9 con captura de red si se quiere).
