# YiSecure — plan por fases (diseño Obsidian Sentinel)

Comparación del diseño `stitch_smart_home_security_ui` (Cámaras, En vivo, Eventos, Ajustes) con YiSecure 0.3.1.
Cada punto indica de dónde sale: **SDK** = función ya presente en `yisdk.jar` (falta probarla), **App** = solo código
propio, **Nube** = API gw-*.xiaoyi.com.

Reglas:
- Solo se implementa lo que funciona de verdad con las cámaras del usuario; nada decorativo que finja datos.
- **PTZ real queda fuera**: las cámaras son fijas. La cruceta sigue moviendo el **zoom digital (simulado)**.
  Se desarrolla el día que haya una cámara motorizada.
- Cada fase termina con: compila, instalado por ADB, probado en el teléfono, commit.

---

## Fase 1 — Uso diario (0.4.0, instalada, en prueba)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 1.1 | **Ojo en el video** para ocultar/mostrar todos los controles e información de la cámara (chips, cruceta, Hablar, botones, cajas de la IA). Se recuerda entre sesiones. | App | hecho (sin probar) |
| 1.2 | **Eventos agrupados por día**: "Hoy", "Ayer", fecha; contador de detecciones por día. | App | hecho (sin probar) |
| 1.3 | **Nuevos / leído**: "N eventos nuevos" desde la última visita + botón "Marcar leído". | App | hecho (sin probar) |
| 1.4 | **Compartir** imagen o clip de un evento (nube e IA del teléfono) con el menú de Android. | App | hecho (sin probar) |

## Fase 2 — Avisos (0.5.0, instalada)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 2.1 | **Notificaciones** de alertas nuevas de la nube: WorkManager cada 15 min, imagen descifrada, resumen si son >4, toque → pestaña Eventos. Ajustes: interruptor (pide permiso en Android 13+) y "Revisar ahora". | App + Nube | hecho (sin probar) |
| 2.1b | Avisos de la IA del teléfono con la app en segundo plano (monitor fijo). Pasa a Fase 7 junto al modo kiosco. | App | pendiente |
| 2.2 | **Modo silencioso global**: apaga/enciende el push de todas las cámaras (`/v5/alert/push_prop`, afecta también a Kami/Yi Home) y los avisos de la app. Al salir no avisa lo ocurrido en silencio. | Nube | hecho (sin probar) |
| 2.3 | Barra de **acciones rápidas** en Cámaras: Hablar (abre la cámara principal), Monitor, Silenciosa, Eventos. Sin "Captura" directa ni "Abrir" (necesitan P2P abierto / no hay cerradura). | App | hecho (sin probar) |

## Fase 3 — Pantalla Cámaras como el diseño (0.6.0, probada)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 3.1 | **Perfiles En casa / Fuera de casa / Modo noche**: conexión P2P corta por cámara + push en la nube. En casa = interiores en privacidad y sin push; Fuera = todas encendidas con push; Noche = además IR auto y sensibilidad alta. Marca "Cámara de interior" en Ajustes de la cámara. | App + SDK + Nube | hecho |
| 3.2 | **Pánico ⚠** (con confirmación): abre la cámara principal y graba MP4 en cuanto llega el video; aplica "Fuera de casa" a todas. | App | hecho |
| 3.3 | **Principal grande + resto en cuadrícula de 2**; pie con "Movimiento hace X", Wi-Fi y batería. | App | hecho |
| 3.4 | **Etiquetas por miniatura**: ALERTA (<10 min), PRIVACIDAD, IR ACTIVO, SD 24H / SD EVENTOS (último estado leído por P2P, data/CameraStatus.kt). | App + SDK | hecho |
| 3.5 | **IA: mascotas** — sin "paquete" (COCO no tiene caja; sillas salían como paquete). Gato/Perro/Ave con confianza mínima 0,55–0,6, descarte de cajas >80 % del cuadro, eventos "Gato detectado", "en movimiento", "solo en casa" (>60 s sin personas). | App | hecho |

## Fase 4 — Eventos completos (0.7.0)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 4.1 | Etiquetas sobre las cajas "PERSONA #01 · 96%", "GATO #01 · 81%" (numeradas por tipo) y confianza + objeto en la tarjeta. Solo en eventos nuevos (los viejos no guardaban esos datos). | App | hecho |
| 4.2 | Eventos de noche (cámara en IR): badge "IR NOCTURNO", tipo en rojo oscuro "… NOCTURNO" y "N NOCTURNO" en el encabezado del día. | App | hecho |
| 4.3 | "Ver en la microSD" desde Eventos (nube e IA) y desde las alertas del vivo: abre la cámara y reproduce 5 s antes. | App + SDK | hecho |
| 4.4 | Favoritos ⭐ en eventos de la IA (no se borran al rotar los 100), filtro FAVORITOS; alertas de la nube → "Guardar en galería" (imagen o clip), porque la nube las borra a los pocos días. | App | hecho |
| 4.5 | Filtro MASCOTA con **resumen por mascota** (veces vista, última hora y cámara). Pendiente: modelo de mascotas si EfficientDet confunde gato/perro. | App | hecho (modelo pendiente) |

