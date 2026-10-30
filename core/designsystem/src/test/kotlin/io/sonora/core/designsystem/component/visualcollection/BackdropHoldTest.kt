package io.sonora.core.designsystem.component.visualcollection

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.designsystem.component.visualcollection.BackdropHold.Companion.FADE_MS
import io.sonora.core.designsystem.component.visualcollection.BackdropHold.Companion.HOLD_SPEED
import io.sonora.core.designsystem.component.visualcollection.BackdropHold.Companion.SETTLE_MS
import org.junit.Test

class BackdropHoldTest {

    private val frameMs = 8L

    /** [hold] after the strip ran from [fromMs] to [toMs] at [speed], with [nearest] its nearest cover at each time. */
    private fun run(hold: BackdropHold<String>, fromMs: Long, toMs: Long, speed: Float, nearest: (Long) -> String): BackdropHold<String> {
        var now = hold
        var t = fromMs
        while (t <= toMs) {
            now = now.next(nearest(t), speed, t)
            t += frameMs
        }
        return now
    }

    @Test
    fun `while the strip passes more than a cover per cross-fade, the background holds`() {
        // Twice the hold speed: a new cover every 150 ms, longer than the dwell, shorter than a cross-fade.
        val speed = HOLD_SPEED * 2f
        val coverMs = (1_000f / speed).toLong()
        var hold = BackdropHold.start("a", 0L)
        var t = frameMs
        while (t <= 3_000L) {
            hold = hold.next("cover ${t / coverMs}", speed, t)
            assertWithMessage("at $t ms").that(hold.shown).isEqualTo("a")
            t += frameMs
        }
    }

    @Test
    fun `once the strip slows, the nearest cover takes over after a short dwell`() {
        // Cover x is nearest for a while at speed; the last fast frame is at 1000 ms, then the strip is slow.
        var hold = run(BackdropHold.start("a", 0L), frameMs, 800L, speed = HOLD_SPEED * 3f) { "cover $it" }
        hold = run(hold, 800L + frameMs, 1_000L, speed = HOLD_SPEED * 3f) { "x" }
        assertThat(hold.shown).isEqualTo("a")
        hold = run(hold, 1_000L + frameMs, 1_000L + SETTLE_MS - frameMs, speed = HOLD_SPEED / 2f) { "x" }
        assertThat(hold.shown).isEqualTo("a")
        hold = hold.next("x", HOLD_SPEED / 2f, 1_000L + SETTLE_MS)
        assertThat(hold.shown).isEqualTo("x")
    }

    @Test
    fun `a strip that stops settles on its cover when the dwell is over`() {
        // The last frame was fast; nothing moves afterwards, so the caller wakes up when the hold says.
        val hold = BackdropHold.start("a", 0L).next("x", HOLD_SPEED * 4f, 1_000L)
        assertThat(hold.shown).isEqualTo("a")
        val at = hold.settlesAtMs()!!
        assertThat(at).isEqualTo(1_000L + SETTLE_MS)
        assertThat(hold.next("x", 0f, at).shown).isEqualTo("x")
        assertThat(hold.next("x", 0f, at).settlesAtMs()).isNull()
    }

    @Test
    fun `a new background never cuts the previous cross-fade short`() {
        var hold = BackdropHold.start("a", 0L)
        hold = run(hold, 1_000L, 1_000L + SETTLE_MS, speed = 1f) { "b" }
        val faded = hold.shownSinceMs
        assertThat(hold.shown).isEqualTo("b")
        hold = run(hold, faded + frameMs, faded + FADE_MS - frameMs, speed = 1f) { "c" }
        assertThat(hold.shown).isEqualTo("b")
        assertThat(hold.settlesAtMs()).isEqualTo(faded + FADE_MS)
        assertThat(hold.next("c", 1f, faded + FADE_MS).shown).isEqualTo("c")
    }

    @Test
    fun `under reduced motion the background changes at once, only after the dwell`() {
        val fadeMs = BackdropHold.fadeMs(reducedMotion = true)
        assertThat(fadeMs).isEqualTo(0)
        assertThat(BackdropHold.fadeMs(reducedMotion = false)).isEqualTo(FADE_MS)
        var hold = BackdropHold.start("a", 0L).next("b", 0f, 1_000L, fadeMs).next("b", 0f, 1_000L + SETTLE_MS, fadeMs)
        assertThat(hold.shown).isEqualTo("b")
        hold = hold.next("c", 0f, 1_000L + SETTLE_MS + frameMs, fadeMs)
        assertThat(hold.settlesAtMs(fadeMs)).isEqualTo(1_000L + SETTLE_MS + frameMs + SETTLE_MS)
    }

    @Test
    fun `the strip's speed is how many covers it moved per second between two frames`() {
        assertThat(BackdropHold.speed(from = 10f, to = 10.5f, elapsedMs = 8L)).isWithin(1e-3f).of(62.5f)
        assertThat(BackdropHold.speed(from = 10f, to = 9.9f, elapsedMs = 10L)).isWithin(1e-3f).of(10f)
        // No time between them tells nothing, so the background holds.
        assertThat(BackdropHold.speed(from = 10f, to = 10f, elapsedMs = 0L)).isGreaterThan(HOLD_SPEED)
    }
}
