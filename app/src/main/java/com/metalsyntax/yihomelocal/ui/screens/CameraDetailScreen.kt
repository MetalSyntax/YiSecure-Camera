package com.metalsyntax.yihomelocal.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.Gallery
import com.metalsyntax.yihomelocal.data.Store
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.p2p.DeviceInfo
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import com.xiaoyi.camera.sdk.AntsCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraDetailScreen(
    camera: CameraDevice, store: Store, cloud: YiCloud, account: YiCloud.Session,
    onBack: () -> Unit, onEdit: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Conectando…") }
    var session by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf("") }
    var quality by remember { mutableIntStateOf(store.quality) }
    var audio by remember { mutableStateOf(store.audio) }
    var flipped by remember { mutableStateOf(store.flipped(camera.uid)) }
    var cam by remember { mutableStateOf<AntsCamera?>(null) }
    var full by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recStart by remember { mutableLongStateOf(0L) }
    var talking by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val tools = remember { LiveTools() }
    // zoom con dos dedos sobre el video (doble toque = restablecer)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // reproducción de la microSD: hora pedida (null = en vivo) y hora del último cuadro recibido
    var sdAt by remember { mutableStateOf<Long?>(null) }
    var frameSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        while (true) { clock = f.format(Date()); delay(1000) }
    }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }
    val live = status.startsWith("En vivo") || status.startsWith("Conectado")

    ImmersiveLandscape(full)
    BackHandler(enabled = full) { full = false }

    fun capture() {
        tools.capture { bmp ->
            if (bmp == null) { message = "Aún no hay imagen para capturar"; return@capture }
            scope.launch {
                message = runCatching { withContext(Dispatchers.IO) { "Captura guardada en " + Gallery.saveImage(ctx, camera.name, bmp) } }
                    .getOrElse { "No se pudo guardar: ${it.message}" }
            }
        }
    }

    fun toggleRecording() {
        if (!recording) {
            tools.startRecording(Gallery.tempVideo(ctx, camera.name)); recStart = System.currentTimeMillis(); recording = true
        } else {
            recording = false
            scope.launch {
                val path = runCatching { withContext(Dispatchers.IO) { tools.stopRecording(ctx) } }.getOrNull()
                message = if (path != null) "Video guardado en $path" else "No se grabó ningún cuadro (espera el video)"
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) message = "Sin permiso de micrófono no se puede hablar"
    }

    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.verticalScroll(rememberScrollState()))) {
        if (!full) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PureWhite) }
            Column(Modifier.weight(1f)) {
                Text(camera.name, color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Mono("${camera.model} · ${status.uppercase()}", if (live) MutedText else CrimsonRed, 10.sp)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Tune, "Editar cámara", tint = MutedText) }
        }

        Box(
            (if (full) Modifier.fillMaxSize()
            else Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).panel().background(Color.Black)).clipToBounds(),
        ) {
            LiveView(
                camera, session, quality, audio, { status = it }, { cam = it },
                Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                tools, onFrameTime = { frameSec = it },
            )
            // capa de gestos encima del video: pellizco/arrastre solo con 2 dedos o ya con zoom, para no bloquear el scroll
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
            }.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2 || zoom > 1f) {
                            zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                            val maxX = (zoom - 1f) * size.width / 2f
                            val maxY = (zoom - 1f) * size.height / 2f
                            val p = pan + ev.calculatePan()
                            pan = Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            })
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sdAt != null) StatusBadge(true, "SD " + sdTime(frameSec, sdAt!!))
                else StatusBadge(live, if (live) "LIVE" else "…")
                if (zoom > 1.05f) StatusBadge(true, "%.1fx".format(zoom))
                if (recording) {
                    val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
                    clock.length // recomponer cada segundo con el reloj
                    StatusBadge(true, "REC %02d:%02d".format(secs / 60, secs % 60))
                }
            }
            Mono(
                clock, OnAccent, 11.sp,
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            if (audio) Mono(
                "AUDIO ON", OnAccent, 10.sp,
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            IconButton(
                onClick = { full = !full },
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            ) {
                Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    if (full) "Salir de pantalla completa" else "Pantalla completa", tint = OnAccent)
            }
        }
        if (full) return@Column

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // calidad: el cambio lo aplica la cámara; el decodificador se reconfigura solo al llegar el nuevo tamaño
            Segmented(listOf(YiP2p.QUALITY_AUTO to "AUTO", YiP2p.QUALITY_SD to "SD", YiP2p.QUALITY_HIGH to "2K"), quality) { q ->
                quality = q; store.quality = q; cam?.let { YiP2p.setQuality(it, q) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control(Icons.Default.PhotoCamera, "Captura", "A galería", false, Modifier.weight(1f).clickable(enabled = live) { capture() })
                Control(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, "Grabar",
                    if (recording) "Detener" else "MP4", recording, Modifier.weight(1f).clickable(enabled = live || recording) { toggleRecording() })
                Control(Icons.Default.Mic, "Hablar", if (talking) "Hablando…" else "Mantener", talking, Modifier.weight(1f).pointerInput(cam) {
                    detectTapGestures(onPress = {
                        val c = cam ?: return@detectTapGestures
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                        }
                        val ok = runCatching { YiP2p.startTalk(c) }.onFailure { message = "No se pudo abrir el micrófono: ${it.message}" }.isSuccess
                        if (!ok) return@detectTapGestures
                        talking = true
                        tryAwaitRelease()
                        runCatching { YiP2p.stopTalk(c) }
                        talking = false
                    })
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control(if (audio) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    "Audio", if (audio) "Activado" else "Silencio", audio, Modifier.weight(1f).clickable {
                        audio = !audio; store.audio = audio; cam?.let { YiP2p.setAudio(it, audio) }
                    })
                Control(Icons.Default.ScreenRotation, "Girar", if (flipped) "180°" else "Normal", flipped, Modifier.weight(1f).clickable {
                    flipped = !flipped; store.setFlipped(camera.uid, flipped); cam?.let { YiP2p.setFlipped(it, flipped) }
                })
                Control(Icons.Default.Refresh, "Reconectar", "P2P", false, Modifier.weight(1f).clickable {
                    status = "Reconectando…"; sdAt = null; session++
                })
            }
            // intercomunicador manos libres: hablar y escuchar a la vez con cancelación de eco, hasta colgar
            Control(if (calling) Icons.Default.CallEnd else Icons.Default.Call, "Intercomunicador",
                if (calling) "En llamada · toca para colgar" else "Manos libres (habla y escucha)", calling,
                Modifier.fillMaxWidth().clickable(enabled = cam != null) {
                    val c = cam ?: return@clickable
                    if (calling) {
                        runCatching { YiP2p.stopTalk(c) }; YiP2p.setAudio(c, audio); calling = false
                    } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        calling = runCatching { YiP2p.startIntercom(c) }.onFailure { message = "No se pudo iniciar: ${it.message}" }.isSuccess
                    }
                })
            message?.let { Text(it, color = MutedText, fontSize = 12.sp) }

            SdTimeline(cam, sdAt, frameSec, onPlay = { ms -> cam?.let { YiP2p.playSd(it, ms); sdAt = ms; frameSec = 0 } },
                onLive = { cam?.let { YiP2p.goLive(it) }; sdAt = null })

            CameraSettings(camera, cam, cloud, account)

            SectionTitle("Detalles")
            Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Detail("Modelo", camera.model)
                Detail("UID", camera.uid)
                Detail("P2P", if (camera.tnpKey.isNotEmpty()) "TNP · ${camera.p2pId}" else "TUTK")
                if (camera.localIp.isNotEmpty()) Detail("IP local", camera.localIp)
                Detail("Cifrado", if (camera.encrypted) "Sí" else "No")
            }
        }
    }
}

