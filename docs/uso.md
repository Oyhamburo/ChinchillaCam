# Uso previsto de ChinchillaCam

> Estado: definición de producto antes de escribir código. Este documento describe recorridos de instalación y uso previstos. No es una guía validada de instalación, no reemplaza pruebas de hardware y no confirma compatibilidad real.

ChinchillaCam busca convertir un teléfono Android Samsung en cámara de video para Windows 11 o macOS 13+ Apple Silicon, con conexión por USB o por Wi‑Fi local, sin cuentas, sin nube y sin servidores externos.

## 1. Antes de empezar

La primera versión funcional debe cubrir estos perfiles:

- teléfono Android Samsung compatible, con Samsung Galaxy Note10 y Samsung Galaxy S24+ como referencias pendientes de validar;
- computadora Windows 11;
- computadora macOS 13+ Apple Silicon;
- OBS Studio gratuito como dependencia aceptada en macOS para el flujo de cámara virtual;
- red local para Wi‑Fi, o cable USB para el modo USB.

No se promete soporte para Windows 10, Mac Intel, otros teléfonos, audio, grabación, fotos, nube ni varias computadoras activas a la vez.

## 2. Instalación prevista desde GitHub

### 2.1 Android: APK desde GitHub

Flujo previsto (versión 0.1.0, preliminar: todavía sin pruebas en hardware, M9):

1. abrir la página de releases de GitHub del proyecto;
2. descargar `ChinchillaCam-0.1.0-android.apk` y `SHA256SUMS-android.txt`, y comprobar el archivo (por ejemplo con `shasum -a 256 -c SHA256SUMS-android.txt` en una computadora);
3. permitir la instalación desde el navegador o gestor de archivos usado;
4. instalar la app;
5. abrir ChinchillaCam y conceder los permisos que Android pida: cámara y, en Android 13 o posterior, notificaciones (la app no pide permisos de red).

Riesgos pendientes:

- la versión mínima de Android todavía es una hipótesis no validada;
- el soporte real en Samsung Galaxy Note10 y S24+ debe comprobarse en dispositivo;
- la continuidad con pantalla bloqueada debe validarse por dispositivo y versión de Android.

### 2.2 Windows 11: instalador desde GitHub

Flujo previsto:

1. abrir la página de releases de GitHub;
2. descargar el instalador o paquete gratuito para Windows 11;
3. instalar la app de escritorio;
4. abrir ChinchillaCam Desktop;
5. emparejar el teléfono por QR;
6. seleccionar `ChinchillaCam` como cámara en la aplicación de videollamada, streaming o captura.

Puertas pendientes:

- confirmar que Windows 11 puede exponer la salida como cámara seleccionable con la implementación elegida;
- validar USB directo sin ADB, opciones de desarrollador ni depuración USB;
- validar reconexión después de desconectar y volver a conectar el cable.

### 2.3 macOS 13+ Apple Silicon: paquete desde GitHub y OBS Studio

Flujo previsto:

1. instalar OBS Studio desde su sitio oficial si todavía no está instalado;
2. abrir la página de releases de GitHub de ChinchillaCam;
3. descargar el paquete gratuito para macOS Apple Silicon;
4. instalar o abrir la app de escritorio;
5. aceptar los permisos de macOS que correspondan;
6. configurar o habilitar el flujo con OBS Studio y su cámara virtual;
7. emparejar el teléfono por QR;
8. seleccionar `OBS Virtual Camera` o la fuente OBS documentada en la aplicación de destino; no se promete un dispositivo llamado `ChinchillaCam` en macOS sin validación futura.

Restricciones importantes:

- el presupuesto inicial de publicación es cero;
- no se promete firma propia, notarización ni cuenta Apple Developer ID al inicio;
- si el paquete no está firmado o notarizado, macOS puede mostrar advertencias de seguridad o bloquear la apertura hasta que el usuario la autorice manualmente;
- la automatización de OBS Studio y la experiencia final de cámara virtual son hipótesis no validadas.

## 3. Emparejamiento por QR

El emparejamiento previsto ocurre una vez por computadora:

1. el usuario abre la app de escritorio;
2. la app muestra un código QR;
3. el usuario abre la app Android y elige emparejar;
4. Android escanea el QR;
5. ambos extremos guardan una confianza local para reconexiones futuras.

Reglas del producto:

