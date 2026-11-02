package com.metalsyntax.yihomelocal.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.metalsyntax.yihomelocal.data.Catalog
import com.metalsyntax.yihomelocal.data.CatalogItem
import com.metalsyntax.yihomelocal.data.Store
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { MODEL, WIFI, QR, AP }

/**
 * Emparejar como Kami/Yi Home: elegir modelo (catálogo por categorías o escaneando la etiqueta) → Wi-Fi →
 * el teléfono muestra un QR (bindkey + Wi-Fi) que la cámara lee con su lente, o por modo AP (red CAM_xxxx).
 * La cámara se registra sola en la nube y aquí se sondea check_bindkey hasta tener su UID.
 */
@Composable
fun AddCameraScreen(cloud: YiCloud, session: YiCloud.Session, store: Store, onBack: () -> Unit, onPaired: (uid: String) -> Unit) {
    var step by remember { mutableStateOf(Step.MODEL) }
    var model by remember { mutableStateOf<CatalogItem?>(null) }
    var ssid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var bindKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = step != Step.MODEL) { step = if (step == Step.WIFI) Step.MODEL else Step.WIFI; status = null }

    // una bindkey por intento (la de QR y la de modo AP son la misma); al tenerla se sondea hasta 3 min
    LaunchedEffect(bindKey) {
        val key = bindKey ?: return@LaunchedEffect
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until) {
            delay(3000)
            val (ret, uid) = runCatching { withContext(Dispatchers.IO) { cloud.checkBind(session, key) } }.getOrDefault(0 to "")
            when {
                ret == 1 -> { status = "¡Cámara vinculada!"; onPaired(uid); return@LaunchedEffect }
                ret == -5 -> { status = "Esa cámara ya pertenece a otra cuenta. Bórrala allí primero."; bindKey = null; return@LaunchedEffect }
                ret < 0 -> { status = "El código expiró (error $ret). Genera uno nuevo."; bindKey = null; return@LaunchedEffect }
            }
        }
        status = "Tiempo agotado. Revisa la clave del Wi-Fi y que la región de la cuenta sea la de la cámara."
        bindKey = null
    }

    suspend fun newKey(): String? = try {
        store.setWifiPassword(ssid, wifiPassword)
        withContext(Dispatchers.IO) { cloud.bindKey(session) }.also { status = null }
    } catch (e: YiCloud.ApiException) {
        status = "Yi respondió código ${e.code}"; null
    } catch (e: Exception) {
        status = e.message ?: e.javaClass.simpleName; null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step == Step.MODEL) onBack() else { step = if (step == Step.WIFI) Step.MODEL else Step.WIFI; bindKey = null } }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PureWhite)
            }
            Column {
                Text("Añadir cámara", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                model?.let { Mono(it.name.uppercase(), MutedText, 10.sp) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                Step.MODEL -> ModelPicker(store.region) { model = it; step = Step.WIFI }
                Step.WIFI -> {
                    SectionTitle("1 · Prepara la cámara")
                    Text(
                        "Enchúfala y mantén presionado RESET hasta que diga \"Waiting to connect\" (luz amarilla parpadeando).",
                        color = MutedText, fontSize = 14.sp,
                    )
                    SectionTitle("2 · Wi-Fi de la cámara")
                    WifiPicker(ssid) { ssid = it; store.wifiPassword(it)?.let { p -> wifiPassword = p } }
                    Field(ssid, { ssid = it }, "Nombre de la red (2.4 GHz)")
                    Field(wifiPassword, { wifiPassword = it }, "Contraseña del Wi-Fi", password = true)
                    Mono("Se recuerda por red. Mayúsculas y minúsculas cuentan.", GhostText, 10.sp)
                    status?.let { Text(it, color = CrimsonRed, fontSize = 13.sp) }
                    Spacer(Modifier.height(4.dp))
                    PrimaryButton("Generar código QR", enabled = ssid.isNotBlank()) { step = Step.QR }
                    SecondaryButton("Modo AP (sin QR)") { step = Step.AP }
                }
                Step.QR -> {
                    LaunchedEffect(Unit) { if (bindKey == null) bindKey = newKey() }
                    val key = bindKey
                    if (key == null) {
                        status?.let { Text(it, color = CrimsonRed, fontSize = 13.sp) } ?: CircularProgressIndicator(color = CrimsonRed)
                        PrimaryButton("Generar otro código") { status = null; scope.launch { bindKey = newKey() } }
                        SecondaryButton("Probar modo AP") { step = Step.AP }
                    } else PairingQr(YiCloud.pairingQr(key, ssid, wifiPassword), status) { bindKey = null; step = Step.AP }
                }
                Step.AP -> ApMode(ssid, wifiPassword, bindKey, status, newKey = { bindKey = newKey(); bindKey })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Catálogo por categorías con foto; si el modelo no aparece, escanear la etiqueta o elegir "Otro modelo". */
@Composable
private fun ModelPicker(accountRegion: String, onPick: (CatalogItem) -> Unit) {
    val ctx = LocalContext.current
    val items = remember { Catalog.load(ctx) }
    val categories = remember { listOf("Todas") + items.map { it.category }.distinct() }
    var cat by remember { mutableStateOf("Todas") }
    var label by remember { mutableStateOf<String?>(null) }

    SectionTitle("Elige tu cámara")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.forEach { c ->
            FilterChip(
                selected = cat == c, onClick = { cat = c }, label = { Text(c) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CrimsonRed, selectedLabelColor = OnAccent, labelColor = MutedText),
            )
        }
    }
    items.filter { cat == "Todas" || it.category == cat }.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { m -> ModelCard(m, Modifier.weight(1f)) { onPick(m) } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }

    SectionTitle("¿No la encuentras?")
    SecondaryButton("Escanear etiqueta de la cámara") {
        GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { label = it.rawValue.orEmpty() }
            .addOnFailureListener { label = "" }
    }
    label?.let { l ->
        val reg = Catalog.regionOfLabel(l)
        Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("ETIQUETA", MutedText, 10.sp)
            Text(l.ifEmpty { "No se leyó nada" }, color = PureWhite, fontSize = 13.sp)
            when {
                l.isEmpty() -> {}
                reg == null -> Text("No reconozco la región de esta etiqueta; sigue con \"Otro modelo\".", color = MutedText, fontSize = 12.sp)
                reg != accountRegion -> Text(
                    "Esta cámara es de la región ${reg.uppercase()} y tu sesión está en ${accountRegion.uppercase()}: " +
                        "no se registrará. Cierra sesión y entra eligiendo la región correcta.", color = CrimsonRed, fontSize = 13.sp,
                )
                else -> Text("Región ${reg.uppercase()}: coincide con tu cuenta.", color = MutedText, fontSize = 12.sp)
            }
        }
        PrimaryButton("Seguir con \"Otro modelo\"") { onPick(items.first { it.model == "common" }) }
    }
}

@Composable
private fun ModelCard(m: CatalogItem, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val img = remember(m.image) { m.bitmap(ctx)?.asImageBitmap() }
    Column(modifier.panel().clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Icon(Icons.Default.Videocam, null, tint = GhostText, modifier = Modifier.size(36.dp))
        }
        Text(m.name, color = PureWhite, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
        Mono(m.model.substringAfterLast('.').uppercase(), MutedText, 9.sp)
    }
}

/**
 * QR que lee la cámara. Igual que Kami: corrección de errores L (módulos más grandes) y margen blanco de 4 módulos.
 * El tamaño se ajusta porque cada lente enfoca distinto; pantalla completa para los modelos que necesitan uno grande.
 */
@Composable
private fun PairingQr(text: String, status: String?, onApMode: () -> Unit) {
    var scale by remember { mutableFloatStateOf(0.85f) }
    var full by remember { mutableStateOf(false) }
    val bmp = remember(text) { qrBitmap(text) }

    // brillo al máximo mientras se muestra el QR para que la cámara lo lea
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val w = activity?.window
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (full) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { full = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.White).clickable { full = false }, contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), "Código QR de emparejamiento", Modifier.fillMaxWidth().aspectRatio(1f))
                Text("Toca para salir", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }

    SectionTitle("3 · Muéstrale el código a la cámara")
    Text(
        "Pon la pantalla frente al lente a 15–30 cm y acércala/aléjala despacio, sin reflejos. Si no pita en 20 s cambia el tamaño.",
        color = MutedText, fontSize = 14.sp,
    )
    Box(Modifier.fillMaxWidth().background(Color.White), contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), "Código QR de emparejamiento", Modifier.fillMaxWidth(scale).aspectRatio(1f).padding(vertical = 8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono("TAMAÑO", MutedText, 10.sp)
        Slider(scale, { scale = it }, valueRange = 0.4f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = CrimsonRed, activeTrackColor = CrimsonRed))
        TextButton(onClick = { full = true }) { Mono("COMPLETO", CrimsonRed, 11.sp) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(status ?: "Esperando a que la cámara lea el código…", color = PureWhite, fontSize = 12.sp)
    }
    SecondaryButton("No lo lee: probar modo AP", onClick = onApMode)
}

