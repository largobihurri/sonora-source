package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.feature.dj.DjTimelineSizes
import io.sonora.feature.dj.controls.DjControlSizes

/*
 * The height budget of the DJ portrait arrangement (D4.1b). The scrolling waveform is the most important element of a
 * CDJ screen, and in DjPortraitContent it takes whatever height the rows around it leave; once the cue panel joined
 * those rows it shrank to about 40 dp on the realme GT 7 Pro (density 540: 1080x2376 px is 320x704 dp, about 669 dp
 * once the status bar is taken off). So every other row has a fixed, named height here, and the layout picks the
 * densest set of them that still leaves the waveform its minimum, instead of letting it collapse.
 *
 * Pure arithmetic over dp: the composables read their sizes from the chosen [DjPortraitMetrics], and the unit tests add
 * the rows up. The rows' real heights may differ from these by a few dp (text metrics, font scale), which the waveform,
 * still the one flexible row, absorbs.
 */

/** The least height the scrolling waveform is promised; the density is chosen so that it keeps this. */
internal val DjWaveformMinHeight = 160.dp

/** The volume row's height: the [io.sonora.core.designsystem.component.CapsuleSlider]'s own 32 dp. */
internal val DjVolumeRowHeight = 32.dp

/** The least height the scrolling waveform is promised on a tablet, whose [DjDensity.TABLET] rows are chosen only if they keep it. */
internal val DjTabletWaveformMinHeight = 160.dp

/** How tightly the DJ portrait rows are packed, from the roomiest to the densest. */
internal enum class DjDensity {
    /** A tablet (T6): 56 dp touch rows, a 88 dp play button, the whole width (no 560 dp cap), a much taller waveform. */
    TABLET,

    /** A tall phone: 44 dp rows, 72 dp transport, 28 dp side margins, as before the cue panel. */
    COMFORTABLE,

    /** About 700 dp tall: 40 dp rows, a slimmer header and transport, 16 dp side margins. */
    COMPACT,

    /** Too short even for [COMPACT]: the same rows without the volume row (the hardware keys set the volume). */
    TIGHT,
}

/**
 * The fixed sizes of one [density] of the DJ portrait arrangement. Every row but the scrolling waveform has a height
 * here; the waveform takes what is left ([waveformHeight]).
 */
internal data class DjPortraitMetrics(
    val density: DjDensity,
    /** The control rows (pads, memory, tempo, grid editor). */
    val controls: DjControlSizes,
    /** Left and right margin of the whole arrangement. */
    val sidePadding: Dp,
    /** A slim drag handle row ([grabberRowHeight]). */
    val compactGrabber: Boolean,
    /** The header's artwork, which sets the header's height (the title and artist lines are shorter). */
    val headerArtwork: Dp,
    val headerGapAbove: Dp,
    val headerGapBelow: Dp,
    /** Everything under the scrolling waveform: the overview, its gaps and the time row. */
    val timeline: DjTimelineSizes,
    /** Between the timeline and the first control row. */
    val controlsTopGap: Dp,
    /** The transport row is as tall as its play button; the previous and next glyph buttons are [transportGlyph]. */
    val transportPlay: Dp,
    val transportGlyph: Dp,
    /** The bottom capsule's button side. */
    val bottomButtons: Dp,
    val bottomPadding: Dp,
    /** The widest the arrangement gets: 560 dp on a phone, [Dp.Unspecified] (the whole width) on a tablet. */
    val contentMaxWidth: Dp = 560.dp,
    /** The widest the transport row and the bottom capsule get inside the content (shuffle, repeat and the capsule's buttons spread out). */
    val transportMaxWidth: Dp = 560.dp,
    val capsuleMaxWidth: Dp = 560.dp,
) {
    /** Whether the volume row is shown (not in [DjDensity.TIGHT]). */
    val showsVolume: Boolean get() = density != DjDensity.TIGHT

    /**
     * The height of everything but the scrolling waveform: the grabber, the header, the overview and times and the
     * control rows, or, with the grid [editorOpen], the editor and the transport (no cue panel, tempo section,
     * volume row or bottom capsule).
     */
    fun fixedHeight(editorOpen: Boolean): Dp {
        val gap = controls.gap
        val header = headerGapAbove + headerArtwork + headerGapBelow
        val controlRows = if (editorOpen) {
            controls.gridEditor + gap + transportPlay
        } else {
            val volume = if (showsVolume) gap + DjVolumeRowHeight else 0.dp
            controls.modePanel + gap + controls.tempoSection + gap + transportPlay + volume + gap + bottomButtons
        }
        return grabberRowHeight(compactGrabber) + header + timeline.fixedHeight + controlsTopGap + controlRows + bottomPadding
    }

    /** The scrolling waveform's height in an arrangement [available] tall, never below zero. */
    fun waveformHeight(available: Dp, editorOpen: Boolean): Dp = (available - fixedHeight(editorOpen)).coerceAtLeast(0.dp)
}

