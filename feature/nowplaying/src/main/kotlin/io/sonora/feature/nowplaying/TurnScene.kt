package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/*
 * The pure parts of Now Playing's turn as one live scene (see TurnStage): while turns are enabled both arrangements
 * stay composed and laid out, each at its own shape, and a turn only changes layer properties of their blocks.
 */

/** Landscape when the window is wider than it is tall, in pixels; as [nowPlayingLayout] for a size in dp. */
internal fun nowPlayingLayout(root: IntSize): NowPlayingLayout =
    if (root.width > root.height) NowPlayingLayout.Landscape else NowPlayingLayout.Portrait

/**
 * The size [layout] is laid out at in a window of [root] size while turns are enabled: its own shape whichever way the
 * window is turned (portrait: the short side by the long one), so a rotation, which swaps the window's sides, measures
 * neither arrangement again. The arrangement for the window's shape gets exactly [root].
 */
internal fun arrangementSize(layout: NowPlayingLayout, root: IntSize): IntSize {
    val short = min(root.width, root.height)
    val long = max(root.width, root.height)
    return when (layout) {
        NowPlayingLayout.Portrait -> IntSize(short, long)
        NowPlayingLayout.Landscape -> IntSize(long, short)
    }
}

/** The other arrangement. */
internal val NowPlayingLayout.other: NowPlayingLayout
    get() = when (this) {
        NowPlayingLayout.Portrait -> NowPlayingLayout.Landscape
        NowPlayingLayout.Landscape -> NowPlayingLayout.Portrait
    }

/**
 * Safe drawing insets of a window, in pixels, and the height of its top [bar] (the status bar, or a cutout on top,
 * whether or not the system counts it in the safe insets: in landscape some do not, T71).
 */
internal data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int, val bar: Int = 0) {
    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom

    /**
     * These insets after the display turned a quarter turn clockwise: the old top edge on the left. The top bar stays on
     * top (it does not turn with the phone); its height is kept as the estimate.
     */
    fun quarterTurned(): SafeInsets = SafeInsets(left = top, top = right, right = bottom, bottom = left, bar = bar)

    companion object {
        val Zero = SafeInsets(0, 0, 0, 0)
    }
}

/**
 * What an arrangement pads by within its window: the portrait one by the [insets] as they are; the landscape one pads
 * its left and right by the larger of the two (a camera cutout on one side, the navigation bar maybe on the other), so
 * both halves stay centred on the screen.
 */
internal fun arrangementPadding(layout: NowPlayingLayout, insets: SafeInsets): SafeInsets = when (layout) {
    NowPlayingLayout.Portrait -> insets
    NowPlayingLayout.Landscape -> max(insets.left, insets.right).let { side -> insets.copy(left = side, right = side) }
}

/**
 * How far below an arrangement's [padding] its top row must start to clear the window's top bar (T71): nothing when the
 * padding already covers the bar.
 */
internal fun topRowClearance(padding: SafeInsets): Int = (padding.bar - padding.top).coerceAtLeast(0)

/**
 * Each arrangement's safe insets: the window's own while it is shown, the last ones seen in its orientation while it
 * waits for the next turn, so that a turn back to it finds it laid out as the window will be.
 */
internal class InsetMemory {
    private val seen = HashMap<NowPlayingLayout, SafeInsets>()

    /** The window shows [layout] with [live] insets; whether they differ from the ones remembered for it. */
    fun shown(layout: NowPlayingLayout, live: SafeInsets): Boolean = seen.put(layout, live) != live

    /**
     * The insets [layout] pads by: the last seen while it was shown; before its first visit, an estimate (the other
     * arrangement's turned a quarter, as if the system's bars and cutout turned with the phone); none before either.
     */
    fun insetsFor(layout: NowPlayingLayout): SafeInsets =
        seen[layout] ?: seen[layout.other]?.quarterTurned() ?: SafeInsets.Zero
}

/** What a change of the display rotation and of the window's size means for Now Playing. */
internal sealed interface WindowTurn {
    /** The display did not turn. */
    data object None : WindowTurn

    /** A quarter turn whose window has not changed size yet: the turn starts with the new size. */
    data object Wait : WindowTurn

    /** The display turned and the window into another, unrelated size (a resized split screen): nothing to animate. */
    data object Skip : WindowTurn

    /** The window turned [quarterTurns] quarter turns clockwise. */
    data class Turn(val quarterTurns: Int) : WindowTurn
}

/**
 * The turn from display rotation [fromRotation] and window size [fromRoot] to [toRotation] and [toRoot]. A quarter turn
 * swaps the window's sides (a square window keeps them); a half turn keeps its size.
 */
internal fun windowTurn(fromRotation: Int, toRotation: Int, fromRoot: IntSize, toRoot: IntSize): WindowTurn {
    val quarters = quarterTurns(fromRotation, toRotation)
    if (quarters == 0) return WindowTurn.None
    val turnedRoot = if (quarters % 2 == 1) IntSize(fromRoot.height, fromRoot.width) else fromRoot
    return when (toRoot) {
        turnedRoot -> WindowTurn.Turn(quarters)
        fromRoot -> WindowTurn.Wait
        else -> WindowTurn.Skip
    }
}

/** The role of one of Now Playing's two arrangements. */
internal enum class ArrangementRole {
    /** The arrangement for the window's shape: drawn, touchable and read by TalkBack. */
    Shown,

    /** During a turn, the arrangement shown before it: still drawn while it turns away and fades out. */
    Outgoing,

    /** Kept composed and laid out for the next turn, but neither drawn, touchable nor read. */
    Hidden,
}

