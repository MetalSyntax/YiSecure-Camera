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
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.metalsyntax.yihomelocal.data.Alert
import com.metalsyntax.yihomelocal.data.Catalog
import com.metalsyntax.yihomelocal.data.LocalEvent
import com.metalsyntax.yihomelocal.data.LocalEvents
import com.metalsyntax.yihomelocal.ai.Det
import com.metalsyntax.yihomelocal.ai.Detector
import com.metalsyntax.yihomelocal.ai.Kind
import com.metalsyntax.yihomelocal.ai.Scene
import com.metalsyntax.yihomelocal.ai.SceneAnalyzer
import com.metalsyntax.yihomelocal.ai.Sentinel
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    cameras: List<CameraDevice> = listOf(camera), onSwitch: (CameraDevice) -> Unit = {},
    /** Pánico: empezar a grabar MP4 en cuanto llegue el video. */
    autoRecord: Boolean = false,
    /** Abrir reproduciendo la microSD desde esta hora (epoch ms), p. ej. desde un evento. */
    sdStart: Long? = null,
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
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // reproducción de la microSD: hora pedida (null = en vivo) y hora del último cuadro recibido
    var sdAt by remember { mutableStateOf<Long?>(null) }
    var frameSec by remember { mutableLongStateOf(0L) }
    // cuadros por segundo reales (se cuentan al llegar) y estado leído de la cámara (Wi-Fi, batería, microSD)
    val frameCount = remember { java.util.concurrent.atomic.AtomicInteger() }
    // reconexión automática: último cuadro recibido e intentos seguidos (espera 2, 4, 8… hasta 60 s)
    val lastFrame = remember { java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()) }
    var retries by remember { mutableIntStateOf(0) }
    var sdPaused by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(YiP2p.Link.UNKNOWN) }
    var fps by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var picker by remember { mutableStateOf(false) }
    // IA del teléfono: cajas del último análisis, día/noche y eventos guardados
    var aiOn by remember { mutableStateOf(store.ai) }
    var hud by remember { mutableStateOf(store.hud) }
    val zone = remember(camera.uid) { store.zone(camera.uid) } // ojo: mostrar/ocultar todo lo que va sobre el video
    var dets by remember { mutableStateOf<List<Det>>(emptyList()) }
    var scene by remember { mutableStateOf<Scene?>(null) }
    var events by remember { mutableStateOf(LocalEvents.list(ctx, camera.uid)) }
    var alerts by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var openAlert by remember { mutableStateOf<Alert?>(null) }
    var openEvent by remember { mutableStateOf<LocalEvent?>(null) }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        while (true) {
            clock = f.format(Date()); fps = frameCount.getAndSet(0)
            if (fps > 0) { lastFrame.set(System.currentTimeMillis()); if (retries > 0) retries = 0 }
            delay(1000)
        }
    }
    LaunchedEffect(session) {
        lastFrame.set(System.currentTimeMillis())
        delay(20_000) // margen para conectar la primera vez
        while (true) {
            delay(5_000)
            val stalled = System.currentTimeMillis() - lastFrame.get() > 15_000 && !sdPaused
            if (stalled || status.startsWith("Error")) {
                val wait = minOf(60, 2 shl minOf(retries, 5))
                for (left in wait downTo 1) { status = "Sin señal · reconectando en $left s…"; delay(1000) }
                retries++; sdAt = null; session++ // reabre la conexión P2P (LiveView se reinicia con session)
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }
    val live = status.startsWith("En vivo") || status.startsWith("Conectado")
    // tipo de conexión una vez en vivo (el SDK lo deja en previousConnectType)
    LaunchedEffect(cam, live) { val c = cam; if (c != null && live) link = YiP2p.link(ctx, c, camera) }
    val resolution = Regex("(\\d+)×(\\d+)").find(status)?.groupValues?.get(2)?.let { "${it}p" }

    LaunchedEffect(cam) {
        val c = cam ?: return@LaunchedEffect
        delay(1500) // dejar que el SDK termine de autenticar
        info = YiP2p.info(c)
    }
    // estado para las etiquetas de la pantalla Cámaras
    LaunchedEffect(info) { info?.let { com.metalsyntax.yihomelocal.data.CameraStatus.save(ctx, camera.uid, it) } }
    LaunchedEffect(camera.uid) {
        alerts = runCatching { withContext(Dispatchers.IO) { loadAlerts(cloud, account) } }.getOrDefault(emptyList()).filter { it.uid == camera.uid }
    }

    // análisis de cada cuadro visible: día/noche y movimiento siempre; objetos y acciones con la IA activada
    LaunchedEffect(live, aiOn, session) {
        if (!live) return@LaunchedEffect
        val analyzer = SceneAnalyzer()
        val sentinel = Sentinel()
        while (true) {
            delay(800)
            val v = tools.view ?: continue
            if (!v.isAvailable) continue
            val bmp = v.getBitmap(480, 270) ?: continue
            val now = System.currentTimeMillis()
            val replay = sdAt != null
            val (sc, d, found) = withContext(Dispatchers.Default) {
                // movimiento fuera de la zona de vigilancia = sin movimiento
                val sc = analyzer.analyze(bmp).let { raw ->
                    if (zone != null && raw.motion?.let { android.graphics.RectF.intersects(it, zone) } == false) raw.copy(motion = null) else raw
                }
                // con zona de vigilancia, solo cuenta lo que tiene el centro dentro de la zona
                val d = (if (aiOn) runCatching { Detector.detect(ctx, bmp) }.getOrDefault(emptyList()) else emptyList())
                    .filter { zone == null || zone.contains(it.box.centerX(), it.box.centerY()) }
                // en la reproducción de la microSD no se guardan eventos: la hora no sería la real
                Triple(sc, d, if (aiOn && !replay) sentinel.update(now, d, sc) else emptyList())
            }
            scene = sc
            val motion = sc.motion?.takeIf { aiOn && d.isEmpty() }?.let { listOf(Det(if (sc.shadow) Kind.SHADOW else Kind.MOTION, "", 1f, it)) }.orEmpty()
            dets = d + motion
            if (found.isNotEmpty()) {
                val shown = dets
                withContext(Dispatchers.IO) { found.forEach { LocalEvents.add(ctx, camera.uid, now, it, bmp, shown, sc.night) } }
                events = LocalEvents.list(ctx, camera.uid)
            }
        }
    }

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
            // el MP4 lleva el audio de la cámara aunque el altavoz esté en silencio: hay que recibirlo
            if (!audio) cam?.startListening()
            tools.startRecording(Gallery.tempVideo(ctx, camera.name)); recStart = System.currentTimeMillis(); recording = true
        } else {
            recording = false
            if (!audio) cam?.stopListening()
            scope.launch {
                val path = runCatching { withContext(Dispatchers.IO) { tools.stopRecording(ctx) } }.getOrNull()
                message = if (path != null) "Video guardado en $path" else "No se grabó ningún cuadro (espera el video)"
            }
        }
    }

    var dlLeft by remember { mutableIntStateOf(0) } // segundos que faltan de la descarga de la microSD
    var sdStarted by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        val c = cam
        if (sdStart != null && live && !sdStarted && c != null) {
            delay(1500); sdStarted = true
            val at = sdStart - 5_000
            YiP2p.playSd(c, at); sdAt = at; frameSec = 0
        }
    }
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (autoRecord && live && !autoStarted && !recording) {
            delay(1000); autoStarted = true; toggleRecording(); message = "Pánico: grabando MP4 de esta cámara"
        }
    }

    fun setZoom(z: Float) { zoom = z; if (z <= 1f) pan = Offset.Zero }

    /** Flechas del panel: mueven el encuadre del zoom digital (sin SDK; si está en 1x pasa a 2x). */
    fun nudge(dx: Float, dy: Float) {
        if (zoom < 1.5f) setZoom(2f)
        val maxX = (zoom - 1f) * boxSize.width / 2f
        val maxY = (zoom - 1f) * boxSize.height / 2f
        pan = Offset((pan.x - dx * maxX / 2f).coerceIn(-maxX, maxX), (pan.y - dy * maxY / 2f).coerceIn(-maxY, maxY))
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) message = "Sin permiso de micrófono no se puede hablar"
    }

    // mantener pulsado = hablar (botón del video y botón de abajo)
    val pushToTalk = Modifier.pointerInput(cam) {
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
    }

    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.verticalScroll(rememberScrollState()))) {
        if (!full) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PureWhite) }
            Box(Modifier.size(36.dp).panel(4), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Videocam, null, tint = CrimsonRed, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // desplegable: cambiar de cámara sin volver a la lista
                Box {
                    Row(Modifier.clickable(enabled = cameras.size > 1) { picker = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(camera.name, color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false))
                        if (cameras.size > 1) Icon(Icons.Default.ExpandMore, "Cambiar de cámara", tint = PureWhite)
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }, modifier = Modifier.background(SurfaceHigh)) {
                        cameras.forEach { c ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(c.name, color = PureWhite, fontSize = 14.sp)
                                        Mono(modelLabel(ctx, c.model), MutedText, 10.sp)
                                    }
                                },
                                trailingIcon = { if (c.uid == camera.uid) Icon(Icons.Default.Check, null, tint = CrimsonRed) },
                                onClick = { picker = false; if (c.uid != camera.uid) onSwitch(c) },
                            )
                        }
                    }
                }
                Mono(
                    listOfNotNull(modelLabel(ctx, camera.model), resolution, if (fps > 0) "$fps FPS" else null,
                        if (!live) status.uppercase() else null).joinToString(" • "),
                    if (live || status.startsWith("Conectando")) MutedText else CrimsonRed, 10.sp,
                )
            }
            info?.let { i ->
                val signal = i.signal_quality.toInt() and 0xFF
                if (signal in 1..100) MetaChip(wifiIcon(signal), "$signal%")
                val battery = i.battery_precent.toInt() and 0xFF
                if (battery in 1..100) MetaChip(if (i.charger_plugin.toInt() != 0) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd, "$battery%")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Tune, "Ajustes de la cámara", tint = MutedText) }
        }

        Box(
            (if (full) Modifier.fillMaxSize()
            else Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).panel().background(Color.Black))
                .clipToBounds().onSizeChanged { boxSize = it },
        ) {
            val zoomLayer = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
            LiveView(
                camera, session, quality, audio, { status = it }, { cam = it },
                zoomLayer, tools, onFrameTime = { frameSec = it; frameCount.incrementAndGet() },
            )
            // cajas de la IA encima del video, con el mismo zoom
            if (hud) zone?.let { ZoneOverlay(it, zoomLayer) }
            if (hud) DetectionOverlay(dets, zoomLayer)
            // capa de gestos encima del video: pellizco/arrastre solo con 2 dedos o ya con zoom, para no bloquear el scroll
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { setZoom(1f) })
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
            if (hud) {
                Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sdAt != null) StatusBadge(true, "SD " + sdTime(frameSec, sdAt!!))
                    else StatusBadge(live, if (live) "LIVE" else "…")
                    if (recording) {
                        val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
                        clock.length // recomponer cada segundo con el reloj
                        StatusBadge(true, "REC %02d:%02d".format(secs / 60, secs % 60))
                    }
                    if (live && fps > 0) VideoChip(listOfNotNull("$fps FPS", resolution).joinToString(" • "))
                    if (live && link != YiP2p.Link.UNKNOWN) Row(Modifier.videoChrome(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (link == YiP2p.Link.WIFI_LOCAL) Icons.Default.Wifi else Icons.Default.Public, null, tint = OnAccent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp)); Mono(link.label, OnAccent, 10.sp)
                    }
                    if (retries > 0 && !live) VideoChip("INTENTO ${retries + 1}")
                    if (zoom > 1.05f) VideoChip("%.1fx".format(zoom))
                }
                Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoChip("$clock ${utcOffset()}")
                    // detector día/noche: la imagen sin color = infrarrojo
                    scene?.let { s ->
                        Row(Modifier.videoChrome(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (s.night) Icons.Default.Bedtime else Icons.Default.WbSunny, null, tint = OnAccent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Mono(if (s.night) "NOCHE IR" else "DÍA", OnAccent, 10.sp)
                        }
                    }
                    Row(Modifier.videoChrome(if (aiOn) CrimsonRed else Color.Black.copy(alpha = 0.7f)).clickable {
                        aiOn = !aiOn; store.ai = aiOn; if (!aiOn) dets = emptyList()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, null, tint = OnAccent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Mono(if (aiOn) "IA ON" else "IA OFF", OnAccent, 10.sp)
                    }
                }
                // panel de movimiento: encuadre del zoom digital
                if (live) MovePad(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), ::nudge, onCenter = { setZoom(1f) })
                // hablar (mantener) abajo a la izquierda, como en el diseño
                Row(
                    Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(if (talking) DarkRed else CrimsonRed).then(pushToTalk).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Mic, "Hablar", tint = OnAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Mono(if (talking) "HABLANDO" else "HABLAR", OnAccent, 11.sp)
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 52.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoButton(Icons.Default.PhotoCamera, "Tomar foto", enabled = live) { capture() }
                    VideoButton(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        if (full) "Salir de pantalla completa" else "Pantalla completa") { full = !full }
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                VideoButton(if (hud) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    if (hud) "Ocultar controles e información" else "Mostrar controles e información") { hud = !hud; store.hud = hud }
            }
        }
        if (full) return@Column

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            message?.let {
                Row(Modifier.fillMaxWidth().panel().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = PureWhite, fontSize = 12.sp)
                }
            }

            SdTimeline(cam, sdAt, frameSec, clock, events.map { it.time } + alerts.map { it.time }, info?.let { storageText(it) },
                info, onInfo = { info = it },
                onPlay = { ms -> cam?.let { YiP2p.playSd(it, minOf(ms, System.currentTimeMillis() - 10_000)); sdAt = ms; frameSec = 0; sdPaused = false } },
                onLive = { cam?.let { YiP2p.goLive(it) }; sdAt = null; sdPaused = false; if (dlLeft > 0 && recording) toggleRecording(); dlLeft = 0 },
                paused = sdPaused, onPause = { p -> cam?.let { if (p) YiP2p.pauseSd(it) else YiP2p.resumeSd(it) }; sdPaused = p },
                downloading = if (dlLeft > 0) "Descargando… faltan %d:%02d".format(dlLeft / 60, dlLeft % 60) else null,
                onDownload = { secs ->
                    if (!recording) {
                        toggleRecording(); dlLeft = secs
                        scope.launch {
                            while (dlLeft > 0 && recording) { delay(1000); dlLeft-- }
                            if (recording) toggleRecording() // guarda el MP4 y muestra la ruta
                            dlLeft = 0
                        }
                    } else message = "Ya hay una grabación en curso"
                })

            // acciones principales (Hablar y Captura ya están sobre el video)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, "Grabar",
                    if (recording) "Detener" else "MP4", recording, Modifier.weight(1f).clickable(enabled = live || recording) { toggleRecording() })
                Control(if (audio) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    "Audio", if (audio) "Activado" else "Silencio", audio, Modifier.weight(1f).clickable {
                        audio = !audio; store.audio = audio; cam?.let { YiP2p.setAudio(it, audio || recording) }
                    })
                Control(if (calling) Icons.Default.CallEnd else Icons.Default.Call, "Llamada",
                    if (calling) "Colgar" else "Manos libres", calling,
                    Modifier.weight(1f).clickable(enabled = cam != null) {
                        val c = cam ?: return@clickable
                        if (calling) {
                            runCatching { YiP2p.stopTalk(c) }; YiP2p.setAudio(c, audio); calling = false
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            calling = runCatching { YiP2p.startIntercom(c) }.onFailure { message = "No se pudo iniciar: ${it.message}" }.isSuccess
                        }
                    })
            }

            info?.let { i ->
                fun apply(cmd: suspend (AntsCamera) -> DeviceInfo?) { val c = cam ?: return; scope.launch { cmd(c)?.let { info = it } } }
                val ledOn = i.close_light.toInt() != 1
                val night = i.day_night_mode.toInt()
                val privacy = i.close_camera.toInt() != 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickCard(Icons.Default.Lightbulb, "Luz LED", if (ledOn) "Encendida" else "Apagada", ledOn, Modifier.weight(1f)) { apply { YiP2p.setLed(it, !ledOn) } }
                    QuickCard(if (night == 3) Icons.Default.Bedtime else if (night == 2) Icons.Default.WbSunny else Icons.Default.BrightnessAuto, "Visión IR",
                        when (night) { 3 -> "Activa"; 2 -> "Apagada"; else -> "Auto" }, night == 3, Modifier.weight(1f)) {
                        apply { YiP2p.setNightVision(it, when (night) { 1 -> 3; 3 -> 2; else -> 1 }) }
                    }
                    QuickCard(if (privacy) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Privacidad", if (privacy) "Activa" else "Desactivada", privacy, Modifier.weight(1f)) {
                        apply { YiP2p.setCameraOn(it, privacy) }
                    }
                }
            }

            RecentEvents(events, alerts, aiOn,
                onOpenEvent = { openEvent = it }, onOpenAlert = { openAlert = it },
                onSave = { bmp -> scope.launch {
                    message = runCatching { withContext(Dispatchers.IO) { "Guardada en " + Gallery.saveImage(ctx, camera.name, bmp) } }
                        .getOrElse { "No se pudo guardar: ${it.message}" }
                } })

            Advanced("Calidad del video, girar imagen y reconectar") {
                // calidad: el cambio lo aplica la cámara; el decodificador se reconfigura solo al llegar el nuevo tamaño
                Text("Calidad", color = PureWhite, fontSize = 14.sp)
                Segmented(listOf(YiP2p.QUALITY_AUTO to "AUTO", YiP2p.QUALITY_SD to "SD", YiP2p.QUALITY_HIGH to "2K"), quality) { q ->
                    quality = q; store.quality = q; cam?.let { YiP2p.setQuality(it, q) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Control(Icons.Default.ScreenRotation, "Girar", if (flipped) "180°" else "Normal", flipped, Modifier.weight(1f).clickable {
                        flipped = !flipped; store.setFlipped(camera.uid, flipped); cam?.let { YiP2p.setFlipped(it, flipped) }
                    })
                    Control(Icons.Default.Refresh, "Reconectar", "Si se congela", false, Modifier.weight(1f).clickable {
                        status = "Reconectando…"; sdAt = null; session++
                    })
                }
            }
        }
    }

    openAlert?.let { a ->
        AlertDetailDialog(a, camera.name, onSd = if (cam != null) {
            { cam?.let { YiP2p.playSd(it, a.time - 5_000); sdAt = a.time - 5_000; frameSec = 0 }; openAlert = null }
        } else null) { openAlert = null }
    }
    openEvent?.let { e ->
        LocalEventDialog(e, camera.name, onDismiss = { openEvent = null }, onSd = if (cam != null) {
            { cam?.let { YiP2p.playSd(it, e.time - 5_000); sdAt = e.time - 5_000; frameSec = 0 }; openEvent = null }
        } else null, onFav = { events = LocalEvents.list(ctx, camera.uid) })
    }
}

