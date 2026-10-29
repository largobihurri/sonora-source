package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.feature.dj.DjTimelineSizes
import io.sonora.feature.dj.controls.DjControlSizes

/*
 * The budget of the DJ landscape arrangement (D2.10). A landscape phone is short: the realme GT 7 Pro (density 540,
 * 1080x2376 px = 704x320 dp) leaves at most 320 dp, and the scrolling waveform, the most important element of a CDJ
 * screen, must keep at least 120 dp of it. Stacking a deck row of transport, pads and tempo under the waveform cannot do
 * that (the tempo section alone is 100 dp), nor can it fit side by side across 624 dp (the pad panel needs 280 dp, the
 * tempo section 288 dp and the transport more). So the arrangement is:
 *
 *   grabber
 *   header: artwork | title and artist | previous, play, next | chip, star, "..."        (the transport lives here)
 *   scrolling waveform, the full width, taking the height the rows below leave
 *   [ overview + times        ] [ tempo section: readout, fader, range ]  <- the tempo section stands beside the
 *   [ pad panel: pads, Q, ... ] [                                        ]     overview and the pad panel
 *
 * With the grid editor open the pad panel and the tempo section make way for the editor, which takes the right column
 * from the header down, so the waveform (and the overview under it) stay in view to its left. Tall windows (tablets) get
 * roomier rows and a bottom row with the volume slider and the bottom capsule, which a phone has no room for.
 *
 * A large window (T6, the Pad 6 is about 1152x720 dp) gets the [DjLandscapeDensity.TABLET] arrangement instead, a single
 * deck with room to spare:
 *
 *   grabber
 *   header: artwork | title and artist | chip, star, "..."
 *   scrolling waveform, the full width
 *   [ overview + times                        ] [ tempo column:                ]
 *   [ transport | pad panel (Cues | Loop)     ] [  readout, nudges, reset,     ] [ vertical
 *   [ volume slider | bottom capsule          ] [  range, Master Tempo         ] [ fader   ]
 *
 * The tempo column stands at the trailing edge from the overview down to the bottom, so its vertical fader, like a CDJ
 * pitch fader, is as tall as the whole deck; the transport (previous, play, next) moved from the header to the deck's
 * leading side, with the pads in the middle.
 *
 * Pure arithmetic over dp, like [DjPortraitMetrics]: the composables take their sizes from the chosen
 * [DjLandscapeMetrics] and the unit tests add the rows up. The waveform is the one flexible row, so it absorbs the few dp
 * by which real text metrics may differ.
 */

/** The least height the scrolling waveform is promised; the density is chosen so that it keeps this. */
internal val DjLandscapeWaveformMinHeight = 120.dp

/** The least height the scrolling waveform is promised on a tablet, whose [DjLandscapeDensity.TABLET] rows are chosen only if they keep it. */
internal val DjLandscapeTabletWaveformMinHeight = 160.dp

/** The pad panel never grows past this on a tablet (eight pads of about 76 dp). */
internal val DjTabletMaxPadPanel = 680.dp

/** The narrowest pad panel a tablet deck accepts: eight pads of 44 dp and seven 8 dp gaps, with a little to spare. */
private val DjTabletMinPadPanel = 408.dp

/** How tightly the DJ landscape rows are packed, from the roomiest to the densest. */
internal enum class DjLandscapeDensity {
    /** A tablet (T6): 56 dp touch rows, the transport on the deck, a vertical tempo fader, volume and capsule under the deck. */
    TABLET,

    /** A tablet: 44 dp rows, the volume slider and the bottom capsule under the deck. */
    COMFORTABLE,

    /** A phone about 320 dp tall: 34 dp pads, 30 dp pills, a 36 dp header, no bottom row. */
    COMPACT,

    /** Under about 300 dp: the same rows 4 dp smaller still, so the waveform keeps its minimum. */
    TIGHT,
}

