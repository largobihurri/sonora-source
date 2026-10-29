package io.sonora.feature.nowplaying

import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Now Playing turns the way iOS Music does when the display rotates: nothing changes size or content in one frame.
 * Both arrangements stay composed and laid out while turns are enabled (TurnStage), so a turn only moves layers: first
 * each part of the old arrangement is drawn exactly where it was on the display, counter-rotated; the artwork and the
 * controls then glide and turn into their new places, the old and the new rendering of each sharing one frame whose
 * centre, size and angle ease from the old place to the new one, the old one fading out over the first part of the turn
 * while the new one fades in. The drag handle stays put on the display and only cross-fades; the backdrop never changes
 * at all, laid out fixed on the display ([ArtworkBackdrop]). The window itself rotates seamlessly meanwhile (the shell
 * asks for it), so the system neither rebuilds nor animates the screen. This file holds the turn's geometry and timing.
 */

/** The parts of Now Playing that take part in a turn; only the artwork and the controls move ([moves]). */
internal enum class NowPlayingBlock(val moves: Boolean) {
    /** The drag handle: it cross-fades from its old place on the display to its new one. */
    Handle(moves = false),
    Artwork(moves = true),
    Controls(moves = true),
}

/** Quarter turns, clockwise, from display rotation [from] to [to] (Surface.ROTATION_0..ROTATION_270 as 0..3), in 0..3. */
internal fun quarterTurns(from: Int, to: Int): Int = Math.floorMod(to - from, 4)

/**
 * This rect, laid out on a [screen] before the display turned [quarterTurns] quarter turns clockwise, in the coordinates
 * of the turned screen: the same place on the display. One quarter turn (0 to 90: the phone turned anticlockwise) puts
 * the old top edge on the left.
 */
internal fun Rect.turned(quarterTurns: Int, screen: Size): Rect = when (Math.floorMod(quarterTurns, 4)) {
    1 -> Rect(left = top, top = screen.width - right, right = bottom, bottom = screen.width - left)
    2 -> Rect(left = screen.width - right, top = screen.height - bottom, right = screen.width - left, bottom = screen.height - top)
    3 -> Rect(left = screen.height - bottom, top = left, right = screen.height - top, bottom = right)
    else -> this
}

/**
 * Where and how a block is drawn: its content box, [size] in its own (unturned) axes, centred on [center] and turned
 * [rotation] degrees clockwise about it. Content of another size is fitted into it ([fitScale]).
 */
internal data class BlockFrame(val center: Offset, val size: Size, val rotation: Float)

/** A block laid out in this rect, upright. */
internal fun Rect.frame(): BlockFrame = BlockFrame(center, size, 0f)

/** The uniform scale that fits [content] inside this frame, keeping its proportions; 1 for empty content. */
internal fun BlockFrame.fitScale(content: Size): Float =
    if (content.width <= 0f || content.height <= 0f) 1f else min(size.width / content.width, size.height / content.height)

/**
 * Where a block laid out at [old] on [oldScreen] is on the display right after the display turned [quarterTurns]
 * quarter turns clockwise, seen from the turned screen: the same place and size, counter-rotated (an anticlockwise
 * quarter for one clockwise display quarter) so that it looks unmoved.
 */
internal fun turnStartFrame(old: Rect, oldScreen: Size, quarterTurns: Int): BlockFrame =
    BlockFrame(old.turned(quarterTurns, oldScreen).center, old.size, counterRotation(quarterTurns))

/**
 * The angle (degrees, clockwise) that keeps a picture unmoved on the display after the display turned [quarterTurns]
 * quarter turns clockwise: an anticlockwise quarter for each clockwise display quarter.
 */
internal fun counterRotation(quarterTurns: Int): Float = when (Math.floorMod(quarterTurns, 4)) {
    1 -> -90f
    2 -> 180f
    3 -> 90f
    else -> 0f
}

/**
 * One block's way from [start] to [end]. Its size and angle ease linearly; its centre travels round [pivot] (the screen
 * centre) in polar coordinates: the distance from the pivot eases, and the angle sweeps the way the content turns
 * (the sweep is taken within half a turn of the content's own rotation). Turning towards the artwork's side the
 * sweep is nearly nil, so each block stays on its own side of the screen and only turns in place; turning the other
 * way, the blocks must swap sides, and since both sweep the same way round they pass on opposite sides of the centre,
 * like one body, instead of crossing through it as straight lines would.
 */
internal class BlockPath(private val start: BlockFrame, private val end: BlockFrame, private val pivot: Offset) {
    private val startRadius = (start.center - pivot).getDistance()
    private val endRadius = (end.center - pivot).getDistance()
    private val endAngle = (end.center - pivot).angle(fallback = (start.center - pivot).angle(fallback = 0f))
    private val startAngle = (start.center - pivot).angle(fallback = endAngle)
    private val sweep: Float = run {
        val turn = end.rotation - start.rotation
        turn + wrapDegrees(endAngle - startAngle - turn)
    }

    /** The frame [fraction] of the way (0 = start, 1 = end), exactly [start] and [end] at the ends. */
    fun at(fraction: Float): BlockFrame {
        if (fraction <= 0f) return start
        if (fraction >= 1f) return end
        val radius = lerp(startRadius, endRadius, fraction)
        val radians = Math.toRadians((startAngle + sweep * fraction).toDouble())
        return BlockFrame(
            center = pivot + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius,
            size = Size(lerp(start.size.width, end.size.width, fraction), lerp(start.size.height, end.size.height, fraction)),
            rotation = lerp(start.rotation, end.rotation, fraction),
        )
    }
}

