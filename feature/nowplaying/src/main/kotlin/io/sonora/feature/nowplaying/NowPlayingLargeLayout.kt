package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.layout.WindowClass
import kotlin.math.ceil
import kotlin.math.min

/*
 * Now Playing on a tablet (T5): the pure decisions behind LargeNowPlayingContent. The arrangement is chosen from the
 * window class, the side panel (lyrics or Up Next) takes the artwork's place instead of opening a sheet, and the
 * controls are drawn at a scale that grows with the room, capped so they always fit. The phone arrangements are
 * untouched: every one of these decisions answers "phone" for a window that is not large.
 */

/** Which family of arrangements Now Playing draws: the phone's, or the tablet's (artwork and scaled-up controls). */
internal enum class NowPlayingStyle { Phone, Large }

/**
 * The style of a window of [windowClass]: large only on a large window (a tablet, an unfolded foldable) and not in DJ
 * mode, whose layouts are their own ([DjPortraitContent], [DjLandscapeContent]) and keep the phone's panels too.
 */
internal fun nowPlayingStyle(windowClass: WindowClass, djMode: Boolean = false): NowPlayingStyle =
    if (windowClass.isLarge && !djMode) NowPlayingStyle.Large else NowPlayingStyle.Phone

/** What takes the artwork's place in Now Playing. */
internal enum class SidePanel { None, Lyrics, Queue }

/** Whether the lyrics panel and the Up Next queue have been asked for. */
internal data class PanelState(val lyrics: Boolean = false, val queue: Boolean = false)

/**
 * The panel in the artwork's place. A phone has only lyrics there (its Up Next is a sheet, see [queueAsSheet]); a tablet
 * shows either, the queue first should both be asked for (the taps below never leave both on).
 */
internal fun sidePanelShown(style: NowPlayingStyle, panels: PanelState): SidePanel = when {
    style == NowPlayingStyle.Large && panels.queue -> SidePanel.Queue
    panels.lyrics -> SidePanel.Lyrics
    else -> SidePanel.None
}

/** Whether Up Next opens as the translucent sheet: on a phone while asked for; a tablet shows it in the artwork's place. */
internal fun queueAsSheet(style: NowPlayingStyle, panels: PanelState): Boolean = style == NowPlayingStyle.Phone && panels.queue

/**
 * The panels after the lyrics button is tapped: it toggles the lyrics. On a tablet the queue closes when the lyrics
 * open, as only one panel fits the artwork's place.
 */
internal fun PanelState.lyricsTapped(style: NowPlayingStyle): PanelState =
    if (style == NowPlayingStyle.Large) PanelState(lyrics = !lyrics) else copy(lyrics = !lyrics)

/**
 * The panels after the Up Next button is tapped. On a phone it opens the sheet (the sheet closes itself, the lyrics stay);
 * on a tablet it toggles the queue and closes the lyrics.
 */
internal fun PanelState.queueTapped(style: NowPlayingStyle): PanelState =
    if (style == NowPlayingStyle.Large) PanelState(queue = !queue) else copy(queue = true)

/**
 * How a large window lays Now Playing out: the room it gives the controls (drawn at [controlsScale], a density
 * multiplier that grows their sizes, text and gaps alike) and the artwork.
 */
internal data class LargeLayoutPlan(
    /** Between the window's (safe) edges and the arrangement. */
    val sidePadding: Dp,
    /** Landscape: between the artwork's side and the controls' side; 0 in portrait. */
    val columnGap: Dp,
    /** The density multiplier of the controls block, in [1, MaxLargeControlsScale]. */
    val controlsScale: Float,
    /** The controls block's width on screen. */
    val controlsWidth: Dp,
    /** The largest side of the artwork's square: the layout fits it within the room that is left, never past this. */
    val artworkSide: Dp,
)