/**
 * Modo AP (ApDirectActivity de Kami): la cámara en espera abre una red "CAM_xxxx"; el teléfono se conecta a ella y
 * le manda por TCP a 192.168.10.1:6000 {"operator":"ap_bind","data":<mismo texto del QR>}. Luego la cámara se va
 * a tu Wi-Fi y se registra como con el QR. No todos los modelos lo tienen.
 */
@Composable
private fun ApMode(ssid: String, wifiPassword: String, bindKey: String?, status: String?, newKey: suspend () -> String?) {
    val ctx = LocalContext.current
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf(bindKey) }
    // la bindkey se pide ANTES de pasarse a la red de la cámara (que no tiene internet)
    LaunchedEffect(Unit) { if (key == null) key = newKey() }

    SectionTitle("Modo AP")
    Text(
        "1. Con la cámara esperando conexión, ve a Ajustes → Wi-Fi del teléfono y conéctate a la red CAM_xxxx de la cámara " +
            "(acepta \"sin internet\").\n2. Vuelve aquí y toca Enviar.\n3. Regresa a tu Wi-Fi o datos; la app espera el registro.",
        color = MutedText, fontSize = 14.sp,
    )
    val wifiName = currentSsid(ctx)
    Mono("CONECTADO A: ${wifiName ?: "—"}", if (wifiName?.startsWith("CAM_") == true) CrimsonRed else MutedText, 11.sp)
    PrimaryButton(if (sending) "Enviando…" else "Enviar Wi-Fi a la cámara", enabled = !sending && key != null) {
        sending = true
        Thread {
            val r = runCatching { sendApBind(ctx, "{\"operator\":\"ap_bind\",\"data\":" + YiCloud.pairingQr(key!!, ssid, wifiPassword) + "}") }
            result = r.fold({ "Enviado. Vuelve a tu Wi-Fi y espera aquí." }, { "No se pudo hablar con la cámara (${it.message}). ¿Estás en la red CAM_?" })
            sending = false
        }.start()
    }
    result?.let { Text(it, color = PureWhite, fontSize = 13.sp) }
    status?.let { Text(it, color = CrimsonRed, fontSize = 13.sp) }
    if (key == null && status == null) CircularProgressIndicator(color = CrimsonRed)
}

