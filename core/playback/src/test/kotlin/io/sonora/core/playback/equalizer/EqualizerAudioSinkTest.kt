package io.sonora.core.playback.equalizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/** The equalizer as the player runs it: in front of the audio sink, which takes the stream's own buffers in bypass. */
@OptIn(UnstableApi::class)
class EqualizerAudioSinkTest {

    private var settings = EqualizerSettings()

    /** The sink downstream: takes every buffer whole, keeps its bytes and counts the stream's own buffers it got. */
    private inner class CapturingSink {
        val received = ByteArrayOutputStream()
        var input: ByteBuffer? = null
        var ownBuffers = 0

        val sink: AudioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "handleBuffer" -> {
                        val buffer = args[0] as ByteBuffer
                        if (buffer === input) ownBuffers++
                        received.write(ByteArray(buffer.remaining()).also { buffer.get(it) })
                        true
                    }
                    "configure", "flush", "reset", "play", "pause" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as AudioSink
    }

    private fun sine(frames: Int, from: Int): FloatArray =
        FloatArray(frames * CHANNELS) { i -> (0.5 * sin(2 * PI * 1_000.0 * (from + i / CHANNELS) / RATE)).toFloat() }

    @Test
    fun `switched back on after the sink took the stream's own buffers, it starts as if the filters had followed`() {
        val curves = listOf(
            EqualizerSettings(enabled = true).withGain(EqualizerMode.FOUR_BANDS, 1, 9f),
            // Without the automatic preamp the limiter is on as well, with a delay line of its own.
            EqualizerSettings(enabled = true, automaticPreamp = false).withGain(EqualizerMode.FOUR_BANDS, 3, 12f),
        )
        for (curve in curves) {
            settings = curve
            val downstream = CapturingSink()
            val sink = EqualizerAudioSink(downstream.sink, EqualizerAudioProcessor { settings }, { settings })
            // What the processor alone gives when it gets every buffer, the bypassed ones too.
            val reference = EqualizerAudioProcessor { settings }.apply {
                floatOutput = true
                configure(AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_FLOAT))
                flush()
            }
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE)
                .setChannelCount(CHANNELS)
                .build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

            var frame = 0
            fun feed(chunks: Int): Pair<ByteArray, ByteArray> {
                val expected = ByteArrayOutputStream()
                downstream.received.reset()
                repeat(chunks) {
                    val samples = sine(CHUNK_FRAMES, frame)
                    frame += CHUNK_FRAMES
                    reference.queueInput(Pcm.encode(samples, 0, samples.size, C.ENCODING_PCM_FLOAT))
                    val out = reference.output
                    expected.write(ByteArray(out.remaining()).also { out.get(it) })
                    val buffer = Pcm.encode(samples, 0, samples.size, C.ENCODING_PCM_FLOAT)
                    downstream.input = buffer
                    assertThat(sink.handleBuffer(buffer, frame * 1_000_000L / RATE, 1)).isTrue()
                }
                return downstream.received.toByteArray() to expected.toByteArray()
            }

            feed(chunks = 20)
            settings = settings.withEnabled(false)
            feed(chunks = 20)
            assertThat(downstream.ownBuffers).isGreaterThan(10)
            settings = settings.withEnabled(true)
            val (played, expected) = feed(chunks = 20)

            assertThat(played).isEqualTo(expected)
        }
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 512
    }
}
