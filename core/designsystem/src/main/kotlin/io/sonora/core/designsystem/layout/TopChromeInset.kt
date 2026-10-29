package io.sonora.core.designsystem.layout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far below the status bar the shell's top chrome reaches over the content (the top pill of a large screen whose
 * sidebar is hidden), plus a small gap: a page's first content starts at least this far down. Zero on a phone, with the
 * sidebar shown and under a Cover Flow, where nothing floats over the top of the content. The page bars share the pill's
 * row and stay beside it; only what sits under them (a large title, a header) must clear it.
 */
val LocalTopChromeInset = staticCompositionLocalOf { 0.dp }

/** The room a page reserves at its top: its [own] (a bar's height, or nothing), or the [inset] when the chrome reaches lower. */
fun topChromeSpace(own: Dp, inset: Dp): Dp = maxOf(own, inset)

/** A spacer of [topChromeSpace] height: put it where the page's first content would start so it clears the top chrome. */
@Composable
fun TopChromeSpace(own: Dp = 0.dp, modifier: Modifier = Modifier) {
    Spacer(modifier.height(topChromeSpace(own, LocalTopChromeInset.current)))
}
