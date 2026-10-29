package io.sonora.core.domain.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListeningMeterTest {

    private var clock = 0L
    private var wall = WALL_START
    private var ids = 0
    private val meter = ListeningMeter(now = { clock }, wallNow = { wall }, newId = { "p${++ids}" })

    private fun advance(ms: Long) {
        clock += ms
        wall += ms
    }

    @Test
    fun `a skip records the partial time heard, from when the song started playing`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(30_000)

        val skipped = meter.start("e2", "content://b", isPlaying = true, isRepeat = false)

        assertThat(skipped).isEqualTo(Listening("p1", "content://a", WALL_START, 30_000, counted = false))
    }

    @Test
    fun `paused time is not heard, and a pause saves the progress so far under the same playback`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(10_000)
        val paused = meter.setPlaying(false)
        advance(60_000)
        assertThat(meter.setPlaying(true)).isNull()
        advance(5_000)

        val ended = meter.start("e2", "content://b", isPlaying = false, isRepeat = false)

        assertThat(paused).isEqualTo(Listening("p1", "content://a", WALL_START, 10_000, counted = false))
        assertThat(ended).isEqualTo(Listening("p1", "content://a", WALL_START, 15_000, counted = false))
    }

    @Test
    fun `a playback starts when it first plays, not when it became current while paused`() {
        meter.start("e1", "content://a", isPlaying = false, isRepeat = false)
        advance(3_600_000)
        meter.setPlaying(true)
        advance(20_000)

        val ended = meter.finish()

        assertThat(ended).isEqualTo(Listening("p1", "content://a", WALL_START + 3_600_000, 20_000, counted = false))
    }

    @Test
    fun `a counted playback reports at once and keeps its count to the end`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(120_000)

        val counted = meter.markCounted()
        advance(60_000)
        val ended = meter.start("e2", "content://b", isPlaying = true, isRepeat = false)

        assertThat(counted).isEqualTo(Listening("p1", "content://a", WALL_START, 120_000, counted = true))
        assertThat(ended).isEqualTo(Listening("p1", "content://a", WALL_START, 180_000, counted = true))
    }

    @Test
    fun `the same entry restated goes on, a repeat starts a new playback`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(10_000)
        assertThat(meter.start("e1", "content://a", isPlaying = true, isRepeat = false)).isNull()
        advance(10_000)

        val first = meter.start("e1", "content://a", isPlaying = true, isRepeat = true)
        advance(5_000)
        val second = meter.finish()

        assertThat(first).isEqualTo(Listening("p1", "content://a", WALL_START, 20_000, counted = false))
        assertThat(second).isEqualTo(Listening("p2", "content://a", WALL_START + 20_000, 5_000, counted = false))
    }

    @Test
    fun `under a second heard, nothing heard, or no song key records nothing`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(999)
        assertThat(meter.start("e2", "content://b", isPlaying = false, isRepeat = false)).isNull()
        advance(10_000)
        assertThat(meter.start("e3", null, isPlaying = true, isRepeat = false)).isNull()
        advance(10_000)
        assertThat(meter.start("e4", " ", isPlaying = true, isRepeat = false)).isNull()
        advance(10_000)
        assertThat(meter.finish()).isNull()
    }

    @Test
    fun `finishing pauses the playback, so a second finish adds nothing`() {
        meter.start("e1", "content://a", isPlaying = true, isRepeat = false)
        advance(2_000)

        val first = meter.finish()
        advance(2_000)

        assertThat(first?.listenedMs).isEqualTo(2_000)
        assertThat(meter.finish()?.listenedMs).isEqualTo(2_000)
    }

    private companion object {
        const val WALL_START = 1_790_000_000_000L
    }
}
