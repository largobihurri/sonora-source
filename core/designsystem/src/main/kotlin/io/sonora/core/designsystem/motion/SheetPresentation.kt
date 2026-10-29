package io.sonora.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Release speed (any direction) that settles the sheet the way it was flung, whatever the distance. */
val SHEET_FLING_VELOCITY = 400.dp

/** How far the sheet must travel from where a drag started before a slow release changes its state. */
val SHEET_SETTLE_DISTANCE = 140.dp

/** Shrink of the sheet when fully lowered (it reaches it about a third of the way down). */
const val SHEET_DRAG_MAX_SHRINK = 0.08f

/**
 * Whether a released drag settles the sheet open. A drag that caught the sheet at [startProgress] while it was heading
 * open ([startedOpen]) or closed keeps that heading unless the finger moved it more than [distancePx] the other way, or
 * a fling faster than [flingVelocityPx] decides the direction. Travel counts from where the finger caught the sheet, so
 * catching it mid-spring and letting go never reverses it. Progress is 0 closed .. 1 open; [velocityPx] is positive
 * downwards.
 */
fun sheetSettlesOpen(
    startedOpen: Boolean,
    startProgress: Float,
    progress: Float,
    heightPx: Float,
    velocityPx: Float,
    distancePx: Float,
    flingVelocityPx: Float,
): Boolean {
    if (abs(velocityPx) >= flingVelocityPx) return velocityPx < 0f
    val moved = (progress.coerceIn(0f, 1f) - startProgress.coerceIn(0f, 1f)) * heightPx // positive = up
    val reversed = if (startedOpen) -moved > distancePx else moved > distancePx
    return startedOpen != reversed
}

/**
 * The sheet's place at open [progress]: downward translation (share of its height) and scale about its centre. The
 * translation makes up for the shrink so the visible top edge sits exactly at `1 - progress` of the height, moving one
 * to one with a finger dragging it.
 */
data class SheetPresentFrame(val offsetFraction: Float, val scale: Float)

fun sheetPresentFrame(progress: Float): SheetPresentFrame {
    val lowered = 1f - progress.coerceIn(0f, 1f)
    val scale = 1f - (lowered / 4f).coerceAtMost(SHEET_DRAG_MAX_SHRINK)
    return SheetPresentFrame(offsetFraction = lowered - (1f - scale) / 2f + 0f, scale = scale)
}

/**
 * Open state of a full-screen sheet that rises from the bottom, shared by everything that drives it (the sheet's own
 * drag-down, the mini player's drag-up, taps and back). [progress] goes 0 (off screen) .. 1 (open) and follows the
 * finger one to one; a release settles it by distance and velocity ([sheetSettlesOpen]). With "Remove animations" on
 * it settles without motion. The host sets [heightPx] from its layout. [log], when set, receives each drag and settle
 * decision (debug builds). Snapshot state, main thread only.
 */