/** Nombre comercial del modelo (catálogo de Kami) o el código interno. */
private fun modelLabel(ctx: android.content.Context, model: String): String =
    (catalogCache ?: runCatching { Catalog.load(ctx) }.getOrDefault(emptyList()).also { catalogCache = it })
        .firstOrNull { it.model == model }?.name ?: model.uppercase()

private var catalogCache: List<com.metalsyntax.yihomelocal.data.CatalogItem>? = null

private fun wifiIcon(signal: Int) = when {
    signal >= 75 -> Icons.Default.Wifi
    signal >= 40 -> Icons.Default.Wifi2Bar
    else -> Icons.Default.Wifi1Bar
}

private fun utcOffset(): String {
    val m = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    return "UTC" + (if (m < 0) "-" else "+") + (kotlin.math.abs(m) / 60) + (if (m % 60 != 0) ":%02d".format(kotlin.math.abs(m) % 60) else "")
}

/** "Almacenamiento: 84% usado" de la microSD; null si no hay tarjeta. */
private fun storageText(i: DeviceInfo): String? {
    if (i.tfstat.toInt() == 5 || i.total <= 0) return null
    return "Almacenamiento: ${((i.total - i.free) * 100L / i.total).coerceIn(0, 100)}% usado"
}

/** Fondo negro translúcido de las etiquetas sobre el video. */
private fun Modifier.videoChrome(bg: Color = Color.Black.copy(alpha = 0.7f)) =
    clip(RoundedCornerShape(2.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp)

@Composable
private fun VideoChip(text: String) = Mono(text, OnAccent, 10.sp, Modifier.videoChrome())

@Composable
private fun VideoButton(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) OnAccent else GhostText, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun MetaChip(icon: ImageVector, text: String) {
    Row(Modifier.padding(start = 6.dp).panel(4).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = PureWhite, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Mono(text, PureWhite, 10.sp)
    }
}

/** Cruceta del diseño: mueve el encuadre del zoom; el centro vuelve a 1x. */
@Composable
private fun MovePad(modifier: Modifier, onMove: (Float, Float) -> Unit, onCenter: () -> Unit) {
    val btn = Modifier.size(30.dp)
    Box(modifier.size(96.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))) {
        IconButton(onClick = { onMove(0f, -1f) }, modifier = btn.align(Alignment.TopCenter)) { Icon(Icons.Default.KeyboardArrowUp, "Arriba", tint = OnAccent) }
        IconButton(onClick = { onMove(0f, 1f) }, modifier = btn.align(Alignment.BottomCenter)) { Icon(Icons.Default.KeyboardArrowDown, "Abajo", tint = OnAccent) }
        IconButton(onClick = { onMove(-1f, 0f) }, modifier = btn.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Izquierda", tint = OnAccent) }
        IconButton(onClick = { onMove(1f, 0f) }, modifier = btn.align(Alignment.CenterEnd)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Derecha", tint = OnAccent) }
        Box(Modifier.align(Alignment.Center).size(14.dp).clip(RoundedCornerShape(50)).background(CrimsonRed).clickable(onClick = onCenter))
    }
}

/** Cajas de las detecciones (coordenadas 0..1 del cuadro) con su etiqueta. */
@Composable
private fun DetectionOverlay(dets: List<Det>, modifier: Modifier) {
    if (dets.isEmpty()) return
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.Canvas(modifier) {
        dets.forEach { d ->
            val tl = Offset(d.box.left * size.width, d.box.top * size.height)
            val sz = androidx.compose.ui.geometry.Size(d.box.width() * size.width, d.box.height() * size.height)
            val c = if (d.kind == Kind.MOTION || d.kind == Kind.SHADOW) Color.White else CrimsonRed
            drawRect(c, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            val label = if (d.name.isEmpty()) d.kind.label else "${d.kind.label} ${(d.score * 100).toInt()}%"
            val t = measurer.measure(label, androidx.compose.ui.text.TextStyle(color = OnAccent, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            val y = (tl.y - t.size.height).coerceAtLeast(0f)
            drawRect(c, Offset(tl.x, y), androidx.compose.ui.geometry.Size(t.size.width + 8f, t.size.height.toFloat()))
            drawText(t, color = if (c == Color.White) Color.Black else OnAccent, topLeft = Offset(tl.x + 4f, y))
        }
    }
}

/** "Eventos recientes detectados": IA del teléfono + alertas de la nube de esta cámara, lo más nuevo primero. */
@Composable
private fun RecentEvents(
    events: List<LocalEvent>, alerts: List<Alert>, aiOn: Boolean,
    onOpenEvent: (LocalEvent) -> Unit, onOpenAlert: (Alert) -> Unit, onSave: (android.graphics.Bitmap) -> Unit,
) {
    val today = dayStart(System.currentTimeMillis())
    val todayCount = events.count { it.time >= today } + alerts.count { it.time >= today }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Bolt, null, tint = CrimsonRed, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Eventos Recientes Detectados", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Mono("HOY ($todayCount)", MutedText, 10.sp)
    }
    if (events.isEmpty() && alerts.isEmpty()) {
        Text(
            if (aiOn) "Todavía nada. La IA del teléfono revisa el video mientras lo miras y guarda aquí personas, vehículos, mascotas (gatos, perros, aves), sombras y movimiento."
            else "Activa \"IA\" sobre el video para detectar personas, vehículos y mascotas en el teléfono.",
            color = MutedText, fontSize = 13.sp,
        )
        return
    }
    val rows = (events.map { it.time to (it as Any) } + alerts.map { it.time to (it as Any) }).sortedByDescending { it.first }.take(15)
    rows.forEach { (_, item) ->
        when (item) {
            is LocalEvent -> {
                val img = remember(item.file) { runCatching { BitmapFactory.decodeFile(item.file) }.getOrNull() }
                RecentRow(img, item.kind, item.time, item.title, item.desc, { onOpenEvent(item) }, img?.let { { onSave(it) } })
            }
            is Alert -> {
                var img by remember(item.picUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(item.picUrl) { img = alertImage(item) }
                RecentRow(img, "NUBE", item.time, item.kind, "Alerta de la cámara en la nube Yi" + if (item.videoUrl.isNotEmpty()) " · con clip" else "",
                    { onOpenAlert(item) }, img?.let { b -> { onSave(b) } })
            }
        }
    }
}

@Composable
private fun RecentRow(img: android.graphics.Bitmap?, tag: String, time: Long, title: String, desc: String, onPlay: () -> Unit, onSave: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().panel().clickable(onClick = onPlay).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            img?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Default.Videocam, null, tint = BorderStrong)
            Mono(tag, OnAccent, 8.sp, Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(SimpleDateFormat("HH:mm", Locale.US).format(Date(time)), CrimsonRed, 12.sp)
                Text(" • $title", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(desc, color = MutedText, fontSize = 12.sp, maxLines = 1)
        }
        VideoButtonFlat(Icons.Default.PlayArrow, "Ver", onPlay)
        if (onSave != null) { Spacer(Modifier.width(4.dp)); VideoButtonFlat(Icons.Default.Download, "Guardar en galería", onSave) }
    }
}

@Composable
private fun VideoButtonFlat(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)).background(SurfaceHigh).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = PureWhite, modifier = Modifier.size(18.dp))
    }
}

