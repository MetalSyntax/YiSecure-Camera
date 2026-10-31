package com.metalsyntax.yihomelocal.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.metalsyntax.yihomelocal.ui.components.PasswordEye
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Solo se usa una vez: para bajar UID + contraseña P2P de cada cámara desde la cuenta Yi. */
@Composable
fun LoginScreen(
    region: String,
    busy: Boolean,
    error: String?,
    saved: Pair<String, String>?,
    onLogin: (account: String, password: String, region: String, remember: Boolean) -> Unit,
) {
    var account by remember { mutableStateOf(saved?.first.orEmpty()) }
    var password by remember { mutableStateOf(saved?.second.orEmpty()) }
    var rememberMe by remember { mutableStateOf(saved != null) }
    var shown by remember { mutableStateOf(false) }
    var reg by remember { mutableStateOf(region) }
    var settingPassword by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    if (settingPassword) {
        PasswordSetup(account, reg, onCancel = { settingPassword = false }) { acc, pwd ->
            account = acc; password = pwd; settingPassword = false
            info = "Contraseña creada. Ya puedes entrar."
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("YI SECURE", color = PureWhite, fontSize = 22.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Inicia sesión con tu cuenta Yi Home una sola vez.\nLuego las cámaras se guardan en el teléfono.",
            color = MutedText, fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = account, onValueChange = { account = it.trim() }, label = { Text("Correo") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { PasswordEye(shown) { shown = !shown } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().clickable { rememberMe = !rememberMe }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberMe, { rememberMe = it }, colors = CheckboxDefaults.colors(checkedColor = CrimsonRed))
            Text("Recordar correo y contraseña", color = MutedText, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("us" to "América", "eu" to "Europa", "sg" to "Asia").forEach { (k, label) ->
                FilterChip(
                    selected = reg == k, onClick = { reg = k }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CrimsonRed, selectedLabelColor = OnAccent),
                )
            }
        }
        if (info != null && error == null) {
            Spacer(Modifier.height(12.dp))
            Text(info!!, color = MutedText, fontSize = 13.sp)
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = CrimsonRed, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onLogin(account, password, reg, rememberMe) },
            enabled = !busy && account.isNotEmpty() && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(color = OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            else Text("Entrar", color = OnAccent)
        }
        TextButton(onClick = { settingPassword = true }) {
            Text("¿Entras con Google o no tienes contraseña? Crear contraseña", color = MutedText, fontSize = 12.sp)
        }
    }
}

/** Flujo "olvidé mi contraseña" de Yi: captcha → código al correo → contraseña nueva. */
@Composable
private fun PasswordSetup(initialAccount: String, region: String, onCancel: () -> Unit, onDone: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val cloud = remember(region) { YiCloud(region) }
    var account by remember { mutableStateOf(initialAccount) }
    var captcha by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var captchaText by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) = scope.launch {
        busy = true; msg = null
        try { block() } catch (e: YiCloud.ApiException) {
            msg = "Yi respondió código ${e.code}"
        } catch (e: Exception) {
            msg = e.message ?: e.javaClass.simpleName
        }
        busy = false
    }
    fun reloadCaptcha() = run { captcha = withContext(Dispatchers.IO) { cloud.captcha() }; captchaText = "" }
    LaunchedEffect(cloud) { reloadCaptcha() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CREAR CONTRASEÑA", color = PureWhite, fontSize = 20.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Para cuentas que entran con Google: Yi te envía un código al correo y fijas una contraseña. " +
                "Seguirás pudiendo entrar con Google en la app oficial.",
            color = MutedText, fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = account, onValueChange = { account = it.trim() }, label = { Text("Correo") }, singleLine = true,
            enabled = !codeSent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        if (!codeSent) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bmp = remember(captcha) { captcha?.second?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                if (bmp != null) Image(bmp, "Captcha (toca para cambiar)", Modifier.size(120.dp, 45.dp).clickable { reloadCaptcha() })
                OutlinedTextField(
                    value = captchaText, onValueChange = { captchaText = it.trim().take(4) }, label = { Text("Captcha") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { run { withContext(Dispatchers.IO) { cloud.requestReset(account, captcha!!.first, captchaText) }; codeSent = true } },
                enabled = !busy && account.isNotEmpty() && captchaText.isNotEmpty() && captcha != null,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enviar código al correo", color = OnAccent) }
        } else {
            OutlinedTextField(
                value = emailCode, onValueChange = { emailCode = it.trim() }, label = { Text("Código del correo") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            var shownNew by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it }, label = { Text("Contraseña nueva") }, singleLine = true,
                visualTransformation = if (shownNew) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { PasswordEye(shownNew) { shownNew = !shownNew } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { run { withContext(Dispatchers.IO) { cloud.resetPassword(account, newPassword, emailCode) }; onDone(account, newPassword) } },
                enabled = !busy && emailCode.isNotEmpty() && newPassword.length >= 8,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar contraseña", color = OnAccent) }
        }
        if (msg != null) {
            Spacer(Modifier.height(12.dp))
            Text(msg!!, color = CrimsonRed, fontSize = 13.sp)
        }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
        TextButton(onClick = onCancel) { Text("Volver", color = MutedText) }
    }
}