/**
 * Ajustes que la propia cámara guarda (mismos comandos P2P que Yi Home): se leen con getDeviceInfo al conectar.
 * Las alertas en la nube (pushflag) se leen/escriben en la API de Yi.
 */
@Composable
private fun CameraSettings(camera: CameraDevice, cam: AntsCamera?, cloud: YiCloud, account: YiCloud.Session) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var push by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cam) {
        val c = cam ?: return@LaunchedEffect
        delay(1500) // dejar que el SDK termine de autenticar
        info = YiP2p.info(c) ?: YiP2p.info(c)
        version = YiP2p.version(c)
        if (info == null) error = "La cámara no respondió a la lectura de ajustes"
    }
    LaunchedEffect(camera.uid) {
        push = runCatching { withContext(Dispatchers.IO) { cloud.alertPush(account, camera.uid) } }.getOrNull()
    }

    // cada comando devuelve el estado nuevo de la cámara; si no responde se deja el anterior
    fun apply(cmd: suspend (AntsCamera) -> DeviceInfo?) {
        val c = cam ?: return
        busy = true; error = null
        scope.launch {
            val r = cmd(c)
            if (r != null) info = r else error = "La cámara no confirmó el cambio"
            busy = false
        }
    }

    SectionTitle("Ajustes de la cámara") {
        if (busy) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
    }
    val i = info
    if (i == null) {
        Text(error ?: if (cam == null) "Conecta para leer los ajustes" else "Leyendo ajustes de la cámara…", color = MutedText, fontSize = 13.sp)
    } else {
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Toggle("Cámara encendida", "Apagada = modo privacidad, no transmite", i.close_camera.toInt() == 0) { on ->
                apply { YiP2p.setCameraOn(it, on) }
            }
            Toggle("Luz de estado", "LED del frente de la cámara", i.close_light.toInt() != 1) { on -> apply { YiP2p.setLed(it, on) } }
            Toggle("Modo vidrio", "Detrás de una ventana: apaga IR y LED para que no se reflejen", i.close_light.toInt() == 1 && i.day_night_mode.toInt() == 2) { on ->
                apply { YiP2p.setGlassMode(it, on) }
            }
        }
        SectionTitle("Detecciones")
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Toggle("Solo personas", "Alertas solo cuando hay personas (si no, cualquier movimiento)", i.personDetectionEnabled()) { on ->
                apply { YiP2p.setPersonDetection(it, on) }
            }
            Toggle("Sonido anormal", "Ruidos fuertes, golpes, alarmas", i.v1_extend_abnormal_sound.toInt() == 2 || i.v2_extend_abnormal_sound.toInt() == 2) { on ->
                apply { YiP2p.setSoundDetection(it, on) }
            }
            Toggle("Llanto de bebé", "Avisa si detecta llanto", i.baby_crying_mode.toInt() == 2) { on -> apply { YiP2p.setBabyCry(it, on) } }
        }
        SectionTitle("Imagen")
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Visión nocturna", color = PureWhite, fontSize = 14.sp)
                Segmented(listOf(1 to "AUTO", 3 to "ON", 2 to "OFF"), i.day_night_mode.toInt()) { m -> apply { YiP2p.setNightVision(it, m) } }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sensibilidad de movimiento", color = PureWhite, fontSize = 14.sp)
                Segmented(listOf(2 to "BAJA", 1 to "MEDIA", 0 to "ALTA"), i.alarm_sensitivity.toInt()) { v -> apply { YiP2p.setSensitivity(it, v) } }
            }
            error?.let { Text(it, color = CrimsonRed, fontSize = 12.sp) }
        }
    }

    push?.let { flag ->
        Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
            Toggle("Alertas en la nube", if (flag == "2") "Con horario (configurado en Yi Home)" else "Eventos e imágenes en la pestaña Eventos", flag != "0") { on ->
                scope.launch {
                    val ok = runCatching { withContext(Dispatchers.IO) { cloud.setAlertPush(account, camera.uid, on) } }.isSuccess
                    if (ok) push = if (on) "1" else "0"
                }
            }
        }
    }

    SectionTitle("Almacenamiento y firmware")
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Detail("Firmware", version ?: "—")
        version?.let { FirmwareUpdate(camera, cam, cloud, it) }
        Detail("microSD", i?.let { sdText(it.tfstat.toInt()) } ?: "—")
        if (i != null && i.tfstat.toInt() != 5 && i.total > 0) Detail("Espacio", "${i.free} libres de ${i.total}")
    }
}

