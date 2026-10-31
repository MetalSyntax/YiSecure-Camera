package com.metalsyntax.yihomelocal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CrimsonRed,
    onPrimary = PureWhite,
    primaryContainer = DarkRed,
    onPrimaryContainer = PureWhite,
    secondary = CrimsonRed,
    onSecondary = PureWhite,
    background = PitchBlack,
    onBackground = PureWhite,
    surface = DarkSurface,
    onSurface = PureWhite,
    surfaceVariant = CardBackground,
    onSurfaceVariant = MutedText,
    outline = BorderSubtle
)

@Composable
fun YiSecureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
