package io.sonora.core.playback.levels

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.LevelBand
import io.sonora.core.domain.playback.PlaybackBandLevels
import io.sonora.core.playback.equalizer.Pcm
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/** The four-band meter on generated PCM: band split, AES-17 RMS levels, ballistics, timestamps and idling. */
@OptIn(UnstableApi::class)
class BandLevelMeterTest {

    private val levels = LiveBandLevels { 0L }
    private val meter = BandLevelMeter(levels)
    private val newest = FloatArray(PlaybackBandLevels.BAND_COUNT)

    private fun sine(frequency: Double, rate: Int, seconds: Double, amplitude: Double, channels: Int = 1): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i -> (amplitude * sin(2 * PI * frequency * (i / channels) / rate)).toFloat() }
    }

    /** Feeds [samples] (interleaved) in buffers of [chunkFrames], timed from [startUs]; calls [afterChunk] after each. */
    private fun feed(
        samples: FloatArray,
        rate: Int,
        channels: Int = 1,
        encoding: Int = C.ENCODING_PCM_16BIT,
        chunkFrames: Int = 1_024,
        startUs: Long = 0L,
        afterChunk: (endUs: Long) -> Unit = {},
    ) {
        meter.configure(rate, channels, encoding)
        val frames = samples.size / channels
        var frame = 0
        while (frame < frames) {
            val n = minOf(chunkFrames, frames - frame)
            meter.process(Pcm.encode(samples, frame * channels, n * channels, encoding), startUs + frame * 1_000_000L / rate)
            frame += n
            afterChunk(startUs + frame * 1_000_000L / rate)
        }
    }

    private fun newestLevels(): FloatArray {
        checkNotNull(levels.newestFrame(newest)) { "no level frame" }
        return newest.copyOf()
    }

    @Test
    fun `each tone lands in its own band, the others well below`() {
        levels.watch()
        val tones = mapOf(50.0 to LevelBand.BASS, 150.0 to LevelBand.KICK, 1_000.0 to LevelBand.MIDS, 8_000.0 to LevelBand.HIGHS)

        for ((frequency, band) in tones) {
            meter.flush()
            feed(sine(frequency, 44_100, 1.0, 0.5, channels = 2), 44_100, channels = 2)
            val measured = newestLevels()

            // A 0.5 sine is -6 dBFS on the AES-17 scale; a band edge costs up to about a dB.
            assertThat(measured[band.ordinal].toDouble()).isWithin(2.0).of(-6.0)
            for (other in LevelBand.entries - band) {
                assertThat(measured[other.ordinal]).isLessThan(measured[band.ordinal] - 12f)
            }
        }
    }

    @Test
    fun `every encoding measures the same level`() {
        levels.watch()
        for (encoding in Pcm.ENCODINGS) {
            meter.flush()
            feed(sine(1_000.0, 48_000, 0.5, 0.5), 48_000, encoding = encoding)

            assertThat(newestLevels()[LevelBand.MIDS.ordinal].toDouble()).isWithin(0.5).of(-6.02)
        }
    }

    @Test
    fun `stereo is mixed to mono`() {
        levels.watch()
        feed(sine(1_000.0, 48_000, 0.5, 0.5), 48_000)
        val mono = newestLevels()
        meter.flush()
        feed(sine(1_000.0, 48_000, 0.5, 0.5, channels = 2), 48_000, channels = 2)

        assertThat(newestLevels()[LevelBand.MIDS.ordinal]).isWithin(0.1f).of(mono[LevelBand.MIDS.ordinal])
    }

    @Test
    fun `levels rise fast and fall back slower, down to silence`() {
        levels.watch()
        val rate = 48_000
        val tone = sine(1_000.0, rate, 0.5, 0.5)
        val signal = tone + FloatArray(rate) // Half a second of tone, then a second of silence.
        val mids = mutableListOf<Pair<Long, Float>>()
        feed(signal, rate, chunkFrames = 48) { endUs ->
            if (levels.newestFrame(newest) != null) mids += endUs to newest[LevelBand.MIDS.ordinal]
        }
        fun at(us: Long) = mids.last { it.first <= us }.second

        // Attack: within 3 dB of the tone's level 40 ms in.
        assertThat(at(40_000).toDouble()).isAtLeast(-9.0)
        // Release: 20 ms after the tone stops the bar is still close to where it was...
        assertThat(at(520_000).toDouble()).isAtLeast(-12.0)
        // ...and half a second later it has fallen below the bars' floor.
        assertThat(at(1_000_000).toDouble()).isLessThan(-48.0)
    }

    @Test
    fun `frames are stamped with the media time they end at, one every 16 ms`() {
        levels.watch()
        val rate = 48_000 // 16 ms is exactly 768 frames here.
        val startUs = 5_000_000L

        feed(sine(1_000.0, rate, 0.16, 0.5), rate, chunkFrames = 500, startUs = startUs)

        assertThat(levels.newestFrame(newest)).isEqualTo(startUs + 10 * 16_000L)
    }

    @Test
    fun `audio measured before anyone watches is readable as soon as a viewer arrives`() {
        feed(sine(1_000.0, 48_000, 0.2, 0.5), 48_000)
        assertThat(levels.newestFrame(newest)).isNotNull()

        val watching = levels.watch()
        levels.setRunning(true)
        levels.reportPlayed(100_000)

        // The viewer did not restart the meter: the frame heard now is already there, at the tone's level.
        assertThat(levels.read(newest)).isTrue()
        assertThat(newest[LevelBand.MIDS.ordinal].toDouble()).isWithin(1.0).of(-6.02)

        // Frames keep their continuity across the arrival: the next buffer extends the same ring.
        val before = checkNotNull(levels.newestFrame(newest))
        feed(sine(1_000.0, 48_000, 0.1, 0.5), 48_000, startUs = 200_000)
        assertThat(levels.newestFrame(newest)).isGreaterThan(before)
        watching.close()
    }

    @Test
    fun `a viewer leaving does not stop the measuring, it only stops the reads`() {
        val watching = levels.watch()
        feed(sine(1_000.0, 48_000, 0.2, 0.5), 48_000)
        watching.close()

        feed(sine(1_000.0, 48_000, 0.2, 0.5), 48_000, startUs = 200_000)

        assertThat(levels.newestFrame(newest)).isGreaterThan(200_000L)
        levels.setRunning(true)
        levels.reportPlayed(300_000)
        assertThat(levels.read(newest)).isFalse()
    }

    @Test
    fun `a stream the meter cannot read leaves no levels`() {
        levels.watch()
        meter.configure(48_000, 2, C.ENCODING_AC3)
        meter.process(java.nio.ByteBuffer.allocateDirect(4_096), 0L)

        assertThat(levels.newestFrame(newest)).isNull()
    }
}
