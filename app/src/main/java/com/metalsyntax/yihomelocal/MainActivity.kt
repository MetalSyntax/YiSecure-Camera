package com.metalsyntax.yihomelocal

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private var stoppedAt = 0L
    private val relock = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }

    override fun onStart() {
        super.onStart()
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > AppLock.RELOCK_MS) relock.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        if (store.session != null && store.notify) com.metalsyntax.yihomelocal.data.AlertNotifier.schedule(this, true)

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
                // bloqueo con huella: se pide al abrir y al volver tras >30 s en segundo plano
                var unlockedApp by remember { mutableStateOf(!store.appLock) }
                val relockTick = relock.intValue
                LaunchedEffect(relockTick) { if (relockTick > 0 && store.appLock) unlockedApp = false }
                if (!unlockedApp) {
                    var lockMsg by remember { mutableStateOf<String?>(null) }
                    fun ask() = AppLock.prompt(this@MainActivity, onOk = { unlockedApp = true }, onError = { lockMsg = it })
                    LaunchedEffect(Unit) { ask() }
                    Surface(Modifier.fillMaxSize(), color = PitchBlack) {
                        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Fingerprint, null, tint = CrimsonRed, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("YiSecure está bloqueada", color = PureWhite, fontSize = 20.sp)
                            lockMsg?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MutedText, fontSize = 13.sp) }
                            Spacer(Modifier.height(24.dp))
                            com.metalsyntax.yihomelocal.ui.components.PrimaryButton("Desbloquear") { lockMsg = null; ask() }
                        }
                    }
                    return@YiSecureTheme
                }
                var session by remember { mutableStateOf(store.session) }
                var cameras by remember { mutableStateOf(store.cameras) }
                var selected by remember { mutableStateOf<CameraDevice?>(null) }
                var editing by remember { mutableStateOf<CameraDevice?>(null) }
                var adding by remember { mutableStateOf(false) }
                var tab by remember { mutableIntStateOf(intent.getIntExtra(com.metalsyntax.yihomelocal.data.AlertNotifier.EXTRA_TAB, if (store.kiosk) 1 else 0)) }
                // toque en una notificación con la app abierta → pestaña Eventos
                DisposableEffect(Unit) {
                    val l = androidx.core.util.Consumer<android.content.Intent> { i ->
                        if (i.hasExtra(com.metalsyntax.yihomelocal.data.AlertNotifier.EXTRA_TAB)) { selected = null; tab = i.getIntExtra(com.metalsyntax.yihomelocal.data.AlertNotifier.EXTRA_TAB, 0) }
                    }
                    addOnNewIntentListener(l); onDispose { removeOnNewIntentListener(l) }
                }
                var monitorFull by remember { mutableStateOf(store.kiosk) }
                var kiosk by remember { mutableStateOf(store.kiosk) }
                // monitor fijo: pantalla siempre encendida mientras la app está delante
                DisposableEffect(kiosk) {
                    if (kiosk) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { }
                }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var silent by remember { mutableStateOf(store.silent) }
                var silentBusy by remember { mutableStateOf(false) }
                fun setSilent(on: Boolean) {
                    silentBusy = true
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { com.metalsyntax.yihomelocal.data.AlertNotifier.setSilent(this@MainActivity, on) }
                        silent = store.silent; silentBusy = false
                        error = if (failed.isNotEmpty()) "No se pudo cambiar el push en: ${failed.joinToString()}" else null
                    }
                }
                var online by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
                var snapshotVersion by remember { mutableIntStateOf(0) }
                var profile by remember { mutableStateOf(store.profile) }
                var profileBusy by remember { mutableStateOf(false) }
                var autoRecord by remember { mutableStateOf(false) }
                var sdStart by remember { mutableStateOf<Long?>(null) }
                fun applyProfile(p: com.metalsyntax.yihomelocal.data.Profile, after: () -> Unit = {}) {
                    profileBusy = true
                    scope.launch {
                        val failed = com.metalsyntax.yihomelocal.data.Profiles.apply(this@MainActivity, p)
                        profile = store.profile; silent = store.silent; profileBusy = false; snapshotVersion++
                        error = if (failed.isNotEmpty()) "${p.label}: no respondieron ${failed.joinToString()}" else null
                        after()
                    }
                }
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
                            cloudCall(login = { YiCloud(region).login(account, password) },
                                after = { if (store.notify) com.metalsyntax.yihomelocal.data.AlertNotifier.schedule(this@MainActivity, true) })
                        }
                        adding -> AddCameraScreen(YiCloud(store.region), s, store, onBack = { adding = false }) { uid ->
                            // recién vinculada: refrescar y abrir el diálogo para ponerle nombre
                            cloudCall(after = { adding = false; editing = cameras.firstOrNull { it.uid == uid } })
                        }
                        edit != null -> edit.let { e ->
                        EditCameraScreen(
                            e, busy, store.hasPin(e.uid).also { pinVersion }, onBack = { editing = null },
                            onPin = { pin -> store.setPin(e.uid, pin); unlocked = unlocked + e.uid; pinVersion++ },
                            cloud = YiCloud(store.region), account = s,
                            onRename = { name ->
                                cloudCall(action = { c, ss -> c.rename(ss, e.uid, name) }, after = {
                                    editing = null; selected = selected?.let { sel -> cameras.firstOrNull { it.uid == sel.uid } }
                                })
                            },
                            onDelete = {
                                cloudCall(action = { c, ss -> c.delete(ss, e.uid) }, after = {
                                    Snapshots.delete(this@MainActivity, e.uid)
                                    com.metalsyntax.yihomelocal.data.LocalEvents.delete(this@MainActivity, e.uid)
                                    editing = null; if (selected?.uid == e.uid) selected = null
                                })
                            },
                        )
                    }
                        // key: al cambiar de cámara desde el desplegable se reinicia todo el estado de la pantalla
                        cam != null -> key(cam.uid) {
                            CameraDetailScreen(cam, store, YiCloud(store.region), s, onBack = { autoRecord = false; sdStart = null; closeLive() }, onEdit = { editing = cam },
                                cameras = cameras, onSwitch = { autoRecord = false; sdStart = null; openLive(it) }, autoRecord = autoRecord, sdStart = sdStart)
                        }
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
                                    mainCamera = cameras.firstOrNull { it.uid == lastUid }, onMonitor = { tab = 1 },
                                    silent = silent, silentBusy = silentBusy, onSilent = ::setSilent,
                                    profile = profile, profileBusy = profileBusy, onProfile = { applyProfile(it) },
                                    onPanic = {
                                        // primero abrir y grabar (lo urgente), los perfiles se aplican en paralelo
                                        (cameras.firstOrNull { it.uid == lastUid } ?: cameras.firstOrNull())?.let { c -> autoRecord = true; openLive(c) }
                                        applyProfile(com.metalsyntax.yihomelocal.data.Profile.AWAY)
                                    },
                                    localTimes = remember(snapshotVersion, cameras) {
                                        cameras.associate { c -> c.uid to (com.metalsyntax.yihomelocal.data.LocalEvents.list(this@MainActivity, c.uid).maxOfOrNull { it.time } ?: 0L) }
                                    },
                                ) else if (tab == 1) MonitorScreen(cameras, monitorFull, { monitorFull = it }, ::locked) { openLive(it) }
                                else if (tab == 2) EventsScreen(YiCloud(store.region), s, cameras, onOpenSd = { uid, t ->
                                    cameras.firstOrNull { it.uid == uid }?.let { c -> sdStart = t; openLive(c) }
                                })
                                else SettingsScreen(
                                    cameras, online, store.region, theme, onTheme = { theme = it; store.theme = it },
                                    onAddCamera = { adding = true }, onEditCamera = { c -> guarded(c) { editing = c } },
                                    silent = silent, onSilent = ::setSilent,
                                    kiosk = kiosk, onKiosk = { on -> kiosk = on; store.kiosk = on; if (on) { tab = 1; monitorFull = true } },
                                    onLogout = {
                                        com.metalsyntax.yihomelocal.data.AlertNotifier.schedule(this@MainActivity, false)
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