/** Foto del evento de IA en grande; si la cámara tiene microSD se puede saltar a ese momento. */
@Composable
fun LocalEventDialog(e0: LocalEvent, camName: String, onDismiss: () -> Unit, onSd: (() -> Unit)?, onFav: (LocalEvent) -> Unit = {}) {
    val ctx0 = LocalContext.current
    var e by remember(e0.file) { mutableStateOf(e0) }
    val img = remember(e.file) { runCatching { BitmapFactory.decodeFile(e.file) }.getOrNull() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().panel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // favorito: no se borra al rotar los 100 eventos por cámara
                IconButton(onClick = { e = LocalEvents.setFav(ctx0, e, !e.fav); onFav(e) }) {
                    Icon(if (e.fav) Icons.Default.Star else Icons.Default.StarBorder, if (e.fav) "Quitar de favoritos" else "Guardar en favoritos",
                        tint = if (e.fav) CrimsonRed else MutedText)
                }
            }
            Mono("$camName · " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(e.time)) +
                (if (e.score > 0) " · confianza ${(e.score * 100).toInt()} %" else "") + (if (e.night) " · IR nocturno" else ""), MutedText, 10.sp)
            Text(e.desc, color = MutedText, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                img?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            if (onSd != null) PrimaryButton("Ver en la microSD", onClick = onSd)
            val ctx = LocalContext.current
            SecondaryButton("Compartir", icon = Icons.Default.Share) {
                runCatching {
                    com.metalsyntax.yihomelocal.data.Share.file(ctx, java.io.File(e.file), "image/jpeg",
                        "${e.title} · $camName · " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(e.time)))
                }
            }
            SecondaryButton("Cerrar", onClick = onDismiss)
        }
    }
}

