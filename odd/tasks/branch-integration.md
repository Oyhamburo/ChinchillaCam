# Integración de las ramas Android y desktop en `main`

## 1. Objetivo

Unificar `feat/t15c-fake-usb-sustained` (Android) y `feat/desktop-video-sink` (desktop) en una rama `main` nueva, convertirla en la rama por defecto de GitHub y dejar un único estado del código para las pruebas físicas (M9).

## 2. Situación (2026-10-07)

- Base común `b3faac8`; 163 commits sólo en Android y 146 sólo en desktop.
- El código no se superpone (Android sólo `android/`; desktop `desktop/`, `packaging/`, `scripts/package-macos.sh`).
- Conflictos sólo en documentación: `docs/uso.md` (3), `docs/release.md` (agregado en ambas), `odd/tasks/complete-webcam-product.md` (2) y 11 documentos de feature con el mismo nombre en ambas ramas. `.gitignore` se mezcla solo.
- La rama por defecto de GitHub es `docs/define-product` (`32d0401`); por eso la revisión nativa toma como base `32d0401` y abarca la rama entera.

## 3. Decisiones

1. **Usuario, 2026-10-07:** crear `main`, mergear ambas ramas, pushear y ponerla como rama por defecto de GitHub (`gh repo edit --default-branch main`). Las ramas viejas no se borran.
2. **Orquestador:** `main` nace en la punta Android (`51bc281`) y recibe el desktop con un merge `--no-ff`.
3. **Orquestador:** resolución de documentos:
   - `docs/uso.md`: se conservan las secciones de ambos lados (§6 teléfono y control desde la PC, §8.1/§8.2, §9.6/§9.7) y se quita la frase que decía que el control desde la computadora no existía.
   - `docs/release.md`: una guía con «Android» y «macOS».
   - Documentos de feature duplicados: un único archivo con «Parte Android» y «Parte desktop» (encabezados bajados un nivel), sin perder evidencia.
   - `complete-webcam-product.md`: ambas series de actualizaciones, en orden cronológico inverso.

## 4. Riesgos

- Ninguno de código; el riesgo es perder evidencia en la documentación: la resolución es aditiva.

## 5. Tareas

1. [x] g1 — merge con resolución de documentos y commit de merge.
   - Evidencia g1: merge `--no-ff` de `origin/feat/desktop-video-sink` sobre la punta Android `51bc281`; 14 conflictos, todos de documentación, resueltos de forma aditiva con un script: 11 documentos de feature unificados en «Parte Android»/«Parte desktop», `docs/release.md` con secciones Android y macOS, `docs/uso.md` con ambas secciones (sin la frase desactualizada sobre el control desde la computadora), y `complete-webcam-product.md` con las 21 actualizaciones de ambas copias en orden cronológico inverso más la de integración. Sin marcadores de conflicto restantes; ningún archivo de código en conflicto.
2. [ ] g2 — verificación: suite Android completa, suites `desktop/usb-probe` y `desktop/app`, scripts de release en seco.
3. [ ] g3 — push de `main`, rama por defecto en GitHub, inspect de la revisión nativa y cierre.

## 6. Evidencia
