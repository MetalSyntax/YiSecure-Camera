package com.metalsyntax.yihomelocal.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitor de seguridad: todas las cámaras en vivo a la vez en mosaico de 2 columnas (substream SD, sin audio).
 * Tocar una celda la abre en grande; al volver se regresa aquí. Las cámaras con PIN se muestran bloqueadas hasta abrirlas.
 */
@Composable
fun MonitorScreen(
    cameras: List<CameraDevice>, full: Boolean, onFull: (Boolean) -> Unit,
    locked: (CameraDevice) -> Boolean, onOpen: (CameraDevice) -> Unit,
) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
        while (true) { clock = f.format(Date()); delay(1000) }
    }
    ImmersiveLandscape(full)
    BackHandler(enabled = full) { onFull(false) }

    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.verticalScroll(rememberScrollState()))) {
        if (!full) AppHeader("Monitor", "${cameras.size} cámaras · $clock") {
            IconButton(onClick = { onFull(true) }) { Icon(Icons.Default.Fullscreen, "Pantalla completa", tint = PureWhite) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier.padding(horizontal = 8.dp)), contentAlignment = Alignment.Center) {
            // 2 columnas 16:9; en pantalla completa la celda se achica hasta que todas las filas quepan en el alto
            val gap = 2.dp
            val rows = (cameras.size + 1) / 2
            val byWidth = (maxWidth - gap) / 2
            val cellW = if (full && rows > 0) minOf(byWidth, (maxHeight - gap * (rows - 1)) / rows * 16f / 9f) else byWidth
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                cameras.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { cam -> MonitorCell(cam, locked(cam), Modifier.width(cellW)) { onOpen(cam) } }
                        if (row.size == 1) Spacer(Modifier.width(cellW))
                    }
                }
            }
            if (full) IconButton(onClick = { onFull(false) }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = OnAccent)
            }
        }
        if (!full) Mono("Toca una cámara para abrirla · ⛶ para modo monitor", MutedText, 10.sp, Modifier.padding(16.dp))
    }
}

@Composable
private fun MonitorCell(cam: CameraDevice, locked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var status by remember(cam.uid) { mutableStateOf("Conectando…") }
    val live = status.startsWith("En vivo") || status.startsWith("Conectado")
    Box(modifier.aspectRatio(16f / 9f).background(Color.Black)) {
        if (locked) {
            Icon(Icons.Default.Lock, null, tint = GhostText, modifier = Modifier.align(Alignment.Center).size(36.dp))
        } else {
            LiveView(cam, 0, YiP2p.QUALITY_SD, false, { status = it }, {}, Modifier.fillMaxSize())
        }
        Mono(
            cam.name.uppercase(), OnAccent, 10.sp,
            Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(2.dp))
                .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
        if (!locked) Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { StatusBadge(live, if (live) "LIVE" else "…") }
        // capa encima del SurfaceView para que el toque llegue a Compose
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}