/**
 * Ajustes que la propia cámara guarda (mismos comandos P2P que Yi Home): se leen con getDeviceInfo al conectar.
 * Las alertas en la nube (pushflag) se leen/escriben en la API de Yi.
 */
@Composable
fun CameraSettings(
    camera: CameraDevice, cam: AntsCamera?, cloud: YiCloud, account: YiCloud.Session,
    /** Va entre los ajustes básicos y "Avanzado" (privacidad y acceso). */
    middle: @Composable () -> Unit = {},
    /** Más contenido dentro de "Avanzado" (detalles, eliminar…). */
    extra: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctxS = LocalContext.current
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var push by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cam) {
        val c = cam ?: return@LaunchedEffect
        delay(1500) // dejar que el SDK termine de autenticar
        info = YiP2p.info(c) ?: YiP2p.info(c)
        version = YiP2p.version(c)?.also { com.metalsyntax.yihomelocal.data.Store(ctxS).setFirmware(camera.uid, it) }
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

    SectionTitle("Ajustes de la cámara", Icons.Default.Tune) {
        if (busy) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
    }
    val i = info
    if (i == null) {
        Text(error ?: if (cam == null) "Conecta para leer los ajustes" else "Leyendo ajustes de la cámara…", color = MutedText, fontSize = 13.sp)
    } else {
        // lo de todos los días en un solo panel; lo demás en "Avanzado"
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Toggle("Cámara encendida", "Apagada = privacidad: no transmite, no detecta ni avisa", i.close_camera.toInt() == 0) { on ->
                apply { YiP2p.setCameraOn(it, on) }
            }
            push?.let { flag ->
                Toggle("Alertas", if (flag == "2") "Con horario (configurado en Yi Home)" else "Avisos e imágenes en Eventos", flag != "0") { on ->
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { cloud.setAlertPush(account, camera.uid, on) } }.isSuccess
                        if (ok) push = if (on) "1" else "0"
                    }
                }
            }
            Toggle("Luz de estado", "LED del frente de la cámara", i.close_light.toInt() != 1) { on -> apply { YiP2p.setLed(it, on) } }
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

    middle()

    Advanced("Detecciones, modo vidrio, microSD, firmware, detalles y eliminar") {
    if (i != null) {
        SectionTitle("Detecciones", Icons.Default.CenterFocusStrong)
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Toggle("Solo personas", "Alertas solo cuando hay personas (si no, cualquier movimiento)", i.personDetectionEnabled()) { on ->
                apply { YiP2p.setPersonDetection(it, on) }
            }
            Toggle("Sonido anormal", "Ruidos fuertes, golpes, alarmas", i.v1_extend_abnormal_sound.toInt() == 2 || i.v2_extend_abnormal_sound.toInt() == 2) { on ->
                apply { YiP2p.setSoundDetection(it, on) }
            }
            Toggle("Llanto de bebé", "Avisa si detecta llanto", i.baby_crying_mode.toInt() == 2) { on -> apply { YiP2p.setBabyCry(it, on) } }
            Toggle("Modo vidrio", "Detrás de una ventana: apaga IR y LED para que no se reflejen", i.close_light.toInt() == 1 && i.day_night_mode.toInt() == 2) { on ->
                apply { YiP2p.setGlassMode(it, on) }
            }
        }
    }

    SectionTitle("Almacenamiento y firmware", Icons.Default.SdCard)
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Detail("Firmware", version ?: "—")
        version?.let { FirmwareUpdate(camera, cam, cloud, it) }
        Detail("microSD", i?.let { sdText(it.tfstat.toInt()) } ?: "—")
        if (i != null && i.tfstat.toInt() != 5) {
            if (i.total > 0) {
                val used = ((i.total - i.free) * 100L / i.total).toInt().coerceIn(0, 100)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text("Uso de la tarjeta", color = PureWhite, fontSize = 14.sp, modifier = Modifier.weight(1f)); Mono("$used % USADO", if (used >= 90) CrimsonRed else MutedText, 11.sp) }
                    LinearProgressIndicator(progress = { used / 100f }, color = CrimsonRed, trackColor = SurfaceHigh,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    // ponytail: unidades de total/free sin confirmar (Yi no las documenta), se muestran tal cual
                    Mono("${i.free} libres de ${i.total}", MutedText, 10.sp)
                }
            }
            // para confirmar en la cámara el mapeo de record_mode (1 = continua según el SDK)
            Detail("Grabación", "${if (YiP2p.isContinuous(i)) "Continua 24 h" else "Solo con movimiento"} (record_mode ${i.record_mode})")
            var askFormat by remember { mutableStateOf(false) }
            SecondaryButton("Formatear microSD", color = CrimsonRed, icon = Icons.Default.DeleteForever) { askFormat = true }
            if (askFormat) AlertDialog(
                onDismissRequest = { askFormat = false }, containerColor = CardBackground,
                icon = { Icon(Icons.Default.Warning, null, tint = CrimsonRed) },
                title = { Text("¿Formatear la microSD?", color = PureWhite) },
                text = { Text("Se borran TODAS las grabaciones y eventos de la tarjeta de ${camera.name}. No se puede deshacer.", color = MutedText) },
                confirmButton = { TextButton(onClick = { askFormat = false; apply { YiP2p.formatSd(it) } }) { Text("FORMATEAR", color = CrimsonRed) } },
                dismissButton = { TextButton(onClick = { askFormat = false }) { Text("Cancelar", color = MutedText) } },
            )
        }
    }
    ZoneEditor(camera, cam)

    SectionTitle("Reiniciar", Icons.Default.RestartAlt)
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var askReboot by remember { mutableStateOf(false) }
        var rebootMsg by remember { mutableStateOf<String?>(null) }
        Text("Si la cámara se cuelga o no conecta. Tarda alrededor de 1 minuto en volver.", color = MutedText, fontSize = 13.sp)
        SecondaryButton("Reiniciar cámara", icon = Icons.Default.RestartAlt, modifier = Modifier.fillMaxWidth()) { askReboot = true }
        rebootMsg?.let { Text(it, color = MutedText, fontSize = 12.sp) }
        if (askReboot) AlertDialog(
            onDismissRequest = { askReboot = false }, containerColor = CardBackground,
            title = { Text("¿Reiniciar ${camera.name}?", color = PureWhite) },
            text = { Text("Se desconecta y deja de grabar durante el reinicio.", color = MutedText) },
            confirmButton = { TextButton(onClick = {
                askReboot = false
                val c = cam
                if (c == null) rebootMsg = "Conecta la cámara primero"
                else scope.launch { rebootMsg = if (YiP2p.reboot(c) != null) "Reiniciando… vuelve en ~1 min" else "La cámara no confirmó (puede estar reiniciando igual)" }
            }) { Text("REINICIAR", color = CrimsonRed) } },
            dismissButton = { TextButton(onClick = { askReboot = false }) { Text("Cancelar", color = MutedText) } },
        )
    }

    extra()
    }
}

