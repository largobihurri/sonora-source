package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.AbLoop.Companion.END_MARGIN_MS
import io.sonora.core.domain.playback.AbLoop.Companion.MIN_SPAN_MS
import io.sonora.core.domain.playback.AbLoop.Companion.PRE_SEEK_MARGIN_MS
import org.junit.Test

class AbLoopTest {

    private val duration = 180_000L
    private val on = AbLoop().toggled()

    @Test
    fun `the mode starts off and a toggle turns it on with no points`() {
        assertThat(AbLoop().enabled).isFalse()
        assertThat(on).isEqualTo(AbLoop(enabled = true))
        assertThat(on.phase).isEqualTo(AbLoop.Phase.AWAITING_A)
    }

    @Test
    fun `taps set A, then B, then clear the loop`() {
        val a = on.tapped(10_000, duration, itemId = 7)
        assertThat(a.startMs).isEqualTo(10_000)
        assertThat(a.endMs).isNull()
        assertThat(a.phase).isEqualTo(AbLoop.Phase.AWAITING_B)

        val ab = a.tapped(14_000, duration, itemId = 7)
        assertThat(ab.startMs).isEqualTo(10_000)
        assertThat(ab.endMs).isEqualTo(14_000)
        assertThat(ab.phase).isEqualTo(AbLoop.Phase.LOOPING)
        assertThat(ab.isLooping).isTrue()

        val cleared = ab.tapped(12_000, duration, itemId = 7)
        assertThat(cleared).isEqualTo(on)
    }

    @Test
    fun `taps do nothing while the mode is off`() {
        assertThat(AbLoop().tapped(10_000, duration, itemId = 7)).isEqualTo(AbLoop())
    }