private val Tablet = DjPortraitMetrics(
    density = DjDensity.TABLET,
    controls = DjControlSizes.Tablet,
    sidePadding = 40.dp,
    compactGrabber = false,
    headerArtwork = 64.dp,
    headerGapAbove = 8.dp,
    headerGapBelow = 16.dp,
    timeline = DjTimelineSizes.Tablet,
    controlsTopGap = 12.dp,
    transportPlay = 88.dp,
    transportGlyph = 48.dp,
    bottomButtons = 56.dp,
    bottomPadding = 20.dp,
    contentMaxWidth = Dp.Unspecified,
    transportMaxWidth = 560.dp,
    capsuleMaxWidth = 440.dp,
)

private val Comfortable = DjPortraitMetrics(
    density = DjDensity.COMFORTABLE,
    controls = DjControlSizes.Comfortable,
    sidePadding = 28.dp,
    compactGrabber = false,
    headerArtwork = 56.dp,
    headerGapAbove = 8.dp,
    headerGapBelow = 16.dp,
    timeline = DjTimelineSizes.Comfortable,
    controlsTopGap = 10.dp,
    transportPlay = 72.dp,
    transportGlyph = 40.dp,
    bottomButtons = bottomButtonSide(scale = 1f),
    bottomPadding = 12.dp,
)

private val Compact = Comfortable.copy(
    density = DjDensity.COMPACT,
    controls = DjControlSizes.Compact,
    sidePadding = 16.dp,
    compactGrabber = true,
    headerArtwork = 44.dp,
    headerGapAbove = 4.dp,
    headerGapBelow = 6.dp,
    timeline = DjTimelineSizes.Compact,
    controlsTopGap = 4.dp,
    transportPlay = 56.dp,
    transportGlyph = 32.dp,
    bottomButtons = 44.dp,
    bottomPadding = 8.dp,
)

private val Tight = Compact.copy(density = DjDensity.TIGHT)

/**
 * The roomiest density that leaves the scrolling waveform at least [DjWaveformMinHeight] in an arrangement [available]
 * tall, with the grid editor open or not; [DjDensity.TIGHT] when none does (it is the best that can be done). On a
 * [large] window (a tablet, T6) the [DjDensity.TABLET] rows come first, if they leave the waveform at least
 * [DjTabletWaveformMinHeight]; a large window too short for them takes the phone ladder, as before. A phone never
 * gets them.
 */
internal fun djPortraitMetrics(available: Dp, editorOpen: Boolean, large: Boolean = false): DjPortraitMetrics {
    if (large && Tablet.waveformHeight(available, editorOpen) >= DjTabletWaveformMinHeight) return Tablet
    return listOf(Comfortable, Compact).firstOrNull { it.waveformHeight(available, editorOpen) >= DjWaveformMinHeight } ?: Tight
}