@Stable
class SheetPresentation(
    initiallyOpen: Boolean,
    private val scope: CoroutineScope,
    private val reducedMotion: () -> Boolean,
) {
    val progress = Animatable(if (initiallyOpen) 1f else 0f).apply { updateBounds(0f, 1f) }

    /** Where the sheet is heading: open, or closed (it may still be sliding away). */
    var targetOpen by mutableStateOf(initiallyOpen)
        private set

    /** A finger is moving the sheet. */
    var dragging by mutableStateOf(false)
        private set

    var heightPx = 0f
    var settleDistancePx = 0f
    var flingVelocityPx = Float.MAX_VALUE
    var log: ((String) -> Unit)? = null

    // The drag's own position, ahead of the snaps it queued, so a release decides on where the finger really is.
    private var dragProgress = 0f
    private var startProgress = 0f
    private var startedOpen = initiallyOpen

    /**
     * The sheet is on screen (open, opening, closing or dragged). Derived: a composition that reads it is invalidated when
     * the answer changes, not on every frame of [progress] (the shell reading the progress itself recomposed on each one).
     */
    val shown: Boolean by derivedStateOf { targetOpen || progress.value > 0f }

    /** The sheet rests fully open, so whatever is underneath can leave the screen. Derived like [shown]. */
    val covers: Boolean by derivedStateOf { targetOpen && !dragging && progress.value >= 1f }

    fun open() = settle(open = true, velocityPx = 0f)

    fun close() = settle(open = false, velocityPx = 0f)

    /** Closes without motion, e.g. when there is nothing left to show. */
    fun closeNow() {
        targetOpen = false
        dragging = false
        scope.launch { progress.snapTo(0f) }
    }

    fun dragStarted() {
        if (dragging) return
        dragging = true
        startedOpen = targetOpen
        dragProgress = progress.value
        startProgress = dragProgress
        log?.invoke("drag start at $startProgress heading ${if (startedOpen) "open" else "closed"}")
    }

    /** The finger moved [deltaPx] (positive downwards). */
    fun dragBy(deltaPx: Float) {
        if (!dragging || heightPx <= 0f) return
        dragProgress = (dragProgress - deltaPx / heightPx).coerceIn(0f, 1f)
        val target = dragProgress
        scope.launch { progress.snapTo(target) }
    }

    /** The finger lifted at [velocityPx] (positive downwards); 0 for a cancelled drag. */
    fun dragStopped(velocityPx: Float) {
        if (!dragging) return
        dragging = false
        // Before the host measured the sheet the drag moved nothing, so it keeps heading where it was.
        val open = if (heightPx > 0f) {
            sheetSettlesOpen(startedOpen, startProgress, dragProgress, heightPx, velocityPx, settleDistancePx, flingVelocityPx)
        } else {
            startedOpen
        }
        log?.invoke("drag release at $dragProgress, ${velocityPx.toInt()} px/s -> ${if (open) "open" else "closed"}")
        settle(open, velocityPx)
    }

    /** The system took the touch away: settle by where the finger left the sheet, as a release without a fling. */
    fun dragCancelled() {
        if (!dragging) return
        log?.invoke("drag cancelled")
        dragStopped(0f)
    }

    private fun settle(open: Boolean, velocityPx: Float) {
        targetOpen = open
        val target = if (open) 1f else 0f
        val velocity = if (heightPx > 0f) -velocityPx / heightPx else 0f
        scope.launch {
            if (reducedMotion()) {
                progress.snapTo(target)
            } else {
                progress.animateTo(target, sheetSettleSpring(open), initialVelocity = velocity)
            }
        }
    }
}

/**
 * How a released or tapped sheet settles [open] or closed: one unbouncy spring both ways, so a Now Playing bubble opens
 * as its dismissal played backwards (T67.8), ending within a thousandth of the way (under a pixel of the bubble) so its
 * last step never shows as a jump.
 */
@Suppress("UNUSED_PARAMETER")
internal fun sheetSettleSpring(open: Boolean): SpringSpec<Float> = SheetSettleSpring

private val SheetSettleSpring: SpringSpec<Float> =
    spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.0005f)

/** A [SheetPresentation] that survives configuration changes and process death (open or closed). */
@Composable
fun rememberSheetPresentation(): SheetPresentation {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduced = { context.prefersReducedMotion() }
    val sheet = rememberSaveable(
        saver = Saver(save = { it.targetOpen }, restore = { SheetPresentation(it, scope, reduced) }),
    ) { SheetPresentation(false, scope, reduced) }
    val density = LocalDensity.current
    val distancePx = with(density) { SHEET_SETTLE_DISTANCE.toPx() }
    val flingPx = with(density) { SHEET_FLING_VELOCITY.toPx() }
    SideEffect {
        sheet.settleDistancePx = distancePx
        sheet.flingVelocityPx = flingPx
    }
    return sheet
}
