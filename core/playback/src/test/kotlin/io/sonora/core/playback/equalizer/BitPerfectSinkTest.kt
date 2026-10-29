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
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.playback.output.BitPerfectGate
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Test

/** With bit-perfect output armed, the sink hands the stream on exactly as it came: no equalizer, no gain, no float. */
@OptIn(UnstableApi::class)
class BitPerfectSinkTest {

    private class Gate(override var armed: Boolean) : BitPerfectGate {
        val formats = mutableListOf<Format>()
        val order = mutableListOf<String>()
        override fun streamStarting(format: Format) {
            formats += format
            order += "gate"
        }
    }

    private val equalizer = EqualizerSettings(enabled = true, mode = EqualizerMode.FOUR_BANDS).withGain(EqualizerMode.FOUR_BANDS, 1, 9f)
    private val replayGain = ReplayGainSettings(ReplayGainMode.TRACK)

    private val received = ByteArrayOutputStream()
    private val encodings = mutableListOf<Int>()
    private var gate = Gate(armed = true)

    private fun downstream(): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val buffer = args[0] as ByteBuffer
                    received.write(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    true
                }
                "configure" -> {
                    gate.order += "configure"
                    encodings += (args[0] as AudioSink.AudioSinkConfig).format.pcmEncoding
                    null
                }
                "flush", "reset", "play", "pause", "setVolume" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        },
    ) as AudioSink

    private fun sink() = EqualizerAudioSink(downstream(), EqualizerAudioProcessor { equalizer }, { equalizer }, replayGain = { replayGain }, bitPerfect = gate)

    private fun format(encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(44_100)
        .setChannelCount(2)
        .setMetadata(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.00 dB")))
        .build()

    private fun samples16(): ByteBuffer {
        val bytes = ByteArray(4096) { (it * 37 + 11).toByte() }
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
    }

    @Test
    fun `a 16-bit stream keeps its encoding and its samples although the equalizer and ReplayGain are on`() {
        val sink = sink()
        val input = samples16()
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }

        sink.configure(AudioSink.AudioSinkConfig.Builder(format(C.ENCODING_PCM_16BIT)).build())
        repeat(20) { sink.handleBuffer(samples16(), it * 1_000L, 1) }

        assertThat(encodings).containsExactly(C.ENCODING_PCM_16BIT)
        val played = received.toByteArray()
        for (i in 0 until 20) assertThat(played.copyOfRange(i * expected.size, (i + 1) * expected.size)).isEqualTo(expected)
    }

    @Test
    fun `a 24-bit stream is handed on as it is for the sink to widen`() {
        val sink = sink()

        sink.configure(AudioSink.AudioSinkConfig.Builder(format(C.ENCODING_PCM_24BIT)).build())

        assertThat(encodings).containsExactly(C.ENCODING_PCM_24BIT)
    }

    @Test
    fun `the gate hears the stream before the sink below is configured`() {
        val sink = sink()
        val stream = format(C.ENCODING_PCM_16BIT)

        sink.configure(AudioSink.AudioSinkConfig.Builder(stream).build())

        assertThat(gate.order).containsExactly("gate", "configure").inOrder()
        assertThat(gate.formats).containsExactly(stream)
    }

    @Test
    fun `with the gate not armed the effects still turn a 16-bit stream into float`() {
        gate = Gate(armed = false)
        val sink = sink()

        sink.configure(AudioSink.AudioSinkConfig.Builder(format(C.ENCODING_PCM_16BIT)).build())

        assertThat(encodings).containsExactly(C.ENCODING_PCM_FLOAT)
    }
}
