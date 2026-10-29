package io.sonora.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The AudioTrack's buffer: Media3's own sizing, but never for a speed the app cannot reach. */
@OptIn(UnstableApi::class)
class SpeedCappedBufferSizeProviderTest {

    private val media3 = DefaultAudioTrackBufferSizeProvider.Builder().build()
    private val capped = SpeedCappedBufferSizeProvider(media3, maxSpeed = 2.0)

    // 44.1 kHz stereo float, the track the device reports (8 bytes a frame); the minimum is a few ms.
    private fun bytes(provider: DefaultAudioSink.AudioTrackBufferSizeProvider, speed: Double): Int =
        // Min buffer, encoding, output mode, frame size, sample rate, bitrate (unused for PCM), speed.
        provider.getBufferSizeInBytes(7_056, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM, 8, 44_100, 0, speed)

    @Test
    fun `Media3's own sizing grows with the speed it is asked for (the 4 s track)`() {
        assertThat(bytes(media3, 8.0) / 8 / 44_100.0).isGreaterThan(3.5)
    }

    @Test
    fun `the 8x request gets the same size as the app's own top speed`() {
        assertThat(bytes(capped, 8.0)).isEqualTo(bytes(media3, 2.0))
        assertThat(bytes(capped, 8.0) / 8 / 44_100.0).isLessThan(1.1)
    }

    @Test
    fun `speeds up to the cap are unchanged`() {
        for (speed in listOf(0.5, 1.0, 1.5, 2.0)) assertThat(bytes(capped, speed)).isEqualTo(bytes(media3, speed))
    }
}