- no hay cuenta;
- no hay servidor externo para iniciar sesión;
- el emparejamiento persiste localmente;
- solo una computadora activa puede usar el teléfono a la vez;
- cambiar entre USB y Wi‑Fi no debe exigir escanear otro QR.

## 4. Uso por USB

Objetivo de uso:

1. emparejar el teléfono y la computadora;
2. conectar el teléfono por cable USB;
3. elegir USB como transporte activo;
4. iniciar video;
5. seleccionar la salida de cámara correspondiente en la aplicación externa: `ChinchillaCam` en Windows o `OBS Virtual Camera` en macOS con OBS Studio.

Condiciones de aceptación de primera versión:

- USB debe estar disponible junto con Wi‑Fi; no es una mejora posterior opcional;
- el modo USB debe funcionar sin Internet;
- el objetivo es no requerir ADB, depuración USB ni opciones de desarrollador;
- si una validación futura demuestra que una plataforma o dispositivo no tiene transporte USB viable sin depuración USB, esa exigencia debe aparecer como fallback específico antes de usarlo, con advertencia de seguridad y pasos para revocar la autorización o volver a desactivar la depuración después.

Estado técnico:

- USB directo sin ADB, opciones de desarrollador ni depuración USB en Windows 11 está no validado;
- USB directo sin ADB, opciones de desarrollador ni depuración USB en macOS 13+ Apple Silicon está no validado;
- el hecho de que Android tenga protocolos USB accesorios no demuestra por sí solo que el host de escritorio en Windows/macOS ya funcione;
- USB tethering no debe confundirse con transporte USB directo de video.

### 4.1 Smoke USB host↔teléfono del prototipo AOA

> Estado: guía de prueba manual para el prototipo de transporte. Todavía no es soporte de webcam, no valida Samsung reales, no instala drivers y no convierte ADB en transporte de producto.

Objetivo del smoke:

1. construir el APK Android de prueba;
2. instalarlo manualmente en el teléfono si se decide ejecutar la prueba física;
3. iniciar desde la app una solicitud explícita de permiso USB accessory;
4. ejecutar el host Rust con `--live-bulk-smoke` contra un `VID:PID` explícito;
5. comprobar un frame pequeño host→teléfono y un ACK teléfono→host.

Preparación local automatizada:

```bash
ANDROID_HOME=$HOME/Library/Android/sdk \
  "$HOME/.gradle/wrapper/dists/gradle-8.0.1-all/aro4hu1c3oeioove7l0i4i14o/gradle-8.0.1/bin/gradle" \
  :android:usb-probe:assembleDebug

test -f android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk

PATH=$HOME/.cargo/bin:$PATH \
  cargo test --manifest-path desktop/usb-probe/Cargo.toml
```

APK esperado:

```text
android/usb-probe/build/outputs/apk/debug/usb-probe-debug.apk
```

Plan host sin tocar hardware:

```bash
PATH=$HOME/.cargo/bin:$PATH \
  cargo run --manifest-path desktop/usb-probe/Cargo.toml -- \
  --dry-run --device VID:PID
```

Ese comando solo imprime el plan AOA. No abre el dispositivo, no reclama endpoints y no valida hardware.

Prueba física pendiente, cuando el usuario la autorice explícitamente:

```bash
PATH=$HOME/.cargo/bin:$PATH \
  cargo run --manifest-path desktop/usb-probe/Cargo.toml -- \
  --live-bulk-smoke --device VID:PID
```

Resultado esperado si el prototipo funciona en ese equipo:

- en Android, la pantalla muestra que recibió un frame y envió un ACK para el mismo `stream_id`;
- en host, el comando termina después de leer un frame de respuesta acotado;
- el protocolo del frame es `u32` little-endian `stream_id`, `u32` little-endian `payload_len` y payload acotado;
- cualquier timeout, permiso denegado, accesorio equivocado, callback duplicado/stale, descriptor inesperado o endpoint faltante debe fallar cerrado y mostrarse como diagnóstico, no como éxito parcial.

Notas de plataforma:

- Windows puede requerir una asociación manual compatible con libusb/WinUSB para que `rusb` acceda al dispositivo; esta guía no automatiza drivers, no recomienda Zadig como paso obligatorio y no cambia configuración del sistema.
- macOS puede requerir permisos del sistema o acceso USB permitido por el usuario; todavía no hay validación en macOS real.
- ADB puede usarse solo como herramienta externa de instalación si el usuario lo decide durante pruebas, pero no forma parte del transporte de producto ni de la aceptación USB de ChinchillaCam.
- Hasta ejecutar esta prueba en dispositivos reales, no hay afirmación de compatibilidad con Samsung Galaxy Note10, Samsung Galaxy S24+, Windows 11 ni macOS 13+ Apple Silicon.

