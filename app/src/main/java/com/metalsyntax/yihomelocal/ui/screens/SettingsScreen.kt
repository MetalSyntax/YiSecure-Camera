package com.metalsyntax.yihomelocal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.BuildConfig
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.xiaoyi.camera.sdk.AntsCamera
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*

@Composable
fun SettingsScreen(
    cameras: List<CameraDevice>,
    online: Map<String, Boolean?>,
    region: String,
    theme: Int,
    onTheme: (Int) -> Unit,
    onAddCamera: () -> Unit,
    onEditCamera: (CameraDevice) -> Unit,
    onLogout: () -> Unit,
    silent: Boolean = false,
    onSilent: (Boolean) -> Unit = {},
    kiosk: Boolean = false,
    onKiosk: (Boolean) -> Unit = {},
    /** Se aceptó una cámara compartida: recargar la lista desde la nube. */
    onCamerasChanged: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.metalsyntax.yihomelocal.data.Store(ctx) }
    val scope = rememberCoroutineScope()
    var notify by remember { mutableStateOf(store.notify && com.metalsyntax.yihomelocal.data.AlertNotifier.canNotify(ctx)) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    fun setNotify(on: Boolean) { notify = on; store.notify = on; com.metalsyntax.yihomelocal.data.AlertNotifier.schedule(ctx, on) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> setNotify(ok) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("Ajustes", "Cuenta Yi · región ${region.uppercase()}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // firmware nuevo según la última versión leída de cada cámara (sin abrir P2P)
            var updates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            LaunchedEffect(cameras) {
                updates = cameras.mapNotNull { c ->
                    val cur = store.firmware(c.uid) ?: return@mapNotNull null
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metalsyntax.yihomelocal.data.YiCloud(region).firmware(c.uid, c.model, cur) } }
                        .getOrNull()?.takeIf { it.needUpdate }?.let { c.uid to it.version }
                }.toMap()
            }
            SectionTitle("Gestión de dispositivos", Icons.Default.Videocam) {
                if (updates.isNotEmpty()) Mono("${updates.size} ${if (updates.size == 1) "ACTUALIZACIÓN" else "ACTUALIZACIONES"}", OnAccent, 10.sp,
                    Modifier.clip(RoundedCornerShape(50)).background(CrimsonRed).padding(horizontal = 8.dp, vertical = 3.dp))
                else Mono("${cameras.size} CÁMARAS", MutedText, 10.sp)
            }
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
                            updates[cam.uid]?.let { v -> Mono("Firmware $v disponible", CrimsonRed, 10.sp) }
                        }
                        if (cam.uid in updates) TextButton(onClick = { onEditCamera(cam) }) { Mono("INSTALAR", CrimsonRed, 11.sp) }
                        IconButton(onClick = { onEditCamera(cam) }) { Icon(Icons.Default.Tune, "Editar", tint = MutedText) }
                    }
                    HorizontalDivider(color = BorderSubtle)
                }
                TextButton(onClick = onAddCamera, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    Icon(Icons.Default.AddCircleOutline, null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Mono("AÑADIR NUEVA CÁMARA", PureWhite, 12.sp)
                }
            }
            store.session?.let { ss -> ReceivedInvitesSection(YiCloud(region), ss, onCamerasChanged) }
            SectionTitle("Alertas y notificaciones", Icons.Default.NotificationsActive)
            Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Toggle("Notificaciones en el teléfono", "Revisa la nube de Yi cada 15 min y avisa de alertas nuevas con su imagen", notify) { on ->
                    if (on && !com.metalsyntax.yihomelocal.data.AlertNotifier.canNotify(ctx) && android.os.Build.VERSION.SDK_INT >= 33)
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else setNotify(on)
                }
                Toggle("Modo silencioso", "Apaga las alertas push de todas las cámaras (también en Kami/Yi Home) y los avisos de esta app", silent, onSilent)
                var watch by remember { mutableStateOf(store.aiBackground) }
                Toggle("IA en segundo plano", "Analiza todas las cámaras con la IA del teléfono aunque la app esté cerrada (notificación fija; usa más batería y datos)", watch) { on ->
                    watch = on; store.aiBackground = on
                    if (on) com.metalsyntax.yihomelocal.ai.WatchService.start(ctx) else com.metalsyntax.yihomelocal.ai.WatchService.stop(ctx)
                }
                if (notify && !silent) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(testMsg ?: "Revisar ahora sin esperar los 15 min", color = MutedText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        testMsg = "Revisando…"
                        scope.launch {
                            testMsg = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metalsyntax.yihomelocal.data.AlertNotifier.check(ctx) } }
                                .fold({ if (it == 0) "Sin alertas nuevas" else "$it alertas nuevas avisadas" }, { "Error: ${it.message}" })
                        }
                    }) { Mono("REVISAR", CrimsonRed, 11.sp) }
                }
            }
            SectionTitle("Seguridad", Icons.Default.Fingerprint)
            var lock by remember { mutableStateOf(store.appLock) }
            Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
                Toggle("Bloquear la app", "Pide huella, rostro o el PIN del teléfono al abrir YiSecure", lock) { on ->
                    if (!on || com.metalsyntax.yihomelocal.AppLock.available(ctx)) { lock = on; store.appLock = on }
                    else testMsg = "Configura huella o bloqueo de pantalla en el teléfono primero"
                }
            }
            SectionTitle("Monitor y respaldo", Icons.Default.Tv)
            // exportar los eventos de la IA (fotos + índice) a un ZIP donde el usuario elija (Drive, NAS, carpeta)
            var backupMsg by remember { mutableStateOf<String?>(null) }
            val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri != null) scope.launch {
                    backupMsg = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.metalsyntax.yihomelocal.data.Backup.export(ctx, uri) } }
                        .fold({ "Respaldo listo: $it fotos" }, { "No se pudo exportar: ${it.message}" })
                }
            }
            Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Toggle("Monitor fijo", "Para una tablet en la pared: abre el monitor a pantalla completa y no apaga la pantalla", kiosk, onKiosk)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Respaldar eventos de la IA", color = PureWhite, fontSize = 14.sp)
                        Text(backupMsg ?: "Fotos y datos en un ZIP (Drive, NAS o una carpeta)", color = MutedText, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        exporter.launch("YiSecure-eventos-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip")
                    }) { Mono("EXPORTAR", CrimsonRed, 11.sp) }
                }
            }
            SectionTitle("Apariencia", Icons.Default.Palette)
            Row(Modifier.fillMaxWidth().panel(4)) {
                listOf(0 to "SISTEMA", 1 to "OSCURO", 2 to "CLARO").forEach { (v, label) ->
                    val sel = theme == v
                    Box(
                        Modifier.weight(1f).background(if (sel) CrimsonRed else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onTheme(v) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Mono(label, if (sel) OnAccent else MutedText, 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Cerrar sesión", color = CrimsonRed, icon = Icons.Default.PowerSettingsNew, onClick = onLogout)
            Mono("YI SECURE v${BuildConfig.VERSION_NAME} · SDK Kami Home 4.9.9", GhostText, 10.sp, Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
}

/**
 * Pantalla de la cámara en la cuenta: nombre, PIN de apertura y eliminar. Pantalla completa (no diálogo);
 * borrar pide confirmación porque luego hay que volver a emparejarla.
 */
@Composable
fun EditCameraScreen(
    camera: CameraDevice, busy: Boolean, hasPin: Boolean, onBack: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit,
    onPin: (String?) -> Unit, cloud: YiCloud? = null, account: YiCloud.Session? = null, onShareChanged: () -> Unit = {},
) {
    // conexión P2P propia (sin video en pantalla) para leer y cambiar los ajustes que guarda la cámara
    var cam by remember(camera.uid) { mutableStateOf<AntsCamera?>(null) }
    DisposableEffect(camera.uid) {
        val c = runCatching {
            YiP2p.open(camera, YiP2p.QUALITY_SD, false, object : YiP2p.Listener() {
                override fun onState(text: String, connected: Boolean) {}
                override fun onFrame(f: com.tutk.IOTC.AVFrame) {}
                override fun onAudio(f: com.tutk.IOTC.AVFrame) {}
            })
        }.getOrNull()
        cam = c
        onDispose { cam = null; c?.let(YiP2p::close) }
    }
    var name by remember(camera.uid) { mutableStateOf(camera.name) }
    var pin by remember(camera.uid) { mutableStateOf("") }
    var pinMsg by remember(camera.uid) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(camera.uid) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PureWhite) }
            Column {
                Text("Ajustes de la cámara", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Mono("${camera.model.uppercase()} · #${camera.uid.takeLast(4)}", MutedText, 10.sp)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Nombre", Icons.Default.Edit)
            Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it.take(32) }, "Nombre")
                PrimaryButton("Guardar nombre", enabled = !busy && name.isNotBlank() && name.trim() != camera.name) { onRename(name.trim()) }
            }

            val ctx = androidx.compose.ui.platform.LocalContext.current
            val st = remember { com.metalsyntax.yihomelocal.data.Store(ctx) }
            var interior by remember(camera.uid) { mutableStateOf(st.isInterior(camera.uid)) }
            // privacidad y acceso: interior (perfil "En casa") + PIN, juntos
            val access: @Composable () -> Unit = {
                SectionTitle("Privacidad y acceso", Icons.Default.Lock) { Mono(if (hasPin) "PIN ACTIVO" else "SIN PIN", if (hasPin) CrimsonRed else MutedText, 10.sp) }
                Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Toggle("Cámara de interior", "Con el perfil \"En casa\" se pone en privacidad y sin alertas", interior) {
                        interior = it; st.setInterior(camera.uid, it)
                    }
                    Text(
                        if (hasPin) "PIN: se pide al abrir o editar esta cámara."
                        else "PIN opcional de 4 a 8 dígitos para abrir esta cámara.",
                        color = MutedText, fontSize = 13.sp,
                    )
                    Field(pin, { v -> pin = v.filter(Char::isDigit).take(8); pinMsg = null }, if (hasPin) "Nuevo PIN" else "PIN (4–8 dígitos)", password = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasPin) SecondaryButton("Quitar PIN", Modifier.weight(1f), color = CrimsonRed) { onPin(null); pinMsg = "PIN quitado" }
                        PrimaryButton(if (hasPin) "Cambiar PIN" else "Fijar PIN", enabled = pin.length >= 4, modifier = Modifier.weight(1f)) {
                            onPin(pin); pin = ""; pinMsg = "PIN guardado"
                        }
                    }
                    pinMsg?.let { Text(it, color = MutedText, fontSize = 12.sp) }
                }
            }
            val danger: @Composable () -> Unit = {
                CameraDetails(camera)
                SectionTitle("Eliminar", Icons.Default.DeleteOutline)
                Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Se quita de tu cuenta Yi/Kami (también de la app oficial). Para volver a usarla hay que resetearla y emparejarla.",
                        color = MutedText, fontSize = 13.sp,
                    )
                    if (!confirmDelete) SecondaryButton("Eliminar cámara", color = CrimsonRed) { confirmDelete = true }
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Cancelar", Modifier.weight(1f)) { confirmDelete = false }
                        PrimaryButton("Sí, eliminar", enabled = !busy, modifier = Modifier.weight(1f), onClick = onDelete)
                    }
                }
            }
            if (cloud != null && account != null) CameraShareSection(camera, cloud, account, onShareChanged)
            if (cloud != null && account != null) CameraSettings(camera, cam, cloud, account, middle = access, extra = danger)
            else { access(); Advanced("Detalles y eliminar") { danger() } }
            if (busy) CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Pide el PIN de una cámara; [onOk] solo si coincide. */
@Composable
fun PinDialog(camera: CameraDevice, check: (String) -> Boolean, onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun submit() { if (check(pin)) onOk() else { wrong = true; pin = "" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        title = { Text("PIN de ${camera.name}", color = PureWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(pin, { v -> pin = v.filter(Char::isDigit).take(8); wrong = false }, "PIN", password = true)
                if (wrong) Text("PIN incorrecto", color = CrimsonRed, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = pin.length >= 4) { Text("Abrir", color = PureWhite) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = MutedText) } },
    )
}