/** The fixed sizes of one [density] of the DJ landscape arrangement; the scrolling waveform takes what is left. */
internal data class DjLandscapeMetrics(
    val density: DjLandscapeDensity,
    /** The control rows (pads, Q and memory, tempo, grid editor). */
    val controls: DjControlSizes,
    /** The overview, the time row and their gaps. */
    val timeline: DjTimelineSizes,
    val sidePadding: Dp,
    val compactGrabber: Boolean,
    val headerGapAbove: Dp,
    /** The header row: tall enough for the artwork, the transport and two lines of text. */
    val headerHeight: Dp,
    val headerGapBelow: Dp,
    val headerArtwork: Dp,
    /** The transport in the header: the play button's side, and the previous and next glyphs (their targets are 16 dp larger). */
    val transportPlay: Dp,
    val transportGlyph: Dp,
    /** Between the time row and the pad panel. */
    val deckGap: Dp,
    /** Whether a bottom row (volume slider and capsule) fits under the deck. */
    val showsBottomRow: Boolean,
    val bottomButtons: Dp,
    val bottomRowGap: Dp,
    val bottomPadding: Dp,
    val titleSize: TextUnit,
    val artistSize: TextUnit,
    /** The tablet deck (T6): the transport stands on the deck's leading side and the tempo is a tall column with a vertical fader. */
    val tabletDeck: Boolean = false,
    /** The width of the transport block (previous, play, next) on the tablet deck; none on a phone, whose header holds it. */
    val deckTransportWidth: Dp = 0.dp,
) {
    /** Whether the transport (previous, play, next) is in the header: on a phone it is, on a tablet it is on the deck. */
    val showsHeaderTransport: Boolean get() = !tabletDeck

    /** The width of the bottom capsule beside the volume slider: five buttons and their margins (300 dp at 48 dp buttons). */
    val bottomCapsuleWidth: Dp get() = bottomButtons * 5 + 60.dp

    /**
     * The tablet tempo column's height: it starts at the overview (under the gap) and runs down to the bottom row's end, so
     * its vertical fader is as tall as the overview, the pads and the bottom row together.
     */
    val tempoColumnHeight: Dp
        get() = timeline.overviewHeight + timeline.timeGap + timeline.timeRow + deckGap + controls.modePanel + bottomRowHeight

    /** The width left for the pad panel in the tablet deck's [left] part, after the transport block and its gap. */
    fun padPanelWidth(left: Dp): Dp = left - deckTransportWidth - DjTabletTransportGap

    /** Whether a [width] wide arrangement leaves the tablet deck's pad panel pads of at least 44 dp. */
    fun fitsWidth(width: Dp): Boolean =
        padPanelWidth(djLandscapeWidths(width - sidePadding * 2, tablet = true).left) >= DjTabletMinPadPanel

    /** The overview and the time row, stacked over the pad panel: the left part of the deck block. */
    private val leftDeck: Dp
        get() = timeline.overviewHeight + timeline.timeGap + timeline.timeRow + deckGap + controls.modePanel

    /**
     * The block under the waveform: the overview, the times and the pad panel on the left, the tempo section beside them
     * on the right. Its height is the taller of the two.
     */
    val deckHeight: Dp get() = maxOf(leftDeck, controls.tempoSection)

    /** The bottom row, with the gap above it; nothing when it is not shown. */
    private val bottomRowHeight: Dp get() = if (showsBottomRow) bottomRowGap + bottomButtons else 0.dp

    private val topRows: Dp get() = grabberRowHeight(compactGrabber) + headerGapAbove + headerHeight + headerGapBelow

    /**
     * The height of everything but the scrolling waveform: the grabber, the header, the gap over the overview and the deck
     * block, plus the bottom row; with the grid [editorOpen], the waveform's column keeps the overview and the times and
     * the editor stands beside it, so only those are counted (the editor takes no row of its own).
     */
    fun fixedHeight(editorOpen: Boolean): Dp =
        if (editorOpen) {
            topRows + timeline.fixedHeight + bottomPadding
        } else {
            topRows + timeline.overviewGap + deckHeight + bottomRowHeight + bottomPadding
        }

    /** The scrolling waveform's height in an arrangement [available] tall, never below zero. */
    fun waveformHeight(available: Dp, editorOpen: Boolean): Dp = (available - fixedHeight(editorOpen)).coerceAtLeast(0.dp)

    /**
     * What the right column holds while the grid editor is open: the editor's six rows and, on the tablet deck, whose header
     * has no transport, the transport (previous, play, next) at the bottom of the column, so the waveform's column keeps
     * all its height. A phone's transport stays in the header.
     */
    val editorColumnHeight: Dp
        get() = controls.gridEditor + if (tabletDeck) controls.gap + transportPlay else 0.dp

    /** Whether the grid editor (and, on a tablet, the transport) fits in the right column, from under the header to the bottom padding. */
    fun editorFits(available: Dp): Boolean = available - topRows - bottomPadding >= editorColumnHeight
}

private val Tablet = DjLandscapeMetrics(
    density = DjLandscapeDensity.TABLET,
    controls = DjControlSizes.Tablet,
    timeline = DjTimelineSizes.Tablet,
    sidePadding = 32.dp,
    compactGrabber = false,
    headerGapAbove = 4.dp,
    headerHeight = 64.dp,
    headerGapBelow = 8.dp,
    headerArtwork = 56.dp,
    transportPlay = 80.dp,
    transportGlyph = 40.dp,
    deckGap = 8.dp,
    showsBottomRow = true,
    bottomButtons = 52.dp,
    bottomRowGap = 8.dp,
    bottomPadding = 16.dp,
    titleSize = 22.sp,
    artistSize = 18.sp,
    tabletDeck = true,
    deckTransportWidth = 208.dp,
)

