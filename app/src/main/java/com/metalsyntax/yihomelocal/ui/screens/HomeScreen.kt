package com.metalsyntax.yihomelocal.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metalsyntax.yihomelocal.data.Alert
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.metalsyntax.yihomelocal.data.Snapshots
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*

@Composable
fun AppHeader(section: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).panel(4), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Shield, null, tint = CrimsonRed, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("YI SECURE", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(" / $section", color = MutedText, fontSize = 18.sp)
            }
            Mono(subtitle.uppercase(), MutedText, 10.sp)
        }
        actions()
    }
}

@Composable
fun HomeScreen(
    cameras: List<CameraDevice>,
    /** uid → true/false verificado por P2P; ausente = verificando; null = no se pudo (se usa lo de la nube). */
    online: Map<String, Boolean?>,
    snapshotVersion: Int,
    busy: Boolean,
    error: String?,
    onSelectCamera: (CameraDevice) -> Unit,
    onEditCamera: (CameraDevice) -> Unit,
    onAddCamera: () -> Unit,
    onRefresh: () -> Unit,
    /** Últimas alertas de la nube (null = cargando o sin acceso). */
    recent: List<Alert>? = null,
    onAllEvents: () -> Unit = {},
    /** Barra de acciones rápidas del diseño. */
    mainCamera: CameraDevice? = null,
    onMonitor: () -> Unit = {},
    silent: Boolean = false,
    silentBusy: Boolean = false,
    onSilent: (Boolean) -> Unit = {},
    /** Perfiles (Profile.name activo o null) y pánico. */
    profile: String? = null,
    profileBusy: Boolean = false,
    onProfile: (com.metalsyntax.yihomelocal.data.Profile) -> Unit = {},
    onPanic: () -> Unit = {},
    /** Eventos de la IA del teléfono (para "Movimiento hace X"). */
    localTimes: Map<String, Long> = emptyMap(),
) {
    var panicAsk by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Alert?>(null) }
    fun isOn(c: CameraDevice) = if (c.uid in online) online[c.uid] ?: c.online else null
    val onCount = cameras.count { isOn(it) == true }
    val checking = cameras.any { it.uid !in online }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Cámaras", "${cameras.size} cámaras · $onCount en línea") {
            IconButton(onClick = onRefresh, enabled = !busy) {
                if (busy) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Default.Refresh, "Actualizar", tint = PureWhite)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // perfiles del diseño + botón de pánico
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.metalsyntax.yihomelocal.data.Profile.entries.forEach { p ->
                            val sel = profile == p.name
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(if (sel) CrimsonRed else CardBackground)
                                    .clickable(enabled = !profileBusy) { onProfile(p) }.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (sel) { Box(Modifier.size(6.dp).clip(CircleShape).background(OnAccent)); Spacer(Modifier.width(6.dp)) }
                                Mono(p.label, if (sel) OnAccent else MutedText, 11.sp)
                            }
                        }
                    }
                    if (profileBusy) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(CardBackground).clickable { panicAsk = true },
                        contentAlignment = Alignment.Center) { Icon(Icons.Default.Warning, "Pánico", tint = CrimsonRed) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().panel().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$onCount de ${cameras.size} cámaras en línea", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Mono(if (checking) "Verificando por P2P…" else "Estado verificado por P2P", MutedText, 10.sp)
                    }
                    StatusBadge(onCount > 0, if (checking) "…" else if (onCount > 0) "ACTIVO" else "SIN SEÑAL")
                }
            }
            if (error != null) item { Text(error, color = CrimsonRed, fontSize = 13.sp) }
            // principal grande (la última abierta) y el resto de dos en dos, como el diseño
            val main = mainCamera?.takeIf { m -> cameras.any { it.uid == m.uid } } ?: cameras.firstOrNull()
            fun last(uid: String) = maxOf(recent.orEmpty().filter { it.uid == uid }.maxOfOrNull { it.time } ?: 0L, localTimes[uid] ?: 0L)
            main?.let { cam ->
                item(key = cam.uid) {
                    CameraCard(cam, isOn(cam), snapshotVersion, last(cam.uid), onClick = { onSelectCamera(cam) }, onEdit = { onEditCamera(cam) })
                }
            }
            cameras.filter { it.uid != main?.uid }.chunked(2).forEach { row ->
                item(key = row.joinToString { it.uid }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { cam ->
                            SmallCameraCard(cam, isOn(cam), snapshotVersion, last(cam.uid), Modifier.weight(1f)) { onSelectCamera(cam) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { SecondaryButton("+ Añadir nueva cámara", onClick = onAddCamera) }
            if (cameras.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().panel().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Hablar y Captura abren la cámara principal (necesitan su conexión P2P); "Abrir" no aplica: no hay cerradura
                    QuickAction(Icons.Default.Mic, "Hablar", false, Modifier.weight(1f)) { (mainCamera ?: cameras.first()).let(onSelectCamera) }
                    QuickAction(Icons.Default.GridView, "Monitor", false, Modifier.weight(1f), onClick = onMonitor)
                    QuickAction(if (silent) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                        if (silentBusy) "…" else if (silent) "Silenciosa" else "Alertas", silent, Modifier.weight(1f)) { if (!silentBusy) onSilent(!silent) }
                    QuickAction(Icons.Default.Notifications, "Eventos", false, Modifier.weight(1f), onClick = onAllEvents)
                }
            }
            if (recent != null) {
                item {
                    SectionTitle("Registro de incidentes", Icons.Default.Bolt) {
                        Mono("VER TODO", CrimsonRed, 10.sp, Modifier.clickable(onClick = onAllEvents).padding(4.dp))
                    }
                }
                if (recent.isEmpty()) item { Text("Sin alertas en los últimos 7 días.", color = MutedText, fontSize = 13.sp) }
                items(recent.take(3), key = { "alert" + it.id + it.time }) { a ->
                    AlertRow(a, cameras.firstOrNull { it.uid == a.uid }?.name ?: a.uid) { open = a }
                }
            }
        }
    }
    open?.let { a -> AlertDetailDialog(a, cameras.firstOrNull { it.uid == a.uid }?.name ?: a.uid) { open = null } }
    if (panicAsk) AlertDialog(
        onDismissRequest = { panicAsk = false }, containerColor = CardBackground,
        icon = { Icon(Icons.Default.Warning, null, tint = CrimsonRed) },
        title = { Text("¿Activar pánico?", color = PureWhite) },
        text = { Text("Enciende todas las cámaras con alertas (perfil Fuera de casa) y empieza a grabar MP4 de la cámara principal.", color = MutedText) },
        confirmButton = { TextButton(onClick = { panicAsk = false; onPanic() }) { Text("ACTIVAR", color = CrimsonRed) } },
        dismissButton = { TextButton(onClick = { panicAsk = false }) { Text("Cancelar", color = MutedText) } },
    )
}