/**
 * The plan for [layout] in an area [width] by [height] (the window less its safe insets, the drag handle row and the
 * bottom margin).
 *
 * Landscape: the controls take 42 % of the room between the edges and the gap, between [MinLargeControlsWidth] and
 * [MaxLargeControlsWidth]; the artwork leads, the largest square the rest allows within the height less its margins.
 * Portrait: the controls are as wide as the room allows up to [MaxLargeControlsWidth] and the artwork above them has
 * their width, the largest square the height left by the controls allows.
 * The scale is the one that fills the controls' width at [LargeControlsDesignWidth] (a phone-sized column), not more
 * than the height allows, and always within 1 and [MaxLargeControlsScale].
 */
internal fun largeLayoutPlan(layout: NowPlayingLayout, width: Dp, height: Dp): LargeLayoutPlan = when (layout) {
    NowPlayingLayout.Landscape -> {
        val room = (width - LargeSidePadding * 2f - LargeColumnGap).coerceAtLeast(0.dp)
        val controls = (room * LandscapeControlsShare).coerceIn(MinLargeControlsWidth, MaxLargeControlsWidth).coerceAtMost(room)
        val scale = min(controls / LargeControlsDesignWidth, height / LandscapeControlsMinHeight)
        LargeLayoutPlan(
            sidePadding = LargeSidePadding,
            columnGap = LargeColumnGap,
            controlsScale = scale.coerceIn(1f, MaxLargeControlsScale),
            controlsWidth = controls,
            artworkSide = minOf(room - controls, height - LargeArtworkMargin * 2f, MaxLargeArtworkSide).coerceAtLeast(0.dp),
        )
    }
    NowPlayingLayout.Portrait -> {
        val controls = (width - LargeSidePadding * 2f).coerceAtLeast(0.dp).coerceAtMost(MaxLargeControlsWidth)
        // The scale that leaves the artwork its least side above the controls.
        val byHeight = (height - MinPortraitArtworkSide - PortraitArtworkGap) / PortraitControlsHeight
        val scale = min(controls / LargeControlsDesignWidth, byHeight).coerceIn(1f, MaxLargeControlsScale)
        LargeLayoutPlan(
            sidePadding = LargeSidePadding,
            columnGap = 0.dp,
            controlsScale = scale,
            controlsWidth = controls,
            artworkSide = minOf(controls, height - PortraitControlsHeight * scale - PortraitArtworkGap, MaxLargeArtworkSide)
                .coerceAtLeast(0.dp),
        )
    }
}

/** A control drawn at [scale] (a density multiplier) takes this many of its own dp to measure 44 dp on screen. */
internal fun touchTargetSide(scale: Float): Dp = ceil(MinLargeTouchTarget.value / scale).dp

/** The scale never grows the controls past this: the layout's text and glyphs stay in proportion with the artwork. */
internal const val MaxLargeControlsScale = 1.5f

/** The width the controls are designed for at scale 1: a phone-sized column. Wider room is met by a larger scale. */
internal val LargeControlsDesignWidth = 330.dp

/** The narrowest and widest the controls block gets on screen. */
internal val MinLargeControlsWidth = 300.dp
internal val MaxLargeControlsWidth = 560.dp

/** No artwork is drawn larger than this, however large the window. */
internal val MaxLargeArtworkSide = 800.dp

/** The share of the landscape room the controls take before the clamps above. */
private const val LandscapeControlsShare = 0.42f

/** What landscape controls need at scale 1 to sit comfortably (header, scrubber, transport, volume, bottom row). */
internal val LandscapeControlsMinHeight = 300.dp

/** What the portrait controls column measures at scale 1 (its spacers and gaps included). */
internal val PortraitControlsHeight = 340.dp

/** The least side portrait leaves the artwork before the controls stop growing. */
internal val MinPortraitArtworkSide = 280.dp

/** Between the portrait artwork and the controls. */
private val PortraitArtworkGap = 16.dp

/** Landscape: above and below the artwork. */
private val LargeArtworkMargin = 16.dp

private val LargeSidePadding = 40.dp
private val LargeColumnGap = 56.dp

private val MinLargeTouchTarget = 44.dp
