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
    var message by remember { mutableStateOf<String?>(null) }
    val tools = remember { LiveTools() }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        while (true) { clock = f.format(Date()); delay(1000) }
    }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }
    val live = status.startsWith("En vivo") || status.startsWith("Conectado")

    // pantalla completa = horizontal sin barras del sistema; se deshace al salir
    val activity = ctx as? Activity
    DisposableEffect(full) {
        val w = activity?.window
        if (w != null) {
            val ctl = WindowCompat.getInsetsController(w, w.decorView)
            if (full) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (full) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }
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
            if (full) Modifier.fillMaxSize()
            else Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).panel().background(Color.Black),
        ) {
            LiveView(camera, session, quality, audio, { status = it }, { cam = it }, Modifier.fillMaxSize(), tools)
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(live, if (live) "LIVE" else "…")
                if (recording) {
                    val secs = ((System.currentTimeMillis() - recStart) / 1000).toInt()
                    clock.length // recomponer cada segundo con el reloj
                    StatusBadge(true, "REC %02d:%02d".format(secs / 60, secs % 60))
                }
            }
            Mono(
                clock, PureWhite, 11.sp,
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            if (audio) Mono(
                "AUDIO ON", PureWhite, 10.sp,
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            IconButton(
                onClick = { full = !full },
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            ) {
                Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    if (full) "Salir de pantalla completa" else "Pantalla completa", tint = PureWhite)
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
                    status = "Reconectando…"; session++
                })
            }
            message?.let { Text(it, color = MutedText, fontSize = 12.sp) }

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
            Toggle("Luz indicadora", "LED de estado del frente", i.close_light.toInt() != 1) { on -> apply { YiP2p.setLed(it, on) } }
            Toggle("Detección de persona", "Alertas solo cuando hay personas", i.personDetectionEnabled()) { on ->
                apply { YiP2p.setPersonDetection(it, on) }
            }
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
        Detail("microSD", i?.let { sdText(it.tfstat.toInt()) } ?: "—")
        if (i != null && i.tfstat.toInt() != 5 && i.total > 0) Detail("Espacio", "${i.free} libres de ${i.total}")
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
                checkedThumbColor = PureWhite, checkedTrackColor = CrimsonRed,
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
            ) { Mono(label, if (sel) PureWhite else MutedText, 12.sp) }
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
