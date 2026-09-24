# ChinchillaCam

ChinchillaCam es una aplicación en definición para usar un teléfono Android Samsung como cámara de video para una computadora Windows o macOS.

El objetivo del proyecto es entregar una cámara seleccionable en aplicaciones de terceros, con transporte por USB y por Wi‑Fi local, sin cuentas, sin nube y sin servidores externos.

> Estado del proyecto: definición de producto. Este repositorio todavía no contiene implementación ni pruebas de hardware. Las capacidades técnicas marcadas como hipótesis deben validarse antes de prometerse como soporte real.

## Objetivo del producto

Permitir que una persona use un Samsung Galaxy compatible como webcam de buena calidad para videollamadas, streaming o captura de video en escritorio, con una experiencia clara:

1. instala la app en Android;
2. instala el componente de escritorio en Windows 11 o macOS 13+ Apple Silicon;
3. empareja el teléfono con la computadora una vez mediante QR;
4. elige conexión por USB o por Wi‑Fi local;
5. selecciona ChinchillaCam como cámara en la aplicación de destino.

## Alcance de primera versión

La primera versión funcional debe incluir:

- video en vivo solamente;
- conexión por USB;
- conexión por Wi‑Fi en la misma red local;
- emparejamiento por QR por computadora;
- una sola computadora activa por teléfono;
- selección de cámara del teléfono cuando el hardware lo permita;
- controles básicos de calidad, resolución y FPS cuando el dispositivo lo permita;
- métricas observables de FPS reales, latencia estimada, cuadros perdidos y calidad del modo activo;
- instaladores o paquetes gratuitos publicados desde GitHub.

USB y Wi‑Fi son requisitos de primera versión. No se define un MVP Wi‑Fi-only.

## No objetivos

ChinchillaCam no busca incluir en esta etapa:

- audio;
- grabación local;
- captura de fotos;
- almacenamiento en la nube;
- cuentas de usuario;
- servidores externos;
- sincronización entre múltiples computadoras;
- publicación paga en tiendas;
- soporte prometido para Windows 10;
- soporte prometido para Mac Intel.

## Perfiles soportados previstos

| Perfil | Estado | Alcance previsto |
| --- | --- | --- |
| Android Samsung Galaxy Note10 | Referencia pendiente de validar | Teléfono Android de referencia; versión mínima de Android todavía no comprobada. |
| Android Samsung Galaxy S24+ | Referencia pendiente de validar | Teléfono Android moderno de referencia. |
| Windows 11 | Perfil de escritorio previsto | Receptor de video seleccionable como cámara en aplicaciones compatibles. |
| macOS 13+ Apple Silicon | Perfil de escritorio previsto | Integración mediante OBS Studio como dependencia aceptada para cámara virtual. |

## Hipótesis de implementación y validación

Las siguientes capacidades son objetivos de diseño, no hechos comprobados todavía:

- USB directo sin ADB ni depuración USB en Windows 11 y macOS;
- automatización o integración suficiente con OBS Studio en macOS para exponer la cámara virtual con buena experiencia;
- continuidad del video con la pantalla del teléfono bloqueada;
- soporte efectivo en Samsung Galaxy Note10 y Samsung Galaxy S24+;
- versión mínima de Android compatible;
- disponibilidad real de todos los controles de cámara deseados desde escritorio.

Cada punto debe convertirse en una validación observable antes de documentarse como soporte probado.

## Experiencia de usuario esperada

La experiencia debe priorizar claridad y control:

- el teléfono muestra el estado de conexión y la cámara activa;
- el escritorio permite iniciar, detener y cambiar entre USB y Wi‑Fi;
- el usuario puede elegir resolución y FPS manualmente;
- el modo automático prioriza fluidez y baja latencia manteniendo la mejor calidad posible;
- la calidad se muestra en cinco niveles: muy buena, buena, regular, mala y muy mala;
- la interfaz distingue calidad de red Wi‑Fi de calidad real de video;
- cambiar entre USB y Wi‑Fi puede tener una interrupción breve, pero no debe requerir reemparejar.

## Privacidad y límites de red

La primera versión debe funcionar sin cuentas y sin servidores externos. En Wi‑Fi, la comunicación se limita a la red local. En USB, el objetivo es funcionar sin Internet.

El proyecto transmite video solamente. No captura ni transmite audio.

## Puertas de aceptación observables

Antes de declarar soportada una versión funcional, deben observarse como mínimo estas puertas:

- una aplicación de videollamada o captura permite seleccionar ChinchillaCam como cámara;
- el video llega en vivo por USB;
- el video llega en vivo por Wi‑Fi local;
- el emparejamiento QR funciona una vez y persiste para reconexiones;
- solo una computadora activa recibe video desde el teléfono;
- las métricas muestran FPS reales, latencia y cuadros perdidos;
- el usuario puede distinguir si el problema observado viene de red Wi‑Fi o de calidad de video;
- los instaladores o paquetes se obtienen desde GitHub sin costo;
- las hipótesis de USB sin ADB, OBS, pantalla bloqueada, Samsung y Android mínimo tienen evidencia específica o quedan documentadas como limitaciones.

## Documentación relacionada

- [`docs/requisitos.md`](docs/requisitos.md): contrato detallado de requisitos, no objetivos y aceptación.
- [`docs/factibilidad.md`](docs/factibilidad.md): fuentes primarias, límites técnicos e hipótesis de implementación.
- [`docs/uso.md`](docs/uso.md): recorridos previstos de instalación, emparejamiento, uso, privacidad y recuperación.

## Licencia

Este proyecto usa licencia MIT. Ver [`LICENSE`](LICENSE).
