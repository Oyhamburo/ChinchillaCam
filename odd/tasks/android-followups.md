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

1. [ ] g1 — Pairing sobre `TlsCiphertextTransport`. RED: `pairingFlowCompletesOverRawStreamTransport`. ~300 líneas.
2. [ ] g2 — Mensajes neutrales del canal TLS y composición en un paso. RED: `compositionStartsBindingAndPublishesErrorWithoutHolder`. ~200 líneas.

## Progreso

Plan creado el 2026-10-03.
