package com.metalsyntax.yihomelocal.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.YiCloud
import com.metalsyntax.yihomelocal.ui.components.*
import com.metalsyntax.yihomelocal.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun day(sec: Long) = if (sec <= 0) "—" else SimpleDateFormat("dd/MM/yy HH:mm", Locale.US).format(Date(sec * 1000))

/** Corre [work] en IO y devuelve el mensaje de error legible (null si salió bien). */
private suspend fun cloudTry(work: () -> Unit): String? = runCatching { withContext(Dispatchers.IO) { work() } }.exceptionOrNull()?.let {
    if (it is YiCloud.ApiException) "La nube de Yi respondió código ${it.code}" else it.message ?: it.javaClass.simpleName
}

/**
 * Compartir la cámara con otra cuenta Yi/Kami, como "Compartir dispositivo" de Kami Home: la otra persona entra a
 * YiSecure (o Kami) con SU cuenta, acepta la invitación y ve la cámara. Aquí el dueño invita por correo, ve quién
 * tiene acceso, anula invitaciones y quita accesos. En una cámara compartida contigo solo se puede dejar de verla.
 */
@Composable
fun CameraShareSection(camera: CameraDevice, cloud: YiCloud, account: YiCloud.Session, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember(camera.uid) { mutableStateOf<List<YiCloud.ShareUser>?>(null) }
    var invites by remember(camera.uid) { mutableStateOf<List<YiCloud.ShareInvite>>(emptyList()) }
    var email by remember(camera.uid) { mutableStateOf("") }
    var msg by remember(camera.uid) { mutableStateOf<String?>(null) }
    var busy by remember(camera.uid) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    fun act(ok: String, work: () -> Unit) {
        busy = true; msg = null
        scope.launch { msg = cloudTry(work) ?: ok; busy = false; reload++ }
    }

    SectionTitle("Compartir", Icons.Default.Group)
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (camera.shared) {
            Text("Esta cámara es de otra cuenta y la compartieron contigo.", color = MutedText, fontSize = 13.sp)
            SecondaryButton("Dejar de ver esta cámara", color = CrimsonRed) {
                busy = true
                scope.launch { msg = cloudTry { cloud.leaveShared(account, camera.uid) }; busy = false; if (msg == null) onChanged() }
            }
        } else {
            LaunchedEffect(camera.uid, reload) {
                runCatching {
                    withContext(Dispatchers.IO) { cloud.sharedWith(account, camera.uid) to cloud.sentInvites(account, camera.uid) }
                }.onSuccess { (u, i) -> users = u; invites = i.filter { it.pending } }.onFailure { msg = "No se pudo leer: ${it.message}" }
            }
            Text("La otra persona entra con su propia cuenta Yi/Kami (no con la tuya) y acepta la invitación en Ajustes.",
                color = MutedText, fontSize = 13.sp)
            Field(email, { email = it.trim(); msg = null }, "Correo de su cuenta Yi/Kami")
            PrimaryButton("Invitar", enabled = !busy && email.contains('@')) {
                busy = true; msg = null
                scope.launch {
                    val found = runCatching { withContext(Dispatchers.IO) { cloud.searchUser(account, email) } }
                    val user = found.getOrNull()
                    msg = when {
                        found.isFailure -> "No se pudo buscar: ${found.exceptionOrNull()?.message}"
                        user == null -> "No hay ninguna cuenta Yi/Kami con ese correo"
                        user.first == account.userId -> "Ese correo es tu propia cuenta"
                        else -> cloudTry { cloud.invite(account, camera.uid, user.first) } ?: "Invitación enviada a ${user.second}".also { email = "" }
                    }
                    busy = false; reload++
                }
            }
            when {
                users == null -> CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                users!!.isEmpty() && invites.isEmpty() -> Mono("SIN COMPARTIR", MutedText, 10.sp)
            }
            users.orEmpty().forEach { u ->
                ShareRow(u.nickname.ifEmpty { u.userId }, "Con acceso desde ${day(u.sharedTime)} · último uso ${day(u.lastAccess)}", "Quitar") {
                    act("Acceso quitado a ${u.nickname}") { cloud.unshare(account, camera.uid, u.userId) }
                }
            }
            invites.forEach { i ->
                ShareRow(i.nickname.ifEmpty { "Invitación" }, "${i.stateLabel} · enviada ${day(i.created)}", "Anular") {
                    act("Invitación anulada") { cloud.cancelInvite(account, i.id) }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CrimsonRed, trackColor = SurfaceHigh)
        msg?.let { Text(it, color = MutedText, fontSize = 12.sp) }
    }
}

@Composable
private fun ShareRow(title: String, sub: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Person, null, tint = MutedText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PureWhite, fontSize = 14.sp)
            Text(sub, color = MutedText, fontSize = 11.sp)
        }
        TextButton(onClick = onAction) { Mono(action.uppercase(), CrimsonRed, 11.sp) }
    }
}

/** Invitaciones que recibió esta cuenta: aceptar (la cámara aparece en la lista) o rechazar. Oculto si no hay. */
@Composable
fun ReceivedInvitesSection(cloud: YiCloud, account: YiCloud.Session, onAccepted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<YiCloud.ShareInvite>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        list = runCatching { withContext(Dispatchers.IO) { cloud.receivedInvites(account) } }.getOrDefault(emptyList()).filter { it.pending }
    }
    if (list.isEmpty() && msg == null) return
    SectionTitle("Cámaras compartidas contigo", Icons.Default.Mail)
    Column(Modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { i ->
            Text("${i.nickname.ifEmpty { "Alguien" }} te invitó a ver una cámara · ${day(i.created)}", color = PureWhite, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Rechazar", Modifier.weight(1f)) {
                    scope.launch { msg = cloudTry { cloud.answerInvite(account, i.shareToken, false) } ?: "Invitación rechazada"; reload++ }
                }
                PrimaryButton("Aceptar", modifier = Modifier.weight(1f)) {
                    scope.launch {
                        msg = cloudTry { cloud.answerInvite(account, i.shareToken, true) }
                        if (msg == null) { msg = "Aceptada: la cámara ya está en tu lista"; onAccepted() }
                        reload++
                    }
                }
            }
        }
        msg?.let { Text(it, color = MutedText, fontSize = 12.sp) }
    }
}