/**
 * Zona de vigilancia: se dibuja arrastrando sobre la última imagen. Siempre filtra la IA del teléfono (fuera de la
 * zona no hay eventos). Opcionalmente se manda a la cámara (getMotionDetect/setMotionDetect): la escala de
 * coordenadas se deduce de la zona que ya tenía la cámara y se muestra lo que respondió, para confirmarla.
 */
@Composable
private fun ZoneEditor(camera: CameraDevice, cam: AntsCamera?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { com.metalsyntax.yihomelocal.data.Store(ctx) }
    val snap = remember(camera.uid) { com.metalsyntax.yihomelocal.data.Snapshots.load(ctx, camera.uid)?.first }
    var zone by remember(camera.uid) { mutableStateOf(store.zone(camera.uid)) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var toCamera by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    SectionTitle("Zona de vigilancia", Icons.Default.CropFree) { Mono(if (zone != null) "ACTIVA" else "TODO EL CUADRO", if (zone != null) CrimsonRed else MutedText, 10.sp) }
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Arrastra sobre la imagen para marcar lo que importa (puerta, entrada). Lo de afuera no genera eventos de la IA.", color = MutedText, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> start = o },
                    onDrag = { ch, _ ->
                        val a = start ?: return@detectDragGestures
                        val p = ch.position
                        zone = android.graphics.RectF(
                            (minOf(a.x, p.x) / size.width).coerceIn(0f, 1f), (minOf(a.y, p.y) / size.height).coerceIn(0f, 1f),
                            (maxOf(a.x, p.x) / size.width).coerceIn(0f, 1f), (maxOf(a.y, p.y) / size.height).coerceIn(0f, 1f))
                    },
                    onDragEnd = { start = null },
                )
            }) {
            snap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text("Abre el vivo una vez para tener imagen de fondo", color = MutedText, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            zone?.let { z -> ZoneOverlay(z, Modifier.fillMaxSize()) }
        }
        Toggle("Aplicar también en la cámara", "Experimental: la cámara solo avisaría por movimiento dentro de la zona", toCamera) { toCamera = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Quitar zona", Modifier.weight(1f)) {
                zone = null; store.setZone(camera.uid, null); msg = "Zona quitada: se vigila todo el cuadro"
                if (toCamera) cam?.let { c -> scope.launch { YiP2p.motionZone(c)?.let { cfg -> YiP2p.setMotionZone(c, false, cfg.resolution, cfg.top_left_x, cfg.top_left_y, cfg.bottom_right_x, cfg.bottom_right_y) } } }
            }
            PrimaryButton("Guardar zona", enabled = zone != null, modifier = Modifier.weight(1f)) {
                val z = zone ?: return@PrimaryButton
                store.setZone(camera.uid, z); msg = "Zona guardada para la IA del teléfono"
                val c = cam
                if (toCamera && c != null) scope.launch {
                    val cfg = YiP2p.motionZone(c)
                    if (cfg == null) { msg = "Guardada en el teléfono; la cámara no respondió a la zona"; return@launch }
                    // escala deducida de la zona que ya tenía la cámara (sin documentación de Yi)
                    val (w, h) = when { cfg.bottom_right_x in 1..100 && cfg.bottom_right_y in 1..100 -> 100 to 100
                        cfg.bottom_right_x <= 640 -> 640 to 360; cfg.bottom_right_x <= 1280 -> 1280 to 720; else -> 1920 to 1080 }
                    val r = YiP2p.setMotionZone(c, true, cfg.resolution, (z.left * w).toInt(), (z.top * h).toInt(), (z.right * w).toInt(), (z.bottom * h).toInt())
                    msg = if (r != null) "Cámara: ${r.top_left_x},${r.top_left_y} → ${r.bottom_right_x},${r.bottom_right_y} (escala $w×$h, res ${r.resolution})"
                        else "Guardada en el teléfono; la cámara no confirmó la zona"
                }
            }
        }
        msg?.let { Text(it, color = MutedText, fontSize = 12.sp) }
    }
}

