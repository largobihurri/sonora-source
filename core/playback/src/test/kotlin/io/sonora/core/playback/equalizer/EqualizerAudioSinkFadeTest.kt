package io.sonora.core.playback.equalizer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.playback.crossfade.FadeGain
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Test

/** The crossfade level rides on the player's own volume at the sink, picked up with the next buffer. */
@OptIn(UnstableApi::class)
class EqualizerAudioSinkFadeTest {

    private var volume: Float? = null

    private val downstream: AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "setVolume" -> volume = args[0] as Float
                "handleBuffer" -> return@InvocationHandler true
                else -> throw UnsupportedOperationException(method.name)
            }
            null
        },
    ) as AudioSink

    private val settings = EqualizerSettings()

    private fun buffer() = ByteBuffer.allocate(16)

    @Test
    fun `the fade level multiplies the player's volume from the next buffer on`() {
        val fade = FadeGain()
        val sink = EqualizerAudioSink(downstream, EqualizerAudioProcessor { settings }, { settings }, fadeGain = fade)

        sink.setVolume(0.5f)
        assertThat(volume).isEqualTo(0.5f)

        fade.gain = 0.5f
        assertThat(volume).isEqualTo(0.5f)
        sink.handleBuffer(buffer(), 0L, 1)
        assertThat(volume).isEqualTo(0.25f)

        sink.setVolume(1f)
        assertThat(volume).isEqualTo(0.5f)
    }

    @Test
    fun `an unchanged level is not set again`() {
        val fade = FadeGain()
        val sink = EqualizerAudioSink(downstream, EqualizerAudioProcessor { settings }, { settings }, fadeGain = fade)
        fade.gain = 0f
        sink.handleBuffer(buffer(), 0L, 1)
        assertThat(volume).isEqualTo(0f)

        volume = null
        sink.handleBuffer(buffer(), 0L, 1)
        assertThat(volume).isNull()
    }

    @Test
    fun `without a fade level the player's volume passes through`() {
        val sink = EqualizerAudioSink(downstream, EqualizerAudioProcessor { settings }, { settings })

        sink.setVolume(0.3f)
        sink.handleBuffer(buffer(), 0L, 1)

        assertThat(volume).isEqualTo(0.3f)
    }
}
