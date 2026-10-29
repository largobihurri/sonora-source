package io.sonora.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** How long the accent takes to blend into a new one (a new track's artwork, a new preset). */
private const val ACCENT_CHANGE_MILLIS = 600

/**
 * The Sonora colours for a [dark] or light theme with [accent] as primary. Dark is true black (#000000) with
 * slightly lighter greys only for grouped cards, as on iOS.
 */
fun sonoraColorScheme(dark: Boolean, accent: Color): ColorScheme {
    val onAccent = Color(ColorMath.contentOn(accent.toArgb()))
    return if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            background = SonoraColors.DarkBackground,
            onBackground = SonoraColors.DarkLabel,
            surface = SonoraColors.DarkBackground,
            onSurface = SonoraColors.DarkLabel,
            surfaceVariant = SonoraColors.DarkGroupedBackground,
            onSurfaceVariant = SonoraColors.DarkSecondaryLabel,
            surfaceContainer = SonoraColors.DarkGroupedBackground,
            surfaceContainerHigh = SonoraColors.DarkSecondaryGrouped,
            outline = SonoraColors.DarkSeparator,
            outlineVariant = SonoraColors.DarkSeparator,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            background = SonoraColors.LightBackground,
            onBackground = SonoraColors.LightLabel,
            surface = SonoraColors.LightBackground,
            onSurface = SonoraColors.LightLabel,
            surfaceVariant = SonoraColors.LightGroupedBackground,
            onSurfaceVariant = SonoraColors.LightSecondaryLabel,
            surfaceContainer = SonoraColors.LightGroupedBackground,
            surfaceContainerHigh = SonoraColors.LightSecondaryGrouped,
            outline = SonoraColors.LightSeparator,
            outlineVariant = SonoraColors.LightSeparator,
        )
    }
}

private val LocalDarkTheme = staticCompositionLocalOf { false }
private val LocalAccentOnDark = staticCompositionLocalOf { Color(AccentResolver.Default.dark) }

/** Theme values beyond the Material colour scheme. */
object SonoraTheme {
    /** The app is in its dark theme (the user's choice, not necessarily the system's). */
    val isDark: Boolean
        @Composable get() = LocalDarkTheme.current

    /** The accent for content that always sits on a dark backdrop (Now Playing), whatever the app theme. */
    val accentOnDark: Color
        @Composable get() = LocalAccentOnDark.current
}

/**
 * Sonora theme: [darkTheme] picks the neutrals, [accent] the accent of each theme. Accent changes blend over
 * [ACCENT_CHANGE_MILLIS], so following the artwork recolours smoothly when the track changes.
 */
@Composable
fun SonoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentPair = AccentResolver.Default,
    content: @Composable () -> Unit,
) {
    val current by animateColorAsState(Color(accent.forTheme(darkTheme)), tween(ACCENT_CHANGE_MILLIS), label = "accent")
    val onDark by animateColorAsState(Color(accent.dark), tween(ACCENT_CHANGE_MILLIS), label = "accentOnDark")
    val scheme = remember(darkTheme, current) { sonoraColorScheme(darkTheme, current) }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme, LocalAccentOnDark provides onDark) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SonoraTypography,
            shapes = SonoraShapes,
            content = content,
        )
    }
}