/**
 * Busca firmware nuevo en la nube de Yi y, si hay, se lo manda a la cámara por P2P; el avance se lee de getDeviceInfo
 * (update_stat 1 = instalando, 2 = listo; update_progress %), como CameraUpgradeActivity de Kami (tope 5 min).
 */
@Composable
private fun FirmwareUpdate(camera: CameraDevice, cam: AntsCamera?, cloud: YiCloud, current: String) {
    val scope = rememberCoroutineScope()
    var fw by remember { mutableStateOf<YiCloud.Firmware?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(msg ?: fw?.let { if (it.needUpdate) "Disponible: ${it.version}" else "Está actualizado" } ?: "", color = MutedText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        val f = fw
        if (progress == null) TextButton(onClick = {
            if (f?.needUpdate == true) confirm = true
            else scope.launch {
                msg = "Buscando…"
                msg = runCatching { fw = withContext(Dispatchers.IO) { cloud.firmware(camera.uid, camera.model, current) }; null }
                    .getOrElse { "No se pudo consultar: ${it.message}" }
            }
        }) { Mono(if (f?.needUpdate == true) "ACTUALIZAR" else "BUSCAR", CrimsonRed, 12.sp) }
    }
    progress?.let { LinearProgressIndicator(progress = { it / 100f }, color = CrimsonRed, trackColor = SurfaceHigh, modifier = Modifier.fillMaxWidth()) }
    if (fw?.needUpdate == true && fw!!.notes.isNotBlank()) Text(fw!!.notes, color = MutedText, fontSize = 11.sp)

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = SurfaceHigh,
        title = { Text("¿Actualizar a ${fw?.version}?", color = PureWhite) },
        text = { Text("La cámara se reiniciará sola. No la desenchufes hasta que termine (unos minutos).", color = MutedText) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                val c = cam ?: run { msg = "Conecta la cámara primero"; return@TextButton }
                scope.launch {
                    progress = 0; msg = "Enviando a la cámara…"
                    if (YiP2p.upgrade(c, fw!!.url) == null) { msg = "La cámara no aceptó la orden"; progress = null; return@launch }
                    val until = System.currentTimeMillis() + 300_000
                    while (System.currentTimeMillis() < until) {
                        delay(3000)
                        val i = YiP2p.info(c) ?: continue // se desconecta al reiniciar
                        progress = i.update_progress.toInt().coerceIn(0, 100)
                        msg = "Instalando… $progress%"
                        if (i.update_stat.toInt() == 2) { msg = "Firmware instalado; la cámara se reinicia"; break }
                    }
                    if (progress != null && msg?.startsWith("Firmware") != true) msg = "Sin respuesta: la cámara puede estar reiniciando. Reconecta en unos minutos."
                    progress = null; fw = null
                }
            }) { Text("Actualizar", color = CrimsonRed) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar", color = MutedText) } },
    )
}

