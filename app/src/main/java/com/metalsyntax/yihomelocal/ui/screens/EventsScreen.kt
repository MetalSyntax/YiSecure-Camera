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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.metalsyntax.yihomelocal.data.LocalEvent
import com.metalsyntax.yihomelocal.data.LocalEvents
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
fun EventsScreen(
    cloud: YiCloud, session: YiCloud.Session, cameras: List<CameraDevice>,
    /** Abre la cámara [uid] reproduciendo la microSD desde [time] (epoch ms). */
    onOpenSd: (uid: String, time: Long) -> Unit = { _, _ -> },
) {
    var alerts by remember { mutableStateOf<List<Alert>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf<String?>(null) }
    var camUid by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Alert?>(null) }
    // detecciones de la IA del teléfono (guardadas al mirar el vivo)
    val ctx = LocalContext.current
    var aiOnly by remember { mutableStateOf(false) }
    var aiKind by remember { mutableStateOf<String?>(null) }
    var favVersion by remember { mutableIntStateOf(0) }
    val local = remember(reload, aiOnly, favVersion) { LocalEvents.all(ctx, cameras.map { it.uid }) }
    var favOnly by remember { mutableStateOf(false) }
    var openLocal by remember { mutableStateOf<LocalEvent?>(null) }
    // nuevos = posteriores a la última vez que se marcó "leído"
    val store = remember { com.metalsyntax.yihomelocal.data.Store(ctx) }
    var seen by remember { mutableLongStateOf(store.eventsSeen) }

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
            Chip("TODOS", kind == null && !aiOnly, Icons.Default.Apps) { kind = null; aiOnly = false }
            Chip("IA DEL TELÉFONO (${local.size})", aiOnly, Icons.Default.Psychology) { aiOnly = true }
            if (!aiOnly) alerts.orEmpty().map { it.kind }.distinct().forEach { k -> Chip(k.uppercase(), kind == k, kindIcon(k)) { kind = k } }
        }
        val newCount = alerts.orEmpty().count { it.time > seen } + local.count { it.time > seen }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (newCount > 0) Box(Modifier.size(8.dp).clip(CircleShape).background(CrimsonRed))
            Spacer(Modifier.width(6.dp))
            Mono(if (newCount > 0) "$newCount EVENTOS NUEVOS" else "SIN EVENTOS NUEVOS", if (newCount > 0) PureWhite else MutedText, 11.sp, Modifier.weight(1f))
            if (newCount > 0) Row(
                Modifier.clip(RoundedCornerShape(4.dp)).background(CardBackground).clickable {
                    seen = System.currentTimeMillis(); store.eventsSeen = seen
                }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.DoneAll, null, tint = MutedText, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                Mono("MARCAR LEÍDO", MutedText, 10.sp)
            }
        }
        if (aiOnly && local.isNotEmpty()) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("TODAS", aiKind == null && !favOnly) { aiKind = null; favOnly = false }
            if (local.any { it.fav }) Chip("FAVORITOS (${local.count { it.fav }})", favOnly, Icons.Default.Star) { favOnly = true; aiKind = null }
            local.map { it.kind }.distinct().forEach { k -> Chip(k, aiKind == k, kindIcon(k)) { aiKind = k; favOnly = false } }
        }
        if (cameras.size > 1) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("TODAS LAS CÁMARAS", camUid == null, Icons.Default.GridView) { camUid = null }
            cameras.forEach { c -> Chip(c.name.uppercase(), camUid == c.uid, Icons.Default.Videocam) { camUid = c.uid } }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (aiOnly) {
                val shown = local.filter { (aiKind == null || it.kind == aiKind) && (!favOnly || it.fav) && (camUid == null || it.uid == camUid) }
                // resumen del día por mascota (filtro MASCOTA)
                if (aiKind == "MASCOTA" && shown.isNotEmpty()) item { PetSummary(shown, names) }
                if (shown.isEmpty()) item {
                    Text("Sin detecciones todavía. Abre una cámara con \"IA ON\": el teléfono reconoce personas, vehículos, mascotas (gatos, perros, aves), sombras y movimiento y los guarda aquí.",
                        color = MutedText, fontSize = 13.sp)
                }
                shown.groupBy { dayOf(it.time) }.forEach { (d, evs) ->
                    item(key = "d$d") { DayHeader(d, evs.size, evs.count { it.night }) }
                    items(evs, key = { it.file }) { e -> LocalEventCard(e, names[e.uid] ?: e.uid, e.time > seen) { openLocal = e } }
                }
                return@LazyColumn
            }
            error?.let { e -> item { Text(e, color = CrimsonRed, fontSize = 13.sp) } }
            if (alerts != null && list.isEmpty()) item {
                Text("Sin alertas en este periodo. La cámara sube una alerta a la nube de Yi cuando detecta movimiento con las alertas activadas.",
                    color = MutedText, fontSize = 13.sp)
            }
            list.groupBy { dayOf(it.time) }.forEach { (d, al) ->
                item(key = "d$d") { DayHeader(d, al.size) }
                items(al, key = { it.id.ifEmpty { "${it.uid}${it.time}" } }) { a ->
                    AlertRow(a, names[a.uid] ?: a.uid, a.time > seen) { open = a }
                }
            }
        }
    }

    open?.let { a -> AlertDetailDialog(a, names[a.uid] ?: a.uid, onSd = { open = null; onOpenSd(a.uid, a.time) }) { open = null } }
    openLocal?.let { e ->
        LocalEventDialog(e, names[e.uid] ?: e.uid, onDismiss = { openLocal = null },
            onSd = { openLocal = null; onOpenSd(e.uid, e.time) }, onFav = { favVersion++ })
    }
}

