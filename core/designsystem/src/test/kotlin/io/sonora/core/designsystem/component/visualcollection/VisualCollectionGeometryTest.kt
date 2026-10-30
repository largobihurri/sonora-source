package io.sonora.core.designsystem.component.visualcollection

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class VisualCollectionGeometryTest {

    private val geometry = VisualCollectionGeometry(coverSize = 600f)

    @Test
    fun `the centre cover faces the viewer, full size, on top`() {
        val centre = geometry.transform(0f)

        assertThat(centre.translationX).isEqualTo(0f)
        assertThat(centre.translationY).isEqualTo(0f)
        assertThat(centre.rotationY).isEqualTo(0f)
        assertThat(centre.scale).isEqualTo(1f)
        assertThat(centre.zIndex).isEqualTo(0f)
    }

    @Test
    fun `side covers sit at the full angle, smaller, past the centre gap, then a tighter step apart`() {
        val first = geometry.transform(1f)
        val second = geometry.transform(2f)
        val third = geometry.transform(3f)

        assertThat(abs(first.rotationY)).isEqualTo(geometry.sideAngle)
        assertThat(second.rotationY).isEqualTo(first.rotationY)
        assertThat(first.scale).isEqualTo(geometry.sideScale)
        assertThat(first.translationX).isEqualTo(geometry.centreGap)
        assertThat(second.translationX - first.translationX).isWithin(0.001f).of(geometry.sideStep)
        assertThat(third.translationX - second.translationX).isWithin(0.001f).of(geometry.sideStep)
        assertThat(geometry.sideStep).isLessThan(geometry.centreGap)
    }

    @Test
    fun `left and right covers mirror each other and face the centre`() {
        for (offset in listOf(0.25f, 0.5f, 1f, 2.5f, 7f)) {
            val left = geometry.transform(-offset)
            val right = geometry.transform(offset)
            assertThat(left.translationX).isEqualTo(-right.translationX)
            assertThat(left.rotationY).isEqualTo(-right.rotationY)
            assertThat(left.scale).isEqualTo(right.scale)
            assertThat(left.translationY).isEqualTo(right.translationY)
            assertThat(left.zIndex).isEqualTo(right.zIndex)
        }
        // Rotation turns the face toward the centre: the two sides turn opposite ways.
        assertThat(geometry.transform(-1f).rotationY).isGreaterThan(0f)
        assertThat(geometry.transform(1f).rotationY).isLessThan(0f)
    }

    @Test
    fun `a cover moving away from the centre turns, shrinks, slides out and sinks below monotonically`() {
        var previous = geometry.transform(0f)
        var offset = 0.05f
        while (offset <= 6f) {
            val next = geometry.transform(offset)
            assertThat(abs(next.rotationY)).isAtLeast(abs(previous.rotationY))
            assertThat(next.scale).isAtMost(previous.scale)
            assertThat(next.translationX).isGreaterThan(previous.translationX)
            assertThat(next.zIndex).isLessThan(previous.zIndex)
            previous = next
            offset += 0.05f
        }
    }

    @Test
    fun `smaller covers keep their bottom edge on the centre cover's floor`() {
        val side = geometry.transform(2f)

        // Scaled about its centre, a cover's bottom rises by half the lost height; translationY puts it back.
        assertThat(side.translationY).isWithin(0.001f).of(geometry.coverSize * (1f - side.scale) / 2f)
    }

    @Test
    fun `the visible window spans the radius around the position, clamped to the list`() {
        assertThat(VisualCollectionGeometry.window(position = 10f, count = 100, radius = 8)).isEqualTo(2..18)
        assertThat(VisualCollectionGeometry.window(position = 10.4f, count = 100, radius = 8)).isEqualTo(2..19)
        assertThat(VisualCollectionGeometry.window(position = 0f, count = 100, radius = 8)).isEqualTo(0..8)
        assertThat(VisualCollectionGeometry.window(position = 98.6f, count = 100, radius = 8)).isEqualTo(90..99)
        assertThat(VisualCollectionGeometry.window(position = 0f, count = 3, radius = 8)).isEqualTo(0..2)
        assertThat(VisualCollectionGeometry.window(position = 0f, count = 0, radius = 8).isEmpty()).isTrue()
    }

    @Test
    fun `a tap hits the frontmost cover under the finger`() {
        val window = 0..20
        // At position 5, the centre cover spans half its size either side of the middle.
        assertThat(geometry.hitTest(x = 0f, position = 5f, window = window)).isEqualTo(5)
        assertThat(geometry.hitTest(x = 250f, position = 5f, window = window)).isEqualTo(5)
        // Right of the centre cover: the first side cover, then farther ones on their visible slivers.
        val first = geometry.transform(1f)
        assertThat(geometry.hitTest(x = first.translationX, position = 5f, window = window)).isEqualTo(6)
        val third = geometry.transform(3f)
        val sliver = third.translationX + geometry.projectedHalfWidth(3f) - 1f
        assertThat(geometry.hitTest(x = sliver, position = 5f, window = window)).isEqualTo(8)
        assertThat(geometry.hitTest(x = -sliver, position = 5f, window = window)).isEqualTo(2)
    }

    @Test
    fun `a tap beyond every cover hits nothing`() {
        assertThat(geometry.hitTest(x = 5_000f, position = 5f, window = 0..20)).isNull()
        assertThat(geometry.hitTest(x = 0f, position = 0f, window = IntRange.EMPTY)).isNull()
    }

    @Test
    fun `the centre cover is the smaller of 70 percent of the height and 47 percent of the width`() {
        assertThat(VisualCollectionGeometry.coverSize(screenWidth = 2000f, screenHeight = 800f)).isWithin(0.001f).of(560f)
        assertThat(VisualCollectionGeometry.coverSize(screenWidth = 1000f, screenHeight = 2000f)).isWithin(0.001f).of(470f)
    }

    @Test
    fun `a flipped cover's back is the smaller of 90 percent of the height and 60 percent of the width`() {
        assertThat(VisualCollectionGeometry.backSize(screenWidth = 2000f, screenHeight = 800f)).isWithin(0.001f).of(720f)
        assertThat(VisualCollectionGeometry.backSize(screenWidth = 1000f, screenHeight = 2000f)).isWithin(0.001f).of(600f)
    }

    @Test
    fun `the card on show grows from the centre cover to the back as the flip goes`() {
        // 2000 x 800: a 560 cover, a 720 back.
        assertThat(VisualCollectionGeometry.cardWidth(2000f, 800f, flip = 0f)).isWithin(0.001f).of(560f)
        assertThat(VisualCollectionGeometry.cardWidth(2000f, 800f, flip = 0.5f)).isWithin(0.001f).of(640f)
        assertThat(VisualCollectionGeometry.cardWidth(2000f, 800f, flip = 1f)).isWithin(0.001f).of(720f)
        // An overshooting animation never measures past either card.
        assertThat(VisualCollectionGeometry.cardWidth(2000f, 800f, flip = 1.2f)).isWithin(0.001f).of(720f)
        assertThat(VisualCollectionGeometry.cardWidth(2000f, 800f, flip = -0.1f)).isWithin(0.001f).of(560f)
    }

    @Test
    fun `a corner caption fills the room beside the card on show, within its floor and cap`() {
        // 2000 wide, a 560 card: 720 either side, less a 40 edge, a 10 cutout and a 30 gap.
        assertThat(VisualCollectionGeometry.captionMaxWidth(2000f, 560f, edge = 40f, cutout = 10f, gap = 30f, floor = 100f, cap = 900f))
            .isWithin(0.001f).of(640f)
        assertThat(VisualCollectionGeometry.captionMaxWidth(2000f, 560f, edge = 40f, cutout = 10f, gap = 30f, floor = 100f, cap = 400f))
            .isEqualTo(400f)
        // A card that leaves less room than the floor still keeps the caption's floor (its artwork).
        assertThat(VisualCollectionGeometry.captionMaxWidth(900f, 800f, edge = 40f, cutout = 0f, gap = 30f, floor = 200f, cap = 400f))
            .isEqualTo(200f)
    }

    @Test
    fun `a caption above the card on show takes its cap, a caption beside it the room beside it`() {
        // A tablet upright: the card's top is far below the caption, so the narrow room beside it does not count.
        assertThat(VisualCollectionGeometry.captionMaxWidth(1800f, 1044f, edge = 50f, cutout = 0f, gap = 20f, floor = 120f, cap = 700f, captionBottom = 170f, cardTop = 650f))
            .isEqualTo(700f)
        // Turned sideways the card reaches the caption's band: the room beside the card holds again.
        assertThat(VisualCollectionGeometry.captionMaxWidth(2880f, 1500f, edge = 50f, cutout = 0f, gap = 20f, floor = 120f, cap = 700f, captionBottom = 170f, cardTop = 100f))
            .isWithin(0.001f).of(620f)
    }

    @Test
    fun `the card on show rises to the middle and grows into its back as it turns`() {
        // A 400 cover at 300 on a 2000 high screen, its 800 back centred: from 300 to 600.
        assertThat(VisualCollectionGeometry.cardTop(2000f, coverTop = 300f, cover = 400f, back = 800f, flip = 0f)).isWithin(0.001f).of(300f)
        assertThat(VisualCollectionGeometry.cardTop(2000f, coverTop = 300f, cover = 400f, back = 800f, flip = 1f)).isWithin(0.001f).of(600f)
    }

    @Test
    fun `only the cutout on the caption's own end side counts`() {
        assertThat(VisualCollectionGeometry.endInset(left = 100f, right = 0f, rtl = false)).isEqualTo(0f)
        assertThat(VisualCollectionGeometry.endInset(left = 0f, right = 100f, rtl = false)).isEqualTo(100f)
        assertThat(VisualCollectionGeometry.endInset(left = 100f, right = 0f, rtl = true)).isEqualTo(100f)
        assertThat(VisualCollectionGeometry.endInset(left = 0f, right = 100f, rtl = true)).isEqualTo(0f)
    }

    /** The Realme (2376 x 1080 sideways, density 540): the caption's width in dp, a ~100 px cutout [near] it or not. */
    private fun realmeCaption(flip: Float, near: Boolean): Float {
        val dp = 540f / 160f
        return VisualCollectionGeometry.captionMaxWidth(
            screenWidth = 2376f,
            cardWidth = VisualCollectionGeometry.cardWidth(2376f, 1080f, flip),
            edge = 20f * dp,
            cutout = if (near) 100f else 0f,
            gap = 8f * dp,
            floor = 48f * dp,
            cap = 280f * dp,
        ) / dp
    }

    @Test
    fun `on a 2376 x 1080 phone sideways the caption gets the whole gap beside the cover`() {
        // A 756 px cover: (2376 - 756) / 2 = 810 px, less 67.5 px padding and a 27 px gap = 715.5 px.
        assertThat(realmeCaption(flip = 0f, near = false)).isWithin(0.01f).of(212f)
        // The cutout on the caption's side takes its 100 px too.
        assertThat(realmeCaption(flip = 0f, near = true)).isWithin(0.01f).of(615.5f / 3.375f)
    }

    @Test
    fun `on a 2376 x 1080 phone sideways the caption stays beside the flipped back card`() {
        // A 972 px back card: (2376 - 972) / 2 = 702 px, less 67.5 px padding and a 27 px gap = 607.5 px.
        assertThat(realmeCaption(flip = 1f, near = false)).isWithin(0.01f).of(180f)
        assertThat(realmeCaption(flip = 1f, near = true)).isWithin(0.01f).of(507.5f / 3.375f)
        // Halfway through the flip it is halfway between the two.
        assertThat(realmeCaption(flip = 0.5f, near = false)).isWithin(0.01f).of(196f)
    }

    // The Xiaomi Pad 6 upright, in dp: a 720 x 1152 window.
    private val padWidth = 720f
    private val padHeight = 1152f

    @Test
    fun `a phone or a sideways window keeps the old cover size and the top-anchored place`() {
        val phone = VisualCollectionGeometry.frame(screenWidth = 2376f, screenHeight = 1080f, large = false)
        assertThat(phone.coverSize).isEqualTo(VisualCollectionGeometry.coverSize(2376f, 1080f))
        assertThat(phone.coverTop).isWithin(0.001f).of(1080f * 0.045f)

        // A large window turned sideways is as wide as a phone: nothing changes there either.
        val sideways = VisualCollectionGeometry.frame(screenWidth = 1152f, screenHeight = 720f, large = true)
        assertThat(sideways.coverSize).isEqualTo(VisualCollectionGeometry.coverSize(1152f, 720f))
        assertThat(sideways.coverTop).isWithin(0.001f).of(720f * 0.045f)
    }

    @Test
    fun `a large window upright gets bigger covers than the phone rule gives`() {
        val frame = VisualCollectionGeometry.frame(padWidth, padHeight, large = true)
        assertThat(frame.coverSize).isGreaterThan(VisualCollectionGeometry.coverSize(padWidth, padHeight))
        assertThat(frame.coverSize).isWithin(0.001f).of(padWidth * 0.58f)
    }

    @Test
    fun `a large window upright centres the covers and their reflection between its top and the caption`() {
        val frame = VisualCollectionGeometry.frame(padWidth, padHeight, large = true, captionReserve = 80f)
        val reflectionEnd = frame.coverTop + frame.coverSize * (1f + VisualCollectionGeometry.REFLECTION_FRACTION)
        val above = frame.coverTop
        val below = (padHeight - 80f) - reflectionEnd
        assertThat(above).isWithin(0.5f).of(below)
        // Far lower than the phone rule's 4.5 percent: the covers no longer hug the top.
        assertThat(frame.coverTop).isGreaterThan(padHeight * 0.1f)
    }

    @Test
    fun `a square large window is not upright and a short upright one keeps its covers inside 70 percent of its height`() {
        val square = VisualCollectionGeometry.frame(screenWidth = 800f, screenHeight = 800f, large = true)
        assertThat(square.coverSize).isEqualTo(VisualCollectionGeometry.coverSize(800f, 800f))
        val narrow = VisualCollectionGeometry.frame(screenWidth = 600f, screenHeight = 640f, large = true)
        assertThat(narrow.coverSize).isAtMost(640f * 0.70f)
        assertThat(narrow.coverTop).isAtLeast(0f)
    }

    @Test
    fun `the card width follows the cover the frame chose`() {
        val frame = VisualCollectionGeometry.frame(padWidth, padHeight, large = true)
        assertThat(VisualCollectionGeometry.cardWidth(padWidth, padHeight, flip = 0f, cover = frame.coverSize))
            .isWithin(0.001f).of(frame.coverSize)
        // The back card (60 percent of the width) is never smaller than the cover it grows from.
        assertThat(VisualCollectionGeometry.cardWidth(padWidth, padHeight, flip = 1f, cover = frame.coverSize))
            .isAtLeast(frame.coverSize)
    }
}
