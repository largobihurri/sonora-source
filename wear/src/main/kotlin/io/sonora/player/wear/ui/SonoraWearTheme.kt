package io.sonora.player.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val Accent = Color(0xFFFA2D48)
private val AccentContainer = Color(0xFF5C1020)
private val OnAccentContainer = Color(0xFFFFD9DE)

/** Wear Material 3 on black (the watch's OLED screen), with Sonora's accent. */
@Composable
fun SonoraWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = AccentContainer,
            onPrimaryContainer = OnAccentContainer,
            background = Color.Black,
        ),
        content = content,
    )
}
