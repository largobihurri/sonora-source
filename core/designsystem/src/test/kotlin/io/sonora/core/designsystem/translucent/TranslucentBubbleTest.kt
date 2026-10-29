package io.sonora.core.designsystem.translucent

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TranslucentBubbleTest {

    private val size = Size(2400f, 1080f)
    private val capsule = MorphFrame.capsule(Rect(2000f, 60f, 2340f, 204f))
    private val screen = MorphFrame(Rect(0f, 0f, 2400f, 1080f), radius = 0f)
    private val push = size.height * TranslucentMorph.ARC_PUSH_FRACTION
    private var progress = 0f
    private var reduced = false
    private var placed = true
    private var sheetRadius = 0f
    private val bubble = TranslucentBubble({ progress }, { capsule.takeIf { placed } }, { reduced }, { sheetRadius })

    private fun at(p: Float): MorphFrame? {
        progress = p
        return bubble.frame(size)
    }

    @Test
    fun `it opens straight out of the capsule`() {
        at(0f)
        assertThat(at(0.5f)).isEqualTo(TranslucentMorph.straight(0.5f, capsule, screen))
    }

    @Test
    fun `once open, a dismissal takes the pushed-down arc back into the capsule`() {
        at(0f)
        at(0.5f)
        at(1f)
        assertThat(at(0.6f)).isEqualTo(TranslucentMorph.arc(0.6f, capsule, screen, push))
        assertThat(at(0.2f)).isEqualTo(TranslucentMorph.arc(0.2f, capsule, screen, push))
    }

    @Test
    fun `a dismissal caught and opened again stays on its arc, and the next opening is straight again`() {
        at(1f)
        at(0.4f)
        assertThat(at(0.7f)).isEqualTo(TranslucentMorph.arc(0.7f, capsule, screen, push))
        at(0f)
        assertThat(at(0.3f)).isEqualTo(TranslucentMorph.straight(0.3f, capsule, screen))
    }

    @Test
    fun `with reduced motion it stays full screen, everything shown, for the host to cross-fade`() {
        reduced = true
        assertThat(at(0f)).isEqualTo(screen)
        assertThat(at(0.4f)).isEqualTo(screen)
        assertThat(bubble.contentAlpha(size)).isEqualTo(1f)
        assertThat(bubble.backdropAlpha(size)).isEqualTo(1f)
    }

    @Test
    fun `without a capsule there is no bubble`() {
        val none = TranslucentBubble({ 0.5f }, { null })
        assertThat(none.frame(size)).isNull()
    }

    @Test
    fun `its frames hold the bubble with the capsule and the screen it morphs between`() {
        progress = 0.5f
        assertThat(bubble.frames(size)).isEqualTo(BubbleFrames(TranslucentMorph.straight(0.5f, capsule, screen), capsule, screen))
    }

    @Test
    fun `a capsule that left while Now Playing rested open still sends the dismissal along the arc`() {
        at(0.5f)
        placed = false // the mini player leaves the screen under an open Now Playing
        assertThat(at(1f)).isNull()
        placed = true // and comes back as it is dismissed
        assertThat(at(0.6f)).isEqualTo(TranslucentMorph.arc(0.6f, capsule, screen, push))
    }

    @Test
    fun `dismissed, the controls start fading as soon as the card shrinks`() {
        at(1f)
        assertThat(bubble.contentAlpha(size)).isEqualTo(1f)
        assertThat(at(0.95f).let { bubble.contentAlpha(size) }).isIn(Range.open(0f, 1f))
        assertThat(at(0.95f).let { bubble.backdropAlpha(size) }).isEqualTo(1f)
    }

    @Test
    fun `dismissed, Now Playing follows the bubble's size, its backdrop always under its controls`() {
        at(1f)
        for (step in 100 downTo 0) {
            val frames = at(step / 100f).let { bubble.frames(size)!! }
            val open = TranslucentMorph.openness(frames.bubble, frames.capsule, frames.screen)
            assertThat(bubble.contentAlpha(size)).isWithin(0.0001f).of(TranslucentMorph.contentAlpha(open))
            assertThat(bubble.backdropAlpha(size)).isWithin(0.0001f).of(TranslucentMorph.backdropAlpha(open))
            assertThat(bubble.backdropAlpha(size)).isAtLeast(bubble.contentAlpha(size))
        }
    }

    @Test
    fun `dismissed into the portrait mini player, the card is translucent before it is about square`() {
        val portrait = Size(1080f, 2400f)
        val miniPlayer = MorphFrame.capsule(Rect(48f, 1980f, 1032f, 2148f))
        val card = TranslucentBubble({ progress }, { miniPlayer })
        progress = 1f
        card.frames(portrait)
        var squareAt = -1f
        for (step in 100 downTo 0) {
            progress = step / 100f
            val rect = card.frame(portrait)!!.rect
            if (rect.height > rect.width * 1.1f) {
                // Still clearly taller than wide: the controls must already be gone by the time it is about square.
                continue
            }
            squareAt = progress
            assertThat(card.contentAlpha(portrait)).isEqualTo(0f)
            assertThat(card.backdropAlpha(portrait)).isAtMost(0.1f)
            break
        }
        assertThat(squareAt).isGreaterThan(0f)
        // And the controls are gone while the card is still clearly taller than wide.
        progress = 0.55f
        val tall = card.frame(portrait)!!.rect
        assertThat(tall.height).isGreaterThan(tall.width * 1.3f)
        assertThat(card.contentAlpha(portrait)).isEqualTo(0f)
    }

    @Test
    fun `before the host is laid out, Now Playing follows the progress instead of showing at once`() {
        at(0f)
        at(0.2f)
        assertThat(bubble.contentAlpha(Size.Zero)).isEqualTo(TranslucentMorph.contentAlpha(0.2f))
        assertThat(bubble.backdropAlpha(Size.Zero)).isEqualTo(TranslucentMorph.backdropAlpha(0.2f))
        at(1f)
        at(0.8f)
        assertThat(bubble.contentAlpha(Size.Zero)).isEqualTo(TranslucentMorph.contentAlpha(0.8f))
        assertThat(bubble.backdropAlpha(Size.Zero)).isEqualTo(TranslucentMorph.backdropAlpha(0.8f))
    }

    @Test
    fun `dismissed, the card is rounded like the sheet from the first drag frame and square again at rest`() {
        sheetRadius = 77f
        assertThat(at(1f)!!.radius).isEqualTo(0f)
        assertThat(at(0.99f)!!.radius).isWithin(1f).of(77f)
        assertThat(at(1f)!!.radius).isEqualTo(0f)
    }

    @Test
    fun `the bubble's translucency hands over to the capsule as it lands and is gone once open`() {
        assertThat(at(1f).let { bubble.translucentAlpha() }).isEqualTo(0f)
        assertThat(at(0.5f).let { bubble.translucentAlpha() }).isEqualTo(1f)
        assertThat(at(0.08f).let { bubble.translucentAlpha() }).isIn(Range.open(0f, 1f))
        assertThat(at(0.01f).let { bubble.translucentAlpha() }).isEqualTo(0f)
        assertThat(at(0f).let { bubble.translucentAlpha() }).isEqualTo(0f)
        for (step in 0..99) {
            val p = step / 100f
            at(p)
            assertThat(bubble.translucentAlpha() + TranslucentMorph.capsuleAlpha(p)).isWithin(0.0001f).of(1f)
        }
    }

    @Test
    fun `the bubble's translucency is not drawn under a Now Playing backdrop that already covers it`() {
        // T10: the translucency (a blur and a refraction shader) kept being drawn at nearly the whole screen under an
        // opaque backdrop.
        at(0f)
        assertThat(at(0.5f).let { bubble.translucentAlpha(size) }).isEqualTo(1f)
        assertThat(at(0.84f).let { bubble.translucentAlpha(size) }).isEqualTo(1f)
        assertThat(at(0.9f).let { bubble.translucentAlpha(size) }).isEqualTo(0f)
        at(1f)
        assertThat(at(0.9f).let { bubble.translucentAlpha(size) }).isEqualTo(0f) // dismissed: the card is as open as that
        assertThat(at(0.5f).let { bubble.translucentAlpha(size) }).isEqualTo(1f)
    }

    @Test
    fun `translucency hides exactly when the backdrop is fully opaque, otherwise as the capsule hand-over has it`() {
        at(0f)
        for (step in 0..100) {
            at(step / 100f)
            val covered = bubble.backdropAlpha(size) >= 1f
            if (covered) {
                assertThat(bubble.translucentAlpha(size)).isEqualTo(0f)
            } else {
                assertThat(bubble.translucentAlpha(size)).isEqualTo(bubble.translucentAlpha())
            }
        }
    }

    @Test
    fun `on a tablet the bubble's translucency is liquid while it is small and a cheap flat one once it is large`() {
        val tablet = TranslucentBubble({ progress }, { capsule }, liquidLimit = 0.3f)
        progress = 0f

        progress = 0.15f // a small card: all of it liquid
        assertThat(tablet.flatAlpha(size)).isEqualTo(0f)
        assertThat(tablet.liquidAlpha(size)).isEqualTo(tablet.translucentAlpha(size))
        progress = 0.25f // handing over: both
        assertThat(tablet.liquidAlpha(size)).isIn(Range.open(0f, tablet.translucentAlpha(size)))
        assertThat(tablet.flatAlpha(size)).isIn(Range.open(0f, tablet.translucentAlpha(size)))
        progress = 0.5f // a big card: the flat translucency alone (a refraction shader over most of the screen cost 30 ms a frame)
        assertThat(tablet.liquidAlpha(size)).isEqualTo(0f)
        assertThat(tablet.flatAlpha(size)).isEqualTo(tablet.translucentAlpha(size))
    }

    @Test
    fun `liquid and flat translucency together are exactly the translucency, so the card never dips or doubles in opacity`() {
        val tablet = TranslucentBubble({ progress }, { capsule }, liquidLimit = 0.3f)
        for (step in 0..100) {
            progress = step / 100f
            assertThat(tablet.liquidAlpha(size) + tablet.flatAlpha(size)).isWithin(0.0001f).of(tablet.translucentAlpha(size))
        }
    }

    @Test
    fun `with no limit (a phone) the translucency is liquid at every size and there is no flat one`() {
        for (step in 0..100) {
            progress = step / 100f
            assertThat(bubble.flatAlpha(size)).isEqualTo(0f)
            assertThat(bubble.liquidAlpha(size)).isEqualTo(bubble.translucentAlpha(size))
        }
    }

    @Test
    fun `before the host is laid out the translucency follows the hand-over alone`() {
        at(0f)
        at(0.5f)
        assertThat(bubble.translucentAlpha(Size.Zero)).isEqualTo(bubble.translucentAlpha())
    }

    /** A bubble of its own, driven by [p], that took [route]: opened from rest closed, or dismissed from rest open. */
    private class Driven(capsule: MorphFrame, route: MorphRoute, radius: Float) {
        var p = if (route == MorphRoute.Arc) 1f else 0f
        val bubble = TranslucentBubble({ p }, { capsule }, screenRadius = { radius })
    }

    @Test
    fun `opening shows what a dismissal shows at the same bubble size, played backwards`() {
        val scenes = listOf(
            size to capsule,
            Size(1080f, 2400f) to MorphFrame.capsule(Rect(48f, 1980f, 1032f, 2148f)),
        )
        for ((host, from) in scenes) {
            val opening = Driven(from, MorphRoute.Straight, 77f).also { it.bubble.frames(host) }
            val dismissal = Driven(from, MorphRoute.Arc, 77f).also { it.bubble.frames(host) }
            for (step in 1..99) {
                val open = step / 100f
                opening.p = open // straight: the bubble's openness is its progress
                dismissal.p = open // the arc too: its openness is its progress (T67.8)
                val a = opening.bubble.frames(host)!!
                val b = dismissal.bubble.frames(host)!!
                val where = "$host at openness $open"
                assertWithMessage(where).that(a.bubble.rect.width).isWithin(0.5f).of(b.bubble.rect.width)
                assertWithMessage(where).that(a.bubble.rect.height).isWithin(0.5f).of(b.bubble.rect.height)
                assertWithMessage(where).that(a.bubble.radius).isWithin(0.5f).of(b.bubble.radius)
                assertWithMessage(where).that(opening.bubble.contentAlpha(host)).isWithin(0.001f).of(dismissal.bubble.contentAlpha(host))
                assertWithMessage(where).that(opening.bubble.backdropAlpha(host)).isWithin(0.001f).of(dismissal.bubble.backdropAlpha(host))
            }
        }
    }

    @Test
    fun `opening, the small card is clear translucency and Now Playing comes in last, never on bare translucency`() {
        at(0f)
        at(0.2f)
        assertThat(bubble.translucentAlpha()).isEqualTo(1f)
        assertThat(bubble.backdropAlpha(size)).isEqualTo(0f)
        assertThat(bubble.contentAlpha(size)).isEqualTo(0f)
        for (step in 0..100) {
            at(step / 100f)
            assertThat(bubble.backdropAlpha(size)).isAtLeast(bubble.contentAlpha(size))
        }
    }

    @Test
    fun `opening, the card has the sheet's rounded corners while it moves and is square once open`() {
        sheetRadius = 77f
        at(0f)
        assertThat(at(0.99f)!!.radius).isWithin(1f).of(77f)
        assertThat(at(1f)!!.radius).isEqualTo(0f)
    }
}
