package io.sonora.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.sonora.core.designsystem.motion.LocalPageSlide
import io.sonora.core.designsystem.motion.prefersReducedMotion

/**
 * Shows the tab screens side by side on one horizontal strip ([strip]) and moves the viewport along it: switching from
 * the first tab to the fourth slides past the two in between, like one canvas (mirrored in right-to-left layouts). A
 * tap on a tab springs the strip to [selected]; a scrub on the tab bar drives it directly and this host settles it on
 * release. Only the tabs in the viewport, plus the next one ahead while moving, are composed; each keeps its saveable
 * state (back stack, scroll) while it is off the strip. With "Remove animations" on, tabs switch at once. [stateKey]
 * names each tab's saved state. Content is not swipeable: lists and sliders own horizontal gestures on the screen body.
 */
@Composable
fun <T : Any> TabSlideHost(
    tabs: List<T>,
    selected: T,
    strip: TabStripState,
    modifier: Modifier = Modifier,
    stateKey: (T) -> String = { it.toString() },
    content: @Composable (T) -> Unit,
) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val holder = rememberSaveableStateHolder()
    val target = tabs.indexOf(selected).coerceAtLeast(0)
    val count = tabs.size
    // Recomposes only when a screen enters or leaves the strip; the slide itself runs in each screen's layer. The
    // mask is a small Int, so reading it every frame allocates nothing.
    val pages by remember(strip, count) { derivedStateOf { stripPageMask(strip.position, strip.heading, count) } }

    // A new tab, or the end of a scrub, settles the strip; a starting scrub cancels the running slide. Keyed on the
    // release count too: a scrub that starts and ends between two frames on the same tab still settles, with its speed.
    LaunchedEffect(target, strip.dragging, strip.releases) {
        if (!strip.dragging) strip.settle(target, context.prefersReducedMotion())
    }

    Box(modifier.fillMaxSize().clipToBounds()) {
        for (index in tabs.indices) {
            if (pages and (1 shl index) == 0) continue
            val tab = tabs[index]
            val name = stateKey(tab)
            key(name) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = tabSlideOffset(index, strip.position, rtl) * size.width },
                ) {
                    // Pages with their own background fade its edge into the next tab while sliding (slidingPageBackground).
                    val slide = remember(index, strip, rtl) { { tabSlideOffset(index, strip.position, rtl) } }
                    CompositionLocalProvider(LocalPageSlide provides slide) {
                        holder.SaveableStateProvider(name) { content(tab) }
                    }
                }
            }
        }
    }
}