## 5. Uso por Wi‑Fi local

Objetivo de uso:

1. conectar teléfono y computadora a la misma red local;
2. abrir ambas apps;
3. elegir Wi‑Fi como transporte activo;
4. iniciar video;
5. seleccionar la salida de cámara correspondiente en la aplicación externa: `ChinchillaCam` en Windows o `OBS Virtual Camera` en macOS con OBS Studio.

Condiciones de aceptación:

- Wi‑Fi debe estar disponible junto con USB en la primera versión funcional;
- no debe requerir nube ni servidores externos;
- la interfaz debe distinguir problemas de red Wi‑Fi de problemas de calidad real de video;
- si la red local bloquea el descubrimiento o el tráfico, la app debe mostrar un error accionable.

## 6. Controles de cámara y calidad

La experiencia prevista debe permitir, cuando el dispositivo y el sistema lo permitan:

- elegir cámara frontal, trasera u otra cámara disponible;
- cambiar de cámara durante la sesión;
- elegir resolución;
- elegir FPS;
- usar modo automático;
- iniciar y detener transmisión;
- cambiar entre USB y Wi‑Fi sin reemparejar.

El modo automático debe priorizar fluidez y baja latencia con la mejor calidad posible. Los controles disponibles desde escritorio son una hipótesis hasta validar las APIs, permisos y comportamiento de cada teléfono.

Estado actual (T26, pendiente de validar con hardware en M9): la pantalla de conexión del teléfono permite elegir la cámara, la resolución (1920 × 1080, 1280 × 720, 960 × 540 o 640 × 480) y los FPS (30, 24 o 15), o dejar el modo **Automático** (1280 × 720 a 30 FPS si la cámara lo admite). Las opciones que la cámara no admite aparecen deshabilitadas con el motivo. Si la sesión está activa, el cambio se aplica en vivo: el video se corta unos segundos mientras se reinicia la cámara, sin reconectar. El control desde la computadora todavía no existe.

Control desde la computadora (pendiente de validar con hardware en M9): con el teléfono conectado, la ventana de ChinchillaCam en la computadora muestra la sección **Cámara y calidad** con las mismas opciones que el teléfono (las no admitidas aparecen deshabilitadas con el motivo al pasar el mouse). Teléfono y computadora editan una sola preferencia: gana el último cambio, se aplica en vivo y los dos muestran el mismo estado. Con una versión vieja del teléfono que no anuncia esta función, la sección no aparece.

## 7. Métricas visibles

ChinchillaCam debe mostrar métricas entendibles para diagnosticar problemas:

- transporte activo: USB o Wi‑Fi;
- calidad del modo activo en cinco niveles: muy buena, buena, regular, mala y muy mala;
- FPS reales;
- latencia estimada;
- cuadros perdidos;
- estado de conexión;
- causa probable del problema cuando sea posible.

La calidad de red Wi‑Fi y la calidad real de video son conceptos distintos. Una red puede estar bien y aun así haber baja calidad por resolución, FPS, codificación o límites del teléfono. También puede haber buena cámara pero mala red local.

## 8. Privacidad y red local

La primera versión se define con estas garantías de producto:

- no hay cuentas;
- no hay nube;
- no hay servidores externos;
- no hay audio;
- no hay grabación por parte de ChinchillaCam;
- en Wi‑Fi, la comunicación ocurre dentro de la red local;
- en USB, el objetivo es funcionar sin Internet.

La app debe explicar qué permisos usa y por qué. Cualquier telemetría remota, analítica, cuenta, backend o servidor público quedaría fuera del alcance actual y requeriría una decisión explícita de cambio de producto.

### 8.1 Datos en el teléfono y permisos

Estado actual (T28), protegido por tests de contrato (`PrivacyContractTest`, `NoNetworkListenerContractTest`):

| Dato | Dónde se guarda | Cómo borrarlo |
| --- | --- | --- |
| Identidad segura del teléfono (clave privada) | Android Keystore; la clave no se puede exportar | Borrar los datos de la app o desinstalarla |
| Computadoras vinculadas (huella de su identidad y nombre) | Preferencias privadas de la app | **Olvidar** en la lista de computadoras, o borrar los datos de la app |
| Cámara, resolución y FPS elegidos | Preferencias privadas de la app | Elegir **Automático**, o borrar los datos de la app |

