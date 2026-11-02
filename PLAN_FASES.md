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

## Fase 5 — microSD / NVR

| # | Tarea | Origen |
|---|-------|--------|
| 5.1 | Confirmar en cámara: modo continua/solo movimiento (`record_mode`) y eventos de tarjeta (`getAlertEvents`). | SDK |
| 5.2 | **Descargar un tramo** de la microSD a MP4 (marcar inicio/fin en la línea de tiempo; reusa `Mp4Recorder`). | App + SDK |
| 5.3 | Velocidad 2x/4x/8x y saltar al evento anterior/siguiente. | SDK |
| 5.4 | **Barra de uso** de la tarjeta (% usado, sobrescritura circular) y **Formatear microSD** (`formatTfCard`, con confirmación). | SDK |

## Fase 6 — Ajustes del diseño

| # | Tarea | Origen |
|---|-------|--------|
| 6.1 | **Zonas de actividad**: dibujar rectángulo sobre el video (`setMotionRect`). | SDK |
| 6.2 | Badge **"N actualizaciones"** e INSTALAR en la lista de dispositivos (reusa FirmwareUpdate). | Nube + SDK |
| 6.3 | **Reiniciar cámara** (`rebootDevice`). | SDK |
| 6.4 | **Sirena / luz de alerta** solo si el modelo la tiene (`setAlertLightStatus`); si no, no se muestra. | SDK |
| 6.5 | Bloqueo de la app con huella (BiometricPrompt), además del PIN por cámara. | App |

## Fase 7 — Plataforma y calidad

| # | Tarea | Origen |
|---|-------|--------|
| 7.1 | Reconexión automática P2P con espera creciente cuando la cámara se cae. | App |
| 7.2 | Conexión LAN directa (sin relay) → menos retraso y 2K. | SDK |
| 7.3 | Widget de Android con la última captura por cámara. | App |
| 7.4 | Fire 7 como monitor fijo (modo kiosco) — requiere las .so armeabi-v7a del APK universal de Kami. | App |
| 7.5 | Respaldo de eventos de la IA a una carpeta / NAS. | App |
| 7.6 | IA: dirección del movimiento ("se acerca/se aleja") y tiempo en escena. | App |
| 7.7 | Commit + release en GitHub de cada fase (0.3.0 y 0.3.1 siguen sin commit). | — |

## Fuera de alcance por ahora

- **PTZ real** (cámaras fijas) → zoom digital simulado.
- "Abrir" cerradura, contactos de emergencia, geofencing, plan de nube Aegis: no hay hardware ni API.
