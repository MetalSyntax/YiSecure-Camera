package com.metalsyntax.yihomelocal.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Emparejar como Yi Home: el teléfono muestra un QR (bindkey + Wi-Fi) que la cámara lee con su lente;
 * la cámara se registra sola en la nube y aquí se sondea check_bindkey hasta tener su UID.
 */
@Composable
fun AddCameraScreen(cloud: YiCloud, session: YiCloud.Session, onBack: () -> Unit, onPaired: (uid: String) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        qr = null
        try {
            val key = withContext(Dispatchers.IO) { cloud.bindKey(session) }
            qr = withContext(Dispatchers.Default) { qrBitmap(YiCloud.pairingQr(key, ssid, wifiPassword)) }
            status = "Esperando a que la cámara lea el código…"
            val until = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < until) {
                delay(3000)
                val (ret, uid) = runCatching { withContext(Dispatchers.IO) { cloud.checkBind(session, key) } }.getOrDefault(0 to "")
                when {
                    ret == 1 -> { status = "¡Cámara vinculada!"; onPaired(uid); return@LaunchedEffect }
                    ret == -5 -> { status = "Esa cámara ya pertenece a otra cuenta. Bórrala allí primero."; qr = null; return@LaunchedEffect }
                    ret < 0 -> { status = "El código expiró (error $ret). Genera uno nuevo."; qr = null; return@LaunchedEffect }
                }
            }
            status = "Tiempo agotado. Revisa la clave del Wi-Fi (debe ser 2.4 GHz) y vuelve a intentar."
            qr = null
        } catch (e: YiCloud.ApiException) {
            status = "Yi respondió código ${e.code}"
        } catch (e: Exception) {
            status = e.message ?: e.javaClass.simpleName
        }
    }

    // brillo al máximo mientras se muestra el QR para que la cámara lo lea
    val activity = LocalContext.current as? Activity
    DisposableEffect(qr != null) {
        val w = activity?.window
        if (qr != null) w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        if (qr != null) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PureWhite) }
            Text("Añadir cámara", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val bmp = qr
            if (bmp == null) {
                SectionTitle("1 · Prepara la cámara")
                Text(
                    "Enchúfala y mantén presionado RESET hasta que diga \"Waiting to connect\" (luz amarilla parpadeando).",
                    color = MutedText, fontSize = 14.sp,
                )
                SectionTitle("2 · Wi-Fi de la cámara")
                Field(ssid, { ssid = it }, "Nombre de la red (2.4 GHz)")
                Field(wifiPassword, { wifiPassword = it }, "Contraseña del Wi-Fi", password = true)
                Mono("Mayúsculas y minúsculas cuentan. Las redes 5 GHz no funcionan.", GhostText, 10.sp)
                status?.let { Text(it, color = CrimsonRed, fontSize = 13.sp) }
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Generar código QR", enabled = ssid.isNotBlank()) { status = null; attempt++ }
            } else {
                SectionTitle("3 · Muéstrale el código a la cámara")
                Text(
                    "Pon la pantalla frente al lente a 15–20 cm, sin brillo de reflejos. Cuando escuches el pitido, espera aquí.",
                    color = MutedText, fontSize = 14.sp,
                )
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(12.dp)) {
                    Image(bmp.asImageBitmap(), "Código QR de emparejamiento", Modifier.fillMaxSize())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Mono(status.orEmpty(), PureWhite, 11.sp)
                }
                SecondaryButton("Cambiar Wi-Fi") { qr = null; status = null; attempt = 0 }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "utf-8", EncodeHintType.MARGIN to 1),
    )
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
}