/**
 * The turn of the moving blocks from [starts] to [ends] round [pivot]. Each follows its [BlockPath]; and since two big
 * blocks turning about their own centres side by side would swing their corners into each other, a pair is pushed
 * apart along the line between them by just enough to keep their turned boxes clear all the way, a push that grows
 * and shrinks smoothly (half a sine) and is nil at both ends, at most [pivot]'s smaller coordinate. It starts at
 * eased progress [from] (a turn re-aimed part way, see [retargeted]).
 */
internal class TurnMotion(
    starts: Map<NowPlayingBlock, BlockFrame>,
    val ends: Map<NowPlayingBlock, BlockFrame>,
    private val pivot: Offset,
    private val from: Float = 0f,
) {
    private val paths: Map<NowPlayingBlock, BlockPath> =
        ends.mapNotNull { (block, end) -> starts[block]?.let { block to BlockPath(it, end, pivot) } }.toMap()
    private val pair: Pair<NowPlayingBlock, NowPlayingBlock>? = paths.keys.toList().takeIf { it.size == 2 }?.let { it[0] to it[1] }
    private val bow: Float = pair?.let { separatingBow(paths.getValue(it.first), paths.getValue(it.second), min(pivot.x, pivot.y)) } ?: 0f

    /** The frame [block] is drawn in at eased progress [eased], or null when it takes no part in the turn. */
    fun frame(block: NowPlayingBlock, eased: Float): BlockFrame? {
        val path = paths[block] ?: return null
        val fraction = local(eased)
        val base = path.at(fraction)
        val couple = pair ?: return base
        if (bow == 0f || fraction <= 0f || fraction >= 1f) return base
        val other = paths.getValue(if (couple.first == block) couple.second else couple.first).at(fraction)
        val away = direction(other.center, base.center) ?: return base
        return base.copy(center = base.center + away * (bow * sin(PI * fraction).toFloat() / 2f))
    }

    /**
     * This turn aimed at new [ends] from eased progress [eased] on: it continues from where the blocks are then; a block
     * that had no way yet (its end was unknown) sets off from its frame in [starts].
     */
    fun retargeted(eased: Float, ends: Map<NowPlayingBlock, BlockFrame>, starts: Map<NowPlayingBlock, BlockFrame> = emptyMap()): TurnMotion {
        val now = starts.filterKeys { it !in paths } + paths.keys.associateWith { frame(it, eased)!! }
        return TurnMotion(now, ends, pivot, from = eased)
    }

    private fun local(eased: Float): Float = if (from >= 1f) 1f else ((eased - from) / (1f - from)).coerceIn(0f, 1f)
}

/**
 * How far apart (in total) [a] and [b] must be pushed half way, the push easing as half a sine, for their turned boxes
 * to stay clear of each other along the line between their centres (which then separates them), at most [limit].
 */
private fun separatingBow(a: BlockPath, b: BlockPath, limit: Float): Float {
    var bow = 0f
    for (i in 1 until BOW_SAMPLES) {
        val fraction = i / BOW_SAMPLES.toFloat()
        val fa = a.at(fraction)
        val fb = b.at(fraction)
        val axis = direction(fa.center, fb.center) ?: continue
        val overlap = halfExtent(fa, axis) + halfExtent(fb, axis) - (fb.center - fa.center).getDistance()
        if (overlap > 0f) bow = max(bow, overlap / sin(PI * fraction).toFloat())
    }
    return if (bow == 0f) 0f else min(bow + BOW_MARGIN, limit)
}

private const val BOW_SAMPLES = 120
private const val BOW_MARGIN = 4f

/** Half the length of [frame]'s turned box projected on the unit [axis]. */
private fun halfExtent(frame: BlockFrame, axis: Offset): Float {
    val radians = Math.toRadians(frame.rotation.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return abs(frame.size.width / 2f * (c * axis.x + s * axis.y)) + abs(frame.size.height / 2f * (-s * axis.x + c * axis.y))
}

/** The unit vector from [from] to [to], or null when they (nearly) coincide. */
private fun direction(from: Offset, to: Offset): Offset? {
    val d = to - from
    val length = d.getDistance()
    return if (length < 1e-3f) null else d / length
}

/** This vector's angle in degrees, clockwise from the x axis (y grows downwards), or [fallback] for a (near) zero vector. */
private fun Offset.angle(fallback: Float): Float =
    if (hypot(x, y) < 1e-3f) fallback else Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

/** The share of the turn (by time) over which the old picture fades out and a new, see-through block fades in. */
internal const val CROSSFADE_END = 0.6f

/** The old picture's opacity [time] (0..1) through the turn: 1 to 0 over the first [CROSSFADE_END], smoothly. */
internal fun snapshotAlpha(time: Float): Float = 1f - smoothStep(time / CROSSFADE_END)

/**
 * The new live block's opacity [time] through the turn. An [opaque] block (the artwork: the same picture before and
 * after) is fully drawn beneath the fading old one, so the two together never let the background through;
 * the others fade in as the old picture fades out.
 */
internal fun liveAlpha(time: Float, opaque: Boolean): Float = if (opaque) 1f else smoothStep(time / CROSSFADE_END)

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A gentle start and a long, smooth deceleration into place, never past it (CSS "ease"). */
private val TurnEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** The eased progress [time] (0..1) through a turn. */
internal fun turnEasing(time: Float): Float = if (time >= 1f) 1f else if (time <= 0f) 0f else TurnEasing.transform(time)

/** How long a turn takes. */
internal const val TURN_MILLIS = 420

@Suppress("DEPRECATION") // View.getDisplay is the window's display; Context.getDisplay needs API 30.
internal fun View.displayRotation(): Int = display?.rotation ?: 0
