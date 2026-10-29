package io.sonora.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import io.sonora.core.designsystem.theme.animatedChromeColor

/** How strongly the album tint washes over the translucent chrome: the page colour shows through, as on iOS 26. */
private const val WASH_ALPHA = 0.6f

/**
 * The colour laid over the translucent tab bar and mini player: the tint of the album or artist page on screen,
 * blending in and out with it, or nothing on untinted screens.
 */
@Composable
internal fun chromeWash(): State<Color> =
    animatedChromeColor(Color.Transparent) { Color(it.background).copy(alpha = WASH_ALPHA) }