La app tiene desactivado el backup de Android (`allowBackup="false"`): estos datos no se suben a la nube ni se copian a otro teléfono. No guarda video, fotos ni audio, y no escribe en el almacenamiento compartido.

Permisos que pide y por qué:

- **Cámara**: para transmitir el video y para escanear el QR de vinculación.
- **Notificaciones** (Android 13 o posterior): para avisar si la transmisión se detiene por un error.
- **Servicio en primer plano de cámara**: para que la transmisión siga mientras la notificación visible está activa.

No pide permisos de Internet, red, Wi‑Fi, micrófono ni almacenamiento. Su única dependencia externa es la biblioteca de lectura de QR (ZXing), que funciona sin red.

## 9. Fallos y recuperación

La experiencia debe cubrir fallos comunes con mensajes claros.

### 9.1 Falla el emparejamiento QR

Recuperación prevista:

- mostrar si el QR venció;
- permitir generar un QR nuevo;
- indicar si teléfono y escritorio no pueden comunicarse;
- permitir borrar un emparejamiento local y repetir el proceso.

### 9.2 Falla USB

Recuperación prevista:

- indicar si el cable no fue detectado;
- sugerir probar otro puerto o cable;
- mostrar si el transporte USB no está soportado por la plataforma actual;
- permitir cambiar a Wi‑Fi sin reemparejar;
- si el perfil documentado requiere depuración USB como fallback, explicar cómo revocar la autorización de la computadora y desactivar depuración USB cuando el usuario termine.

### 9.3 Falla Wi‑Fi

Recuperación prevista:

- indicar si teléfono y computadora no parecen estar en la misma red local;
- advertir posibles bloqueos de firewall, red invitada o aislamiento de clientes;
- mostrar degradación de red;
- permitir cambiar a USB sin reemparejar.

### 9.4 Falla la cámara externa

Recuperación prevista:

- confirmar si ChinchillaCam está transmitiendo video internamente;
- explicar si la aplicación de destino no muestra la cámara;
- en macOS, guiar hacia la revisión de OBS Studio y cámara virtual;
- en Windows, indicar si la cámara virtual o integración del sistema no está disponible.

### 9.5 Pantalla bloqueada o app en segundo plano

La continuidad con pantalla bloqueada es una meta, no un hecho validado. Si falla, el producto debe informar que la sesión requiere mantener la app visible o el teléfono desbloqueado hasta completar la validación por dispositivo.

### 9.6 Mensajes del teléfono y qué hacer

Estado actual (T27, pendiente de validar con hardware en M9). El teléfono muestra un mensaje sin detalles técnicos y un botón para salir de la falla. Si la sesión se detiene por un error, además deja una notificación «ChinchillaCam se detuvo» que abre la app (en Android 13 o posterior, sólo si se aceptó el permiso de notificaciones).

| Situación | Qué hacer |
| --- | --- |
| Se perdió la conexión con la computadora, se desconectó el cable o el envío se saturó | **Reintentar** vuelve a conectar con la última computadora usada. Revisá el cable y que la app de la computadora esté abierta. |
| La computadora y el teléfono no se entendieron | Actualizá ChinchillaCam en los dos y tocá **Reintentar**. |
| La computadora no reconoce al teléfono o cambió su identidad | **Vincular de nuevo** y escaneá el QR nuevo. |
| No se pudo abrir la cámara o preparar el video durante la transmisión | **Reintentar cámara** reinicia sólo la cámara, sin desconectar. Cerrá otras apps que usen la cámara. |
| Falta el permiso de cámara | **Abrir ajustes**, permití la cámara y volvé a la app. |
| El QR venció, no es válido o falló la vinculación | **Vincular una computadora** y escaneá un QR nuevo. |

## 10. Criterio para declarar soporte real

Un flujo deja de ser hipótesis y pasa a soporte real solo cuando existe evidencia reproducible. Como mínimo, deben validarse:

- APK instalado y ejecutado en los Samsung de referencia;
- video por USB en Windows 11;
- video por USB en macOS 13+ Apple Silicon o limitación documentada;
- video por Wi‑Fi local;
- cámara seleccionable o flujo OBS funcional en apps externas;
- reconexión sin cuenta ni nuevo QR;
- métricas visibles;
- comportamiento con pantalla bloqueada;
- experiencia de instalación sin prometer firma o publicación paga inexistente.
