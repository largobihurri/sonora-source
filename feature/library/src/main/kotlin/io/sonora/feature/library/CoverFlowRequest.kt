package io.sonora.feature.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the Library's Cover Flow is on screen. A phone shows it whenever it is held sideways ([landscape]); a large
 * screen ([large], a tablet) never takes the screen over by itself and shows it only while it was asked for
 * ([requested], see [CoverFlowRequest]), in either orientation.
 */
internal fun coverFlowOnScreen(large: Boolean, landscape: Boolean, requested: Boolean): Boolean =
    if (large) requested else landscape

/**
 * Whether the Library's pages are hidden from accessibility: on a [large] screen while the Cover Flow is on screen
 * ([coverFlowShown]) it covers them completely, so TalkBack must not list their controls beneath it. A phone keeps its tree.
 */
internal fun listHiddenBehindCoverFlow(large: Boolean, coverFlowShown: Boolean): Boolean = large && coverFlowShown

/**
 * The Cover Flow on demand, for large screens. A page with a Cover Flow shows a button that calls [onOpen] while
 * [LocalCoverFlowRequest] is set; the Cover Flow's own close button (and the system back, handled by [LibraryScreen])
 * calls [onClose]. [open] says whether it is on screen now.
 */
@Stable
internal class CoverFlowRequest(val open: Boolean, val onOpen: () -> Unit, val onClose: () -> Unit)

/**
 * The Cover Flow request on a large screen, `null` on a phone (which turns into a Cover Flow by being turned sideways).
 * Provided by [LibraryScreen] to its pages and to the Cover Flow itself.
 */
internal val LocalCoverFlowRequest = staticCompositionLocalOf<CoverFlowRequest?> { null }
