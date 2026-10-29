package io.sonora.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The Cast route chooser drawn in Now Playing's bottom row (B12), tinted like the row's other glyphs. The app provides it
 * only in a build and on a device that can cast (the `full` build, with Google Play services); null hides the slot.
 */
typealias CastButton = @Composable (tint: Color, modifier: Modifier) -> Unit

/** Null by default: no Cast slot. */
val LocalCastButton = staticCompositionLocalOf<CastButton?> { null }