/** "hace 4 m", "hace 2 h", "hace 3 d". */
private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "ahora"; m < 60 -> "hace $m m"; m < 1440 -> "hace ${m / 60} h"; else -> "hace ${m / 1440} d" }
}

/** Etiquetas sobre el video a partir del último estado leído por P2P y de la última alerta. */
@Composable
private fun StatusChips(uid: String, snapshotVersion: Int, lastEvent: Long, modifier: Modifier) {
    val ctx = LocalContext.current
    val st = remember(uid, snapshotVersion) { com.metalsyntax.yihomelocal.data.CameraStatus.load(ctx, uid) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lastEvent > System.currentTimeMillis() - 10 * 60_000) Chip("ALERTA", CrimsonRed)
        if (st?.privacy == true) Chip("PRIVACIDAD", Color.Black.copy(alpha = 0.8f), Icons.Default.VisibilityOff)
        if (st?.night == 3) Chip("IR ACTIVO", Color.Black.copy(alpha = 0.8f), Icons.Default.Bedtime)
        if (st != null && st.sd) Chip(if (st.continuous) "SD 24H" else "SD EVENTOS", Color.Black.copy(alpha = 0.8f), Icons.Default.SdCard)
    }
}

@Composable
private fun Chip(text: String, bg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(2.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = OnAccent, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(4.dp)) }
        Mono(text, OnAccent, 10.sp)
    }
}

