package com.metalsyntax.yihomelocal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.BuildConfig
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*

@Composable
fun SettingsScreen(
    cameras: List<CameraDevice>,
    online: Map<String, Boolean?>,
    region: String,
    onAddCamera: () -> Unit,
    onEditCamera: (CameraDevice) -> Unit,
    onLogout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("Ajustes", "Cuenta Yi · región ${region.uppercase()}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Gestión de dispositivos") { Mono("${cameras.size} CÁMARAS", MutedText, 10.sp) }
            Column(Modifier.fillMaxWidth().panel()) {
                cameras.forEach { cam ->
                    val on = if (cam.uid in online) online[cam.uid] ?: cam.online else null
                    Row(
                        Modifier.fillMaxWidth().clickable { onEditCamera(cam) }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cam.name, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Mono("${cam.model} · ${when (on) { true -> "En línea"; false -> "Desconectada"; null -> "Verificando…" }}", if (on == false) CrimsonRed else MutedText, 10.sp)
                        }
                        IconButton(onClick = { onEditCamera(cam) }) { Icon(Icons.Default.Tune, "Editar", tint = MutedText) }
                    }
                    HorizontalDivider(color = BorderSubtle)
                }
                TextButton(onClick = onAddCamera, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    Mono("⊕  AÑADIR NUEVA CÁMARA", PureWhite, 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("⏻  Cerrar sesión", color = CrimsonRed, onClick = onLogout)
            Mono("YI SECURE v${BuildConfig.VERSION_NAME} · SDK Yi Home 6.9.9", GhostText, 10.sp, Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
}

/** Renombrar o quitar una cámara de la cuenta. Borrar pide confirmación porque luego hay que volver a emparejarla. */
@Composable
fun EditCameraDialog(camera: CameraDevice, busy: Boolean, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember(camera.uid) { mutableStateOf(camera.name) }
    var confirmDelete by remember(camera.uid) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        title = { Text(if (confirmDelete) "¿Eliminar ${camera.name}?" else "Editar cámara", color = PureWhite) },
        text = {
            if (confirmDelete) {
                Text(
                    "Se quita de tu cuenta Yi (también de la app oficial). Para volver a usarla hay que resetearla y emparejarla con QR.",
                    color = MutedText, fontSize = 14.sp,
                )
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it.take(32) }, "Nombre")
                Mono("UID ${camera.uid}", MutedText, 10.sp)
                if (camera.localIp.isNotEmpty()) Mono("IP ${camera.localIp}", MutedText, 10.sp)
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Mono("ELIMINAR CÁMARA", CrimsonRed, 12.sp) }
            }
        },
        confirmButton = {
            if (confirmDelete) TextButton(onClick = onDelete, enabled = !busy) { Text("Eliminar", color = CrimsonRed) }
            else TextButton(onClick = { onRename(name.trim()) }, enabled = !busy && name.isNotBlank() && name.trim() != camera.name) {
                Text("Guardar", color = PureWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) { Text("Cancelar", color = MutedText) }
        },
    )
}
