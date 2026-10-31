package com.metalsyntax.yihomelocal.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Modo claro global; al ser estado de Compose, cambiarlo recompone todo lo que lee los colores de abajo. */
object Palette { var light by mutableStateOf(false) }

private fun pick(dark: Long, light: Long) = Color(if (Palette.light) light else dark)

// Obsidian Sentinel (~/Downloads/DESIGN.md): superficies planas, bordes finos, rojo solo para estado en vivo/alertas.
// Los nombres vienen del tema oscuro: PureWhite = color de texto principal (negro en modo claro).
val PitchBlack get() = pick(0xFF121212, 0xFFF4F4F5)
val DarkSurface get() = PitchBlack
val CardBackground get() = pick(0xFF1E1E1E, 0xFFFFFFFF)
val SurfaceHigh get() = pick(0xFF2A2A2A, 0xFFECECEF)
val BorderSubtle get() = pick(0xFF2A2A2A, 0xFFE0E0E3)
val BorderStrong get() = pick(0xFF383838, 0xFFC4C4C8)

val PureWhite get() = pick(0xFFFFFFFF, 0xFF141414)
val MutedText get() = pick(0xFFA0A0A0, 0xFF5F6368)
val GhostText get() = pick(0xFF555555, 0xFF9AA0A6)

/** Texto/íconos sobre rojo o sobre el video: siempre blanco. */
val OnAccent = Color.White

val CrimsonRed = Color(0xFFE53935)
val DarkRed = Color(0xFFD32F2F)
val LiveIndicatorRed = Color(0xFFE53935)
