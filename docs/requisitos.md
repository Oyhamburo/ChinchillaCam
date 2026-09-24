# Requisitos de ChinchillaCam

Este documento define el contrato inicial del producto antes de escribir código. Está escrito para separar requisitos, no objetivos e hipótesis técnicas pendientes de validación.

## 1. Propósito

ChinchillaCam debe permitir usar un teléfono Android Samsung como cámara de video para una computadora de escritorio, con conexión por USB o Wi‑Fi local, y con salida seleccionable como cámara en aplicaciones de terceros.

El producto apunta a una experiencia gratuita, local y simple: instalar, emparejar por QR, conectar y usar como webcam.

## 2. Alcance funcional

### 2.1 Video solamente

La primera versión transmite video en vivo. Quedan fuera:

- audio;
- grabación;
- fotos;
- edición de video;
- almacenamiento local gestionado por la app;
- almacenamiento en la nube.

### 2.2 Transporte

La primera versión debe soportar ambos transportes:

- USB directo;
- Wi‑Fi en la misma red local.

USB y Wi‑Fi son requisitos de primera versión. No se acepta definir la primera entrega como Wi‑Fi-only.

El objetivo del modo USB es funcionar sin Internet y sin ADB. Esa condición todavía es una hipótesis de implementación y debe validarse en Windows 11 y macOS.

### 2.3 Emparejamiento

El emparejamiento previsto es mediante QR:

- se realiza una vez por computadora;
- persiste para reconexiones futuras;
- no requiere cuenta;
- no depende de un servidor externo;
- permite solo una computadora activa por teléfono.

### 2.4 Cámara y controles

La experiencia debe permitir:

- elegir la cámara del teléfono desde Android cuando corresponda;
- elegir o cambiar la cámara desde escritorio cuando sea técnicamente posible;
- cambiar cámara en tiempo real si el dispositivo lo permite;
- elegir resolución;
- elegir FPS;
- usar un modo automático que priorice fluidez y baja latencia con la mejor calidad posible.

La disponibilidad de controles avanzados depende del hardware, del sistema operativo Android y de las APIs accesibles. No se promete que todos los controles existan en todos los Samsung hasta validarlo.

### 2.5 Métricas visibles

La aplicación debe exponer indicadores comprensibles:

- calidad del modo activo en cinco niveles: muy buena, buena, regular, mala y muy mala;
- FPS reales;
- latencia estimada;
- cuadros perdidos;
- estado del transporte activo: USB o Wi‑Fi;
- distinción entre calidad de red Wi‑Fi y calidad real de video.

### 2.6 Cambio de transporte

El usuario debe poder cambiar entre USB y Wi‑Fi sin repetir el emparejamiento. Es deseable que el cambio no corte el video. Una interrupción breve es aceptable para la primera versión si se informa claramente.

## 3. Perfiles soportados previstos

### 3.1 Android

Perfiles de referencia:

- Samsung Galaxy Note10;
- Samsung Galaxy S24+.

El soporte Samsung y la versión mínima de Android son puertas de validación, no hechos probados. El Note10 requiere verificar versión de Android disponible, permisos, APIs de cámara y comportamiento con pantalla bloqueada.

### 3.2 Windows

Perfil previsto:

- Windows 11.

No se promete soporte para Windows 10 en esta definición inicial.

Puertas pendientes:

- exposición del video como cámara seleccionable en aplicaciones de terceros;
- funcionamiento USB sin ADB;
- reconexión luego de desconectar y conectar el teléfono;
- comportamiento con una sola computadora activa.

### 3.3 macOS

Perfil previsto:

- macOS 13 o superior;
- Apple Silicon;
- OBS Studio como dependencia aceptada para cámara virtual.

No se promete soporte para Mac Intel.

OBS Studio puede ser una dependencia de instalación o uso en macOS. La automatización de OBS, la configuración de cámara virtual y la experiencia final de permisos de macOS son hipótesis de implementación pendientes de validación.

## 4. Experiencia de usuario

### 4.1 Primer uso

El recorrido esperado es:

1. la persona descarga la app Android desde GitHub;
2. descarga el instalador o paquete de escritorio desde GitHub;
3. abre la app de escritorio;
4. escanea un QR desde Android;
5. elige USB o Wi‑Fi local;
6. selecciona ChinchillaCam como cámara en la aplicación de destino.

En macOS, el recorrido puede incluir instalación y habilitación de OBS Studio. La documentación debe advertirlo con claridad, sin ocultar pasos de seguridad o permisos.

