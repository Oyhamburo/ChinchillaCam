# Pruebas físicas M9 (macOS + Samsung)

Checklist para validar ChinchillaCam 0.1.0 con hardware real. Cubre T33 (USB en macOS), T35 (cámara y estados no visibles) y T36 (apps externas vía OBS), además de instalación, vinculación, calidad, errores y privacidad. Con la evidencia que salga de acá se hace T37: decidir qué pasa de hipótesis a soporte real (`docs/uso.md` §10).

Fuera de alcance por ahora: Windows (T30 y la parte Windows de T33) y Wi‑Fi (T34), porque el transporte Wi‑Fi todavía sólo existe en loopback (T17 pendiente).

Cómo marcar cada ítem: **✅** funciona, **❌** falla, **⚠️** funciona con problemas, **—** no se probó. Anotá observaciones en la columna «Notas» o en la sección de registro del final.

## 0. Preparación

### 0.1 Datos del equipo

| Dato | Valor |
| --- | --- |
| Fecha | |
| Commit de `main` probado (`git log --oneline -1`) | |
| Teléfono (modelo exacto) | |
| Versión de Android / One UI | |
| Mac (modelo y chip) | |
| Versión de macOS | |
| Cable USB (marca, USB‑C a USB‑C o adaptador) | |
| Versión de OBS Studio | |

### 0.2 Antes de empezar

- [ ] Respaldaste `~/.chinchillacam/release.jks` y `~/.chinchillacam/release-signing.env` (ver `docs/release.md`).
- [ ] Generaste los artefactos desde `main` y anotaste sus checksums:

  ```sh
  set -a; . ~/.chinchillacam/release-signing.env; set +a
  EXPECTED_CERT_SHA256=b31c0838b6b48b1f40fe7fdb5062b7b494977f5d409fa4f28cc41b67d367240c scripts/release-android.sh
  scripts/package-macos.sh
  cat dist/SHA256SUMS-*.txt
  ```

- [ ] OBS Studio instalado desde su sitio oficial.
- [ ] Para capturar errores del desktop, abrí la app desde la terminal (después de la primera apertura con Gatekeeper, punto 1.2):

  ```sh
  /Applications/ChinchillaCam.app/Contents/MacOS/chinchillacam 2>&1 | tee ~/chinchillacam-m9.log
  ```

## 1. Instalación

### 1.1 Teléfono

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 1.1.1 | Instalar `ChinchillaCam-0.1.0-android.apk` (pasando el archivo o con `adb install`) | Se instala sin errores | | |
| 1.1.2 | Ajustes → Apps → ChinchillaCam | Nombre «ChinchillaCam», versión 0.1.0 | | |
| 1.1.3 | Primer uso: permisos que pide | Sólo cámara y, en Android 13+, notificaciones | | |
| 1.1.4 | Depuración USB **desactivada** para el resto de las pruebas | El producto no depende de ADB | | |

### 1.2 Mac

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 1.2.1 | `shasum -a 256 -c SHA256SUMS-macos.txt` | OK | | |
| 1.2.2 | Descomprimir y mover `ChinchillaCam.app` a Aplicaciones; abrir con doble clic | macOS la bloquea (no notarizada) | | |
| 1.2.3 | Clic derecho → Abrir (o Privacidad y seguridad → Abrir igualmente) | Abre; la ventana muestra «ChinchillaCam 0.1.0 (preliminar)» | | |
| 1.2.4 | Al conectar el teléfono por primera vez | Si macOS pregunta «¿Permitir que el accesorio se conecte?», aceptar y anotar el texto | | |

## 2. Vinculación (QR + código corto)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 2.1 | Desktop: iniciar la vinculación | Aparece el QR con «Vence en N s» | | |
| 2.2 | Teléfono: «Vincular una computadora» y escanear el QR | El teléfono pide conectar el cable | | |
| 2.3 | Conectar el cable | Android ofrece abrir ChinchillaCam para el accesorio USB; aceptar | | |
| 2.4 | Código corto | Los dos muestran el **mismo** código de 6 dígitos | | |
| 2.5 | Confirmar en los dos lados | Se conecta; el teléfono aparece en «Teléfonos confiables» del desktop y la PC en la lista del teléfono | | |
| 2.6 | Repetir y tocar «No coincide» | No se guarda la confianza en ningún lado | | |
| 2.7 | Dejar vencer el QR (más de 120 s) | Se renueva solo; el QR viejo ya no sirve | | |

## 3. Transmisión por USB (T33, macOS)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 3.1 | Conectado, en modo automático | Estado «Transmitiendo» en el teléfono; métricas en el desktop | | FPS de llegada: ___ / decodificados: ___ |
| 3.2 | «Mostrar video para OBS» | Ventana «ChinchillaCam — Video» con imagen fluida y colores correctos | | |
| 3.3 | Latencia: filmar un cronómetro en pantalla y sacar una foto con los dos visibles | Diferencia anotada | | Latencia: ___ ms |
| 3.4 | 10 minutos seguidos | Sin cortes; métricas estables; ni el teléfono ni la Mac se recalientan | | Descartados: ___ |
| 3.5 | Desconectar el cable en plena transmisión | Teléfono: «Se perdió la conexión…» + **Reintentar** y notificación «ChinchillaCam se detuvo». Desktop: mensaje con sugerencia | | |
| 3.6 | Volver a conectar y tocar **Reintentar** | Reconecta **sin QR nuevo** | | |
| 3.7 | Cerrar y reabrir las dos apps y reconectar | Reconecta sin QR | | |
| 3.8 | Reiniciar el teléfono y reconectar | Reconecta sin QR | | |
| 3.9 | Desktop sin teléfono conectado | «No encontramos el teléfono.» fijo, sin parpadeo | | |
| 3.10 | Enchufar con el teléfono bloqueado | Aviso para desbloquear o aceptar el USB; al desbloquear, conecta | | |