/** Tarjeta del historial (diseño "Eventos"): foto con las cajas, hora, cámara, tipo y descripción. */
@Composable
private fun LocalEventCard(e: LocalEvent, camName: String, isNew: Boolean, onOpen: () -> Unit) {
    val img = remember(e.file) { runCatching { BitmapFactory.decodeFile(e.file) }.getOrNull() }
    Column(Modifier.fillMaxWidth().panel().clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.fav) Icon(Icons.Default.Star, "Favorito", tint = CrimsonRed, modifier = Modifier.size(18.dp).background(Color.Black.copy(alpha = 0.75f)).padding(2.dp))
                if (e.night) Row(Modifier.background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, null, tint = OnAccent, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(4.dp))
                    Mono("IR NOCTURNO", OnAccent, 10.sp)
                }
                if (isNew) StatusBadge(true, "NUEVO")
            }
            Mono(camName.uppercase(), OnAccent, 10.sp, Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = OnAccent, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                Mono(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.time)), OnAccent, 11.sp)
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(e.time)), MutedText, 11.sp)
                Text("  ${e.title}", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                // de noche el tipo va en rojo oscuro, como "MOVIMIENTO NOCTURNO" del diseño
                Row(Modifier.clip(RoundedCornerShape(2.dp)).background(if (e.night) DarkRed else CrimsonRed).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(e.kind), null, tint = OnAccent, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                    Mono(if (e.night) "${e.kind} NOCTURNO" else e.kind, OnAccent, 10.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.desc, color = MutedText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (e.score > 0) Mono("${LocalEvents.objLabel(e.obj)} · ${(e.score * 100).toInt()} %", PureWhite, 10.sp,
                    Modifier.clip(RoundedCornerShape(2.dp)).background(CardBackground).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** Resumen por mascota de los eventos mostrados: veces vista, última hora y cámara. */
@Composable
private fun PetSummary(evs: List<LocalEvent>, names: Map<String, String>) {
    val pets = evs.filter { it.obj.isNotEmpty() }.groupBy { it.obj }
    if (pets.isEmpty()) return
    Column(Modifier.fillMaxWidth().panel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Pets, null, tint = CrimsonRed, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Mono("RESUMEN DE MASCOTAS", PureWhite, 12.sp)
        }
        pets.forEach { (obj, l) ->
            val last = l.maxBy { it.time }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LocalEvents.objLabel(obj).lowercase().replaceFirstChar { it.uppercase() }, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Mono("${l.size} ${if (l.size == 1) "vez" else "veces"} · última ${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(last.time))} · ${names[last.uid] ?: last.uid}", MutedText, 10.sp)
            }
        }
    }
}

/** Fila del "Registro de incidentes": miniatura + tipo + cámara + hora. */
@Composable
fun AlertRow(a: Alert, camName: String, isNew: Boolean = false, onClick: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(a.kind), null, tint = CrimsonRed, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(a.kind, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (isNew) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(CrimsonRed)) }
            }
            Mono(camName.uppercase(), MutedText, 10.sp)
        }
        Mono(SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(a.time)), MutedText, 10.sp)
    }
}

