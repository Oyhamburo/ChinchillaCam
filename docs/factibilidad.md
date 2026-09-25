# Factibilidad técnica de ChinchillaCam

> Estado: análisis documental inicial antes de escribir código. Las fuentes citadas son insumos para hipótesis de implementación, no evidencia de que ChinchillaCam ya funcione en hardware real.

Este documento reúne fuentes primarias y marca las hipótesis que deben validarse antes de prometer soporte. La primera versión funcional mantiene ambos transportes como requisitos: USB y Wi‑Fi local. No se define un MVP Wi‑Fi-only.

## 1. Fuentes primarias consultadas

| Tema | Fuente primaria | Relevancia para ChinchillaCam |
| --- | --- | --- |
| Restricciones de inicio de foreground services en Android | [Android Developers: Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | Afecta la continuidad cuando la app Android no está visible, especialmente pantalla bloqueada o segundo plano. |
| USB accessory en Android | [Android Developers: USB accessory overview](https://developer.android.com/develop/connectivity/usb/accessory) | Describe el modelo de accesorio USB Android y su uso por apps. |
| Android Open Accessory Protocol | [Android Open Source Project: Android Open Accessory protocol](https://source.android.com/docs/core/interaction/accessories/aoa) | Describe handshake, endpoints bulk y que USB debugging no es requerido para conexiones de accesorio. |
| USB tethering en Android y Mac | [Google Help: Share a mobile connection by hotspot or tethering on Android](https://support.google.com/android/answer/9059108) | Indica que USB tethering con computadoras Mac no está soportado; esto no equivale a imposibilidad de otro protocolo USB, pero sí bloquea asumir tethering USB cross-platform. |
| OBS virtual camera en macOS 13+ | [OBS Project: Virtual Camera Troubleshooting](https://obsproject.com/kb/virtual-camera-troubleshooting) y [OBS Project: Virtual Camera Guide](https://obsproject.com/kb/virtual-camera-guide) | Relevante para la dependencia OBS Studio, problemas de cámara virtual en macOS moderno y el nombre esperado `OBS Virtual Camera`. |
| Cámara virtual en Windows | [Microsoft Learn: MfCreateVirtualCamera function](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera) | API de Media Foundation para crear cámara virtual; la documentación marca requisito de Windows build 22000. |
| Distribución fuera de Mac App Store | [Apple Developer: Developer ID](https://developer.apple.com/developer-id/) | Relevante para firma, notarización y distribución de apps macOS fuera de la tienda. |

## 2. Hipótesis y puertas de aceptación

Las siguientes hipótesis no deben presentarse como soporte real hasta verificarse con una build o prototipo reproducible.

| Hipótesis | Estado | Puerta de aceptación |
| --- | --- | --- |
| USB directo sin ADB ni depuración USB en Windows 11 | No validada | Windows 11 recibe video por cable USB sin habilitar depuración USB ni opciones de desarrollador. |
| USB directo sin ADB ni depuración USB en macOS 13+ Apple Silicon | No validada | macOS recibe video por cable USB sin ADB, sin depuración USB y sin depender de USB tethering. |
| Automatización o integración aceptable con OBS Studio | No validada | El usuario puede completar el flujo macOS con pasos documentados y seleccionar la salida en apps externas. |
| Continuidad con pantalla bloqueada | No validada | La transmisión continúa, o la limitación queda visible, en cada Samsung de referencia. |
| Soporte Samsung Galaxy Note10 | No validado | APK instala, permisos funcionan, cámara transmite y métricas son visibles en el dispositivo real. |
| Soporte Samsung Galaxy S24+ | No validado | APK instala, permisos funcionan, cámara transmite y métricas son visibles en el dispositivo real. |
| Versión mínima de Android | No validada | Se define una versión mínima basada en APIs requeridas y pruebas en dispositivos. |
| Controles de cámara desde escritorio | No validados | Cámara, resolución, FPS y modo automático responden según capacidades reales del dispositivo. |

## 3. Android: ejecución, cámara y pantalla bloqueada

La fuente sobre restricciones de foreground services indica que Android limita cuándo una app puede iniciar servicios en primer plano desde segundo plano. Para ChinchillaCam, esto vuelve riesgoso prometer que el video continuará siempre con pantalla bloqueada o app no visible.

Hipótesis de implementación:

- la sesión de cámara debería iniciarse con la app visible;
- la app podría requerir un foreground service con notificación mientras transmite;
- la continuidad con pantalla bloqueada debe medirse en cada dispositivo y versión de Android;
- si Android corta cámara, red o servicio, el producto debe mostrar una limitación clara en vez de prometer soporte.

Puertas de aceptación:

- iniciar transmisión con la app visible;
- bloquear pantalla y observar si continúa el video;
- medir reconexión después de desbloquear;
- registrar diferencias entre Note10 y S24+.

## 4. USB: AOA, accesorio y límites de la hipótesis

Android documenta el modo USB accessory y AOA describe un protocolo donde el accesorio USB inicia una negociación con el dispositivo Android. La fuente de AOA dice que USB debugging no es requerido para conexiones de accesorio y describe handshake y endpoints bulk para comunicación.

Esa evidencia permite formular una hipótesis: ChinchillaCam podría explorar un transporte USB sin ADB, opciones de desarrollador ni depuración USB usando un modelo accesorio o protocolo equivalente. Pero no valida por sí sola el producto final.

Límites importantes:

- no todos los dispositivos Android tienen por qué soportar AOA de forma útil para este caso;
- AOA no demuestra que Samsung Note10 o S24+ funcionen con el flujo elegido;
- AOA no demuestra que el host de escritorio en Windows 11 y macOS 13+ Apple Silicon esté implementado;
- USB debugging no requerido para AOA no equivale automáticamente a “USB directo listo”;
- si las pruebas muestran que un perfil específico no tiene transporte viable sin depuración USB, requerirla sería un fallback documentado para ese perfil, no una decisión previa de usar ADB ni una condición general del producto;
- USB tethering es otro flujo y no debe mezclarse con la hipótesis de video USB directo.

La fuente de ayuda de Google indica que USB tethering con computadoras Mac no está soportado. Esto debe leerse con precisión: impide asumir tethering USB como solución universal para macOS, pero no prueba que todo transporte USB directo sea imposible. La aceptación de ChinchillaCam debe validar su propio transporte.

Puertas de aceptación USB:

- detectar dispositivo por cable en Windows 11;
- detectar dispositivo por cable en macOS 13+ Apple Silicon;
- enviar video sin ADB, opciones de desarrollador ni depuración USB;
- reconectar después de retirar el cable;
- mantener emparejamiento persistente;
- documentar cualquier permiso o paso manual requerido;
- cuando exista un fallback validado con depuración USB, documentar el riesgo, la autorización de la computadora y cómo revocarla o desactivar la depuración después del uso.

## 5. Wi‑Fi local

Wi‑Fi local es requisito de primera versión junto con USB. La factibilidad general depende menos de una API única y más de descubrimiento local, permisos de red, firewalls y rendimiento.

Hipótesis de implementación:

- teléfono y escritorio se comunican en la misma red local;
- no se usa nube ni servidor externo;
- el QR puede transportar claves o datos de emparejamiento;
- el video puede enviarse con métricas de latencia, FPS y cuadros perdidos;
- el producto puede distinguir degradación de red de degradación de video.

Puertas de aceptación Wi‑Fi:

- conectar en una red local común;
- fallar con mensaje claro en red invitada o firewall;
- transmitir video en vivo;
- mostrar FPS reales, latencia y cuadros perdidos;
- cambiar desde o hacia USB sin nuevo QR.

## 6. Windows 11: cámara seleccionable

Microsoft documenta `MfCreateVirtualCamera` como API de Media Foundation para crear cámaras virtuales, con requisito de build 22000. Build 22000 corresponde al piso técnico de Windows 11, por eso el perfil de producto queda en Windows 11 y no promete Windows 10.

Hipótesis de implementación:

- la app de escritorio podría exponer una cámara virtual usando la API de Windows;
- el instalador podría requerir componentes o permisos específicos;
- las aplicaciones de terceros deberían poder seleccionar la cámara si el registro y el pipeline funcionan.

Puertas de aceptación:

- crear o registrar la salida como cámara seleccionable;
- abrirla desde una aplicación real de videollamada o captura;
- comprobar reconexión cuando el teléfono cambia de USB a Wi‑Fi;
- documentar limitaciones si una app externa no acepta la cámara.

## 7. macOS 13+ Apple Silicon: OBS Studio y distribución

El perfil macOS acepta OBS Studio como dependencia gratuita. La fuente de OBS sobre cámara virtual en macOS 13+ es relevante porque ese flujo puede requerir permisos, componentes y solución de problemas específicos.

Hipótesis de implementación:

- ChinchillaCam podría entregar video a OBS Studio como fuente, o automatizar parte del flujo;
- OBS Studio podría exponer `OBS Virtual Camera` a aplicaciones externas; no se debe prometer un dispositivo macOS llamado `ChinchillaCam` sin validación;
- la automatización completa puede no ser viable sin pasos manuales;
- la experiencia de permisos de macOS debe documentarse con precisión.

Puertas de aceptación macOS:

- instalar OBS Studio sin costo;
- conectar ChinchillaCam con OBS o con el mecanismo definido;
- activar la cámara virtual;
- seleccionar la salida en una aplicación externa;
- recuperar de fallos comunes de permisos o cámara virtual.

Apple Developer ID es el camino documentado por Apple para distribuir apps fuera de Mac App Store con firma de Developer ID. Como el presupuesto inicial de publicación es cero, ChinchillaCam no debe prometer firma propia, notarización ni una experiencia de instalación sin fricción si no existe una cuenta o mecanismo gratuito disponible. El documento de uso debe advertir posibles bloqueos o avisos de seguridad en macOS.

## 8. Publicación con presupuesto cero

Requisito de producto:

- APK Android gratuito en GitHub releases;
- instalador o paquete Windows gratuito en GitHub releases;
- paquete macOS gratuito en GitHub releases;
- OBS Studio como dependencia gratuita para macOS;
- sin publicación paga en tiendas;
- sin prometer code signing, notarización o Developer ID propio al inicio.

Riesgos:

- Android puede mostrar advertencias al instalar APK fuera de tienda;
- Windows puede mostrar advertencias si el instalador no está firmado;
- macOS puede bloquear o dificultar la apertura de apps no firmadas/notarizadas;
- la falta de firma puede afectar confianza del usuario y soporte.

## 9. Criterio de avance

Antes de pasar de definición a implementación completa, conviene resolver con prototipos pequeños:

1. prueba USB sin ADB, opciones de desarrollador ni depuración USB en Windows 11;
2. prueba USB sin ADB, opciones de desarrollador ni depuración USB en macOS 13+ Apple Silicon;
3. prueba Wi‑Fi local con métricas básicas;
4. prueba de cámara seleccionable en Windows 11;
5. prueba del flujo OBS en macOS 13+;
6. prueba de pantalla bloqueada en Note10 y S24+;
7. definición de versión mínima de Android.

Si alguna puerta falla, el producto debe ajustar el alcance públicamente sin convertir USB en opcional ni redefinir la primera entrega como Wi‑Fi-only sin una decisión explícita.
