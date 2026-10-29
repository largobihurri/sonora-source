package io.sonora.core.playback.levels

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackBandLevels
import org.junit.Test

/** What the UI reads: the level frame matching what is heard now, and the reference-counted watching flag. */
class LiveBandLevelsTest {

    private var nowNanos = 1_000_000_000L
    private val levels = LiveBandLevels { nowNanos }
    private val read = FloatArray(PlaybackBandLevels.BAND_COUNT)

    /** Publishes frames ending at 16, 32 and 48 ms whose levels are their own times in ms (e.g. all -16 dB). */
    private fun publishThreeFrames() {
        for (ms in listOf(16, 32, 48)) levels.publish(ms * 1_000L, FloatArray(PlaybackBandLevels.BAND_COUNT) { -ms.toFloat() })
    }

    private fun advanceMs(ms: Long) {
        nowNanos += ms * 1_000_000
    }

    @Test
    fun `nothing reported, there are no live levels`() {
        levels.watch()
        assertThat(levels.read(read)).isFalse()

        publishThreeFrames()
        assertThat(levels.read(read)).isFalse() // Frames, but no played position yet.
    }

    @Test
    fun `the frame heard now is the newest one at or before the played position`() {
        levels.watch()
        publishThreeFrames()
        levels.setRunning(true)
        levels.reportPlayed(40_000)

        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(-32f)

        // The sink reports its position now and then; in between, the clock carries it on.
        advanceMs(10)
        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(-48f)
    }

    @Test
    fun `paused, the played position stays put`() {
        levels.watch()
        publishThreeFrames()
        levels.setRunning(true)
        levels.reportPlayed(40_000)
        levels.setRunning(false)

        advanceMs(30)

        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(-32f)
    }

    @Test
    fun `audio not yet measured or long gone gives no levels`() {
        levels.watch()
        publishThreeFrames()
        levels.setRunning(true)

        levels.reportPlayed(10_000) // Before the first frame.
        assertThat(levels.read(read)).isFalse()

        levels.reportPlayed(400_000) // Far past the newest frame: the meter stopped hearing the stream.
        assertThat(levels.read(read)).isFalse()
    }

    @Test
    fun `a sink that stops reporting goes stale`() {
        levels.watch()
        publishThreeFrames()
        levels.reportPlayed(40_000)

        advanceMs(1_000)

        assertThat(levels.read(read)).isFalse()
    }

    @Test
    fun `cleared frames, as after a seek, give no levels until new ones come`() {
        levels.watch()
        publishThreeFrames()
        levels.reportPlayed(40_000)

        levels.clearFrames()

        assertThat(levels.read(read)).isFalse()
    }

    @Test
    fun `the ring keeps the latest frames`() {
        levels.watch()
        repeat(1_000) { levels.publish((it + 1) * 16_000L, FloatArray(PlaybackBandLevels.BAND_COUNT) { _ -> it.toFloat() }) }
        levels.reportPlayed(999 * 16_000L + 1)

        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(998f)
    }

    @Test
    fun `a queue deeper than four seconds still reads the frame at the played position`() {
        levels.watch()
        // 6 s of audio measured ahead of what is heard: the AudioTrack's buffer can be this deep.
        val frames = 6_000_000 / 16_000
        for (i in 1..frames) levels.publish(i * 16_000L, FloatArray(PlaybackBandLevels.BAND_COUNT) { _ -> i.toFloat() })
        levels.setRunning(true)
        levels.reportPlayed(16_000L * 3 + 1)

        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(3f)
    }

    @Test
    fun `the ring holds well over any plausible audio queue`() {
        assertThat(LiveBandLevels.CAPACITY * BandLevelMeter.HOP_SECONDS).isAtLeast(10.0)
    }

    @Test
    fun `watching is reference counted, and a handle closes once`() {
        assertThat(levels.watching).isFalse()
        val a = levels.watch()
        val b = levels.watch()

        a.close()
        a.close()
        assertThat(levels.watching).isTrue()

        b.close()
        assertThat(levels.watching).isFalse()
    }

    @Test
    fun `nobody watching, nothing reads, but a watcher that arrives reads the frame heard now`() {
        publishThreeFrames()
        levels.reportPlayed(40_000)
        assertThat(levels.read(read)).isFalse()

        levels.watch()

        assertThat(levels.read(read)).isTrue()
        assertThat(read[0]).isEqualTo(-32f)
    }
}