## Fase 5 — microSD / NVR (0.8.0)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 5.1 | Confirmar `record_mode` y `getAlertEvents`: Ajustes de la cámara → Almacenamiento muestra "Grabación: … (record_mode N)" para comparar con lo que dice Kami. | SDK | listo para confirmar |
| 5.2 | **Descargar MP4** de la microSD (30 s / 1 min / 5 min desde la posición actual). Es una captura en tiempo real de la reproducción (el SDK no baja archivos de la tarjeta): 1 min tarda 1 min. | App + SDK | hecho |
| 5.3 | Controles de reproducción: evento anterior/siguiente, ±30 s, ±5 min, pausa/reanudar. **Sin 2x/4x/8x**: el SDK solo tiene seekTo/pausePlay/resumePlay. | SDK | hecho (velocidad no posible) |
| 5.4 | **Barra de uso** de la tarjeta y **Formatear microSD** (`formatTfCard`, con confirmación). | SDK | hecho |

## Fase 6 — Ajustes del diseño + simplificación (0.9.0)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 6.0 | **Simplificación con "Avanzado"** (skill impeccable · distill). Vivo: sobre el video solo LIVE/REC, hora, IA, Hablar, foto, pantalla completa y ojo; debajo Grabar · Audio · Llamada, tarjetas rápidas, NVR, eventos; en Avanzado calidad, girar, reconectar. Ajustes de la cámara: un panel diario (encendida, alertas, LED, visión nocturna, sensibilidad) + "Privacidad y acceso" (interior + PIN); en Avanzado detecciones, modo vidrio, zona, microSD, firmware, reiniciar, detalles y eliminar. | App | hecho |
| 6.1 | **Zona de vigilancia**: se dibuja arrastrando sobre la última imagen; la IA del teléfono ignora lo de afuera y se ve sobre el video. Opcional/experimental en la cámara (`getMotionDetect`/`setMotionDetect`; escala deducida de la zona actual, se muestra lo que respondió). | App + SDK | hecho (cámara sin confirmar) |
| 6.2 | Badge **"N actualizaciones"** e INSTALAR en la lista de Ajustes (versión guardada al abrir los ajustes de cada cámara). | Nube | hecho |
| 6.3 | **Reiniciar cámara** (`rebootDevice`, con confirmación). | SDK | hecho |
| 6.4 | Sirena / luz de alerta: el SDK trae `setAlarmRing`, `playDeviceAlarmVoice` y `setAlertLightStatus`, pero sin saber qué modelos los soportan. Se deja hasta tener un modelo con sirena para probarlo. | SDK | pospuesto |
| 6.5 | **Bloqueo de la app** con huella/rostro o PIN del teléfono (BiometricPrompt); vuelve a pedir tras 30 s en segundo plano. | App | hecho |

## Fase 7 — Plataforma y calidad (v1.0.0)

| # | Tarea | Origen | Estado |
|---|-------|--------|--------|
| 7.1 | **Reconexión automática**: si no llegan cuadros en 15 s o hay error, reabre la conexión con espera 2, 4, 8… hasta 60 s ("Sin señal · reconectando en N s", "INTENTO N"). | App | hecho |
| 7.2 | **Conexión LAN por Wi-Fi** (no cable): el SDK TNP ya busca la cámara en la red local primero (bit LAN en `PPPP_Connect`, `forceRelay=false`). La app muestra **WI-FI LOCAL** (P2P con el teléfono en la misma red Wi-Fi que la cámara), **P2P INTERNET** o **RELAY**. Si sale RELAY estando en casa, el router aísla clientes Wi-Fi o la cámara está en otra red/banda. | SDK | hecho (indicador) |
| 7.3 | **Widget** de pantalla de inicio con la última imagen de la cámara principal; toque abre la app. | App | hecho |
| 7.4 | **Monitor fijo** (tablet en la pared): abre el monitor a pantalla completa y no apaga la pantalla. En la Fire 7 sigue faltando el video: sin las .so armeabi-v7a del APK universal de Kami. | App | hecho (Fire pendiente de .so) |
| 7.5 | **Respaldo** de eventos de la IA a ZIP (Drive, NAS o carpeta) desde Ajustes. | App | hecho |
| 7.6 | IA: persona **se acerca / se aleja** (tamaño de la caja) y lado por el que salió + tiempo en escena. | App | hecho |
| 7.7 | Commit + etiqueta **v1.0.0** (push y release en GitHub: el usuario con GitHub Desktop). | — | hecho (sin push) |
| 2.1b | Avisos de la IA del teléfono con la app en segundo plano (necesita un servicio en primer plano con el video abierto). | App | pendiente post-1.0 |

## Fuera de alcance por ahora

- **PTZ real** (cámaras fijas) → zoom digital simulado.
- "Abrir" cerradura, contactos de emergencia, geofencing, plan de nube Aegis: no hay hardware ni API.
