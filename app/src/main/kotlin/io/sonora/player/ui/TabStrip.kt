package io.sonora.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The strip's spring stiffness: with no bounce, a tap from the first tab to the fourth settles in about 0.3 s. */
private const val TAB_STRIP_STIFFNESS = 420f

/** A thousandth of a screen width: below what a 120 Hz frame can show. */
private const val TAB_STRIP_THRESHOLD = 0.001f

private val TabStripSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = TAB_STRIP_STIFFNESS,
    visibilityThreshold = TAB_STRIP_THRESHOLD,
)

/**
 * The tab screens as one horizontal strip: [position] is a fractional tab index (0 = first tab) that both the screens
 * ([TabSlideHost]) and the tab bar's selection pill ([TranslucentTabBar]) read, so they never disagree. Tapping a tab
 * springs the strip there, passing through the tabs in between; scrubbing the tab bar moves it with the finger
 * ([drag]) and [release] lets the host settle it on the chosen tab, keeping the finger's speed.
 */
@Stable
class TabStripState(initialIndex: Int, val count: Int) {
    private val last = (count - 1).coerceAtLeast(0).toFloat()
    private val offset = Animatable(initialIndex.toFloat().coerceIn(0f, last), TAB_STRIP_THRESHOLD).apply {
        updateBounds(0f, last)
    }

    // While the finger drives the strip, [position] reads this instead of the animation. It stays in charge after the
    // release until the settle spring takes over from exactly there (no frame jumps back to a stale animation value).
    private var fingerPosition by mutableFloatStateOf(offset.value)
    private var followsFinger by mutableStateOf(false)
    private var releaseVelocity = 0f

    /** Where the strip sits, in tabs. */
    val position: Float get() = if (followsFinger) fingerPosition else offset.value

    /** The tab the strip is moving towards (the shown tab at rest; the end it is dragged towards while scrubbing). */
    var heading by mutableIntStateOf(initialIndex)
        private set

    /** A finger is scrubbing the tab bar; the host does not animate meanwhile. */
    var dragging by mutableStateOf(false)
        private set

    /**
     * How many scrubs were released. The host settles on each one: a scrub that starts and ends between two frames
     * leaves [dragging] false as it was, but still changes this.
     */
    var releases by mutableIntStateOf(0)
        private set

    /** Puts the strip at [newPosition] under the finger (no animation, no allocation: called per pointer event). */
    fun drag(newPosition: Float) {
        val clamped = newPosition.coerceIn(0f, last)
        val previous = position
        if (clamped > previous) heading = last.toInt() else if (clamped < previous) heading = 0
        fingerPosition = clamped
        followsFinger = true
        dragging = true
    }

    /** The finger lifted moving at [velocity] tabs/s; the host then settles the strip on the selected tab. */
    fun release(velocity: Float) {
        releaseVelocity = velocity
        dragging = false
        releases++
    }

    /**
     * Moves the strip to tab [index]: a no-bounce spring that starts with the release speed after a scrub, or with the
     * current speed when a new tap interrupts a slide; at once with [reducedMotion].
     */
    suspend fun settle(index: Int, reducedMotion: Boolean) {
        val target = index.toFloat().coerceIn(0f, last)
        heading = target.toInt()
        var velocity = offset.velocity
        if (followsFinger) {
            velocity = releaseVelocity
            releaseVelocity = 0f
            offset.snapTo(fingerPosition)
            followsFinger = false
        }
        if (reducedMotion) {
            offset.snapTo(target)
        } else {
            val start = stripReleaseVelocity(offset.value, target.toInt(), velocity, TAB_STRIP_STIFFNESS)
            offset.animateTo(target, TabStripSpring, initialVelocity = start)
        }
    }
}

/** A [TabStripState] for [count] tabs starting on [initialIndex] (e.g. the restored selected tab). */
@Composable
fun rememberTabStripState(initialIndex: Int, count: Int): TabStripState =
    remember(count) { TabStripState(initialIndex, count) }
