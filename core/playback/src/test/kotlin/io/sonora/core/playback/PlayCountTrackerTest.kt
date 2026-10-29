package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayCountTrackerTest {

    private var now = 0L
    private val tracker = PlayCountTracker { now }

    private fun listen(ms: Long) {
        now += ms
    }

    private fun startPlaying(key: String = SONG, durationMs: Long = 3 * MINUTE, entry: String = "1:1") {
        tracker.start(entry, key, durationMs, isPlaying = true, isRepeat = false)
    }

    @Test
    fun `a song counts once half of it was heard`() {
        startPlaying(durationMs = 3 * MINUTE)

        listen(89_999)
        assertThat(tracker.poll()).isNull()
        listen(1)
        assertThat(tracker.poll()).isEqualTo(SONG)
        listen(60_000)
        assertThat(tracker.poll()).isNull()
    }

    @Test
    fun `a long song counts after four minutes`() {
        startPlaying(durationMs = 20 * MINUTE)

        assertThat(tracker.nextCheckInMs()).isEqualTo(4 * MINUTE)
        listen(4 * MINUTE - 1)
        assertThat(tracker.poll()).isNull()
        listen(1)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `paused time does not count`() {
        startPlaying(durationMs = 2 * MINUTE)
        listen(30_000)
        tracker.setPlaying(false)
        listen(10 * MINUTE)

        assertThat(tracker.poll()).isNull()
        assertThat(tracker.nextCheckInMs()).isNull()

        tracker.setPlaying(true)
        assertThat(tracker.nextCheckInMs()).isEqualTo(30_000)
        listen(30_000)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `seeking ahead does not count as listening`() {
        startPlaying(durationMs = 10 * MINUTE)
        listen(10_000)
        // A seek to near the end: buffering pauses playback, the position jumps, nothing is heard meanwhile.
        tracker.setPlaying(false)
        tracker.setPlaying(true)
        listen(10_000)
        tracker.start("1:2", "next", 3 * MINUTE, isPlaying = true, isRepeat = false)

        assertThat(tracker.poll()).isNull()
    }

    @Test
    fun `a song that ends exactly at its threshold counts when the next one starts`() {
        startPlaying(durationMs = 60_000)
        listen(30_000)

        assertThat(tracker.start("1:2", "next", 3 * MINUTE, isPlaying = true, isRepeat = false)).isEqualTo(SONG)
        assertThat(tracker.poll()).isNull()
    }

    @Test
    fun `a repeat of the same entry counts again`() {
        startPlaying(durationMs = 60_000)
        listen(60_000)
        assertThat(tracker.poll()).isEqualTo(SONG)

        tracker.start("1:1", SONG, 60_000, isPlaying = true, isRepeat = true)
        listen(29_000)
        assertThat(tracker.poll()).isNull()
        listen(1_000)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `the same entry restated by a queue change keeps its listening`() {
        startPlaying(durationMs = 60_000)
        listen(20_000)

        tracker.start("1:1", SONG, 60_000, isPlaying = true, isRepeat = false)
        listen(10_000)

        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `a counted entry restated by a queue change does not count twice`() {
        startPlaying(durationMs = 60_000)
        listen(30_000)
        assertThat(tracker.poll()).isEqualTo(SONG)

        tracker.start("1:1", SONG, 60_000, isPlaying = true, isRepeat = false)
        listen(60_000)

        assertThat(tracker.poll()).isNull()
    }

    @Test
    fun `the same song queued twice counts for each entry`() {
        startPlaying(entry = "1:1", durationMs = 60_000)
        listen(30_000)
        assertThat(tracker.poll()).isEqualTo(SONG)

        startPlaying(entry = "1:2", durationMs = 60_000)
        listen(30_000)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `an unknown duration counts after four minutes`() {
        startPlaying(durationMs = 0)

        listen(4 * MINUTE - 1)
        assertThat(tracker.poll()).isNull()
        listen(1)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `a duration learnt later moves the threshold`() {
        startPlaying(durationMs = 0)
        listen(30_000)

        tracker.setDuration(60_000)

        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `listening starts only when playback does`() {
        tracker.start("1:1", SONG, 60_000, isPlaying = false, isRepeat = false)
        listen(10 * MINUTE)
        assertThat(tracker.poll()).isNull()

        tracker.setPlaying(true)
        listen(30_000)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `no entry, or one without a song, never counts`() {
        tracker.start(null, null, 0, isPlaying = true, isRepeat = false)
        listen(10 * MINUTE)
        assertThat(tracker.poll()).isNull()
        assertThat(tracker.nextCheckInMs()).isNull()

        tracker.start("1:1", "", 60_000, isPlaying = true, isRepeat = false)
        listen(10 * MINUTE)
        assertThat(tracker.poll()).isNull()
    }

    @Test
    fun `at double speed half of the song is heard in a quarter of its length`() {
        tracker.setSpeed(2f)
        startPlaying(durationMs = 4 * MINUTE)

        assertThat(tracker.nextCheckInMs()).isEqualTo(MINUTE)
        listen(MINUTE - 1)
        assertThat(tracker.poll()).isNull()
        listen(1)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `a speed change mid-song counts each stretch at its own speed`() {
        startPlaying(durationMs = 6 * MINUTE)
        listen(MINUTE)
        tracker.setSpeed(0.5f)

        // One minute heard at 1x, so two minutes of song are left to hear: four minutes at half speed.
        assertThat(tracker.nextCheckInMs()).isEqualTo(4 * MINUTE)
        listen(4 * MINUTE - 2)
        assertThat(tracker.poll()).isNull()
        listen(2)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    @Test
    fun `a speed change while paused applies from the next play`() {
        startPlaying(durationMs = 4 * MINUTE)
        listen(MINUTE)
        tracker.setPlaying(false)
        tracker.setSpeed(2f)
        listen(10 * MINUTE)
        tracker.setPlaying(true)

        assertThat(tracker.nextCheckInMs()).isEqualTo(MINUTE / 2)
        listen(MINUTE / 2)
        assertThat(tracker.poll()).isEqualTo(SONG)
    }

    private companion object {
        const val SONG = "content://tree/music/document/song.flac"
        const val MINUTE = 60_000L
    }
}
