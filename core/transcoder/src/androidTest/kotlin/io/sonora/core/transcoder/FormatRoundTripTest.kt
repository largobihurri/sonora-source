package io.sonora.core.transcoder

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.sonora.core.decoder.AndroidPcmDecoder
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.android.AndroidCoverScaler
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.ContainerMetadataChunkSource
import io.sonora.core.transcoder.metadata.FlacMetadataChunkSource
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import io.sonora.core.transcoder.metadata.Mp4MetadataChunkSource
import io.sonora.core.transcoder.metadata.OpusTagsMetadataChunkSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every output format through the whole engine on the device's codecs: a synthetic 1 s, 1 kHz stereo sine with tags
 * and a cover is encoded, verified the way the batch runner does it (re-decoded by [AndroidPcmDecoder]), and checked
 * for its duration, sample rate and dominant frequency, then for the tags Android itself reads back. Silent: nothing
 * is played.
 */
@RunWith(AndroidJUnit4::class)
class FormatRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceUri = "synthetic://sine"
    private val tone = 1_000.0

    private class SineStream(override val format: PcmFormat, private val tone: Double) : PcmStream {
        private val total = format.sampleRateHz // 1 s
        private var frame = 0

        override suspend fun read(buffer: FloatArray): Int {
            if (frame >= total) return -1
            val n = minOf(buffer.size / 2, 4_096, total - frame)
            for (i in 0 until n) {
                val v = (0.5 * sin(2 * PI * tone * (frame + i) / format.sampleRateHz)).toFloat()
                buffer[i * 2] = v
                buffer[i * 2 + 1] = v
            }
            frame += n
            return n
        }

        override fun close() = Unit
    }

    private fun sineDecoder(rate: Int, sampleFormat: PcmSampleFormat) = object : PcmDecoder {
        override suspend fun open(sourceUri: String): PcmStream =
            SineStream(PcmFormat(rate, 2, sampleFormat, "synthetic", 1_000_000L, rate.toLong()), tone)
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
        trackTotal = 12,
        discNumber = 1,
        discTotal = 2,
        cover = cover(),
    )

    private fun cover(): CoverArt {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 120, 200))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        return CoverArt(bytes, "image/jpeg", 600, 600)
    }

    private val tags = run {
        val reader = SourceMetadataReader { metadata }
        ContainerMetadataChunkSource(
            Id3MetadataChunkSource(reader),
            OpusTagsMetadataChunkSource(reader, AndroidCoverScaler()),
            FlacMetadataChunkSource(reader),
            Mp4MetadataChunkSource(reader),
        )
    }

    private val platformDecoder = AndroidPcmDecoder(context, Dispatchers.IO)

    private class Decoded(val format: PcmFormat, val frames: Long, val frequencyHz: Double)

    /** Encodes the sine for [profile] and checks what every format must give; returns the file and what it decodes to. */
    private fun roundTrip(
        profile: ConversionProfile,
        sourceRate: Int = 44_100,
        sourceFormat: PcmSampleFormat = PcmSampleFormat.INT16,
        expectedRate: Int = sourceRate,
        toleranceSeconds: Double = 0.0,
    ): Pair<File, Decoded> = runBlocking {
        val file = File(context.cacheDir, "roundtrip-${profile.id.replace(':', '-')}.${profile.fileExtension}")
        val result: ConversionResult = FileChannel.open(
            file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel -> ConversionEngine(sineDecoder(sourceRate, sourceFormat), EncoderRegistry.default(), tags).convert(sourceUri, profile, channel) }
        val uri = Uri.fromFile(file).toString()

        val verification = ConversionEngine(platformDecoder, EncoderRegistry.default(), tags).verifyDecodes(uri, result)
        val decoded = decode(uri)
        val seconds = decoded.frames.toDouble() / decoded.format.sampleRateHz
        println(
            "round trip ${profile.id}: ${file.length()} bytes, ${decoded.format.sampleRateHz} Hz x ${decoded.format.channelCount}, " +
                "${decoded.format.sourceSampleFormat?.bitsPerSample} bits, written ${result.outputFrames}, decoded ${decoded.frames} " +
                "frames = $seconds s, ${"%.2f".format(decoded.frequencyHz)} Hz, verification $verification",
        )
        assertThat(verification).isEqualTo(OutputVerification.Valid)
        assertThat(result.tagsWritten).isTrue()
        assertThat(decoded.format.sampleRateHz).isEqualTo(expectedRate)
        assertThat(decoded.format.channelCount).isEqualTo(2)
        assertThat(abs(seconds - 1.0)).isAtMost(toleranceSeconds + 1e-9)
        assertThat(abs(decoded.frequencyHz - tone)).isLessThan(2.0)
        file to decoded
    }

    /** Decodes [uri] with the converter's own decoder: format, frames and the left channel's zero-crossing rate. */
    private suspend fun decode(uri: String): Decoded = platformDecoder.open(uri).use { stream ->
        val format = stream.format
        val buffer = FloatArray(4_096 * format.channelCount)
        val left = ArrayList<Float>()
        var frames = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            for (i in 0 until n) left += buffer[i * format.channelCount]
            frames += n
        }
        // Zero crossings between 0.2 s and 0.8 s, away from the encoders' edges.
        val from = format.sampleRateHz / 5
        val to = format.sampleRateHz * 4 / 5
        var crossings = 0
        for (i in from + 1 until minOf(to, left.size)) if ((left[i - 1] < 0) != (left[i] < 0)) crossings++
        Decoded(format, frames, crossings / 2.0 / ((to - from).toDouble() / format.sampleRateHz))
    }

    private fun retrieve(file: File, check: (MediaMetadataRetriever) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            check(retriever)
        } finally {
            retriever.release()
        }
    }

    /** The tags Android reads from FLAC, M4A, MP3 and Ogg files (it reads none from WAV or AIFF). */
    private fun assertTagsReadBack(file: File) = retrieve(file) { r ->
        println("tags of ${file.name}: ${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)} / " +
            "${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)} / cover ${r.embeddedPicture?.size}")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)).isEqualTo("Sine 1 kHz")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)).isEqualTo("Sonora Test")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)).isEqualTo("Synthetic")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)).isEqualTo("Sonora Test Group")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)).isEqualTo("Test")
        assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)).startsWith("3")
        assertThat(r.embeddedPicture).isNotNull()
    }

    private fun mimeOf(file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)!!
        } finally {
            extractor.release()
        }
    }

    private fun lossless(format: AudioFormat, bits: Int, cap: Int? = null) =
        OutputTargets.profileFor(OutputTarget.Lossless(format, bits, cap))

    @Test
    fun aiff16And24DecodeBackExactly() {
        val (_, sixteen) = roundTrip(lossless(AudioFormat.AIFF, 16))
        assertThat(sixteen.format.sourceSampleFormat?.bitsPerSample).isEqualTo(16)
        val (_, wide) = roundTrip(lossless(AudioFormat.AIFF, 24), sourceRate = 96_000, sourceFormat = PcmSampleFormat.INT24)
        assertThat(wide.format.sourceSampleFormat?.bitsPerSample).isEqualTo(24)
    }

    @Test
    fun wav16And24DecodeBackExactly() {
        val (file, sixteen) = roundTrip(lossless(AudioFormat.WAV, 16))
        assertThat(mimeOf(file)).isEqualTo(MediaFormat.MIMETYPE_AUDIO_RAW)
        assertThat(sixteen.format.sourceSampleFormat?.bitsPerSample).isEqualTo(16)
        val (_, wide) = roundTrip(lossless(AudioFormat.WAV, 24), sourceRate = 96_000, sourceFormat = PcmSampleFormat.INT24)
        assertThat(wide.format.sourceSampleFormat?.bitsPerSample).isEqualTo(24)
    }

    @Test
    fun flac16DecodesBackExactlyWithTags() {
        val (file, decoded) = roundTrip(lossless(AudioFormat.FLAC, 16))
        // Newer platforms' FLAC extractor decodes the frames itself and hands out raw PCM.
        assertThat(mimeOf(file)).isAnyOf(MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_RAW)
        assertThat(decoded.format.sourceSampleFormat?.bitsPerSample).isEqualTo(16)
        assertTagsReadBack(file)
    }

    @Test
    fun flac24DecodesBackExactlyWithTags() {
        val (file, decoded) = roundTrip(lossless(AudioFormat.FLAC, 24), sourceRate = 96_000, sourceFormat = PcmSampleFormat.INT24)
        assertThat(decoded.format.sourceSampleFormat?.bitsPerSample).isEqualTo(24)
        assertTagsReadBack(file)
    }

    @Test
    fun flacWithA48kHzCapResamplesA96kHzSource() {
        roundTrip(lossless(AudioFormat.FLAC, 16, cap = 48_000), sourceRate = 96_000, sourceFormat = PcmSampleFormat.INT24, expectedRate = 48_000)
    }

    @Test
    fun aacInM4aPlaysBackWithTags() {
        val (file, _) = roundTrip(OutputTargets.profileFor(OutputTarget.Aac(256)), toleranceSeconds = AAC_TOLERANCE_S)
        assertThat(mimeOf(file)).isEqualTo(MediaFormat.MIMETYPE_AUDIO_AAC)
        assertTagsReadBack(file)
        retrieve(file) { r ->
            assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)).isEqualTo("2026")
            assertThat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)).startsWith("1")
        }
    }

    @Test
    fun aacFromA96kHzSourceComesOutAt48kHz() {
        roundTrip(
            OutputTargets.profileFor(OutputTarget.Aac(128)),
            sourceRate = 96_000, sourceFormat = PcmSampleFormat.INT24, expectedRate = 48_000, toleranceSeconds = AAC_TOLERANCE_S,
        )
    }

    @Test
    fun opusAtTheChosenBitratePlaysBackWithTags() {
        val (file, _) = roundTrip(OutputTargets.profileFor(OutputTarget.Opus(96)), expectedRate = 48_000, toleranceSeconds = 0.020)
        assertTagsReadBack(file)
    }

    @Test
    fun mp3CbrPlaysBackWithTags() {
        val (file, _) = roundTrip(OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Cbr(320))), toleranceSeconds = MP3_TOLERANCE_S)
        assertThat(mimeOf(file)).isEqualTo(MediaFormat.MIMETYPE_AUDIO_MPEG)
        assertTagsReadBack(file)
    }

    @Test
    fun mp3VbrPlaysBackWithTags() {
        val (file, _) = roundTrip(OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Vbr(2))), toleranceSeconds = MP3_TOLERANCE_S)
        assertTagsReadBack(file)
    }

    private companion object {
        /** What the engine's verification accepts for AAC: the encoder's priming and its last frame's padding. */
        const val AAC_TOLERANCE_S = 4 * 1_024 / 44_100.0

        /** What the engine's verification accepts for MP3. */
        const val MP3_TOLERANCE_S = 2 * 1_152 / 44_100.0
    }
}
