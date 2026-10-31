package com.metalsyntax.yihomelocal.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metalsyntax.yihomelocal.data.Alert
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Miniaturas descifradas en memoria, compartidas por Eventos y el registro de la pantalla principal. */
private val thumbs = android.util.LruCache<String, Bitmap>(60)

suspend fun alertImage(a: Alert): Bitmap? {
    if (a.picUrl.isEmpty()) return null
    thumbs.get(a.picUrl)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching { Alert.download(a.picUrl, a.picPwd).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }?.also { thumbs.put(a.picUrl, it) }
}

/** Alertas de los últimos 7 días (la nube recorta a lo que guarda el plan de la cuenta). */
fun loadAlerts(cloud: YiCloud, s: YiCloud.Session): List<Alert> {
    val now = System.currentTimeMillis() / 1000
    return cloud.alerts(s, now - 7 * 86400, now)
}

@Composable
fun EventsScreen(cloud: YiCloud, session: YiCloud.Session, cameras: List<CameraDevice>) {
    var alerts by remember { mutableStateOf<List<Alert>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf<String?>(null) }
    var camUid by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Alert?>(null) }

    LaunchedEffect(reload) {
        error = null
        runCatching { withContext(Dispatchers.IO) { loadAlerts(cloud, session) } }
            .onSuccess { alerts = it }
            .onFailure { error = (it as? YiCloud.ApiException)?.let { e -> "La API de Yi respondió código ${e.code}" } ?: it.message }
    }

    val names = cameras.associate { it.uid to it.name }
    val list = alerts.orEmpty().filter { (kind == null || it.kind == kind) && (camUid == null || it.uid == camUid) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Eventos", if (alerts == null) "Cargando alertas…" else "${list.size} alertas · últimos 7 días") {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Actualizar", tint = PureWhite) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("TODOS", kind == null) { kind = null }
            alerts.orEmpty().map { it.kind }.distinct().forEach { k -> Chip(k.uppercase(), kind == k) { kind = k } }
        }
        if (cameras.size > 1) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("TODAS LAS CÁMARAS", camUid == null) { camUid = null }
            cameras.forEach { c -> Chip(c.name.uppercase(), camUid == c.uid) { camUid = c.uid } }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { e -> item { Text(e, color = CrimsonRed, fontSize = 13.sp) } }
            if (alerts != null && list.isEmpty()) item {
                Text("Sin alertas en este periodo. La cámara sube una alerta a la nube de Yi cuando detecta movimiento con las alertas activadas.",
                    color = MutedText, fontSize = 13.sp)
            }
            items(list, key = { it.id.ifEmpty { "${it.uid}${it.time}" } }) { a ->
                AlertRow(a, names[a.uid] ?: a.uid) { open = a }
            }
        }
    }

    open?.let { a -> AlertDetailDialog(a, names[a.uid] ?: a.uid) { open = null } }
}

/** Fila del "Registro de incidentes": miniatura + tipo + cámara + hora. */
@Composable
fun AlertRow(a: Alert, camName: String, onClick: () -> Unit) {
    var img by remember(a.picUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(a.picUrl) { img = alertImage(a) }
    Row(Modifier.fillMaxWidth().panel().clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Default.Videocam, null, tint = BorderStrong)
            if (a.videoUrl.isNotEmpty()) Icon(Icons.Default.PlayArrow, "Tiene clip", tint = OnAccent, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.kind, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Mono(camName.uppercase(), MutedText, 10.sp)
        }
        Mono(SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(a.time)), MutedText, 10.sp)
    }
}

/** Imagen grande y, si la nube dio clip, reproducción (se descarga y descifra a caché). */
@Composable
fun AlertDetailDialog(a: Alert, camName: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var clip by remember { mutableStateOf<File?>(null) }
    var loadingClip by remember { mutableStateOf(false) }
    var clipError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a) { img = alertImage(a) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().panel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.kind, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Mono("$camName · " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(a.time)), MutedText, 10.sp)
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                val f = clip
                if (f != null) AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        setVideoPath(f.path)
                        setOnErrorListener { _, _, _ -> clipError = "Android no pudo reproducir el clip"; true }
                        start()
                    }
                }, modifier = Modifier.fillMaxSize())
                else img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            clipError?.let { Text(it, color = CrimsonRed, fontSize = 12.sp) }
            if (a.videoUrl.isNotEmpty() && clip == null) {
                PrimaryButton(if (loadingClip) "Descargando clip…" else "Reproducir clip", enabled = !loadingClip) { loadingClip = true }
                if (loadingClip) LaunchedEffect(Unit) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            File(ctx.cacheDir, "clip_${a.uid}_${a.time}.mp4").apply { if (!exists()) writeBytes(Alert.download(a.videoUrl, a.videoPwd)) }
                        }
                    }.onSuccess { clip = it }.onFailure { clipError = "No se pudo descargar el clip: ${it.message}" }
                    loadingClip = false
                }
            } else if (a.videoUrl.isEmpty()) Text("Esta alerta solo trae imagen (la nube de Yi no guardó clip).", color = MutedText, fontSize = 12.sp)
            SecondaryButton("Cerrar", onClick = onDismiss)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) CrimsonRed else CardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Mono(text, if (selected) OnAccent else MutedText, 11.sp) }
}
