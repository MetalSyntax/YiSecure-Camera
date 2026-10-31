package com.metalsyntax.yihomelocal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun YiSecureTheme(content: @Composable () -> Unit) {
    val colors = if (Palette.light) lightColorScheme(
        primary = CrimsonRed, onPrimary = OnAccent, primaryContainer = DarkRed, onPrimaryContainer = OnAccent,
        secondary = CrimsonRed, onSecondary = OnAccent, background = PitchBlack, onBackground = PureWhite,
        surface = DarkSurface, onSurface = PureWhite, surfaceVariant = CardBackground, onSurfaceVariant = MutedText, outline = BorderSubtle,
    ) else darkColorScheme(
        primary = CrimsonRed, onPrimary = OnAccent, primaryContainer = DarkRed, onPrimaryContainer = OnAccent,
        secondary = CrimsonRed, onSecondary = OnAccent, background = PitchBlack, onBackground = PureWhite,
        surface = DarkSurface, onSurface = PureWhite, surfaceVariant = CardBackground, onSurfaceVariant = MutedText, outline = BorderSubtle,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
