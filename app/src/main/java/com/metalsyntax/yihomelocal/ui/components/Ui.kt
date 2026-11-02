package com.metalsyntax.yihomelocal.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metalsyntax.yihomelocal.ui.theme.*

// Piezas comunes del diseño Obsidian Sentinel

/** Etiqueta técnica en monoespaciada (timestamps, UID, modelo). */
@Composable
fun Mono(text: String, color: Color = MutedText, size: TextUnit = 11.sp, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size, fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp, modifier = modifier, maxLines = 1)

/** Panel nivel 1: #1E1E1E con borde de 1px. */
fun Modifier.panel(radius: Int = 8) = this
    .clip(RoundedCornerShape(radius.dp))
    .background(CardBackground)
    .border(1.dp, BorderSubtle, RoundedCornerShape(radius.dp))

/** Pastilla ● LIVE / ● OFFLINE. */
@Composable
fun StatusBadge(online: Boolean, text: String = if (online) "LIVE" else "OFFLINE") {
    val c = if (online) CrimsonRed else GhostText
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (online) CrimsonRed.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.8f))
            .border(1.dp, c, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Mono(text, if (online) OnAccent else MutedText, 10.sp)
    }
}

/** Título de sección en mayúsculas con acento rojo a la izquierda. */
@Composable
fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
        else Box(Modifier.size(3.dp, 14.dp).background(CrimsonRed))
        Spacer(Modifier.width(8.dp))
        Mono(text.uppercase(), PureWhite, 13.sp, Modifier.weight(1f))
        trailing()
    }
}

/** Botón primario rojo con radio de 4dp. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed, disabledContainerColor = SurfaceHigh),
    ) { Text(text, color = OnAccent, fontWeight = FontWeight.SemiBold) }

/** Botón secundario: #2A2A2A con borde #383838. */
@Composable
fun SecondaryButton(
    text: String, modifier: Modifier = Modifier.fillMaxWidth(), color: Color = PureWhite,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit,
) =
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong),
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Mono(text.uppercase(), color, 12.sp)
    }

/** Campo de texto plano según el diseño (borde blanco al enfocar). Con [password] lleva el ojo para ver lo escrito. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false, enabled: Boolean = true,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = modifier,
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        trailingIcon = if (password) {
            { PasswordEye(shown) { shown = !shown } }
        } else null,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PureWhite, unfocusedBorderColor = BorderSubtle, focusedLabelColor = PureWhite,
            unfocusedLabelColor = MutedText, focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
            cursorColor = PureWhite, focusedTextColor = PureWhite, unfocusedTextColor = PureWhite,
        ),
    )
}

@Composable
fun PasswordEye(shown: Boolean, onToggle: () -> Unit) = IconButton(onClick = onToggle) {
    Icon(
        if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        if (shown) "Ocultar contraseña" else "Ver contraseña", tint = MutedText,
    )
}

/** Pantalla completa = horizontal sin barras del sistema; se deshace al salir o al pasar a false. */
@Composable
fun ImmersiveLandscape(full: Boolean) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(full) {
        val w = activity?.window
        if (w != null) {
            val ctl = WindowCompat.getInsetsController(w, w.decorView)
            if (full) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (full) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }
}

/** Ícono por tipo de evento (alertas de la nube y IA del teléfono), como los filtros del diseño "Eventos". */
fun kindIcon(kind: String): androidx.compose.ui.graphics.vector.ImageVector = with(Icons.Default) {
    val k = kind.lowercase()
    when {
        "persona" in k || "rostro" in k -> Person
        "veh" in k -> DirectionsCar
        "paquete" in k -> Inventory2
        "mascota" in k || "animal" in k -> Pets
        "llanto" in k -> ChildCare
        "sonido" in k -> GraphicEq
        "caída" in k -> PersonalInjury
        "sombra" in k -> Contrast
        "movimiento" in k -> Sensors
        "ia" in k -> Psychology
        else -> NotificationsActive
    }
}

/**
 * Sección "Avanzado" plegable: lo que se usa poco queda fuera de la vista hasta que se pide (divulgación progresiva).
 * Cerrada por defecto; recuerda su estado al girar la pantalla.
 */
@Composable
fun Advanced(hint: String, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(CardBackground)
                .clickable(onClickLabel = if (open) "Ocultar opciones avanzadas" else "Mostrar opciones avanzadas") { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Settings, null, tint = MutedText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Avanzado", color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, color = MutedText, fontSize = 12.sp, maxLines = 2)
            }
            Icon(Icons.Default.ExpandMore, null, tint = MutedText, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        androidx.compose.animation.AnimatedVisibility(
            open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