/** Pie de tarjeta: Wi-Fi/batería del último estado leído y "Movimiento hace X". */
@Composable
private fun CardFooter(uid: String, snapshotVersion: Int, lastEvent: Long, on: Boolean?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val st = remember(uid, snapshotVersion) { com.metalsyntax.yihomelocal.data.CameraStatus.load(ctx, uid) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (lastEvent > 0) {
            Icon(Icons.Default.Sensors, null, tint = CrimsonRed, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
            Mono("Movimiento ${ago(lastEvent)}", PureWhite, 10.sp, Modifier.weight(1f))
        } else Mono(when (on) { true -> "Sin anomalías registradas"; false -> "No responde en la red P2P"; null -> "Comprobando…" },
            if (on == false) CrimsonRed else MutedText, 10.sp, Modifier.weight(1f))
        st?.let {
            if (it.signal in 1..100) { Icon(Icons.Default.Wifi, null, tint = MutedText, modifier = Modifier.size(13.dp)); Mono(" ${it.signal}%", MutedText, 10.sp) }
            if (it.battery in 1..100) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.BatteryStd, null, tint = MutedText, modifier = Modifier.size(13.dp)); Mono(" ${it.battery}%", MutedText, 10.sp) }
        }
    }
}

@Composable
private fun SmallCameraCard(camera: CameraDevice, on: Boolean?, snapshotVersion: Int, lastEvent: Long, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val snap = remember(camera.uid, snapshotVersion) { Snapshots.load(ctx, camera.uid) }
    Column(modifier.panel().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(camera.name, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (on == true) CrimsonRed else BorderStrong))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (snap != null) Image(snap.first.asImageBitmap(), "Última imagen", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Videocam, null, tint = BorderStrong, modifier = Modifier.size(36.dp))
            StatusChips(camera.uid, snapshotVersion, lastEvent, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        CardFooter(camera.uid, snapshotVersion, lastEvent, on, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

@Composable
private fun CameraCard(camera: CameraDevice, on: Boolean?, snapshotVersion: Int, lastEvent: Long, onClick: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val snap = remember(camera.uid, snapshotVersion) { Snapshots.load(ctx, camera.uid) }
    Column(Modifier.fillMaxWidth().panel().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(camera.name, color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Mono("#${camera.uid.takeLast(4)}", MutedText, 11.sp, Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Default.Tune, "Editar cámara", tint = MutedText) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (snap != null) Image(snap.first.asImageBitmap(), "Última imagen", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Videocam, null, tint = BorderStrong, modifier = Modifier.size(48.dp))
            Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge(on == true, when (on) { null -> "VERIFICANDO"; true -> "EN LÍNEA"; false -> "DESCONECTADA" })
                StatusChips(camera.uid, snapshotVersion, lastEvent, Modifier)
            }
            if (snap != null) Mono(
                "ÚLTIMA IMAGEN · " + SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(snap.second)), OnAccent, 10.sp,
                Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Mono(
                camera.model.ifEmpty { "YI" }.uppercase(), OnAccent, 10.sp,
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        CardFooter(camera.uid, snapshotVersion, lastEvent, on, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** Botón de la barra de acciones rápidas (diseño "Cámaras": Hablar / Captura / Silenciosa / Abrir). */
@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(4.dp)).background(SurfaceHigh).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, label, tint = if (active) CrimsonRed else PureWhite, modifier = Modifier.size(22.dp))
        Text(label, color = if (active) CrimsonRed else PureWhite, fontSize = 12.sp, maxLines = 1)
    }
}
