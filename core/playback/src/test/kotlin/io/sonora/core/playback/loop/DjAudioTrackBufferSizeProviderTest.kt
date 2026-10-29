package io.sonora.core.playback.loop

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.SpeedCappedBufferSizeProvider
import org.junit.Test

/** The AudioTrack's depth in DJ mode: about 125 ms, so a loop engages soon after its Out. */
@OptIn(UnstableApi::class)
class DjAudioTrackBufferSizeProviderTest {

    private val media3 = DefaultAudioTrackBufferSizeProvider.Builder().build()
    private var djMode = true
    private val provider = DjAudioTrackBufferSizeProvider(SpeedCappedBufferSizeProvider(media3, maxSpeed = 2.0), { djMode })

    private fun bytes(
        mode: Int = DefaultAudioSink.OUTPUT_MODE_PCM,
        frameSize: Int = 8,
        rate: Int = 44_100,
        min: Int = 7_056,
        encoding: Int = C.ENCODING_PCM_FLOAT,
        bitrate: Int = 0,
    ) = provider.getBufferSizeInBytes(min, encoding, mode, frameSize, rate, bitrate, 2.0)

    @Test
    fun `in DJ mode the buffer holds about 125 ms of the stream`() {
        for ((rate, frameSize) in listOf(44_100 to 8, 48_000 to 4, 96_000 to 12)) {
            val ms = bytes(frameSize = frameSize, rate = rate) * 1_000.0 / (frameSize * rate)
            assertThat(ms).isWithin(1.0).of(DjAudioTrackBufferSizeProvider.DJ_BUFFER_MS.toDouble())
        }
    }

    @Test
    fun `the buffer is a whole number of frames`() {
        assertThat(bytes(frameSize = 12, rate = 44_100) % 12).isEqualTo(0)
        assertThat(bytes(frameSize = 6, rate = 44_100) % 6).isEqualTo(0)
    }

    @Test
    fun `outside DJ mode the sizing is the usual one`() {
        djMode = false

        val usual = SpeedCappedBufferSizeProvider(media3, maxSpeed = 2.0)
            .getBufferSizeInBytes(7_056, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM, 8, 44_100, 0, 2.0)
        assertThat(bytes()).isEqualTo(usual)
        // The usual size is the better part of a second of float stereo.
        assertThat(usual).isGreaterThan(44_100 * 8 / 2)
    }

    @Test
    fun `the DJ buffer is much smaller than the usual one`() {
        val dj = bytes()
        djMode = false
        assertThat(dj * 3).isLessThan(bytes())
    }

    @Test
    fun `a stream that is not PCM keeps the usual size`() {
        val usual = SpeedCappedBufferSizeProvider(media3, maxSpeed = 2.0)
            .getBufferSizeInBytes(20_000, C.ENCODING_AC3, DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH, 1, 48_000, 640_000, 2.0)

        assertThat(
            bytes(
                mode = DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH, frameSize = 1, rate = 48_000, min = 20_000,
                encoding = C.ENCODING_AC3, bitrate = 640_000,
            ),
        ).isEqualTo(usual)
    }

    @Test
    fun `the platform's minimum is a floor`() {
        // A minimum of 100 ms of float stereo at 44.1 kHz: above the 125 ms request only if it is bigger than it.
        val big = 8 * 44_100 / 2
        assertThat(bytes(min = big)).isAtLeast(big - big % 8)
    }

    @Test
    fun `the figure is the named constant`() {
        assertThat(DjAudioTrackBufferSizeProvider.DJ_BUFFER_MS).isIn(100..150)
    }
}