/** Rectángulo de la zona de vigilancia (blanco con esquinas rojas) sobre la imagen. */
@Composable
fun ZoneOverlay(z: android.graphics.RectF, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val l = z.left * size.width; val t = z.top * size.height
        val w = (z.right - z.left) * size.width; val h = (z.bottom - z.top) * size.height
        drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, androidx.compose.ui.geometry.Size(size.width, t))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, t + h), androidx.compose.ui.geometry.Size(size.width, size.height - t - h))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, t), androidx.compose.ui.geometry.Size(l, h))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(l + w, t), androidx.compose.ui.geometry.Size(size.width - l - w, h))
        drawRect(Color.White.copy(alpha = 0.9f), Offset(l, t), androidx.compose.ui.geometry.Size(w, h),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        val k = minOf(14.dp.toPx(), w / 3, h / 3); val sw = 3.dp.toPx()
        listOf(Offset(l, t) to Offset(1f, 1f), Offset(l + w, t) to Offset(-1f, 1f), Offset(l, t + h) to Offset(1f, -1f), Offset(l + w, t + h) to Offset(-1f, -1f)).forEach { (p, d) ->
            drawLine(CrimsonRed, p, Offset(p.x + d.x * k, p.y), sw); drawLine(CrimsonRed, p, Offset(p.x, p.y + d.y * k), sw)
        }
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
 * "Grabación Continua (NVR)": línea de tiempo de la microSD por día. Todo lo grabado va como franja fina gris (continuo)
 * y lo relevante como barras altas rojas: eventos que la cámara guardó en la microSD, IA del teléfono y alertas de la
 * nube. Tocar una barra alta reproduce el evento (3 s antes); tocar la franja fina reproduce desde ese punto (o el
 * siguiente tramo si cae en un hueco). Si la cámara graba "solo con movimiento" la franja fina no existe: se puede
 * pasar a continua 24 h desde aquí.
 */
@Composable
private fun SdTimeline(
    cam: AntsCamera?, sdAt: Long?, frameSec: Long, clock: String, marks: List<Long>, storage: String?,
    info: DeviceInfo?, onInfo: (DeviceInfo) -> Unit,
    onPlay: (Long) -> Unit, onLive: () -> Unit,
    /** Pausa (true) / reanuda (false) la microSD; descarga [seconds] desde la posición actual (null = no descargando). */
    paused: Boolean = false, onPause: (Boolean) -> Unit = {},
    downloading: String? = null, onDownload: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<YiP2p.SdClip>?>(null) }
    var sdAlerts by remember { mutableStateOf<List<YiP2p.SdAlert>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val today = remember { dayStart(System.currentTimeMillis()) }
    var day by remember { mutableLongStateOf(today) }
    var hourDp by remember { mutableStateOf(90.dp) }
    val dayMs = 86_400_000L

    LaunchedEffect(cam, reload) {
        val c = cam ?: return@LaunchedEffect
        loading = true
        delay(2500) // que el SDK termine de autenticar antes de pedir la lista
        // 8 días hacia atrás + 1 de margen por la diferencia GMT/local de la petición
        val from = today - 8 * dayMs; val to = System.currentTimeMillis() + dayMs
        clips = YiP2p.sdClips(c, from, to)
        sdAlerts = YiP2p.sdAlerts(c, from, to).orEmpty()
        loading = false
        clips?.lastOrNull()?.let { day = dayStart(it.start) }
    }

    Column(Modifier.fillMaxWidth().panel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = PureWhite, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Grabación Continua (NVR)", color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (loading) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            else IconButton(onClick = { reload++ }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Recargar", tint = MutedText, modifier = Modifier.size(18.dp)) }
            StatusBadge(true, if (sdAt != null) "MICROSD" else "EN VIVO")
            Spacer(Modifier.width(6.dp))
            Mono(clock, PureWhite, 11.sp)
        }

        // modo de grabación de la tarjeta: continua (todo) o solo movimiento (solo eventos)
        if (info != null && info.tfstat.toInt() != 5) {
            val continuous = YiP2p.isContinuous(info)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SurfaceHigh).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (continuous) Icons.Default.FiberSmartRecord else Icons.Default.Sensors, null,
                    tint = if (continuous) CrimsonRed else MutedText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (continuous) "Continuo 24 h" else "Solo con movimiento", color = PureWhite, fontSize = 13.sp)
                    Text(if (continuous) "Toda la grabación + eventos marcados" else "La microSD solo guarda eventos", color = MutedText, fontSize = 11.sp)
                }
                if (switching) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                else TextButton(onClick = {
                    val c = cam ?: return@TextButton
                    switching = true
                    scope.launch {
                        YiP2p.setContinuous(c, !continuous)?.let(onInfo)
                        switching = false; reload++
                    }
                }, enabled = cam != null) { Mono(if (continuous) "SOLO MOVIMIENTO" else "PASAR A CONTINUA", CrimsonRed, 11.sp) }
            }
        }

        val all = clips.orEmpty()
        when {
            cam == null -> Text("Conecta para leer la microSD", color = MutedText, fontSize = 12.sp)
            clips == null && !loading -> Text("La cámara no respondió (¿sin microSD?)", color = MutedText, fontSize = 12.sp)
            clips != null && all.isEmpty() -> Text("No hay grabaciones en la microSD de los últimos días", color = MutedText, fontSize = 12.sp)
        }

        // días con grabaciones o eventos (hoy siempre)
        val days = (listOf(today) + all.map { dayStart(it.start) } + sdAlerts.map { dayStart(it.start) } + marks.map { dayStart(it) })
            .distinct().sortedDescending().take(9)
        val fmtDay = remember { SimpleDateFormat("EEE dd/MM", Locale("es")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    val sel = d == day
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(if (sel) CrimsonRed else SurfaceHigh)
                            .clickable { day = d }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Mono(when (d) { today -> "HOY"; today - dayMs -> "AYER"; else -> fmtDay.format(Date(d)).uppercase() }, if (sel) OnAccent else MutedText, 11.sp) }
                }
            }
            // escala: más ancho = más detalle por hora
            IconButton(onClick = { hourDp = maxOf(30.dp, hourDp / 2) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ZoomOut, "Alejar línea de tiempo", tint = MutedText, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = { hourDp = minOf(720.dp, hourDp * 2) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ZoomIn, "Acercar línea de tiempo", tint = MutedText, modifier = Modifier.size(18.dp)) }
        }

        val dayClips = all.filter { it.end > day && it.start < day + dayMs }
        val dayAlerts = sdAlerts.filter { it.end > day && it.start < day + dayMs }
        val dayMarks = marks.filter { it in day until day + dayMs }
        val recordedMin = dayClips.sumOf { (minOf(it.end, day + dayMs) - maxOf(it.start, day)) } / 60_000
        val scroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // abrir el día centrado en lo último (o en la hora actual)
        LaunchedEffect(day, dayClips.size, hourDp) {
            val focus = maxOf(dayClips.lastOrNull()?.start ?: 0L, dayAlerts.lastOrNull()?.start ?: 0L, dayMarks.maxOrNull() ?: 0L).takeIf { it > 0 }
                ?: if (day == today) System.currentTimeMillis() else day + dayMs / 2
            val px = with(density) { (hourDp * ((focus - day) / 3_600_000f)).toPx() }
            scroll.scrollTo((px - 300).toInt().coerceAtLeast(0))
        }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val playing = sdAt?.let { if (frameSec > 1_500_000_000L) frameSec * 1000 else it }
        val now = System.currentTimeMillis().also { clock.length } // se redibuja con el reloj
        val labelEvery = when { hourDp >= 180.dp -> 1; hourDp >= 60.dp -> 1; hourDp >= 45.dp -> 2; else -> 3 }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SurfaceHigh).horizontalScroll(scroll)) {
            androidx.compose.foundation.Canvas(
                Modifier.width(hourDp * 24).height(76.dp).pointerInput(day, dayClips, dayAlerts, dayMarks, hourDp) {
                    detectTapGestures { o ->
                        val w = size.width.toFloat()
                        fun x(t: Long) = (t - day).toFloat() / dayMs * w
                        val slop = 14.dp.toPx()
                        // primero lo relevante (barras altas), luego lo continuo
                        val ev = (dayAlerts.map { it.start } + dayMarks).minByOrNull { kotlin.math.abs(x(it) - o.x) }
                            ?.takeIf { kotlin.math.abs(x(it) - o.x) <= slop }
                        val t = ev?.let { it - 3_000 } ?: (day + (o.x / w * dayMs).toLong())
                        val clip = dayClips.firstOrNull { t in it.start until it.end } ?: dayClips.firstOrNull { it.start > t }
                        when {
                            clip != null -> onPlay(maxOf(t, clip.start))
                            ev != null -> onPlay(t) // evento de la tarjeta sin tramo listado: se intenta igual
                        }
                    }
                },
            ) {
                val w = size.width
                fun x(t: Long) = ((t - day).toFloat() / dayMs * w).coerceIn(0f, w)
                val top = 22.dp.toPx(); val bottom = size.height - 4.dp.toPx(); val h = bottom - top
                val mid = top + h / 2; val thin = 6.dp.toPx()
                // fondo del día y franja continua fina
                drawRect(BorderStrong.copy(alpha = 0.25f), Offset(0f, mid - thin / 2), androidx.compose.ui.geometry.Size(w, thin))
                dayClips.forEach { c -> drawRect(MutedText.copy(alpha = 0.8f), Offset(x(c.start), mid - thin / 2), androidx.compose.ui.geometry.Size(maxOf(2f, x(c.end) - x(c.start)), thin)) }
                // relevante: barras altas (ancho = duración, mínimo 4 dp)
                val minW = 4.dp.toPx()
                dayAlerts.forEach { a -> drawRect(CrimsonRed, Offset(x(a.start), top), androidx.compose.ui.geometry.Size(maxOf(minW, x(a.end) - x(a.start)), h)) }
                dayMarks.forEach { m ->
                    drawRect(CrimsonRed.copy(alpha = 0.85f), Offset(x(m) - minW / 2, top + h * 0.15f), androidx.compose.ui.geometry.Size(minW, h * 0.7f))
                    drawCircle(PureWhite, 2.dp.toPx(), Offset(x(m), top + h * 0.15f)) // punto blanco = IA del teléfono / nube
                }
                for (hh in 0..24) {
                    val hx = w * hh / 24f
                    drawLine(BorderStrong, Offset(hx, 0f), Offset(hx, if (hh % 3 == 0) 16.dp.toPx() else 9.dp.toPx()), 2f)
                    if (hh < 24 && hh % labelEvery == 0) drawText(measurer, "%02d:00".format(hh), Offset(hx + 4.dp.toPx(), 0f),
                        androidx.compose.ui.text.TextStyle(color = PureWhite, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    // a partir de 3x se marcan también los cuartos de hora
                    if (hh < 24 && hourDp >= 240.dp) for (q in 1..3) {
                        val qx = hx + w / 24f * q / 4f
                        drawLine(BorderStrong.copy(alpha = 0.6f), Offset(qx, 0f), Offset(qx, 6.dp.toPx()), 1f)
                    }
                }
                if (now in day until day + dayMs) {
                    drawLine(CrimsonRed, Offset(x(now), 12.dp.toPx()), Offset(x(now), size.height), 4f)
                    drawCircle(CrimsonRed, 4.dp.toPx(), Offset(x(now), mid))
                    drawText(measurer, "AHORA", Offset(x(now) - 40.dp.toPx(), 0f),
                        androidx.compose.ui.text.TextStyle(color = CrimsonRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
                playing?.takeIf { it in day until day + dayMs }?.let { p -> drawLine(PureWhite, Offset(x(p), 0f), Offset(x(p), size.height), 4f) }
            }
        }
        // leyenda
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp, 12.dp).background(CrimsonRed)); Spacer(Modifier.width(4.dp)); Mono("Evento (${dayAlerts.size + dayMarks.size})", MutedText, 10.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(12.dp, 4.dp).background(MutedText.copy(alpha = 0.8f))); Spacer(Modifier.width(4.dp))
            Mono("Continuo ${recordedMin / 60}h${"%02d".format(recordedMin % 60)}", MutedText, 10.sp)
        }
        storage?.let { Mono(it, MutedText, 10.sp) }
        if (sdAt != null) {
            val pos = playing ?: sdAt
            val evs = (sdAlerts.map { it.start } + marks).sorted()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SdButton(Icons.Default.SkipPrevious, "Evento anterior", evs.lastOrNull { it < pos - 8_000 } != null) {
                    evs.lastOrNull { it < pos - 8_000 }?.let { onPlay(it - 3_000) }
                }
                SdButton(Icons.Default.Replay5, "5 min atrás") { onPlay(pos - 300_000) }
                SdButton(Icons.Default.Replay30, "30 s atrás") { onPlay(pos - 30_000) }
                SdButton(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Reanudar" else "Pausar", big = true) { onPause(!paused) }
                SdButton(Icons.Default.Forward30, "30 s adelante") { onPlay(pos + 30_000) }
                SdButton(Icons.Default.Forward5, "5 min adelante") { onPlay(pos + 300_000) }
                SdButton(Icons.Default.SkipNext, "Evento siguiente", evs.firstOrNull { it > pos + 3_000 } != null) {
                    evs.firstOrNull { it > pos + 3_000 }?.let { onPlay(it - 3_000) }
                }
            }
            // descarga = se graba la reproducción en tiempo real (el SDK no baja archivos de la tarjeta)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, null, tint = if (downloading != null) CrimsonRed else MutedText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                if (downloading != null) Mono(downloading, CrimsonRed, 11.sp, Modifier.weight(1f))
                else {
                    Mono("DESCARGAR MP4", MutedText, 11.sp, Modifier.weight(1f))
                    listOf(30 to "30 s", 60 to "1 min", 300 to "5 min").forEach { (s, l) ->
                        TextButton(onClick = { onDownload(s) }, enabled = !paused) { Mono(l, CrimsonRed, 11.sp) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sdAt != null) "Reproduciendo la microSD · toca otro punto para saltar"
                else if (all.isNotEmpty() || dayAlerts.isNotEmpty()) "Barra roja = evento · franja gris = grabación continua" else "",
                color = MutedText, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            if (sdAt != null) TextButton(onClick = onLive) { Mono("EN VIVO", CrimsonRed, 12.sp) }
        }
    }
}

@Composable
private fun SdButton(icon: ImageVector, desc: String, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (big) 48.dp else 40.dp)
        .clip(RoundedCornerShape(4.dp)).background(if (big) CrimsonRed else SurfaceHigh)) {
        Icon(icon, desc, tint = if (!enabled) GhostText else if (big) OnAccent else PureWhite, modifier = Modifier.size(if (big) 26.dp else 20.dp))
    }
}

/** Datos de conexión de la cámara (antes al final de la pantalla de vivo; ahora en Ajustes de la cámara). */
@Composable
fun CameraDetails(camera: CameraDevice) {
    SectionTitle("Detalles", Icons.Default.Info)
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Detail("Modelo", camera.model)
        Detail("UID", camera.uid)
        Detail("P2P", if (camera.tnpKey.isNotEmpty()) "TNP · ${camera.p2pId}" else "TUTK")
        if (camera.localIp.isNotEmpty()) Detail("IP local", camera.localIp)
        Detail("Cifrado", if (camera.encrypted) "Sí" else "No")
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
fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
/** Tarjeta de acción rápida del diseño "En vivo" (ícono en recuadro, título y estado). */
@Composable
private fun QuickCard(icon: ImageVector, title: String, state: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.panel().clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(if (active) CrimsonRed.copy(alpha = 0.25f) else SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (active) CrimsonRed else PureWhite, modifier = Modifier.size(22.dp))
        }
        Text(title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Mono(state, MutedText, 10.sp)
    }
}

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
