package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** What the export does with one picked file. */
sealed interface TrackPlan {
    /** The player takes the file as it is: it is copied. */
    data object AsIs : TrackPlan

    /** The player does not take it: it is converted to [target] first and the converted file is copied. */
    data class Convert(val target: ConversionTarget) : TrackPlan

    /** The player does not take it and the decoder cannot read it either. */
    data object Undecodable : TrackPlan
}

/**
 * The format a converted file gets: [bitsPerSample]-bit integer PCM at [sampleRateHz] with [channelCount] channels.
 * Rate and channels are `null` when the source's header does not state them (lossy formats): the converter decides
 * them from the decoded stream by the same rules.
 */
data class ConversionTarget(val sampleRateHz: Int?, val channelCount: Int?, val bitsPerSample: Int)

/**
 * Which picked files the export converts, to what, and the room the result takes on the stick.
 *
 * A file the [DeviceProfile] accepts goes as it is. Anything else the decoder reads is converted with the converter
 * profile of the same player ([conversionProfile]): for the XDJ-700 to plain AIFF 16-bit, keeping rates up to 48 kHz
 * (faster ones become 44.1 kHz) and up to two channels (more are downmixed), with the tags and artwork. AIFF and AIFF-C
 * files whose header the parser refused (compressed AIFF-C such as IMA ADPCM, exotic sample sizes or rates) are
 * [TrackPlan.Undecodable]: the probe then reports only what it found, and the decoder refuses them the same way.
 */
object ExportConversion {

    /** Room for the tags and artwork a converted file carries over, plus its AIFF header. */
    const val METADATA_ALLOWANCE_BYTES = 2L shl 20

    /** Bytes of one 16-bit sample. */
    private const val BYTES_PER_SAMPLE = 2L

    private const val MS_PER_SECOND = 1000L

    /** How much bigger than the source a 16-bit PCM copy of unknown length may be, lossless or lossy. */
    private const val LOSSLESS_GROWTH = 2L
    private const val LOSSY_GROWTH = 12L

    private val AIFF_FORMS = setOf(AudioContainer.AIFF, AudioContainer.AIFF_C)
    private val LOSSLESS = setOf(AudioContainer.AIFF, AudioContainer.AIFF_C, AudioContainer.WAV, AudioContainer.FLAC)

    /** The converter profile that writes what [profile]'s player takes. */
    fun conversionProfile(profile: DeviceProfile): ConversionProfile =
        requireNotNull(ConversionProfiles.byId(profile.id)) { "No conversion profile for ${profile.displayName}" }

    fun plan(audio: SourceAudio, profile: DeviceProfile): TrackPlan {
        if (profile.check(audio) == null) return TrackPlan.AsIs
        val parserRefused = audio.container in AIFF_FORMS &&
            (audio.bitsPerSample == null || audio.sampleRateHz == null || audio.channelCount == null)
        if (audio.container == AudioContainer.UNKNOWN || parserRefused) return TrackPlan.Undecodable
        val conversion = conversionProfile(profile)
        return TrackPlan.Convert(
            ConversionTarget(
                sampleRateHz = audio.sampleRateHz?.let(conversion.sampleRate::targetFor),
                channelCount = audio.channelCount?.let(conversion.channels::targetFor),
                bitsPerSample = conversion.sampleFormat.bitsPerSample,
            ),
        )
    }

    /**
     * About how many bytes [file] takes on the stick: its size when it goes as it is; once converted, its 16-bit PCM
     * at the target rate (from its frame count, else its length, else its size with room to grow) plus
     * [METADATA_ALLOWANCE_BYTES]. Unknown rates and channel counts count as the most the converter writes (48 kHz,
     * stereo for the XDJ-700).
     */
    fun exportBytes(file: SourceFile, profile: DeviceProfile): Long {
        val target = (plan(file.audio, profile) as? TrackPlan.Convert)?.target ?: return file.sizeBytes
        val audio = file.audio
        val conversion = conversionProfile(profile)
        val channels = (target.channelCount ?: conversion.channels.maxChannels).toLong()
        val rate = (target.sampleRateHz ?: conversion.sampleRate.maxSampleRateHz).toLong()
        val frames = audio.frameCount
        val sourceRate = audio.sampleRateHz
        val duration = audio.durationMs
        val pcmBytes = when {
            frames != null && sourceRate != null && sourceRate > 0 ->
                frames * rate / sourceRate * channels * BYTES_PER_SAMPLE
            duration != null -> duration * rate / MS_PER_SECOND * channels * BYTES_PER_SAMPLE
            audio.container in LOSSLESS -> file.sizeBytes * LOSSLESS_GROWTH
            else -> file.sizeBytes * LOSSY_GROWTH
        }
        return pcmBytes + METADATA_ALLOWANCE_BYTES
    }
}

/**
 * Converts picked files the player does not take into temporary files the export copies instead. A port, so the export
 * is tested without the transcoder; [TranscoderExportConverter] is the app's.
 */
interface ExportTrackConverter {
    /**
     * Converts [source] for [profile]'s player, reporting [onProgress] (0..1). The result is a temporary file: close it
     * once copied. Whatever was written is deleted when the conversion fails or is cancelled.
     *
     * @throws io.sonora.core.domain.audio.PcmDecodeException when [source] cannot be decoded.
     * @throws java.io.IOException when it cannot be read or the result cannot be written.
     */
    suspend fun convert(source: SourceFile, profile: DeviceProfile, onProgress: (Float) -> Unit): ConvertedTrack

    /** Deletes temporary files an earlier export could not (the app was killed meanwhile). */
    fun discardLeftovers()
}

/** A converted [file], a temporary one: [close] deletes it (once, however often it is called). */
class ConvertedTrack(val file: SourceFile, private val discard: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) discard()
    }
}
