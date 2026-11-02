package com.metalsyntax.yihomelocal.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
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
                Row(Modifier.fillMaxWidth().panel().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$onCount de ${cameras.size} cámaras en línea", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Mono(if (checking) "Verificando por P2P…" else "Estado verificado por P2P", MutedText, 10.sp)
                    }
                    StatusBadge(onCount > 0, if (checking) "…" else if (onCount > 0) "ACTIVO" else "SIN SEÑAL")
                }
            }
            if (error != null) item { Text(error, color = CrimsonRed, fontSize = 13.sp) }
            items(cameras, key = { it.uid }) { cam ->
                CameraCard(cam, isOn(cam), snapshotVersion, onClick = { onSelectCamera(cam) }, onEdit = { onEditCamera(cam) })
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
}

@Composable
private fun CameraCard(camera: CameraDevice, on: Boolean?, snapshotVersion: Int, onClick: () -> Unit, onEdit: () -> Unit) {
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
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                StatusBadge(on == true, when (on) { null -> "VERIFICANDO"; true -> "EN LÍNEA"; false -> "DESCONECTADA" })
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Mono(when (on) { true -> "Toca para ver en vivo"; false -> "No responde en la red P2P"; null -> "Comprobando conexión…" }, if (on == false) CrimsonRed else MutedText, 11.sp, Modifier.weight(1f))
            Mono(if (camera.tnpKey.isNotEmpty()) "TNP" else "TUTK", MutedText, 11.sp)
        }
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
