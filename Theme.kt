package com.mastermusic.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFFFC83D)
val NeonBlue = Color(0xFF2D7BFF)
val NeonCyan = Color(0xFF22D3EE)
val NeonPurple = Color(0xFF9B3DFF)
val NeonPink = Color(0xFFFF2D9B)
val Navy = Color(0xFF050A1C)
val Ink = Color(0xFF0C1230)

val NeonBrush = Brush.horizontalGradient(listOf(NeonCyan, NeonBlue, NeonPurple, NeonPink))
val AppBackground = Brush.verticalGradient(listOf(Color(0xFF0B1235), Navy, Color(0xFF14041F)))

private val Scheme = darkColorScheme(
    primary = NeonPink,
    onPrimary = Color.White,
    secondary = NeonCyan,
    background = Navy,
    surface = Ink,
    onSurface = Color(0xFFF1F4FF),
    onBackground = Color(0xFFF1F4FF),
)

@Composable
fun MasterMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
