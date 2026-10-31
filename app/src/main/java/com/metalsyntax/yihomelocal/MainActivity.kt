package com.metalsyntax.yihomelocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import com.metalsyntax.yihomelocal.data.Alert
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.Snapshots
import com.metalsyntax.yihomelocal.data.Store
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.screens.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)

        setContent {
            var theme by remember { mutableIntStateOf(store.theme) }
            Palette.light = when (theme) { 2 -> true; 1 -> false; else -> !isSystemInDarkTheme() }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).run {
                    isAppearanceLightStatusBars = Palette.light; isAppearanceLightNavigationBars = Palette.light
                }
            }
            YiSecureTheme {
                val scope = rememberCoroutineScope()
                var session by remember { mutableStateOf(store.session) }
                var cameras by remember { mutableStateOf(store.cameras) }
                var selected by remember { mutableStateOf<CameraDevice?>(null) }
                var editing by remember { mutableStateOf<CameraDevice?>(null) }
                var adding by remember { mutableStateOf(false) }
                var tab by remember { mutableIntStateOf(0) }
                var monitorFull by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var online by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
                var snapshotVersion by remember { mutableIntStateOf(0) }
                var recent by remember { mutableStateOf<List<Alert>?>(null) }
                var lastUid by remember { mutableStateOf(store.lastCamera) }
                // cámaras con PIN ya abiertas en esta ejecución de la app
                var unlocked by remember { mutableStateOf(emptySet<String>()) }
                var pinAsk by remember { mutableStateOf<Pair<CameraDevice, () -> Unit>?>(null) }
                var pinVersion by remember { mutableIntStateOf(0) }
                fun locked(c: CameraDevice) = pinVersion >= 0 && store.hasPin(c.uid) && c.uid !in unlocked
                fun guarded(c: CameraDevice, action: () -> Unit) = if (locked(c)) pinAsk = c to action else action()

                // estado real: pregunta a la red P2P por cada cámara al abrir la lista y cada minuto
                val listVisible = session != null && selected == null && !adding
                LaunchedEffect(listVisible, cameras) {
                    if (!listVisible) return@LaunchedEffect
                    online = online.filterKeys { k -> cameras.any { it.uid == k } }
                    while (true) {
                        val results = cameras.map { c -> async(Dispatchers.IO) { c.uid to YiP2p.isOnline(c) } }.awaitAll()
                        online = results.toMap()
                        delay(60_000)
                    }
                }

                // corre una llamada a la nube en IO con busy/error y luego deja la lista sincronizada en Store
                fun cloudCall(login: (() -> YiCloud.Session)? = null, action: (YiCloud, YiCloud.Session) -> Unit = { _, _ -> }, after: () -> Unit = {}) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val cloud = YiCloud(store.region)
                            val (s, list) = withContext(Dispatchers.IO) {
                                val s = login?.invoke() ?: session!!
                                action(cloud, s)
                                s to cloud.devices(s)
                            }
                            store.session = s; store.cameras = list
                            session = s; cameras = list
                            after()
                        } catch (e: YiCloud.ApiException) {
                            error = "La API de Yi respondió código ${e.code}"
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            busy = false
                        }
                    }
                }

                // registro de incidentes de la pantalla principal (las 3 últimas alertas de la nube)
                LaunchedEffect(listVisible, tab) {
                    val ss = session
                    if (!listVisible || tab != 0 || ss == null) return@LaunchedEffect
                    recent = runCatching { withContext(Dispatchers.IO) { loadAlerts(YiCloud(store.region), ss) } }.getOrNull()
                }

                fun openLive(c: CameraDevice) = guarded(c) { selected = c; lastUid = c.uid; store.lastCamera = c.uid }
                fun closeLive() { selected = null; snapshotVersion++ }

                BackHandler(enabled = selected != null || adding) { if (adding) adding = false else closeLive() }

                Surface(Modifier.fillMaxSize(), color = PitchBlack) {
                    val s = session
                    val cam = selected
                    val edit = editing
                    when {
                        s == null -> LoginScreen(store.region, busy, error, store.savedAccount) { account, password, region, rememberMe ->
                            store.region = region
                            store.savedAccount = if (rememberMe) account to password else null
                            cloudCall(login = { YiCloud(region).login(account, password) })
                        }
                        adding -> AddCameraScreen(YiCloud(store.region), s, store, onBack = { adding = false }) { uid ->
                            // recién vinculada: refrescar y abrir el diálogo para ponerle nombre
                            cloudCall(after = { adding = false; editing = cameras.firstOrNull { it.uid == uid } })
                        }
                        edit != null -> edit.let { e ->
                        EditCameraScreen(
                            e, busy, store.hasPin(e.uid).also { pinVersion }, onBack = { editing = null },
                            onPin = { pin -> store.setPin(e.uid, pin); unlocked = unlocked + e.uid; pinVersion++ },
                            onRename = { name ->
                                cloudCall(action = { c, ss -> c.rename(ss, e.uid, name) }, after = {
                                    editing = null; selected = selected?.let { sel -> cameras.firstOrNull { it.uid == sel.uid } }
                                })
                            },
                            onDelete = {
                                cloudCall(action = { c, ss -> c.delete(ss, e.uid) }, after = {
                                    Snapshots.delete(this@MainActivity, e.uid)
                                    editing = null; if (selected?.uid == e.uid) selected = null
                                })
                            },
                        )
                    }
                        cam != null -> CameraDetailScreen(cam, store, YiCloud(store.region), s, onBack = { closeLive() }, onEdit = { editing = cam })
                        else -> Scaffold(
                            containerColor = PitchBlack,
                            bottomBar = {
                                // el monitor a pantalla completa ocupa todo: sin barra de pestañas
                                if (!(tab == 1 && monitorFull)) NavigationBar(containerColor = CardBackground) {
                                    // "En vivo" = monitor con todas las cámaras a la vez
                                    listOf("CÁMARAS" to Icons.Default.GridView, "EN VIVO" to Icons.Default.Videocam,
                                        "EVENTOS" to Icons.Default.Notifications, "AJUSTES" to Icons.Default.Tune).forEachIndexed { i, (label, icon) ->
                                        NavigationBarItem(
                                            selected = tab == i,
                                            onClick = { tab = i },
                                            icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = CrimsonRed, selectedTextColor = CrimsonRed, indicatorColor = SurfaceHigh,
                                                unselectedIconColor = MutedText, unselectedTextColor = MutedText,
                                            ),
                                        )
                                    }
                                }
                            },
                        ) { pad ->
                            Box(Modifier.padding(pad)) {
                                if (tab == 0) HomeScreen(
                                    cameras, online, snapshotVersion, busy, error,
                                    onSelectCamera = { openLive(it) }, onEditCamera = { c -> guarded(c) { editing = c } },
                                    onAddCamera = { adding = true }, onRefresh = { online = emptyMap(); cloudCall() },
                                    recent = recent, onAllEvents = { tab = 2 },
                                ) else if (tab == 1) MonitorScreen(cameras, monitorFull, { monitorFull = it }, ::locked) { openLive(it) }
                                else if (tab == 2) EventsScreen(YiCloud(store.region), s, cameras)
                                else SettingsScreen(
                                    cameras, online, store.region, theme, onTheme = { theme = it; store.theme = it },
                                    onAddCamera = { adding = true }, onEditCamera = { c -> guarded(c) { editing = c } },
                                    onLogout = {
                                        store.session = null; store.cameras = emptyList()
                                        session = null; cameras = emptyList(); tab = 0
                                    },
                                )
                            }
                        }
                    }

                    pinAsk?.let { (c, action) ->
                        PinDialog(c, check = { store.checkPin(c.uid, it) }, onDismiss = { pinAsk = null }) {
                            unlocked = unlocked + c.uid; pinAsk = null; action()
                        }
                    }
                }
            }
        }
    }
}
