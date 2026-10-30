package io.sonora.feature.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the Library's visual collection is on screen. A phone shows it whenever it is held sideways ([landscape]); a large
 * screen ([large], a tablet) never takes the screen over by itself and shows it only while it was asked for
 * ([requested], see [VisualCollectionRequest]), in either orientation.
 */
internal fun visualCollectionOnScreen(large: Boolean, landscape: Boolean, requested: Boolean): Boolean =
    if (large) requested else landscape

/**
 * Whether the Library's pages are hidden from accessibility: on a [large] screen while the visual collection is on screen
 * ([visualCollectionShown]) it covers them completely, so TalkBack must not list their controls beneath it. A phone keeps its tree.
 */
internal fun listHiddenBehindVisualCollection(large: Boolean, visualCollectionShown: Boolean): Boolean = large && visualCollectionShown

/**
 * The visual collection on demand, for large screens. A page with a visual collection shows a button that calls [onOpen] while
 * [LocalVisualCollectionRequest] is set; the visual collection's own close button (and the system back, handled by [LibraryScreen])
 * calls [onClose]. [open] says whether it is on screen now.
 */
@Stable
internal class VisualCollectionRequest(val open: Boolean, val onOpen: () -> Unit, val onClose: () -> Unit)

/**
 * The visual collection request on a large screen, `null` on a phone (which turns into a visual collection by being turned sideways).
 * Provided by [LibraryScreen] to its pages and to the visual collection itself.
 */
internal val LocalVisualCollectionRequest = staticCompositionLocalOf<VisualCollectionRequest?> { null }
