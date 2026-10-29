package io.sonora.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** How long the chrome and tinted pages take to blend into a new tint or back to the default. */
const val CHROME_TINT_MILLIS = 300

/**
 * The artwork tint of the page on screen (an album or artist page), for the app chrome (tab bar, mini player) to
 * follow: pages [set] it while shown and [clear] it when they leave ([TintChrome] does both). The newest page with a
 * tint wins; when it leaves, the tint of the page below (if still shown) comes back, else [palette] is `null` and the
 * chrome returns to its default colours.
 */
@Stable
class ChromeTint {
    private class Entry(val owner: Any, val palette: ArtworkPalette?, val backdrop: ArtworkPalette?)

    private val entries = mutableStateListOf<Entry>()

    /** The tint to show now; `null` for the default chrome. */
    val palette: ArtworkPalette? get() = entries.lastOrNull { it.palette != null }?.palette

    /**
     * The palette whose background the page on screen draws, for the strip a large screen's sidebar floats over: it
     * follows [palette], but a page whose colours are still loading already names the neutral one it shows meanwhile, so
     * the strip never shows the window's colour beside a dark page. `null` on a plain page.
     */
    val backdrop: ArtworkPalette? get() = entries.lastOrNull { it.backdrop != null }?.backdrop

    /**
     * [owner]'s tint ([palette] `null` while its artwork colour is not known yet, then [backdrop] is what the page draws
     * meanwhile); a new owner goes on top.
     */
    fun set(owner: Any, palette: ArtworkPalette?, backdrop: ArtworkPalette? = palette) {
        val entry = Entry(owner, palette, backdrop)
        val index = entries.indexOfFirst { it.owner === owner }
        if (index >= 0) entries[index] = entry else entries += entry
    }

    fun clear(owner: Any) {
        entries.removeAll { it.owner === owner }
    }
}

/** The app's [ChromeTint]. The default instance has no reader, so pages outside the app shell tint nothing. */
val LocalChromeTint = staticCompositionLocalOf { ChromeTint() }

/**
 * Tints the app chrome with [palette] while the calling page is shown; leaving the page restores the default. While
 * [palette] is not known yet, [backdrop] is the background the page draws meanwhile (see [ChromeTint.backdrop]).
 */
@Composable
fun TintChrome(palette: ArtworkPalette?, backdrop: ArtworkPalette? = palette) {
    val tint = LocalChromeTint.current
    val owner = remember { Any() }
    DisposableEffect(tint, owner, palette, backdrop) {
        tint.set(owner, palette, backdrop)
        onDispose { }
    }
    DisposableEffect(tint, owner) {
        onDispose { tint.clear(owner) }
    }
}

/**
 * [pick] of the current chrome tint, or [default] without one, blending over [CHROME_TINT_MILLIS] so entering and
 * leaving a tinted page does not flash. For the tab bar and mini player, e.g.
 * `animatedChromeColor(translucentTint) { Color(it.background) }`.
 */
@Composable
fun animatedChromeColor(default: Color, pick: (ArtworkPalette) -> Color): State<Color> {
    val target = LocalChromeTint.current.palette?.let(pick) ?: default
    return animateColorAsState(target, tween(CHROME_TINT_MILLIS), label = "chromeTint")
}

/**
 * The background of the page on screen ([ChromeTint.backdrop]) as an ARGB colour, or [default] on a plain page, blending
 * over [CHROME_TINT_MILLIS] as the page does. For what a large screen's shell draws under the sidebar.
 */
@Composable
fun animatedBackdropColor(default: Color): State<Color> {
    val target = LocalChromeTint.current.backdrop?.let { Color(it.background) } ?: default
    return animateColorAsState(target, tween(CHROME_TINT_MILLIS), label = "backdropTint")
}
