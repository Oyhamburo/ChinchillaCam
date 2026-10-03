# Seguimientos técnicos (Android)

## 1. Objetivo

Cerrar seguimientos técnicos anotados en features anteriores que no requieren decisiones de producto ni UI.

## 2. Problema

- `odd/tasks/wifi-loopback-transport.md` w2: la ruta de pairing (`ChannelPairingProofVerifier` / `PendingPairingCoordinator` / `UsbPairingFlow`) sólo acepta `AccessoryIoSession`; reconexión y verificador ya aceptan `TlsCiphertextTransport`.
- Los mensajes de error del canal TLS dicen "USB TLS" aunque el canal ya es neutral respecto del transporte.
- `odd/tasks/session-pipeline-wiring.md` p2: `SessionEgressServicePipelineComposition` recibe el binding ya creado, pero el binding necesita de antemano el callback de la composición (construcción circular resuelta con un holder).

## 3. Decisión

Elegido por el orquestador el 2026-10-03 con delegación del usuario ("continuá sin preguntar"). Sin UI, sin red, sin decisiones de producto.

## 4. Contrato

1. Pairing sobre cualquier `TlsCiphertextTransport`: overloads que aceptan el transporte en el flujo de pairing y el coordinador; las rutas USB existentes delegan sin cambio de comportamiento.
2. Mensajes del canal neutrales ("TLS ciphertext …"), preservando el detalle del transporte; tests de mensaje ajustados y declarados.
3. Composición de servicio y binding construidos en un solo paso, sin holder.

## 5. Riesgos

- Interfaces públicas de pairing: sólo se agregan overloads.

## 6. Reglas de ejecución

TDD estricto donde aplica (fuente: `odd/tasks/complete-webcam-product.md`). Revisión nativa pendiente (consentimiento sin respuesta en la UI del host). Push autorizado por el usuario.

## 7. Tareas

Runner: `ANDROID_HOME=$HOME/Library/Android/sdk "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" :android:usb-probe:testDebugUnitTest --rerun-tasks :android:usb-probe:assembleDebug :android:usb-probe:lintDebug`. Baseline: 431 tests, 2 omitidos, 0 fallos (HEAD `a97b0bd`).

1. [x] g1 — Pairing sobre `TlsCiphertextTransport`. RED: `pairingFlowCompletesOverRawStreamTransport`. ~300 líneas.

   Evidencia g1:
   - RED observado (compilación de tests contra el `src/main` sin cambios, `compileDebugUnitTestKotlin FAILED`): en `UsbPairingFlowRawStreamTest.kt` `Type mismatch: inferred type is UsbTlsPairingProofVerifier but ChannelPairingProofVerifier was expected` (líneas 45 y 91, constructor del flujo) y `Type mismatch: inferred type is StreamTlsCiphertextTransport but AccessoryIoSession was expected` (líneas 51, 96 y 116, `flow.start(qr, transport)`).
   - GREEN: `UsbPairingFlowRawStreamTest` con tres pruebas. `pairingFlowCompletesOverRawStreamTransport`: QR pineado al SPKI del desktop falso de `RawStreamTlsTestSupport`, intercambio CCP1 sobre stream crudo, el flujo queda en confirmación pendiente con canal vivo (transporte abierto), `confirm` activa `pc-1`, devuelve el mismo canal, persiste la huella correcta y el canal sigue escribiendo (el peer lee el PONG). `pairingFlowOverRawStreamRejectsWrongDesktopKey`: QR pineado a otra clave; `ProofRejected`, sin canal, transporte cerrado, estado `Idle`, nada persistido y el peer no queda colgado. Triangulación `transportStartWithoutTransportVerifierFailsBeforeTouchingTransport`: un flujo construido sólo con `ChannelPairingProofVerifier` rechaza el `start` por transporte con `IllegalStateException` sin tocar coordinador ni transporte.
   - Diseño (aditivo): nueva `fun interface TransportPairingProofVerifier` hermana de `ChannelPairingProofVerifier` (no se generalizó la existente para no romper conversiones SAM ni llamadores); overload `PendingPairingCoordinator.start(qr, transport, transportVerifier)` que comparte `startCore`; `UsbPairingFlow` suma el parámetro opcional `transportVerifier` (default `null`), un constructor secundario desde `UsbTlsPairingProofVerifier` que arma ambos verificadores con la misma instancia, y `start(qr, transport)`; ambos `start` comparten el mismo ciclo de vida del canal retenido (`startHolding`). La ruta USB conserva su comportamiento: el `start` por sesión sigue llamando al verificador de sesión, cuyo overload ya envuelve la sesión con `tlsChannel.usbTransport(session)` (adapter configurado del canal) y delega en el overload por transporte.
   - Tests existentes sin cambios. Foco (`UsbPairingFlowRawStreamTest`, `UsbPairingFlowTest`, `PendingPairingCoordinatorTest`, `UsbTlsPairingProofVerifierTest`) verde 3× con `--rerun-tasks` (34 tests por corrida). Suite completa: 434 tests, 2 omitidos, 0 fallos (antes 431/2/0: +3 por las pruebas nuevas); `assembleDebug` y `lintDebug` OK.
2. [ ] g2 — Mensajes neutrales del canal TLS y composición en un paso. RED: `compositionStartsBindingAndPublishesErrorWithoutHolder`. ~200 líneas.

## Progreso

Plan creado el 2026-10-03.
g1 completada (green) el 2026-10-03: pairing de punta a punta sobre cualquier `TlsCiphertextTransport` (`TransportPairingProofVerifier`, overload del coordinador, `UsbPairingFlow.start(qr, transport)` y constructor desde `UsbTlsPairingProofVerifier`), sólo overloads aditivos; suite 434/2/0, assemble+lint OK.