/** TCP por la red Wi-Fi (aunque no tenga internet), no por datos móviles. */
private fun sendApBind(ctx: Context, payload: String) {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: error("sin Wi-Fi")
    wifi.socketFactory.createSocket().use { s ->
        s.connect(java.net.InetSocketAddress("192.168.10.1", 6000), 8000)
        s.getOutputStream().apply { write(payload.toByteArray()); flush() }
        Thread.sleep(1500) // dar tiempo a que la cámara lea antes de cerrar
    }
}

@Suppress("DEPRECATION")
private fun currentSsid(ctx: Context): String? =
    (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid?.trim('"')
        ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

/**
 * Redes cercanas (WifiManager) para no escribir el SSID a mano; marca la red actual y deshabilita las de 5 GHz,
 * que estas cámaras no ven. Sin permiso de ubicación Android no entrega nombres: se pide al entrar.
 */
@Composable
private fun WifiPicker(selected: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val wifi = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    var nets by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var current by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableIntStateOf(0) }
    @Suppress("DEPRECATION") // startScan sigue siendo la única vía de refrescar sin NEARBY_WIFI_DEVICES
    LaunchedEffect(granted, scan) {
        if (!granted) return@LaunchedEffect
        current = currentSsid(ctx)
        if (selected.isEmpty()) current?.let(onPick)
        wifi.startScan()
        repeat(4) {
            nets = wifi.scanResults.orEmpty().filter { it.SSID.isNotEmpty() }
                .groupBy { it.SSID }.map { (_, l) -> l.minBy { it.frequency } }.sortedByDescending { it.level }
            delay(2500)
        }
    }

    if (!granted) {
        SecondaryButton("Permitir ubicación para ver redes") { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }
    Column(Modifier.fillMaxWidth().panel()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono("REDES CERCANAS", MutedText, 10.sp, Modifier.weight(1f))
            IconButton(onClick = { scan++ }) { Icon(Icons.Default.Refresh, "Buscar redes", tint = MutedText) }
        }
        if (nets.isEmpty()) Text("Buscando… (activa la ubicación del teléfono si no aparece nada)", color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
        nets.take(8).forEach { n ->
            // agrupado por SSID quedándonos con la banda más baja: si aún es >4.9 GHz la red es solo 5 GHz
            val is5g = n.frequency > 4900
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !is5g) { onPick(n.SSID) }
                    .background(if (n.SSID == selected) CrimsonRed.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Wifi, null, tint = if (is5g) GhostText else PureWhite, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(n.SSID, color = if (is5g) GhostText else PureWhite, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                @Suppress("DEPRECATION")
                Mono(when { is5g -> "5 GHz NO"; n.SSID == current -> "ACTUAL"; else -> "${WifiManager.calculateSignalLevel(n.level, 4) + 1}/4" }, MutedText, 10.sp)
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int = 900): Bitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "utf-8", EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
    )
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
}
