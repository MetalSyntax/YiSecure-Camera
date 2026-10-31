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
            YiSecureTheme {
                val scope = rememberCoroutineScope()
                var session by remember { mutableStateOf(store.session) }
                var cameras by remember { mutableStateOf(store.cameras) }
                var selected by remember { mutableStateOf<CameraDevice?>(null) }
                var editing by remember { mutableStateOf<CameraDevice?>(null) }
                var adding by remember { mutableStateOf(false) }
                var tab by remember { mutableIntStateOf(0) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var online by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
                var snapshotVersion by remember { mutableIntStateOf(0) }
                var recent by remember { mutableStateOf<List<Alert>?>(null) }
                var lastUid by remember { mutableStateOf(store.lastCamera) }

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

                fun openLive(c: CameraDevice) { selected = c; lastUid = c.uid; store.lastCamera = c.uid }
                fun closeLive() { selected = null; snapshotVersion++ }

                BackHandler(enabled = selected != null || adding) { if (adding) adding = false else closeLive() }

                Surface(Modifier.fillMaxSize(), color = PitchBlack) {
                    val s = session
                    val cam = selected
                    when {
                        s == null -> LoginScreen(store.region, busy, error) { account, password, region ->
                            store.region = region
                            cloudCall(login = { YiCloud(region).login(account, password) })
                        }
                        adding -> AddCameraScreen(YiCloud(store.region), s, onBack = { adding = false }) { uid ->
                            // recién vinculada: refrescar y abrir el diálogo para ponerle nombre
                            cloudCall(after = { adding = false; editing = cameras.firstOrNull { it.uid == uid } })
                        }
                        cam != null -> CameraDetailScreen(cam, store, YiCloud(store.region), s, onBack = { closeLive() }, onEdit = { editing = cam })
                        else -> Scaffold(
                            containerColor = PitchBlack,
                            bottomBar = {
                                NavigationBar(containerColor = CardBackground) {
                                    // "En vivo" abre la última cámara vista (o la primera); no es una pantalla aparte
                                    listOf("CÁMARAS" to Icons.Default.GridView, "EN VIVO" to Icons.Default.Videocam,
                                        "EVENTOS" to Icons.Default.Notifications, "AJUSTES" to Icons.Default.Tune).forEachIndexed { i, (label, icon) ->
                                        NavigationBarItem(
                                            selected = tab == i,
                                            onClick = {
                                                if (i == 1) (cameras.firstOrNull { it.uid == lastUid } ?: cameras.firstOrNull())?.let(::openLive)
                                                else tab = i
                                            },
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
                                    onSelectCamera = { openLive(it) }, onEditCamera = { editing = it },
                                    onAddCamera = { adding = true }, onRefresh = { online = emptyMap(); cloudCall() },
                                    recent = recent, onAllEvents = { tab = 2 },
                                ) else if (tab == 2) EventsScreen(YiCloud(store.region), s, cameras)
                                else SettingsScreen(
                                    cameras, online, store.region,
                                    onAddCamera = { adding = true }, onEditCamera = { editing = it },
                                    onLogout = {
                                        store.session = null; store.cameras = emptyList()
                                        session = null; cameras = emptyList(); tab = 0
                                    },
                                )
                            }
                        }
                    }

                    editing?.let { e ->
                        EditCameraDialog(
                            e, busy, onDismiss = { editing = null },
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
                }
            }
        }
    }
}