/** Which arrangement is [shown] and which, during a turn, is turning away ([outgoing]). */
internal data class TurnRoles(val shown: NowPlayingLayout, val outgoing: NowPlayingLayout? = null) {
    fun role(layout: NowPlayingLayout): ArrangementRole = when (layout) {
        shown -> ArrangementRole.Shown
        outgoing -> ArrangementRole.Outgoing
        else -> ArrangementRole.Hidden
    }

    /** A turn to [next]: the arrangement shown until now turns away (none when it stays the same: a half turn). */
    fun turnedTo(next: NowPlayingLayout): TurnRoles = TurnRoles(next, shown.takeIf { it != next })

    /** The turn is over: the arrangement that turned away is hidden. */
    fun settled(): TurnRoles = TurnRoles(shown)

    /** The arrangements to keep composed: both while [keepsBoth], otherwise the shown one and one turning away. */
    fun composed(keepsBoth: Boolean): List<NowPlayingLayout> =
        NowPlayingLayout.entries.filter { keepsBoth || it == shown || it == outgoing }
}

/**
 * How a block's graphics layer draws its live content: moved by [translation], turned [rotation] degrees clockwise and
 * scaled by [scale] about the block's centre, at [alpha].
 */
internal data class BlockLayer(
    val translation: Offset = Offset.Zero,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
) {
    companion object {
        /** Drawn as laid out. */
        val Still = BlockLayer()

        /** Not drawn. */
        val Invisible = BlockLayer(alpha = 0f)
    }
}

/** The layer that draws a block laid out at [place] in this frame: centred on it, turned with it, fitted into it. */
internal fun BlockFrame.layerFor(place: Rect, alpha: Float): BlockLayer =
    BlockLayer(translation = center - place.center, rotation = rotation, scale = fitScale(place.size), alpha = alpha)

/**
 * One turn of the window by [quarterTurns] quarter turns, from the [outgoing] arrangement (laid out at [old] on a screen
 * of [oldScreen] size) to the [incoming] one on a screen of [screen] size, whose blocks are at [ends] when already laid
 * out ([placed] adds the others). [outgoing] is null when the shown arrangement turns alone (a half turn).
 *
 * Both arrangements are drawn live, every block through its [layer] only. The artwork and the controls of both share
 * one frame per block ([TurnMotion]: from the old place on the display, counter-rotated, to the new layout), each picture
 * fitted into it; the old one fades out ([snapshotAlpha]) above the new one fading in ([liveAlpha]). The drag handles
 * stay put on the display and only cross-fade.
 */
internal class BlockTurn(
    val incoming: NowPlayingLayout,
    val outgoing: NowPlayingLayout?,
    old: Map<NowPlayingBlock, Rect>,
    oldScreen: Size,
    quarterTurns: Int,
    ends: Map<NowPlayingBlock, Rect>,
    screen: Size,
) {
    /** Where each block was on the display, seen from the turned screen. */
    private val starts: Map<NowPlayingBlock, BlockFrame> = old.mapValues { (_, place) -> turnStartFrame(place, oldScreen, quarterTurns) }
    private val pivot = screen.center
    private val ends = HashMap<NowPlayingBlock, BlockFrame>()
    private var motion: TurnMotion

    init {
        for ((block, place) in ends) if (block.moves) this.ends[block] = place.frame()
        motion = plannedMotion()
    }

    private fun plannedMotion() = TurnMotion(starts.filterKeys { it.moves }, ends.toMap(), pivot)

    /**
     * The incoming arrangement laid [block] out at [place], [time] (0..1) through the turn: before it has moved
     * anything the turn is planned anew; later it re-aims from where the blocks are, so nothing jumps. Whether the
     * turn changed.
     */
    fun placed(block: NowPlayingBlock, place: Rect, time: Float): Boolean {
        if (!block.moves) return false
        val end = place.frame()
        if (ends[block] == end) return false
        ends[block] = end
        motion = if (time <= 0f) plannedMotion() else motion.retargeted(turnEasing(time), ends.toMap(), starts)
        return true
    }

    /** The layer of [block] of [arrangement], laid out at [place], [time] (0..1) through the turn; [opaque] as for [liveAlpha]. */
    fun layer(arrangement: NowPlayingLayout, block: NowPlayingBlock, place: Rect, time: Float, opaque: Boolean): BlockLayer {
        val frame = if (block.moves) motion.frame(block, turnEasing(time)) else null
        return when (arrangement) {
            // The old picture: first exactly where it was on the display, a moving block then along the shared path.
            outgoing -> (frame ?: starts[block])?.layerFor(place, snapshotAlpha(time)) ?: BlockLayer.Invisible
            incoming -> when {
                !block.moves -> BlockLayer(alpha = liveAlpha(time, opaque = false))
                // Not laid out when the turn started: it joins once placed.
                frame == null -> BlockLayer.Invisible
                // Turning alone, the same picture moves from its old place: nothing to fade.
                outgoing == null -> frame.layerFor(place, 1f)
                else -> frame.layerFor(place, liveAlpha(time, opaque))
            }
            else -> BlockLayer.Invisible
        }
    }
}

/**
 * The layer of [block] of [arrangement] (whose [role] it is), laid out at [place] (null until known), [time] through
 * [turn] (null outside a turn): outside a turn the shown arrangement is drawn as laid out and the other not at all.
 */
internal fun stageBlockLayer(
    role: ArrangementRole,
    turn: BlockTurn?,
    arrangement: NowPlayingLayout,
    block: NowPlayingBlock,
    place: Rect?,
    time: Float,
    opaque: Boolean,
): BlockLayer = when {
    turn == null -> if (role == ArrangementRole.Shown) BlockLayer.Still else BlockLayer.Invisible
    place == null -> BlockLayer.Invisible
    else -> turn.layer(arrangement, block, place, time, opaque)
}
