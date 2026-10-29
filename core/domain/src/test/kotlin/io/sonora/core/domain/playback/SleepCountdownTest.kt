package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The sleep timer's countdown, read against a fake clock (plain milliseconds) and a fake playhead. */
class SleepCountdownTest {

    private fun entry(album: String?, durationMs: Long?) = SleepEntry(album, durationMs)

    /** An album of three songs of 3, 4 and 5 minutes, then a song of another album. */
    private val queue = listOf(
        entry("a", 180_000),
        entry("a", 240_000),
        entry("a", 300_000),
        entry("b", 200_000),
    )

    private fun at(index: Int, positionMs: Long, entries: List<SleepEntry> = queue) =
        SleepPlayhead(entries, index, positionMs)

    @Test
    fun `a minutes timer counts down on the clock, whatever playback does`() {
        val countdown = SleepCountdown.start(SleepTimerOption.Minutes(15), nowMs = 1_000)

        assertThat(countdown.remainingMs(1_000, at(0, 0))).isEqualTo(900_000)
        assertThat(countdown.remainingMs(301_000, at(3, 5_000))).isEqualTo(600_000)
        assertThat(countdown.isDue(900_999)).isFalse()
        assertThat(countdown.isDue(901_000)).isTrue()
        assertThat(countdown.remainingMs(950_000, at(0, 0))).isEqualTo(0)
        assertThat(countdown.pausesAtEndOfCurrent(at(2, 0))).isFalse()
    }

    @Test
    fun `end of song runs out with the song playing, never by the clock`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfSong, nowMs = 0)

        assertThat(countdown.remainingMs(0, at(1, 40_000))).isEqualTo(200_000)
        assertThat(countdown.remainingMs(10_000_000, at(1, 40_000))).isEqualTo(200_000)
        assertThat(countdown.isDue(10_000_000)).isFalse()
        assertThat(countdown.pausesAtEndOfCurrent(at(0, 0))).isTrue()
        assertThat(countdown.pausesAtEndOfCurrent(at(2, 0))).isTrue()
    }

    @Test
    fun `end of song with no known length has no remaining time`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfSong, nowMs = 0)

        assertThat(countdown.remainingMs(0, at(0, 1_000, listOf(entry("a", null))))).isNull()
    }

    @Test
    fun `end of album adds the rest of the album's run in the queue`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfAlbum, nowMs = 0)

        // 3 min left of the first song, then 4 and 5 minutes; the next album's song does not count.
        assertThat(countdown.remainingMs(0, at(0, 0))).isEqualTo(720_000)
        assertThat(countdown.remainingMs(0, at(1, 60_000))).isEqualTo(480_000)
        assertThat(countdown.remainingMs(0, at(2, 290_000))).isEqualTo(10_000)
        assertThat(countdown.pausesAtEndOfCurrent(at(0, 0))).isFalse()
        assertThat(countdown.pausesAtEndOfCurrent(at(1, 0))).isFalse()
        assertThat(countdown.pausesAtEndOfCurrent(at(2, 0))).isTrue()
        // The other album's song is the last of its own run: the timer follows the album playing now.
        assertThat(countdown.pausesAtEndOfCurrent(at(3, 0))).isTrue()
    }

    @Test
    fun `end of album stops after the song when the song has no album`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfAlbum, nowMs = 0)
        val loose = listOf(entry(null, 100_000), entry(null, 100_000))

        assertThat(countdown.remainingMs(0, at(0, 30_000, loose))).isEqualTo(70_000)
        assertThat(countdown.pausesAtEndOfCurrent(at(0, 0, loose))).isTrue()
    }

    @Test
    fun `end of album is unknown when a song of the run has no length`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfAlbum, nowMs = 0)
        val gaps = listOf(entry("a", 100_000), entry("a", null))

        assertThat(countdown.remainingMs(0, at(0, 0, gaps))).isNull()
    }

    @Test
    fun `extending a minutes timer moves its end later`() {
        val countdown = SleepCountdown.start(SleepTimerOption.Minutes(15), nowMs = 0)
            .extendedBy(minutes = 10, nowMs = 600_000, playhead = at(0, 0))

        assertThat(countdown.option).isEqualTo(SleepTimerOption.Minutes(15))
        assertThat(countdown.remainingMs(600_000, at(0, 0))).isEqualTo(900_000)
        assertThat(countdown.isDue(1_499_999)).isFalse()
        assertThat(countdown.isDue(1_500_000)).isTrue()
    }

    @Test
    fun `extending end of song turns it into a clock timer past the song's end`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfSong, nowMs = 0)
            .extendedBy(minutes = 10, nowMs = 5_000, playhead = at(0, 120_000))

        // 60 s of the song were left, plus 10 minutes.
        assertThat(countdown.remainingMs(5_000, at(0, 120_000))).isEqualTo(660_000)
        assertThat(countdown.pausesAtEndOfCurrent(at(0, 120_000))).isFalse()
        assertThat(countdown.isDue(665_000)).isTrue()
    }

    @Test
    fun `extending a timer with no known end counts the extension from now`() {
        val countdown = SleepCountdown.start(SleepTimerOption.EndOfSong, nowMs = 0)
            .extendedBy(minutes = 5, nowMs = 1_000, playhead = at(0, 0, listOf(entry(null, null))))

        assertThat(countdown.remainingMs(1_000, at(0, 0))).isEqualTo(300_000)
    }

    @Test
    fun `the preset durations are 15, 30, 45, 60 and 90 minutes`() {
        assertThat(SleepTimerOption.PRESET_MINUTES).containsExactly(15, 30, 45, 60, 90).inOrder()
    }

    @Test
    fun `the custom duration steps by five minutes within its bounds`() {
        assertThat(SleepCustomDuration.step(20, +1)).isEqualTo(25)
        assertThat(SleepCustomDuration.step(20, -1)).isEqualTo(15)
        assertThat(SleepCustomDuration.step(SleepCustomDuration.MIN_MINUTES, -1)).isEqualTo(SleepCustomDuration.MIN_MINUTES)
        assertThat(SleepCustomDuration.step(SleepCustomDuration.MAX_MINUTES, +1)).isEqualTo(SleepCustomDuration.MAX_MINUTES)
        // An off-grid value (none today) lands back on the grid.
        assertThat(SleepCustomDuration.step(22, +1)).isEqualTo(25)
        assertThat(SleepCustomDuration.step(22, -1)).isEqualTo(20)
    }
}