    @Test
    fun `turning the mode off forgets the points`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.toggled()).isEqualTo(AbLoop())
    }

    @Test
    fun `B closer than the minimum span to A is pushed to the minimum span`() {
        val ab = on.tapped(10_000, duration, 7).tapped(10_100, duration, 7)
        assertThat(ab.endMs).isEqualTo(10_000 + MIN_SPAN_MS)
    }

    @Test
    fun `B tapped before A still lands after A`() {
        val ab = on.tapped(10_000, duration, 7).tapped(4_000, duration, 7)
        assertThat(ab.endMs).isEqualTo(10_000 + MIN_SPAN_MS)
    }

    @Test
    fun `A near the end of the track leaves room for the minimum span before the end margin`() {
        val a = on.tapped(duration - 100, duration, 7)
        assertThat(a.startMs).isEqualTo(duration - END_MARGIN_MS - MIN_SPAN_MS)
        assertThat(a.tapped(duration, duration, 7).endMs).isEqualTo(duration - END_MARGIN_MS)
    }

    @Test
    fun `B never reaches the end, so a late check cannot let the player move on`() {
        val ab = on.tapped(10_000, duration, 7).tapped(duration, duration, 7)
        assertThat(ab.endMs).isEqualTo(duration - END_MARGIN_MS)
        assertThat(ab.endMs!! - ab.startMs!!).isAtLeast(MIN_SPAN_MS)
    }

    @Test
    fun `a track too short for the span and the end margin takes no A`() {
        assertThat(on.tapped(100, MIN_SPAN_MS + END_MARGIN_MS - 1, 7)).isEqualTo(on)
        assertThat(on.tapped(100, MIN_SPAN_MS + END_MARGIN_MS, 7).startMs).isEqualTo(0)
    }

    @Test
    fun `a track shorter than the minimum span takes no A`() {
        assertThat(on.tapped(100, 400, 7)).isEqualTo(on)
    }

    @Test
    fun `A is taken with an unknown duration`() {
        assertThat(on.tapped(10_000, 0, 7).startMs).isEqualTo(10_000)
    }

    @Test
    fun `the B tap for another entry than A starts over at A`() {
        val loop = on.tapped(10_000, duration, 7).tapped(20_000, duration, 8)
        assertThat(loop.startMs).isEqualTo(20_000)
        assertThat(loop.endMs).isNull()
        assertThat(loop.itemId).isEqualTo(8)
    }

    @Test
    fun `nudging A keeps it at or after zero and the minimum span before B`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.withStart(9_000, duration).startMs).isEqualTo(9_000)
        assertThat(ab.withStart(-50, duration).startMs).isEqualTo(0)
        assertThat(ab.withStart(13_900, duration).startMs).isEqualTo(14_000 - MIN_SPAN_MS)
    }

    @Test
    fun `nudging B keeps it the minimum span after A and within the track`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.withEnd(16_000, duration).endMs).isEqualTo(16_000)
        assertThat(ab.withEnd(9_000, duration).endMs).isEqualTo(10_000 + MIN_SPAN_MS)
        assertThat(ab.withEnd(duration + 5_000, duration).endMs).isEqualTo(duration - END_MARGIN_MS)
    }

    @Test
    fun `nudging A alone keeps room for the minimum span before the end`() {
        val a = on.tapped(10_000, duration, 7)
        assertThat(a.withStart(duration, duration).startMs).isEqualTo(duration - END_MARGIN_MS - MIN_SPAN_MS)
    }

    @Test
    fun `nudging a point that is not set changes nothing`() {
        assertThat(on.withStart(5_000, duration)).isEqualTo(on)
        val a = on.tapped(10_000, duration, 7)
        assertThat(a.withEnd(15_000, duration)).isEqualTo(a)
    }

    @Test
    fun `a seek inside the span keeps the loop and one outside clears it`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.afterSeek(10_000)).isEqualTo(ab)
        assertThat(ab.afterSeek(12_000)).isEqualTo(ab)
        assertThat(ab.afterSeek(14_000)).isEqualTo(ab)
        assertThat(ab.afterSeek(9_999)).isEqualTo(on)
        assertThat(ab.afterSeek(30_000)).isEqualTo(on)
    }

    @Test
    fun `a seek with only A set keeps A`() {
        val a = on.tapped(10_000, duration, 7)
        assertThat(a.afterSeek(60_000)).isEqualTo(a)
    }

    @Test
    fun `a track change clears the points but keeps the mode on`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.onItem(7)).isEqualTo(ab)
        assertThat(ab.onItem(8)).isEqualTo(on)
        assertThat(ab.onItem(null)).isEqualTo(on)
        assertThat(on.tapped(10_000, duration, 7).onItem(8)).isEqualTo(on)
    }

    @Test
    fun `clear forgets the points but keeps the mode on`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.cleared()).isEqualTo(on)
    }

    @Test
    fun `no loop-back is due without both points`() {
        assertThat(on.nextStep(50_000)).isEqualTo(AbLoop.Step.Wait(AbLoop.MAX_POLL_MS))
        assertThat(on.tapped(10_000, duration, 7).nextStep(50_000)).isEqualTo(AbLoop.Step.Wait(AbLoop.MAX_POLL_MS))
    }

    @Test
    fun `the loop jumps back to A once the position reaches the pre-seek margin before B`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.nextStep(14_000 - PRE_SEEK_MARGIN_MS)).isEqualTo(AbLoop.Step.SeekTo(10_000))
        assertThat(ab.nextStep(14_500)).isEqualTo(AbLoop.Step.SeekTo(10_000))
        assertThat(ab.nextStep(14_000 - PRE_SEEK_MARGIN_MS - 1)).isInstanceOf(AbLoop.Step.Wait::class.java)
    }

    @Test
    fun `waits shorten as B approaches and stay within the poll bounds`() {
        val ab = on.tapped(10_000, duration, 7).tapped(14_000, duration, 7)
        assertThat(ab.nextStep(10_000)).isEqualTo(AbLoop.Step.Wait(AbLoop.MAX_POLL_MS))
        assertThat(ab.nextStep(14_000 - PRE_SEEK_MARGIN_MS - 40)).isEqualTo(AbLoop.Step.Wait(20))
        assertThat(ab.nextStep(14_000 - PRE_SEEK_MARGIN_MS - 2)).isEqualTo(AbLoop.Step.Wait(AbLoop.MIN_POLL_MS))
    }

    @Test
    fun `with a fake position clock every jump lands before B and none comes early`() {
        val ab = on.tapped(10_000, duration, 7).tapped(12_000, duration, 7)
        // A position clock that plays at normal speed and whose waits wake up to 4 ms late (a busy main thread).
        var clockMs = 0L
        var positionMs = 10_000L
        val jumpedAt = mutableListOf<Long>()
        var lateness = 0L
        while (clockMs < 10_000) {
            when (val step = ab.nextStep(positionMs)) {
                is AbLoop.Step.SeekTo -> {
                    jumpedAt += positionMs
                    positionMs = step.positionMs
                }
                is AbLoop.Step.Wait -> {
                    lateness = (lateness + 1) % 5
                    clockMs += step.delayMs + lateness
                    positionMs += step.delayMs + lateness
                }
            }
        }
        // Ten seconds of a two-second span: five passes, each jumping back within the margin before B.
        assertThat(jumpedAt).hasSize(5)
        for (at in jumpedAt) assertThat(at).isIn(12_000 - PRE_SEEK_MARGIN_MS until 12_000L)
    }
}