/** Medianoche local del día de [t]. */
private fun dayStart(t: Long) = java.util.Calendar.getInstance().apply {
    timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** Hora del cuadro de la microSD si el timestamp parece epoch; si no, la hora pedida. */
private fun sdTime(frameSec: Long, requested: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(if (frameSec > 1_500_000_000L) frameSec * 1000 else requested))

/**
 * Línea de tiempo de la microSD por día: tramos grabados en rojo sobre 24 h (desplazable); tocar un punto reproduce
 * desde ahí (o desde el siguiente tramo si cae en un hueco). "En vivo" vuelve a la transmisión normal.
 */
@Composable
private fun SdTimeline(cam: AntsCamera?, sdAt: Long?, frameSec: Long, onPlay: (Long) -> Unit, onLive: () -> Unit) {
    var clips by remember { mutableStateOf<List<YiP2p.SdClip>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val today = remember { dayStart(System.currentTimeMillis()) }
    var day by remember { mutableLongStateOf(today) }
    val dayMs = 86_400_000L

    LaunchedEffect(cam, reload) {
        val c = cam ?: return@LaunchedEffect
        loading = true
        delay(2500) // que el SDK termine de autenticar antes de pedir la lista
        // 8 días hacia atrás + 1 de margen por la diferencia GMT/local de la petición
        clips = YiP2p.sdClips(c, today - 8 * dayMs, System.currentTimeMillis() + dayMs)
        loading = false
        clips?.lastOrNull()?.let { day = dayStart(it.start) }
    }

    SectionTitle("Grabaciones microSD") {
        if (loading) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        else IconButton(onClick = { reload++ }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Recargar", tint = MutedText) }
    }
    val all = clips
    when {
        cam == null -> Text("Conecta para leer la microSD", color = MutedText, fontSize = 13.sp)
        all == null && !loading -> Text("La cámara no respondió (¿sin microSD?)", color = MutedText, fontSize = 13.sp)
        all != null && all.isEmpty() -> Text("No hay grabaciones en la microSD de los últimos días", color = MutedText, fontSize = 13.sp)
    }
    if (all.isNullOrEmpty()) return

    // días con grabaciones (hoy siempre)
    val days = (listOf(today) + all.map { dayStart(it.start) }).distinct().sortedDescending()
    val fmtDay = remember { SimpleDateFormat("EEE dd/MM", Locale("es")) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            val sel = d == day
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(if (sel) CrimsonRed else CardBackground)
                    .clickable { day = d }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Mono(when (d) { today -> "HOY"; today - dayMs -> "AYER"; else -> fmtDay.format(Date(d)).uppercase() }, if (sel) OnAccent else MutedText, 11.sp) }
        }
    }

    val dayClips = all.filter { it.end > day && it.start < day + dayMs }
    val hourDp = 90.dp
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // abrir el día centrado en la última grabación
    LaunchedEffect(day, dayClips.size) {
        val focus = dayClips.lastOrNull()?.start ?: System.currentTimeMillis()
        val px = with(density) { (hourDp * ((focus - day) / 3_600_000f)).toPx() }
        scroll.scrollTo((px - 300).toInt().coerceAtLeast(0))
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val playing = sdAt?.let { if (frameSec > 1_500_000_000L) frameSec * 1000 else it }
    Box(Modifier.fillMaxWidth().panel().horizontalScroll(scroll)) {
        androidx.compose.foundation.Canvas(
            Modifier.width(hourDp * 24).height(64.dp).pointerInput(day, dayClips) {
                detectTapGestures { o ->
                    val t = day + (o.x / size.width * dayMs).toLong()
                    val clip = dayClips.firstOrNull { t in it.start until it.end } ?: dayClips.firstOrNull { it.start > t }
                    if (clip != null) onPlay(maxOf(t, clip.start))
                }
            },
        ) {
            val w = size.width
            fun x(t: Long) = ((t - day).toFloat() / dayMs * w).coerceIn(0f, w)
            dayClips.forEach { c -> drawRect(CrimsonRed.copy(alpha = 0.75f), Offset(x(c.start), 22f), androidx.compose.ui.geometry.Size(maxOf(2f, x(c.end) - x(c.start)), size.height - 44f)) }
            for (h in 0..24) {
                val hx = w * h / 24f
                drawLine(BorderStrong, Offset(hx, 0f), Offset(hx, if (h % 3 == 0) 18f else 10f), 2f)
                if (h < 24 && h % 3 == 0) drawText(measurer, "%02d:00".format(h), Offset(hx + 6f, 0f),
                    androidx.compose.ui.text.TextStyle(color = MutedText, fontSize = 10.sp))
            }
            playing?.takeIf { it in day until day + dayMs }?.let { p -> drawLine(PureWhite, Offset(x(p), 0f), Offset(x(p), size.height), 4f) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (sdAt != null) "Reproduciendo la microSD · toca otro punto para saltar" else "Toca la barra roja para ver lo grabado",
            color = MutedText, fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        if (sdAt != null) TextButton(onClick = onLive) { Mono("EN VIVO", CrimsonRed, 12.sp) }
    }
}

/** Textos de CameraSettingFragment.e5() de Yi Home. */
private fun sdText(s: Int) = when (s) {
    0 -> "Correcta"
    1 -> "Lenta"
    2, 3 -> "Necesita formateo"
    4 -> "Sin espacio"
    5 -> "Sin tarjeta"
    6 -> "Formateando"
    7 -> "Detenida"
    else -> "Error ($s)"
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PureWhite, fontSize = 14.sp)
            Text(sub, color = MutedText, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent, checkedTrackColor = CrimsonRed,
                uncheckedThumbColor = MutedText, uncheckedTrackColor = SurfaceHigh, uncheckedBorderColor = BorderStrong,
            ),
        )
    }
}

@Composable
private fun Segmented(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().panel(4)) {
        options.forEach { (v, label) ->
            val sel = selected == v
            Box(
                Modifier.weight(1f).background(if (sel) CrimsonRed else Color.Transparent)
                    .clickable { if (!sel) onSelect(v) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Mono(label, if (sel) OnAccent else MutedText, 12.sp) }
        }
    }
}

/** Botón cuadrado del diseño; [active] lo pinta en rojo. El gesto (clic o mantener) va en [modifier]. */
@Composable
private fun Control(icon: ImageVector, title: String, sub: String, active: Boolean, modifier: Modifier) {
    Column(
        Modifier.panel().then(modifier).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(if (active) CrimsonRed.copy(alpha = 0.25f) else SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, title, tint = if (active) CrimsonRed else PureWhite) }
        Spacer(Modifier.height(6.dp))
        Text(title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Mono(sub, MutedText, 10.sp)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MutedText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Mono(value, PureWhite, 12.sp)
    }
}
