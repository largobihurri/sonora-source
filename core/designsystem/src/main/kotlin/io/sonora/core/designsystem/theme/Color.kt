package io.sonora.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Apple Music inspired neutrals (iOS system colors). The accent is not here: it comes from the user's appearance
 * settings (see [AccentResolver]) through `MaterialTheme.colorScheme.primary`.
 */
object SonoraColors {
    val LightBackground = Color(0xFFFFFFFF)
    val LightGroupedBackground = Color(0xFFF2F2F7)
    val LightSecondaryGrouped = Color(0xFFFFFFFF)
    val LightLabel = Color(0xFF000000)
    val LightSecondaryLabel = Color(0x993C3C43)
    val LightSeparator = Color(0x4A3C3C43)

    /** True black, for OLED. */
    val DarkBackground = Color(0xFF000000)
    val DarkGroupedBackground = Color(0xFF1C1C1E)
    val DarkSecondaryGrouped = Color(0xFF2C2C2E)
    val DarkLabel = Color(0xFFFFFFFF)
    val DarkSecondaryLabel = Color(0x99EBEBF5)
    val DarkSeparator = Color(0xA6545458)
}