### 4.2 Uso diario

Después del primer emparejamiento, el usuario debería poder:

- abrir la app Android;
- abrir la app de escritorio;
- conectar por USB o Wi‑Fi;
- ver estado, calidad y errores;
- cambiar resolución o FPS;
- usar la cámara en una app externa.

### 4.3 Pantalla bloqueada

La continuidad con pantalla bloqueada es una meta importante porque reduce consumo y evita interacción accidental. Sin embargo, todavía no está validada. Debe tratarse como puerta de aceptación por dispositivo y sistema operativo.

## 5. No objetivos y restricciones

ChinchillaCam no incluye en esta primera definición:

- audio;
- grabación;
- fotos;
- nube;
- cuentas;
- backend externo;
- servidores públicos;
- analítica remota;
- múltiples computadoras activas al mismo tiempo;
- publicación paga;
- soporte garantizado fuera de los perfiles listados.

La distribución inicial debe ser gratuita mediante GitHub. En macOS, si no hay firma propia, la documentación debe explicar la experiencia de instalación esperada sin presentarla como una app firmada o notarizada.

## 6. Hipótesis técnicas que no deben presentarse como hechos

Estas afirmaciones requieren evidencia antes de pasar a soporte declarado:

1. USB directo sin ADB en Windows 11.
2. USB directo sin ADB en macOS 13+ Apple Silicon.
3. Automatización suficiente de OBS Studio para una experiencia aceptable.
4. Exposición estable como cámara virtual o fuente seleccionable según plataforma.
5. Continuidad de video con pantalla bloqueada.
6. Soporte real en Samsung Galaxy Note10.
7. Soporte real en Samsung Galaxy S24+.
8. Versión mínima de Android compatible.
9. Controles de cámara disponibles desde escritorio.
10. Cambio entre USB y Wi‑Fi sin corte perceptible.

Si una hipótesis falla, el producto debe documentar la limitación y ajustar el alcance antes de prometer compatibilidad.

## 7. Puertas de aceptación observables

Una versión no debe declararse lista si no cumple puertas observables. Para T1, estas puertas son requisitos documentales; en etapas posteriores deberán convertirse en pruebas, demos o verificaciones manuales reproducibles.

### 7.1 Producto

- El repositorio explica qué problema resuelve ChinchillaCam.
- El README distingue alcance, no objetivos e hipótesis.
- La licencia MIT está presente.
- No hay código de aplicación en esta etapa documental.

### 7.2 USB

- Una computadora Windows 11 recibe video por USB.
- Una computadora macOS 13+ Apple Silicon recibe video por USB o mediante la integración definida.
- La conexión USB no requiere Internet.
- La afirmación de no usar ADB solo se declara soportada después de validación.

### 7.3 Wi‑Fi

- El teléfono y la computadora se conectan en la misma red local.
- El video llega sin servidores externos.
- La interfaz muestra estado y degradación de red.
- La calidad de red no se confunde con calidad de video.

### 7.4 Cámara externa

- En Windows 11, una aplicación externa puede seleccionar la salida de ChinchillaCam como cámara o fuente equivalente definida.
- En macOS, OBS Studio permite usar la salida como parte del flujo de cámara virtual previsto.
- Cualquier dependencia manual se documenta.

### 7.5 Uso y recuperación

- El emparejamiento QR persiste.
- Desconectar y reconectar no requiere crear una cuenta.
- Solo una computadora activa usa el teléfono a la vez.
- El usuario recibe errores claros si el transporte falla.
- La pantalla bloqueada se valida por dispositivo antes de declararse soportada.

## 8. Fuentes y factibilidad

El análisis técnico detallado vive en [`docs/factibilidad.md`](factibilidad.md). Ese documento reúne fuentes primarias para Android, USB, Windows, macOS, OBS Studio y distribución, y separa evidencia documental de hipótesis no validadas.

Referencias breves de contexto:

- OBS Studio es una dependencia aceptada para el perfil macOS.
- Android y Samsung son perfiles de referencia, no evidencia de compatibilidad automática.
- Windows 11 y macOS 13+ Apple Silicon son los sistemas de escritorio definidos para la primera evaluación.
- Los recorridos previstos de instalación y uso están en [`docs/uso.md`](uso.md).

## 9. Criterio de cambio de alcance

Cualquier cambio que agregue audio, nube, cuentas, servidores externos, grabación, perfiles adicionales o un MVP Wi‑Fi-only cambia el contrato del producto y debe tratarse como decisión explícita, no como detalle de implementación.
