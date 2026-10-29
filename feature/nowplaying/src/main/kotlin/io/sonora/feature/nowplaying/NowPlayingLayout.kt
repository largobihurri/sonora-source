package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** How Now Playing arranges itself in its window. */
internal enum class NowPlayingLayout {
    /** Artwork above the controls. */
    Portrait,

    /** Artwork on the left half, the controls on the right half, like iOS Music in landscape. */
    Landscape,
}

/** Landscape whenever the window is wider than it is tall (a rotated phone, a wide tablet); portrait otherwise. */
internal fun nowPlayingLayout(width: Dp, height: Dp): NowPlayingLayout =
    if (width > height) NowPlayingLayout.Landscape else NowPlayingLayout.Portrait

/** One button of the row under the volume slider. */
internal enum class BottomSlot { EQUALIZER, LYRICS, UP_NEXT, OUTPUT, CAST, FILE_INFO }

/**
 * The bottom row's visible buttons, left to right: the equalizer, lyrics, Up Next, the system output switcher, the Cast
 * route chooser and File Info. [lyrics] and [fileInfo] are the user's Settings, [output] whether the device offers the
 * switcher, [cast] whether it can cast (Google Play services); a button left out takes no place, so the row spreads
 * only what it shows. Every arrangement (portrait, landscape, gravity) shows this one row.
 */
internal fun bottomSlots(lyrics: Boolean, output: Boolean, fileInfo: Boolean, cast: Boolean = false): List<BottomSlot> = buildList {
    add(BottomSlot.EQUALIZER)
    if (lyrics) add(BottomSlot.LYRICS)
    add(BottomSlot.UP_NEXT)
    if (output) add(BottomSlot.OUTPUT)
    if (cast) add(BottomSlot.CAST)
    if (fileInfo) add(BottomSlot.FILE_INFO)
}

/**
 * The side of each bottom-row button's touch target in the dp of a block drawn at [scale] (landscape lays its controls
 * out at a smaller density, T71c): 48 dp, and never less than 44 dp on screen.
 */
internal fun bottomButtonSide(scale: Float): Dp = maxOf(BottomButtonSide, ceil(MinTouchTarget.value / scale).dp)

/** The bottom-row buttons' touch target at full size. */
private val BottomButtonSide = 48.dp

/** The smallest touch target on screen. */
private val MinTouchTarget = 44.dp
