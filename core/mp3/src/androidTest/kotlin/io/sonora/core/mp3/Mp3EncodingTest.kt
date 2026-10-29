package io.sonora.core.mp3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Encodes a synthetic 1 s, 440 Hz stereo sine at 44.1 kHz with the vendored LAME (CBR 320 from 16-bit input, VBR V0
 * from float input), then reads the file back with MediaExtractor and MediaCodec. Silent: nothing is played.
 */
@RunWith(AndroidJUnit4::class)
class Mp3EncodingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = 44_100
    private val tone = 440.0

    private fun sample(frame: Int) = 0.5 * sin(2 * PI * tone * frame / rate)

    @Test
    fun encodesCbr320FromShortsIntoAPlayableMp3() {
        val (bytes, tag) = Mp3Encoder.open(rate, 2, Mp3Mode.Cbr(320)).use { encoder ->
            encode(encoder) { from, frames ->
                val pcm = ShortArray(frames * 2) { (sample(from + it / 2) * 32_767).toInt().toShort() }
                encoder.encode(pcm)
            }
        }
        println("MP3 CBR 320: ${bytes.size} bytes, info frame ${tag.size} bytes")

        val frames = frameSizes(bytes)
        // 1 s at 320 kbps is 40,000 bytes; the info frame and LAME's delay and padding add up to three frames.
        assertThat(bytes.size).isIn(Range.closed(40_000, 44_000))
        assertThat(frames.toSet()).isEqualTo(setOf(1_044, 1_045))
        assertThat(String(tag, 36, 4, Charsets.ISO_8859_1)).isEqualTo("Info")
        assertDecodesToTheTone(bytes, "cbr320")
    }

    @Test
    fun encodesVbrV0FromFloatsIntoAPlayableMp3() {
        val (bytes, tag) = Mp3Encoder.open(rate, 2, Mp3Mode.Vbr(0)).use { encoder ->
            encode(encoder) { from, frames ->
                encoder.encode(FloatArray(frames * 2) { sample(from + it / 2).toFloat() })
            }
        }
        println("MP3 VBR V0: ${bytes.size} bytes, info frame ${tag.size} bytes")

        val frames = frameSizes(bytes)
        // A pure tone needs few bits: about 8.4 kB from the same LAME build on a desktop, never more than CBR 320.
        assertThat(bytes.size).isIn(Range.closed(4_000, 44_000))
        assertThat(frames.size).isIn(Range.closed(39, 42))
        assertThat(String(tag, 36, 4, Charsets.ISO_8859_1)).isEqualTo("Xing")
        assertDecodesToTheTone(bytes, "vbr0")
    }

    /**
     * Feeds 1 s in 4,096-frame chunks through [chunk], flushes, and puts the info frame over the stream's first
     * frame, as a caller writing a file does. Returns the stream and the info frame.
     */
    private fun encode(encoder: Mp3Encoder, chunk: (from: Int, frames: Int) -> ByteArray): Pair<ByteArray, ByteArray> {
        val out = ByteArrayOutputStream()
        var frame = 0
        while (frame < rate) {
            val n = minOf(4_096, rate - frame)
            out.write(chunk(frame, n))
            frame += n
        }
        out.write(encoder.flush())
        val bytes = out.toByteArray()
        val tag = encoder.lametagFrame()
        assertThat(tag).isNotEmpty()
        assertThat(String(tag, Charsets.ISO_8859_1)).contains("LAME3.100")
        tag.copyInto(bytes)
        return bytes to tag
    }

    /** Walks the stream frame by frame: every frame starts with an MPEG-1 layer III sync word at 44.1 kHz. */
    private fun frameSizes(bytes: ByteArray): List<Int> {
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val sizes = mutableListOf<Int>()
        var at = 0
        while (at < bytes.size) {
            val b1 = bytes[at + 1].toInt() and 0xFF
            val b2 = bytes[at + 2].toInt() and 0xFF
            assertThat(bytes[at].toInt() and 0xFF).isEqualTo(0xFF)
            assertThat(b1 and 0xFE).isEqualTo(0xFA) // sync, MPEG-1, layer III (protection bit either way)
            assertThat((b2 shr 2) and 0x03).isEqualTo(0) // 44.1 kHz
            val size = 144_000 * kbps[b2 shr 4] / rate + ((b2 shr 1) and 0x01)
            sizes += size
            at += size
        }
        assertThat(at).isEqualTo(bytes.size)
        return sizes
    }

    private fun assertDecodesToTheTone(bytes: ByteArray, name: String) {
        val file = File(context.cacheDir, "mp3-$name.mp3").apply { writeBytes(bytes) }
        val (format, left) = decode(file)
        val seconds = left.size / rate.toDouble()
        println("MP3 $name: decoded ${left.size} frames = $seconds s")
        assertThat(format.getString(MediaFormat.KEY_MIME)).isEqualTo(MediaFormat.MIMETYPE_AUDIO_MPEG)
        assertThat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)).isEqualTo(rate)
        assertThat(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).isEqualTo(2)
        assertThat(abs(seconds - 1.0)).isAtMost(0.1)

        // The strongest frequency between 100 Hz and 4 kHz, over the middle half second, is the tone.
        val middle = left.copyOfRange(left.size / 4, left.size / 4 + rate / 2)
        val peak = (100..4_000 step 5).maxBy { power(middle, it.toDouble()) }
        println("MP3 $name: peak at $peak Hz")
        assertThat(peak).isIn(Range.closed(435, 445))
        assertThat(power(middle, tone)).isGreaterThan(100 * power(middle, 1_000.0))
    }

    /** Goertzel power of [samples] at [hz]. */
    private fun power(samples: FloatArray, hz: Double): Double {
        val coefficient = 2 * cos(2 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }

    /** Decodes the MP3 with MediaExtractor + MediaCodec; returns the track format and the left channel. */
    private fun decode(file: File): Pair<MediaFormat, FloatArray> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            assertThat(extractor.trackCount).isEqualTo(1)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val left = ArrayList<Float>()
            var inputDone = false
            var channels = 2
            try {
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    if (out >= 0) {
                        val pcm = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        val shorts = pcm.asShortBuffer()
                        for (i in 0 until shorts.remaining() / channels) left += shorts.get(i * channels) / 32_768f
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return format to left.toFloatArray()
        } finally {
            extractor.release()
        }
    }
}
