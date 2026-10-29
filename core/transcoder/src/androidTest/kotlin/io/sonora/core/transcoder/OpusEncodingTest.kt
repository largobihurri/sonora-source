package io.sonora.core.transcoder

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.android.AndroidCoverScaler
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.OpusTagsMetadataChunkSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Encodes a synthetic 3 s, 1 kHz stereo sine at 44.1 kHz through the whole engine into Ogg Opus on the device's
 * `c2.android.opus.encoder`, then reads the file back with MediaExtractor and MediaCodec. Silent: nothing is played.
 */
@RunWith(AndroidJUnit4::class)
class OpusEncodingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceUri = "synthetic://sine"
    private val sourceFrames = 132_300 // 3 s at 44.1 kHz

    private class SineStream : PcmStream {
        override val format = PcmFormat(44_100, 2, PcmSampleFormat.INT16, "synthetic", null, 132_300L)
        private var frame = 0

        override suspend fun read(buffer: FloatArray): Int {
            if (frame >= 132_300) return -1
            val n = minOf(buffer.size / 2, 4_096, 132_300 - frame)
            for (i in 0 until n) {
                val v = (0.5 * sin(2 * PI * 1_000.0 * (frame + i) / 44_100.0)).toFloat()
                buffer[i * 2] = v
                buffer[i * 2 + 1] = v
            }
            frame += n
            return n
        }

        override fun close() = Unit
    }

    private val decoder = object : PcmDecoder {
        override suspend fun open(sourceUri: String): PcmStream = SineStream()
    }

    private val metadata = TrackMetadata(
        text = mapOf(
            MetadataTextField.TITLE to "Sine 1 kHz",
            MetadataTextField.ARTIST to "Sonora Test",
            MetadataTextField.ALBUM to "Synthetic",
            MetadataTextField.ALBUM_ARTIST to "Sonora Test Group",
            MetadataTextField.GENRE to "Test",
        ),
        date = "2026",
        trackNumber = 3,
        discNumber = 1,
        cover = cover(),
    )

    private fun cover(): CoverArt {
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 40, 40))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        return CoverArt(bytes, "image/jpeg", 900, 900)
    }

    private fun engine() = ConversionEngine(
        decoder,
        EncoderRegistry.default(),
        OpusTagsMetadataChunkSource(SourceMetadataReader { metadata }, AndroidCoverScaler()),
    )

    private fun encode(file: File): ConversionResult = runBlocking {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel -> engine().convert(sourceUri, ConversionProfiles.WATCH_OPUS, channel) }
    }

    @Test
    fun encodesAThreeSecondSineIntoADecodableOggOpusFileWithTags() {
        val file = File(context.cacheDir, "w1-sine.opus")
        val result = encode(file)
        val bytes = file.readBytes()
        println("W1 opus: ${bytes.size} bytes, ${result.outputFrames} output frames, tags written ${result.tagsWritten}")

        // Ogg framing and the two Opus headers, in order.
        assertThat(String(bytes, 0, 4, Charsets.ISO_8859_1)).isEqualTo("OggS")
        val head = indexOf(bytes, "OpusHead")
        val tags = indexOf(bytes, "OpusTags")
        assertThat(head).isAtLeast(0)
        assertThat(tags).isGreaterThan(head)
        assertThat(result.tagsWritten).isTrue()
        assertThat(result.plan.targetSampleRateHz).isEqualTo(48_000)
        assertThat(result.outputFrames).isEqualTo(144_000)
        val preSkip = (bytes[head + 10].toInt() and 0xFF) or ((bytes[head + 11].toInt() and 0xFF) shl 8)
        println("W1 opus: pre-skip $preSkip")
        assertThat(preSkip).isIn(Range.closed(0, 3_840))

        val comments = vorbisComments(bytes, tags)
        println("W1 opus: comments ${comments.map { it.substringBefore('=') }}")
        assertThat(comments).contains("TITLE=Sine 1 kHz")
        assertThat(comments).contains("ARTIST=Sonora Test")
        assertThat(comments).contains("ALBUM=Synthetic")
        assertThat(comments).contains("ALBUMARTIST=Sonora Test Group")
        assertThat(comments).contains("TRACKNUMBER=3")
        assertThat(comments).contains("DISCNUMBER=1")
        assertThat(comments).contains("DATE=2026")
        assertThat(comments).contains("GENRE=Test")
        assertThat(comments.none { it.startsWith("R128_TRACK_GAIN=") }).isTrue()
        val picture = comments.single { it.startsWith("METADATA_BLOCK_PICTURE=") }.substringAfter('=')
        val block = Base64.getDecoder().decode(picture)
        val width = int(block, 4 + 4 + 10 + 4)
        println("W1 opus: cover block ${block.size} bytes, ${width} px wide")
        assertThat(int(block, 0)).isEqualTo(3)
        assertThat(width).isAtMost(300)

        // The platform reads the file and decodes it, and the length is exact within one 20 ms frame.
        val (format, decodedFrames) = decode(file)
        val seconds = decodedFrames / 48_000.0
        println("W1 opus: extractor ${format.getString(MediaFormat.KEY_MIME)} ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz, decoded $decodedFrames frames = $seconds s")
        assertThat(format.getString(MediaFormat.KEY_MIME)).isEqualTo(MediaFormat.MIMETYPE_AUDIO_OPUS)
        assertThat(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).isEqualTo(2)
        assertThat(abs(seconds - 3.0)).isAtMost(0.020)
    }

    private fun int(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun indexOf(bytes: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** The comments of the OpusTags packet at [at]; the flat test cover keeps the packet inside one page. */
    private fun vorbisComments(bytes: ByteArray, at: Int): List<String> {
        var p = at + 8
        fun le() = ((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
            ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 3].toInt() and 0xFF) shl 24)).also { p += 4 }
        // Not `p += le()`: that reads p before le() moves it past the length field.
        val vendorLength = le()
        p += vendorLength
        val count = le()
        return List(count) {
            val length = le()
            String(bytes, p, length, Charsets.UTF_8).also { p += length }
        }
    }

    /** Decodes the Opus track with MediaExtractor + MediaCodec; returns the track format and the PCM frames. */
    private fun decode(file: File): Pair<MediaFormat, Long> {
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
            var inputDone = false
            var bytesOut = 0L
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
                        bytesOut += info.size
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
            return format to bytesOut / (2L * channels)
        } finally {
            extractor.release()
        }
    }
}
