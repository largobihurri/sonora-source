package io.sonora.core.playback.equalizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.playback.replaygain.GainStage
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import org.junit.Test

/** ReplayGain as the player applies it: a gain stage in front of the equalizer, on the PCM handed to the sink. */
@OptIn(UnstableApi::class)
class ReplayGainSinkTest {

    private var equalizer = EqualizerSettings()
    private var replayGain = ReplayGainSettings(ReplayGainMode.TRACK)

    /** The sink downstream: takes every buffer whole and keeps its bytes, its configured encoding and its own buffers. */
    private inner class CapturingSink {
        val received = ByteArrayOutputStream()
        var input: ByteBuffer? = null
        var ownBuffers = 0
        val encodings = mutableListOf<Int>()

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
                    "configure" -> {
                        encodings += (args[0] as AudioSink.AudioSinkConfig).format.pcmEncoding
                        null
                    }
                    "flush", "reset", "play", "pause" -> null
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as AudioSink
    }

    private val downstream = CapturingSink()
    private val sink = EqualizerAudioSink(downstream.sink, EqualizerAudioProcessor { equalizer }, { equalizer }, replayGain = { replayGain })
    private var frame = 0

    private fun stream(vararg tags: Pair<String, String>, encoding: Int = C.ENCODING_PCM_16BIT): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(RATE)
        .setChannelCount(CHANNELS)
        .setMetadata(Metadata(tags.map { (key, value) -> VorbisComment(key, value) }))
        .build()

    private fun configure(format: Format) = sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

    /** Feeds [chunks] buffers of a 1 kHz sine at [amplitude]; returns what the sink played, decoded to float. */
    private fun feed(chunks: Int, amplitude: Double = 0.5, encoding: Int = C.ENCODING_PCM_16BIT): FloatArray {
        downstream.received.reset()
        repeat(chunks) {
            val samples = FloatArray(CHUNK_FRAMES * CHANNELS) { i -> (amplitude * sin(2 * PI * 1_000.0 * (frame + i / CHANNELS) / RATE)).toFloat() }
            frame += CHUNK_FRAMES
            val buffer = Pcm.encode(samples, 0, samples.size, encoding)
            downstream.input = buffer
            assertThat(sink.handleBuffer(buffer, frame * 1_000_000L / RATE, 1)).isTrue()
        }
        val played = downstream.encodings.last()
        val bytes = downstream.received.toByteArray()
        val out = FloatArray(bytes.size / if (played == C.ENCODING_PCM_16BIT) 2 else 4)
        Pcm.decode(ByteBuffer.wrap(bytes), played, out, 0)
        return out
    }

    private fun peak(samples: FloatArray, from: Int = samples.size / 2): Double {
        var peak = 0.0
        for (i in from until samples.size) peak = max(peak, abs(samples[i].toDouble()))
        return peak
    }

    @Test
    fun `a tagged stream plays at its gain, in float`() {
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"))

        val played = feed(chunks = 20)

        assertThat(downstream.encodings.last()).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(peak(played)).isWithin(0.002).of(0.25)
    }

    @Test
    fun `the gain applies from the stream's first sample`() {
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"))

        val played = feed(chunks = 1)

        assertThat(peak(played, from = 0)).isWithin(0.002).of(0.25)
    }

    @Test
    fun `off, a 16-bit stream keeps a 16-bit track and reaches the sink untouched`() {
        replayGain = ReplayGainSettings(ReplayGainMode.OFF, preampDb = 6f)
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"))

        feed(chunks = 10)

        assertThat(downstream.encodings.last()).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(downstream.ownBuffers).isEqualTo(10)
    }

    @Test
    fun `an untagged stream is untouched`() {
        replayGain = ReplayGainSettings(ReplayGainMode.TRACK, preampDb = 6f)
        configure(stream(encoding = C.ENCODING_PCM_FLOAT))

        feed(chunks = 10, encoding = C.ENCODING_PCM_FLOAT)

        assertThat(downstream.ownBuffers).isEqualTo(10)
    }

    @Test
    fun `the peak keeps a boost from clipping`() {
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "+12.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.5"))

        val played = feed(chunks = 20)

        assertThat(peak(played, from = 0)).isAtMost(1.0)
        assertThat(peak(played)).isWithin(0.002).of(1.0)
    }

    @Test
    fun `the next gapless song moves to its own gain without a click`() {
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"))
        val first = feed(chunks = 20)
        // The next song in the same format: no flush, as in gapless playback.
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-12.04 dB"))
        val second = feed(chunks = 20)

        val joined = first + second
        val reference = FloatArray(joined.size) { i -> (sin(2 * PI * 1_000.0 * (i / CHANNELS) / RATE)).toFloat() }
        // The gain itself (output over a full-scale sine) never steps: it moves by a sliver per frame.
        var largestStep = 0.0
        var previous = 0.25
        for (f in 0 until joined.size / CHANNELS) {
            val r = reference[f * CHANNELS]
            if (abs(r) < 0.5f) continue
            val gain = joined[f * CHANNELS] / r.toDouble()
            largestStep = max(largestStep, abs(gain - previous))
            previous = gain
        }
        val rampFrames = GainStage.RAMP_SECONDS * RATE
        assertThat(largestStep).isLessThan(0.125 / rampFrames * 30)
        assertThat(peak(second)).isWithin(0.002).of(0.125)
        assertThat(downstream.encodings.distinct()).containsExactly(C.ENCODING_PCM_FLOAT)
    }

    @Test
    fun `a settings change applies to the song playing`() {
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB", "REPLAYGAIN_ALBUM_GAIN" to "-12.04 dB"))
        feed(chunks = 10)

        replayGain = ReplayGainSettings(ReplayGainMode.ALBUM)
        val album = feed(chunks = 20)
        replayGain = ReplayGainSettings(ReplayGainMode.ALBUM, preampDb = 6.02f)
        val boosted = feed(chunks = 20)

        assertThat(peak(album)).isWithin(0.002).of(0.125)
        assertThat(peak(boosted)).isWithin(0.002).of(0.25)
    }

    @Test
    fun `the gain stage runs in front of the equalizer`() {
        equalizer = EqualizerSettings(enabled = true)
        configure(stream("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"))

        val played = feed(chunks = 20)

        assertThat(peak(played)).isWithin(0.002).of(0.25)
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 512
    }
}
