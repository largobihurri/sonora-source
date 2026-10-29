package io.sonora.core.designsystem.translucent

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class TranslucentMorphTest {

    // A landscape screen with the Cover Flow's "Now Playing" capsule in its top-right corner.
    private val screen = MorphFrame(Rect(0f, 0f, 2400f, 1080f), radius = 0f)
    private val capsule = MorphFrame.capsule(Rect(2000f, 60f, 2340f, 204f))
    private val push = 1080f * TranslucentMorph.ARC_PUSH_FRACTION

    private fun assertSame(actual: MorphFrame, expected: MorphFrame) {
        assertThat(actual.rect.left).isWithin(0.01f).of(expected.rect.left)
        assertThat(actual.rect.top).isWithin(0.01f).of(expected.rect.top)
        assertThat(actual.rect.right).isWithin(0.01f).of(expected.rect.right)
        assertThat(actual.rect.bottom).isWithin(0.01f).of(expected.rect.bottom)
        assertThat(actual.radius).isWithin(0.01f).of(expected.radius)
    }

    @Test
    fun `a capsule's corners are half its height`() {
        assertThat(capsule.radius).isEqualTo(72f)
    }

    @Test
    fun `both routes start at the capsule and end at the full screen`() {
        for (route in MorphRoute.entries) {
            assertSame(TranslucentMorph.frame(route, 0f, capsule, screen, push), capsule)
            assertSame(TranslucentMorph.frame(route, 1f, capsule, screen, push), screen)
        }
    }

    @Test
    fun `progress outside 0 to 1 stays at the ends`() {
        for (route in MorphRoute.entries) {
            assertSame(TranslucentMorph.frame(route, -0.2f, capsule, screen, push), capsule)
            assertSame(TranslucentMorph.frame(route, 1.3f, capsule, screen, push), screen)
        }
    }

    @Test
    fun `opening, the bubble grows straight from the capsule, its corners easing toward the screen's`() {
        val half = TranslucentMorph.straight(0.5f, capsule, screen)
        assertThat(half.rect.center.x).isWithin(0.01f).of((capsule.rect.center.x + screen.rect.center.x) / 2f)
        assertThat(half.rect.center.y).isWithin(0.01f).of((capsule.rect.center.y + screen.rect.center.y) / 2f)
        assertThat(half.rect.width).isWithin(0.01f).of((capsule.rect.width + screen.rect.width) / 2f)
        assertThat(half.radius).isWithin(0.01f).of(36f)
        var last = TranslucentMorph.straight(0f, capsule, screen).rect.width
        for (step in 1..20) {
            val width = TranslucentMorph.straight(step / 20f, capsule, screen).rect.width
            assertThat(width).isAtLeast(last)
            last = width
        }
    }

    @Test
    fun `dismissed, the bubble is first pushed down while it shrinks`() {
        val start = TranslucentMorph.arc(1f, capsule, screen, push)
        val early = TranslucentMorph.arc(0.9f, capsule, screen, push)
        assertThat(early.rect.center.y).isGreaterThan(start.rect.center.y)
        assertThat(abs(early.rect.center.x - start.rect.center.x)).isLessThan(early.rect.center.y - start.rect.center.y)
        assertThat(early.rect.width).isLessThan(start.rect.width)
        assertThat(early.rect.height).isLessThan(start.rect.height)
    }

    @Test
    fun `dismissed, the bubble shrinks with the progress, as the opening grows with it`() {
        // T67.8: the size follows the finger evenly to the end (an eased shrink left the end of a drag moving nothing).
        for (step in 0..100) {
            val p = step / 100f
            val open = TranslucentMorph.openness(TranslucentMorph.arc(p, capsule, screen, push), capsule, screen)
            assertThat(open).isWithin(0.0001f).of(TranslucentMorph.openness(TranslucentMorph.straight(p, capsule, screen), capsule, screen))
            assertThat(open).isWithin(0.0001f).of(p)
        }
    }

    @Test
    fun `then it curves round to the right and up into the capsule, never back to the left`() {
        var lastX = screen.rect.center.x
        var lowest = screen.rect.center.y
        for (step in 19 downTo 0) {
            val centre = TranslucentMorph.arc(step / 20f, capsule, screen, push).rect.center
            assertThat(centre.x).isAtLeast(lastX - 0.01f)
            lastX = centre.x
            lowest = maxOf(lowest, centre.y)
        }
        // It dips well below where it started before rising to the capsule...
        assertThat(lowest).isGreaterThan(screen.rect.center.y + push / 2f)
        // ...and arrives from below, climbing.
        val nearlyThere = TranslucentMorph.arc(0.05f, capsule, screen, push).rect.center
        assertThat(nearlyThere.y).isGreaterThan(capsule.rect.center.y)
        assertThat(abs(nearlyThere.x - capsule.rect.center.x)).isLessThan(nearlyThere.y - capsule.rect.center.y)
    }

    @Test
    fun `the arc is a curve, not the straight line between its ends`() {
        val start = screen.rect.center
        val end = capsule.rect.center
        val middle = TranslucentMorph.arcCentre(0.5f, start, end, push)
        val chord = end - start
        // Distance from the chord: the cross product over the chord's length.
        val away = abs(chord.x * (middle.y - start.y) - chord.y * (middle.x - start.x)) / chord.getDistance()
        assertThat(away).isGreaterThan(push / 2f)
        assertThat(TranslucentMorph.arcCentre(0f, start, end, push)).isEqualTo(start)
        val arrived = TranslucentMorph.arcCentre(1f, start, end, push)
        assertThat(arrived.x).isWithin(0.01f).of(end.x)
        assertThat(arrived.y).isWithin(0.01f).of(end.y)
    }

    @Test
    fun `corners never round past half the bubble's shorter side`() {
        val round = MorphFrame(screen.rect, radius = 2000f)
        for (route in MorphRoute.entries) {
            for (step in 0..10) {
                val frame = TranslucentMorph.frame(route, step / 10f, capsule, round, push)
                assertThat(frame.radius).isAtMost(frame.rect.minDimension / 2f + 0.01f)
            }
        }
    }

    @Test
    fun `a bubble at rest open is dismissed along the arc and one at rest closed opens straight`() {
        assertThat(MorphRoute.follow(MorphRoute.Straight, 1f)).isEqualTo(MorphRoute.Arc)
        assertThat(MorphRoute.follow(MorphRoute.Arc, 0f)).isEqualTo(MorphRoute.Straight)
    }

    @Test
    fun `on the way, the bubble keeps its route so it never jumps`() {
        assertThat(MorphRoute.follow(MorphRoute.Straight, 0.6f)).isEqualTo(MorphRoute.Straight)
        assertThat(MorphRoute.follow(MorphRoute.Arc, 0.6f)).isEqualTo(MorphRoute.Arc)
    }

    @Test
    fun `Now Playing's controls are only on a large card`() {
        assertThat(TranslucentMorph.contentAlpha(0f)).isEqualTo(0f)
        assertThat(TranslucentMorph.contentAlpha(0.5f)).isEqualTo(0f)
        assertThat(TranslucentMorph.contentAlpha(0.8f)).isIn(Range.open(0f, 1f))
        assertThat(TranslucentMorph.contentAlpha(0.95f)).isIn(Range.open(0f, 1f))
        assertThat(TranslucentMorph.contentAlpha(1f)).isEqualTo(1f)
    }

    @Test
    fun `Now Playing's backdrop is there before its controls, so the bubble never shows them on bare translucency`() {
        for (step in 0..100) {
            val open = step / 100f
            assertThat(TranslucentMorph.backdropAlpha(open)).isAtLeast(TranslucentMorph.contentAlpha(open))
        }
        assertThat(TranslucentMorph.backdropAlpha(0.3f)).isEqualTo(0f)
        assertThat(TranslucentMorph.backdropAlpha(0.9f)).isEqualTo(1f)
    }

    @Test
    fun `the capsule hands over near its own size`() {
        assertThat(TranslucentMorph.capsuleAlpha(0f)).isEqualTo(1f)
        assertThat(TranslucentMorph.capsuleAlpha(0.08f)).isIn(Range.open(0f, 1f))
        assertThat(TranslucentMorph.capsuleAlpha(0.2f)).isEqualTo(0f)
        assertThat(TranslucentMorph.capsuleAlpha(1f)).isEqualTo(0f)
    }

    @Test
    fun `openness is 0 in the capsule, 1 at full screen, and grows with the bubble on both routes`() {
        for (route in MorphRoute.entries) {
            assertThat(TranslucentMorph.openness(TranslucentMorph.frame(route, 0f, capsule, screen, push), capsule, screen)).isWithin(0.0001f).of(0f)
            assertThat(TranslucentMorph.openness(TranslucentMorph.frame(route, 1f, capsule, screen, push), capsule, screen)).isWithin(0.0001f).of(1f)
            var last = -1f
            for (step in 0..100) {
                val open = TranslucentMorph.openness(TranslucentMorph.frame(route, step / 100f, capsule, screen, push), capsule, screen)
                assertThat(open).isIn(Range.closed(0f, 1f))
                assertThat(open).isAtLeast(last - 0.0001f)
                last = open
            }
        }
    }

    @Test
    fun `bubble frames move together`() {
        val bubble = TranslucentMorph.straight(0.5f, capsule, screen)
        val moved = BubbleFrames(bubble, capsule, screen).translate(Offset(10f, -20f))
        assertThat(moved.bubble).isEqualTo(MorphFrame(bubble.rect.translate(10f, -20f), bubble.radius))
        assertThat(moved.capsule).isEqualTo(MorphFrame(capsule.rect.translate(10f, -20f), capsule.radius))
        assertThat(moved.screen).isEqualTo(MorphFrame(screen.rect.translate(10f, -20f), screen.radius))
    }

    @Test
    fun `a full-screen frame draws the layer as laid out`() {
        val layer = TranslucentMorph.layer(screen, Size(2400f, 1080f))
        assertThat(layer.scale).isEqualTo(1f)
        assertThat(layer.translation).isEqualTo(Offset.Zero)
        assertThat(layer.clip).isEqualTo(screen.rect)
        assertThat(layer.radius).isEqualTo(0f)
    }

    @Test
    fun `a smaller frame scales the layer to cover it and clips it to the frame`() {
        val size = Size(2400f, 1080f)
        val frame = MorphFrame(Rect(1200f, 540f, 2400f, 900f), radius = 40f)
        val layer = TranslucentMorph.layer(frame, size)
        // Scaled about the layer's centre, then moved: the clip lands exactly on the frame.
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun onScreen(local: Offset) = centre + (local - centre) * layer.scale + layer.translation
        val topLeft = onScreen(layer.clip.topLeft)
        val bottomRight = onScreen(layer.clip.bottomRight)
        assertThat(topLeft.x).isWithin(0.01f).of(frame.rect.left)
        assertThat(topLeft.y).isWithin(0.01f).of(frame.rect.top)
        assertThat(bottomRight.x).isWithin(0.01f).of(frame.rect.right)
        assertThat(bottomRight.y).isWithin(0.01f).of(frame.rect.bottom)
        assertThat(layer.radius * layer.scale).isWithin(0.01f).of(frame.radius)
        // Covered, never letterboxed: the clip stays within the layer.
        assertThat(layer.clip.width).isAtMost(size.width + 0.01f)
        assertThat(layer.clip.height).isAtMost(size.height + 0.01f)
        assertThat(layer.scale).isEqualTo(0.5f)
    }

    @Test
    fun `the translucency is liquid up to a tenth below its limit, flat from the limit, and hands over smoothly between`() {
        assertThat(TranslucentMorph.liquidShare(openness = 0f, limit = 0.3f)).isEqualTo(1f)
        assertThat(TranslucentMorph.liquidShare(openness = 0.2f, limit = 0.3f)).isEqualTo(1f)
        assertThat(TranslucentMorph.liquidShare(openness = 0.25f, limit = 0.3f)).isWithin(0.0001f).of(0.5f)
        assertThat(TranslucentMorph.liquidShare(openness = 0.3f, limit = 0.3f)).isEqualTo(0f)
        assertThat(TranslucentMorph.liquidShare(openness = 1f, limit = 0.3f)).isEqualTo(0f)
        var last = 1f
        for (step in 0..100) {
            val share = TranslucentMorph.liquidShare(step / 100f, limit = 0.3f)
            assertThat(share).isAtMost(last)
            last = share
        }
    }

    @Test
    fun `a limit of the whole card keeps the translucency liquid at every size`() {
        for (step in 0..100) assertThat(TranslucentMorph.liquidShare(step / 100f, limit = 1f)).isEqualTo(1f)
    }

    @Test
    fun `on a large screen the bubble is liquid only at the very start of the way, flat from a tenth on`() {
        val limit = TranslucentBubble.LARGE_LIQUID_LIMIT

        assertThat(TranslucentMorph.liquidShare(openness = 0f, limit = limit)).isEqualTo(1f)
        assertThat(TranslucentMorph.liquidShare(openness = 0.1f, limit = limit)).isEqualTo(0f)
        assertThat(TranslucentMorph.liquidShare(openness = 0.5f, limit = limit)).isEqualTo(0f)
    }
}
