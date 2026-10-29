package io.sonora.core.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecodeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

/** The decode loop of [MediaCodecPcmReader] against scripted codecs (the platform's edge cases, on the JVM). */
class MediaCodecPcmReaderTest {

    private val stereo44k = AudioOutputFormat(AudioFormat.ENCODING_PCM_FLOAT, 44_100, 2)
    private val track = TrackInfo(
        mime = "audio/mpeg",
        sampleRateHz = 44_100,
        channelCount = 2,
        durationUs = 1_000_000,
        maxInputSize = null,
        pcmEncoding = null,
        sourceSampleFormat = null,
    )

    /** An extractor with [samples] compressed samples of 16 bytes. */
    private class FakeSource(private var samples: Int = 100) : SampleSource {
        var releases = 0

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
            if (samples <= 0) return -1
            buffer.position(offset)
            buffer.put(ByteArray(16))
            return 16
        }

        override val sampleTime: Long get() = 0

        override fun advance() {
            samples--
        }

        override fun release() {
            releases++
        }
    }

    /**
     * A codec that takes every input and answers output requests from [script] in order, then "try again later"
     * forever. Its output format starts as [initialFormat] and follows the [Event.FormatChanged] it hands out.
     */
    private class ScriptedCodec(initialFormat: AudioOutputFormat, vararg script: Event) : DecoderCodec, ConfigurableCodec {
        sealed interface Event {
            class Buffer(val bytes: ByteArray, val endOfStream: Boolean = false) : Event
            class FormatChanged(val format: AudioOutputFormat) : Event
        }

        private val events = ArrayDeque(script.toList())
        private val buffers = mutableMapOf<Int, ByteArray>()
        private var nextIndex = 0
        var acceptsInput = true
        var releases = 0
        var outputRounds = 0
        var onOutputRound: (round: Int) -> Unit = {}
        val calls = mutableListOf<String>()
        var refusesFloat = false
        override var outputFormat: AudioOutputFormat = initialFormat

        override fun configure(floatOutput: Boolean) {
            calls += "configure(float=$floatOutput)"
            if (floatOutput && refusesFloat) throw IllegalArgumentException("float output not supported")
            if (!floatOutput) outputFormat = outputFormat.copy(pcmEncoding = null)
        }

        override fun reset() {
            calls += "reset"
        }

        override fun start() {
            calls += "start"
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int = if (acceptsInput) 0 else MediaCodec.INFO_TRY_AGAIN_LATER

        override fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(64)

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = Unit

        override fun dequeueOutputBuffer(info: OutputBufferInfo, timeoutUs: Long): Int {
            onOutputRound(++outputRounds)
            return when (val event = events.removeFirstOrNull()) {
                null -> MediaCodec.INFO_TRY_AGAIN_LATER
                is Event.FormatChanged -> {
                    outputFormat = event.format
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                }
                is Event.Buffer -> {
                    val index = nextIndex++
                    buffers[index] = event.bytes
                    info.offset = 0
                    info.size = event.bytes.size
                    info.flags = if (event.endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    index
                }
            }
        }

        override fun getOutputBuffer(index: Int): ByteBuffer = ByteBuffer.wrap(checkNotNull(buffers[index]))

        override fun releaseOutputBuffer(index: Int) {
            buffers.remove(index)
        }

        override fun release() {
            releases++
        }
    }

    private fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).apply { values.forEach { putFloat(it) } }.array()

    private fun shorts(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder()).apply { values.forEach { putShort(it.toShort()) } }.array()

    private fun MediaCodecPcmReader.readAll(): List<Float> {
        val all = mutableListOf<Float>()
        val buffer = FloatArray(64)
        while (true) {
            val frames = read(buffer)
            if (frames < 0) return all
            all += buffer.take(frames * format.channelCount)
        }
    }

    @Test
    fun `the format comes from the codec's first output format`() {
        val codec = ScriptedCodec(
            stereo44k,
            ScriptedCodec.Event.FormatChanged(AudioOutputFormat(AudioFormat.ENCODING_PCM_FLOAT, 48_000, 1)),
            ScriptedCodec.Event.Buffer(floats(0.25f, -0.5f), endOfStream = true),
        )

        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)

        assertThat(reader.format.sampleRateHz).isEqualTo(48_000)
        assertThat(reader.format.channelCount).isEqualTo(1)
        assertThat(reader.readAll()).containsExactly(0.25f, -0.5f).inOrder()
    }

    @Test
    fun `the encoder delay and padding the container states are trimmed in frames from the codec output`() {
        // An MP3 with a LAME header or an AAC with priming: the extractor states the delay and the padding, the codec is
        // configured without them (see PlatformDecoderCodec) and the reader trims them, as Media3's player does.
        val codec = ScriptedCodec(
            stereo44k,
            ScriptedCodec.Event.Buffer(floats(0.0f, 0.0f, 0.1f, 0.1f)),
            ScriptedCodec.Event.Buffer(floats(0.2f, 0.2f, 0.3f, 0.3f, 0.4f, 0.4f)),
            ScriptedCodec.Event.Buffer(floats(0.5f, 0.5f), endOfStream = true),
        )
        val trimmed = track.copy(encoderDelayFrames = 3, encoderPaddingFrames = 1)

        val reader = MediaCodecPcmReader.create("content://a.mp3", trimmed, FakeSource(), codec)

        assertThat(reader.readAll()).containsExactly(0.3f, 0.3f, 0.4f, 0.4f).inOrder()
    }

    @Test
    fun `the trim counts frames whatever the codec's output encoding`() {
        val codec = ScriptedCodec(
            AudioOutputFormat(AudioFormat.ENCODING_PCM_16BIT, 44_100, 2),
            ScriptedCodec.Event.Buffer(shorts(0, 0, 16_384, -16_384, 8_192, -8_192), endOfStream = true),
        )

        val reader = MediaCodecPcmReader.create("content://a.mp3", track.copy(encoderDelayFrames = 1), FakeSource(), codec)

        assertThat(reader.readAll()).containsExactly(0.5f, -0.5f, 0.25f, -0.25f).inOrder()
    }

    @Test
    fun `a stream no longer than its delay and padding decodes to nothing`() {
        val codec = ScriptedCodec(stereo44k, ScriptedCodec.Event.Buffer(floats(0.1f, 0.1f, 0.2f, 0.2f), endOfStream = true))

        val reader = MediaCodecPcmReader.create(
            "content://a.mp3", track.copy(encoderDelayFrames = 1, encoderPaddingFrames = 1), FakeSource(), codec,
        )

        assertThat(reader.read(FloatArray(64))).isEqualTo(-1)
    }

    @Test
    fun `a mid-stream change of sample rate is corrupt data`() {
        val codec = ScriptedCodec(
            stereo44k,
            ScriptedCodec.Event.Buffer(floats(0.1f, 0.1f)),
            ScriptedCodec.Event.FormatChanged(AudioOutputFormat(AudioFormat.ENCODING_PCM_FLOAT, 48_000, 2)),
            ScriptedCodec.Event.Buffer(floats(0.2f, 0.2f), endOfStream = true),
        )
        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)
        reader.read(FloatArray(64))

        val failure = assertThrows(PcmDecodeException.CorruptData::class.java) { reader.read(FloatArray(64)) }

        assertThat(failure.detail).contains("48000 Hz mid-stream")
    }

    @Test
    fun `a mid-stream change of channel count is corrupt data`() {
        val codec = ScriptedCodec(
            stereo44k,
            ScriptedCodec.Event.Buffer(floats(0.1f, 0.1f)),
            ScriptedCodec.Event.FormatChanged(AudioOutputFormat(AudioFormat.ENCODING_PCM_FLOAT, 44_100, 1)),
        )
        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)
        reader.read(FloatArray(64))

        val failure = assertThrows(PcmDecodeException.CorruptData::class.java) { reader.read(FloatArray(64)) }

        assertThat(failure.detail).contains("to 1 at 44100 Hz mid-stream")
    }

    @Test
    fun `a codec that neither takes input nor gives output times out as corrupt data and is released`() {
        val codec = ScriptedCodec(stereo44k).apply { acceptsInput = false }
        val source = FakeSource()

        val failure = assertThrows(PcmDecodeException.CorruptData::class.java) {
            MediaCodecPcmReader.create("content://a.mp3", track, source, codec)
        }

        assertThat(failure.detail).contains("stopped producing output")
        assertThat(codec.outputRounds).isGreaterThan(100)
        assertThat(codec.releases).isEqualTo(1)
        assertThat(source.releases).isEqualTo(1)
    }

    @Test
    fun `a codec that refuses float output is configured without it and still decodes`() {
        val codec = ScriptedCodec(
            stereo44k,
            ScriptedCodec.Event.Buffer(shorts(16_384, -16_384), endOfStream = true),
        ).apply { refusesFloat = true }

        configureAndStart(codec)
        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)

        assertThat(codec.calls).containsExactly("configure(float=true)", "reset", "configure(float=false)", "start").inOrder()
        assertThat(reader.readAll()).containsExactly(0.5f, -0.5f).inOrder()
    }

    @Test
    fun `a codec that accepts float output is configured once`() {
        val codec = ScriptedCodec(stereo44k)

        configureAndStart(codec)

        assertThat(codec.calls).containsExactly("configure(float=true)", "start").inOrder()
    }

    @Test
    fun `an encoding change drops the incomplete frame of the old encoding`() {
        val codec = ScriptedCodec(
            AudioOutputFormat(AudioFormat.ENCODING_PCM_16BIT, 44_100, 2),
            // One 16-bit stereo frame plus half of the next: the half frame must not leak into the float data.
            ScriptedCodec.Event.Buffer(shorts(16_384, -16_384, 8_192)),
            ScriptedCodec.Event.FormatChanged(stereo44k),
            ScriptedCodec.Event.Buffer(floats(0.25f, -0.25f), endOfStream = true),
        )

        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)

        assertThat(reader.readAll()).containsExactly(0.5f, -0.5f, 0.25f, -0.25f).inOrder()
    }

    @Test
    fun `cancel stops a read stuck on an idle codec before its timeout`() {
        val codec = ScriptedCodec(stereo44k, ScriptedCodec.Event.Buffer(floats(0.1f, 0.1f)))
        val reader = MediaCodecPcmReader.create("content://a.mp3", track, FakeSource(), codec)
        reader.read(FloatArray(64))
        codec.acceptsInput = false
        val stalledFrom = codec.outputRounds
        codec.onOutputRound = { round -> if (round == stalledFrom + 3) reader.cancel() }

        assertThrows(StreamClosedException::class.java) { reader.read(FloatArray(64)) }

        assertThat(codec.outputRounds).isEqualTo(stalledFrom + 3)
        reader.close()
        assertThat(codec.releases).isEqualTo(1)
    }
}