/** Imagen grande y, si la nube dio clip, reproducción (se descarga y descifra a caché). */
@Composable
fun AlertDetailDialog(a: Alert, camName: String, onSd: (() -> Unit)? = null, onDismiss: () -> Unit) {
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
            // misma hora en la grabación de la microSD (5 s antes)
            if (onSd != null) PrimaryButton("Ver en la microSD", onClick = onSd)
            val text = "${a.kind} · $camName · " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(a.time))
            // la nube borra las alertas a los pocos días: guardarla en la galería del teléfono
            if (clip != null || img != null) SecondaryButton(if (clip != null) "Guardar clip en galería" else "Guardar imagen en galería", icon = Icons.Default.Download) {
                runCatching {
                    clip?.let { f -> com.metalsyntax.yihomelocal.data.Gallery.saveVideo(ctx, f.copyTo(File(ctx.cacheDir, "save_${f.name}"), true)) }
                        ?: img?.let { com.metalsyntax.yihomelocal.data.Gallery.saveImage(ctx, camName, it) }
                }.onSuccess { clipError = "Guardado en $it" }.onFailure { clipError = "No se pudo guardar: ${it.message}" }
            }
            // comparte el clip si ya se descargó; si no, la imagen
            if (clip != null || img != null) SecondaryButton(if (clip != null) "Compartir clip" else "Compartir imagen", icon = Icons.Default.Share) {
                runCatching {
                    clip?.let { com.metalsyntax.yihomelocal.data.Share.file(ctx, it, "video/mp4", text) }
                        ?: img?.let { com.metalsyntax.yihomelocal.data.Share.image(ctx, it, "${a.uid}_${a.time}", text) }
                }.onFailure { clipError = "No se pudo compartir: ${it.message}" }
            }
            SecondaryButton("Cerrar", onClick = onDismiss)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) CrimsonRed else CardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (selected) OnAccent else MutedText, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
        Mono(text, if (selected) OnAccent else MutedText, 11.sp)
    }
}

/** Medianoche local del día de [t] (para agrupar por día). */
private fun dayOf(t: Long) = java.util.Calendar.getInstance().apply {
    timeInMillis = t; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** Encabezado "Hoy 02 de noviembre · N detecciones", como el diseño "Eventos". */
@Composable
private fun DayHeader(day: Long, count: Int, nightCount: Int = 0) {
    val today = dayOf(System.currentTimeMillis())
    val name = when (day) { today -> "Hoy"; today - 86_400_000L -> "Ayer"; else -> SimpleDateFormat("EEEE", Locale("es")).format(Date(day)).replaceFirstChar { it.uppercase() } }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (day == today) Icons.Default.CalendarToday else Icons.Default.History, null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(SimpleDateFormat("d 'de' MMMM, yyyy", Locale("es")).format(Date(day)), color = MutedText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (nightCount > 0) {
            Mono("$nightCount NOCTURNO", OnAccent, 10.sp, Modifier.clip(RoundedCornerShape(2.dp)).background(DarkRed).padding(horizontal = 6.dp, vertical = 3.dp))
            Spacer(Modifier.width(4.dp))
        }
        Mono("$count ${if (count == 1) "DETECCIÓN" else "DETECCIONES"}", MutedText, 10.sp,
            Modifier.clip(RoundedCornerShape(2.dp)).background(CardBackground).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}
