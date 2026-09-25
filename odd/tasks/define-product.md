# Definir ChinchillaCam antes de programar

## Objetivo y motivo

Publicar en `Oyhamburo/ChinchillaCam` (repositorio público) una definición comprensible del producto, sus recorridos de uso, límites técnicos y criterios de aceptación antes de escribir código. La documentación y las interfaces previstas estarán en español. El usuario autorizó expresamente crear el repositorio, publicar los documentos mediante commit y usar licencia MIT; no autorizó implementar la aplicación.

## Alcance y restricciones

- Android Samsung Galaxy Note10 y S24+ de referencia; versión Android del Note10 pendiente de verificar. Kotlin nativo es candidato, no decisión final de arquitectura.
- Windows 11; macOS 13+ Apple Silicon con OBS Studio como dependencia aceptada para la cámara virtual. Sin soporte prometido para Windows 10 ni Mac Intel.
- Webcam seleccionable en aplicaciones de terceros; video solamente. Sin grabación, fotos, audio, cuentas, nube ni servidores externos.
- USB directo y sin Internet; preferiblemente sin opciones de desarrollador ni depuración USB. Wi-Fi solo en la misma red local. Ambos transportes son condiciones de la versión funcional, no un MVP por plataforma.
- Emparejamiento QR una vez por computadora, confianza persistente; solo una computadora activa por teléfono. Inicio de sesión con app Android visible; luego controlar desde escritorio, y validar continuidad con pantalla bloqueada.
- Elegir cámara desde teléfono y escritorio, con cambio en tiempo real; ofrecer todos los controles que el hardware permita desde escritorio. Modo automático prioriza fluidez y baja latencia manteniendo la mejor calidad posible; resolución y FPS configurables manualmente.
- Mostrar calidad del modo activo con cinco niveles (muy buena, buena, regular, mala, muy mala), FPS reales, latencia y cuadros perdidos; distinguir calidad de red Wi-Fi de calidad real de video. Cambio USB/Wi-Fi sin corte es deseable, una interrupción breve es aceptable.
- APK e instaladores gratuitos en GitHub; presupuesto cero de publicación. Instaladores macOS sin firma propia y dependencia OBS; advertir la experiencia de seguridad/instalación, sin prometer extensión propia. Licencia MIT.
- El modo USB sin ADB, opciones de desarrollador ni depuración USB en ambos sistemas, la automatización de OBS, el funcionamiento bloqueado en ambos Samsung y la versión mínima de Android siguen sujetos a validación; no convertir hipótesis en afirmaciones verificadas.

## Tareas recuperables

- [x] **T1 — Base de proyecto y contrato del producto.** Redactar `README.md`, `docs/requisitos.md` y `LICENSE` con objetivos, alcance, no objetivos, perfiles soportados, experiencia de usuario y aceptación observable. Ruta: escritor delegado (varios archivos, lectura preparatoria); superficies: esos tres archivos. Verificación: `git diff --check -- README.md docs/requisitos.md LICENSE` sin salida; revisión nativa RDD aprobada y reconocida para `README.md`, `docs/requisitos.md`, `LICENSE` (`review-36e3fb229898ae28`). Commit: `2fdd487a5988bf6b4a7c823f416d49a3b7221799` (`docs: define ChinchillaCam product contract`).
- [x] **T2 — Uso, límites y publicación.** Redactar `docs/uso.md` y `docs/factibilidad.md`: instalación en Android/Windows/macOS, QR, USB/Wi-Fi, controles, métricas, privacidad, fallos y fuentes primarias/hipótesis; revisar enlaces y coherencia con T1. Ruta: escritor delegado (dos archivos); superficies: esos dos archivos y correcciones acotadas de documentación previa. Verificación: `git diff --check -- README.md docs/requisitos.md docs/uso.md docs/factibilidad.md` sin salida; enlaces primarios presentes en `docs/factibilidad.md`; revisión nativa RDD aprobada y reconocida (`review-f0b8dc7c96f9619f`). Commit: `d5b9e3c84f00ddab8a4bf3a1544004cddc0e10a6` (`docs: document use and feasibility gates`).

## Verificación y entrega

- TDD: no aplica a esta fase exclusivamente documental; configuración/runner de proyecto todavía inexistentes, no inventar pruebas.
- Chequeos por tarea: leer el resultado, `git diff --check`, verificar rutas/enlaces locales y confirmar ausencia de archivos de código; comando y resultado exactos en el cierre. No afirmar verificaciones de dispositivos ni virtual cameras sin ejecutarlas.
- RDD global: activo según `gentle-ai review mode status` en este repositorio; inspeccionar y respetar las rutas nativas por candidato/commit si corresponden. La evaluación puede exigir bootstrap por ser repositorio vacío; no saltarse stops ni inventar recibos.
- Presupuesto estimado: ~350–450 líneas documentales escritas, más este seguimiento. Estrategia de entrega: `ask-on-risk` si el acumulado supera ~400 líneas; no se solicitó PR. Fuente del primer límite de rama: repositorio recién creado sin commits.
- Espejo Engram: guardado antes de redactar documentación en `ChinchillaCam` con topic `odd/define-product/tasks` (observación `7580`), actualizado tras T1/T2 y refrescado después de la publicación remota.
- Entrega remota confirmada: `origin/docs/define-product` quedó publicado en `9bb985705332954b6f93a3d37874a6e9b458e537` con árbol de trabajo limpio antes del ajuste acotado de nombres de cámara macOS/Windows.

## Recuperación T3 — fallback documentado de depuración USB

- [x] **T3 — Aclarar fallback de depuración USB.** Actualizar documentación en español para mantener como preferencia USB sin ADB, opciones de desarrollador ni depuración USB; si la validación por plataforma/dispositivo demuestra que no hay transporte viable sin depuración, permitir requerir depuración USB como fallback documentado, específico por plataforma/dispositivo, no como predeterminado ni supuesto silencioso. Debe aclarar seguridad/configuración, que el usuario puede desactivar o revocar depuración después del uso, y no afirmar pruebas ni decisión de ADB. Superficies previstas: `README.md`, `docs/requisitos.md`, `docs/uso.md`, `docs/factibilidad.md`, y este archivo de seguimiento. Verificación: lectura de diff; `git diff --check -- README.md docs/requisitos.md docs/uso.md docs/factibilidad.md odd/tasks/define-product.md` sin salida; ausencia de código nuevo confirmada con `git diff --name-only -- README.md docs/requisitos.md docs/uso.md docs/factibilidad.md odd/tasks/define-product.md` limitado a documentación y seguimiento; verificación independiente PASS. Revisión nativa RDD aprobada y reconocida para el candidato documental (`review-b2cef5ae9d766d20`). Pendiente: commit convencional y push de la rama `docs/usb-debugging-fallback`.