private val Comfortable = DjLandscapeMetrics(
    density = DjLandscapeDensity.COMFORTABLE,
    controls = DjControlSizes.Comfortable,
    timeline = DjTimelineSizes.Comfortable,
    sidePadding = 24.dp,
    compactGrabber = false,
    headerGapAbove = 4.dp,
    headerHeight = 44.dp,
    headerGapBelow = 6.dp,
    headerArtwork = 44.dp,
    transportPlay = 44.dp,
    transportGlyph = 24.dp,
    deckGap = 6.dp,
    showsBottomRow = true,
    bottomButtons = bottomButtonSide(scale = 1f),
    bottomRowGap = 6.dp,
    bottomPadding = 12.dp,
    titleSize = 19.sp,
    artistSize = 17.sp,
)

private val Compact = Comfortable.copy(
    density = DjLandscapeDensity.COMPACT,
    controls = DjControlSizes(control = 34.dp, slim = 30.dp, gap = 2.dp),
    timeline = DjTimelineSizes(overviewGap = 4.dp, overviewHeight = 28.dp, timeGap = 0.dp, timeRow = 18.dp, compactCards = true),
    sidePadding = 16.dp,
    compactGrabber = true,
    headerGapAbove = 0.dp,
    headerHeight = 36.dp,
    headerGapBelow = 2.dp,
    headerArtwork = 32.dp,
    transportPlay = 36.dp,
    transportGlyph = 20.dp,
    deckGap = 4.dp,
    showsBottomRow = false,
    bottomPadding = 6.dp,
    titleSize = 15.sp,
    artistSize = 13.sp,
)

private val Tight = Compact.copy(
    density = DjLandscapeDensity.TIGHT,
    controls = DjControlSizes(control = 30.dp, slim = 26.dp, gap = 2.dp),
    timeline = DjTimelineSizes(overviewGap = 4.dp, overviewHeight = 24.dp, timeGap = 0.dp, timeRow = 16.dp, compactCards = true),
    headerHeight = 36.dp,
    headerArtwork = 30.dp,
    transportPlay = 32.dp,
    transportGlyph = 18.dp,
    bottomPadding = 4.dp,
    titleSize = 14.sp,
    artistSize = 12.sp,
)

/**
 * The roomiest density that leaves the scrolling waveform at least [DjLandscapeWaveformMinHeight] in an arrangement
 * [available] tall (and, with the grid [editorOpen], room for the editor's six rows); [DjLandscapeDensity.TIGHT] when
 * none does, the best that can be done. On a [large] window (a tablet, T6) [width] wide, the [DjLandscapeDensity.TABLET]
 * deck comes first, if it leaves the waveform [DjLandscapeTabletWaveformMinHeight] and its pads fit beside the transport
 * ([DjLandscapeMetrics.fitsWidth]); otherwise the phone ladder applies as before. A phone never gets it.
 */
internal fun djLandscapeMetrics(available: Dp, editorOpen: Boolean, width: Dp = 0.dp, large: Boolean = false): DjLandscapeMetrics {
    if (large && Tablet.fitsWidth(width) &&
        Tablet.waveformHeight(available, editorOpen) >= DjLandscapeTabletWaveformMinHeight &&
        (!editorOpen || Tablet.editorFits(available))
    ) {
        return Tablet
    }
    return listOf(Comfortable, Compact).firstOrNull {
        it.waveformHeight(available, editorOpen) >= DjLandscapeWaveformMinHeight && (!editorOpen || it.editorFits(available))
    } ?: Tight
}

/**
 * How the arrangement's width is shared under the header: the [left] part (the overview, the times and the pad panel), a
 * [gap], and the [tempo] column on the right (the tempo section, or the grid editor).
 */
internal data class DjLandscapeWidths(val left: Dp, val gap: Dp, val tempo: Dp)

/**
 * The split of a [total] width: the tempo column takes 40% of it, never less than the 288 dp the tempo section needs (the
 * width of the portrait one on a 320 dp phone) nor more than 360 dp; the left part gets the rest, which on the realme is
 * 328 dp, beside even a wide camera cutout still the 280 dp the pad panel needs.
 */
internal fun djLandscapeWidths(total: Dp, tablet: Boolean = false): DjLandscapeWidths {
    if (tablet) {
        val tempo = (total * TabletTempoShare).coerceIn(MinTempoWidth, MaxTabletTempoWidth)
        return DjLandscapeWidths(left = total - tempo - TabletColumnGap, gap = TabletColumnGap, tempo = tempo)
    }
    val tempo = (total * TempoShare).coerceIn(MinTempoWidth, MaxTempoWidth)
    return DjLandscapeWidths(left = total - tempo - ColumnGap, gap = ColumnGap, tempo = tempo)
}

private const val TempoShare = 0.4f
private val MinTempoWidth = 288.dp
private val MaxTempoWidth = 360.dp
private val ColumnGap = 8.dp

/**
 * The tablet deck's split (T6): the tempo column (or the grid editor) takes 27% of the width, 288 to 340 dp (a vertical fader
 * and the controls beside it), 16 dp from the left part.
 */
private const val TabletTempoShare = 0.27f
private val MaxTabletTempoWidth = 340.dp
private val TabletColumnGap = 16.dp

/** The gap between the transport block and the pad panel on the tablet deck. */
internal val DjTabletTransportGap = 16.dp
