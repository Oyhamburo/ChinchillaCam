# Compatibilidad Android API 23

## Bloqueo conocido

`:android:usb-probe:lintDebug` falla con errores `NewApi` preexistentes para `minSdk = 23`.

Primer hallazgo observado:

- `android/usb-probe/src/main/java/dev/chinchillacam/usbprobe/CameraCapabilityCatalog.kt`
- Uso de `Map.putIfAbsent`, requiere API 24 con minSdk 23.

## Alcance

Este documento sólo registra el bloqueo. No corrige código en el corte M3b2.

## Requisito para corte futuro

Abrir un corte TDD independiente para corregir todos los hallazgos lint API23 relevantes y no afirmar soporte runtime minSdk 23 hasta resolverlos.