## 4. Cámara, calidad y estados no visibles (T35)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 4.1 | Desde el teléfono: cada resolución disponible | Se aplica en vivo (corte de unos segundos), sin reconectar; el video del desktop cambia de tamaño | | Tiempo de aplicación: ___ s |
| 4.2 | Desde el teléfono: 30, 24 y 15 FPS | Las métricas reflejan los FPS elegidos | | |
| 4.3 | Opciones no admitidas | Deshabilitadas con el motivo | | ¿Cuáles? |
| 4.4 | Cambiar de cámara trasera a frontal desde el teléfono | Cambia en vivo | | |
| 4.5 | Desde la PC (sección «Cámara y calidad»): cámara, resolución, FPS y Automático | Se aplica en vivo; los dos lados muestran el mismo estado | | |
| 4.6 | Cambiar en el teléfono con la sección abierta en la PC | La PC se actualiza sola | | |
| 4.7 | Bloquear la pantalla del teléfono durante la transmisión | Anotar si el video sigue (meta, no garantía) | | |
| 4.8 | Pasar la app del teléfono a segundo plano | Anotar si el video sigue con la notificación visible | | |
| 4.9 | 30 minutos con la pantalla bloqueada o en segundo plano | Anotar cortes, batería y temperatura | | Batería: ___ % → ___ % |

## 5. OBS y apps externas (T36, macOS)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 5.1 | OBS: fuente «Captura de pantalla de macOS» → ventana «ChinchillaCam — Video» | Se ve el video (después de dar permiso de grabación de pantalla y reiniciar OBS) | | |
| 5.2 | Minimizar la ventana **principal** de ChinchillaCam | El video sigue fluido en OBS | | |
| 5.3 | Ajustar la fuente al lienzo e «Iniciar cámara virtual» | Sin errores | | |
| 5.4 | FaceTime: elegir «OBS Virtual Camera» | Se ve el video | | |
| 5.5 | Google Meet en el navegador | Se ve el video | | Navegador: ___ |
| 5.6 | Zoom (si lo usás) | Se ve el video | | |
| 5.7 | Otra app que uses habitualmente | Se ve el video | | App: ___ |

## 6. Errores y recuperación (T27)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 6.1 | Quitar el permiso de cámara desde Ajustes e intentar transmitir | Mensaje sin texto técnico + **Abrir ajustes** | | |
| 6.2 | Abrir la cámara del sistema en el teléfono durante la transmisión | Mensaje de cámara + **Reintentar cámara**, que recupera el video sin desconectar | | |
| 6.3 | En el desktop, si deja de llegar video más de 5 s con la sesión activa | «No llega video del teléfono.» con sugerencia; se limpia al volver el video | | ¿Cómo se provocó? |
| 6.4 | Olvidar el teléfono en el desktop y reconectar | El desktop no lo reconoce; el teléfono ofrece **Vincular de nuevo** | | |
| 6.5 | Ningún mensaje muestra texto técnico, en inglés ni de excepciones | | | |

## 7. Privacidad (T28)

| # | Prueba | Esperado | Resultado | Notas |
| --- | --- | --- | --- | --- |
| 7.1 | Teléfono: Ajustes → Apps → ChinchillaCam → Permisos | Sólo cámara y notificaciones | | |
| 7.2 | Mac, con la app transmitiendo: `lsof -i -a -c chinchillacam` | Sin conexiones de red | | |
| 7.3 | Mac: `ls -l ~/Library/Application\ Support/ChinchillaCam ~/Library/Application\ Support/ChinchillaCam/identity` | Carpetas `drwx------`; archivos `-rw-------` | | |
| 7.4 | «Olvidar» en los dos lados | Desaparece de las listas; hace falta vincular de nuevo | | |

## 8. Registro

### 8.1 Problemas encontrados

| # de prueba | Qué pasó | Cómo reproducirlo | Log o captura |
| --- | --- | --- | --- |
| | | | |

### 8.2 Logs útiles

- Desktop: `~/chinchillacam-m9.log` (si abriste la app desde la terminal, punto 0.2).
- Teléfono, sólo si la app se cerró sola: activá temporalmente la depuración USB y ejecutá `adb logcat -b crash -d > ~/chinchillacam-crash.txt`. La app no escribe logs propios (es una garantía de privacidad), así que el detalle de cada falla está en el mensaje que muestra la UI.

### 8.3 Conclusión para T37

| Flujo (`docs/uso.md` §10) | Evidencia | ¿Pasa a soporte real? |
| --- | --- | --- |
| APK instalado y ejecutado en el Samsung de referencia | | |
| Video por USB en macOS 13+ Apple Silicon | | |
| Flujo OBS funcional en apps externas | | |
| Reconexión sin cuenta ni QR nuevo | | |
| Métricas visibles | | |
| Comportamiento con pantalla bloqueada | | |
| Instalación sin firma paga (Gatekeeper documentado) | | |
