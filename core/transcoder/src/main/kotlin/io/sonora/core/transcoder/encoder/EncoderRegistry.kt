package io.sonora.core.transcoder.encoder

import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.conversion.OutputSampleFormat
import io.sonora.core.domain.port.UnsupportedOutputException
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.aiff.AiffWriter
import io.sonora.core.transcoder.dsp.TpdfQuantizer
import io.sonora.core.transcoder.wav.WavWriter
import java.io.File
import java.nio.channels.SeekableByteChannel

/** The last stage of a conversion: takes processed float frames at the target rate and channel count. */
interface AudioEncoder : AutoCloseable {
    /**
     * Whether the container puts its tags ahead of the audio (Ogg). The engine then calls [begin] with the metadata
     * before the first [write] and passes [finish] no chunks; otherwise [finish] gets them after the sound data.
     */
    val metadataFirst: Boolean get() = false

    /** Receives the metadata chunks before any audio; only called when [metadataFirst]. */
    fun begin(metadata: List<MetadataChunk>) = Unit

    /** Encodes [frames] interleaved frames of `plan.targetChannels` channels from [samples]. */
    fun write(samples: FloatArray, frames: Int)

    /** Completes the file with [metadata] (empty when [metadataFirst]) and returns the frames written. */
    fun finish(metadata: List<MetadataChunk>): Long

    /** Releases what the encoder holds (a codec); called once, also after a failure. Does not close the output. */
    override fun close() = Unit
}

/** Builds an [AudioEncoder] for one output; [ditherSeed] makes dithered output reproducible. */
fun interface AudioEncoderFactory {
    fun create(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long): AudioEncoder
}

/**
 * Thrown when nothing here encodes a profile: no encoder registered for its container and sample format, or none on
 * this device that accepts its settings ([reason] says which, e.g. a FLAC encoder without 24-bit input). The batch
 * fails only that file, with the domain's [UnsupportedOutputException] message.
 */
class UnsupportedProfileException(
    profile: ConversionProfile,
    reason: String? = null,
    cause: Throwable? = null,
) : UnsupportedOutputException(profile, reason) {
    init {
        cause?.let(::initCause)
    }

    override val message: String
        get() = reason?.let { "Cannot write ${profile.displayName} on this device (profile ${profile.id}): $it" }
            ?: "No encoder for ${profile.container} ${profile.sampleFormat} (profile ${profile.id})"
}

/**
 * Encoders by container and sample format. A new profile that reuses an existing pair needs nothing here; a new
 * pair registers one factory and the engine stays unchanged.
 */
class EncoderRegistry(private val factories: Map<Pair<OutputContainer, OutputSampleFormat>, AudioEncoderFactory>) {

    /** @throws UnsupportedProfileException when nothing encodes [profile]. */
    fun factoryFor(profile: ConversionProfile): AudioEncoderFactory =
        factories[profile.container to profile.sampleFormat] ?: throw UnsupportedProfileException(profile)

    companion object {
        /** Every output format; M4A outputs are muxed through a temporary file in [tempDir] (the app's cache). */
        fun default(tempDir: File = File(System.getProperty("java.io.tmpdir") ?: ".")): EncoderRegistry = EncoderRegistry(
            mapOf(
                (OutputContainer.AIFF to OutputSampleFormat.PCM_16_BIG_ENDIAN) to AudioEncoderFactory(::AiffPcmEncoder),
                (OutputContainer.AIFF to OutputSampleFormat.PCM_24_BIG_ENDIAN) to AudioEncoderFactory(::AiffPcmEncoder),
                (OutputContainer.WAV to OutputSampleFormat.PCM_16_LITTLE_ENDIAN) to AudioEncoderFactory(::WavPcmEncoder),
                (OutputContainer.WAV to OutputSampleFormat.PCM_24_LITTLE_ENDIAN) to AudioEncoderFactory(::WavPcmEncoder),
                (OutputContainer.FLAC to OutputSampleFormat.FLAC_16) to FlacEncoderFactory(),
                (OutputContainer.FLAC to OutputSampleFormat.FLAC_24) to FlacEncoderFactory(),
                (OutputContainer.OGG_OPUS to OutputSampleFormat.OPUS) to OpusEncoderFactory(),
                (OutputContainer.M4A to OutputSampleFormat.AAC) to AacEncoderFactory { tempDir },
                (OutputContainer.MP3 to OutputSampleFormat.MP3) to Mp3EncoderFactory(),
            ),
        )
    }
}

/** AIFF, 16- or 24-bit big-endian PCM (the plan's target bits), TPDF-dithered when the plan says so. */
class AiffPcmEncoder(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long) : AudioEncoder {
    private val channels = plan.targetChannels
    private val bytesPerSample = plan.targetBitsPerSample / 8
    private val writer = AiffWriter(output, channels, plan.targetSampleRateHz, plan.targetBitsPerSample)
    private val quantizer = TpdfQuantizer(ditherSeed, dither = plan.dither, bits = plan.targetBitsPerSample)
    private var bytes = ByteArray(0)

    override fun write(samples: FloatArray, frames: Int) {
        val count = frames * channels
        if (bytes.size < count * bytesPerSample) bytes = ByteArray(count * bytesPerSample)
        quantizer.quantizeToBigEndian(samples, count, bytes)
        writer.writeSamples(bytes, 0, count * bytesPerSample)
    }

    override fun finish(metadata: List<MetadataChunk>): Long = writer.finish(metadata)
}

/** RIFF WAVE, 16- or 24-bit little-endian PCM (the plan's target bits), TPDF-dithered when the plan says so. */
class WavPcmEncoder(plan: ConversionPlan, output: SeekableByteChannel, ditherSeed: Long) : AudioEncoder {
    private val channels = plan.targetChannels
    private val bytesPerSample = plan.targetBitsPerSample / 8
    private val writer = WavWriter(output, channels, plan.targetSampleRateHz, plan.targetBitsPerSample)
    private val quantizer = TpdfQuantizer(ditherSeed, dither = plan.dither, bits = plan.targetBitsPerSample)
    private var bytes = ByteArray(0)

    override fun write(samples: FloatArray, frames: Int) {
        val count = frames * channels
        if (bytes.size < count * bytesPerSample) bytes = ByteArray(count * bytesPerSample)
        quantizer.quantizeToLittleEndian(samples, count, bytes)
        writer.writeSamples(bytes, 0, count * bytesPerSample)
    }

    /** Writes the `LIST` and `id3 ` chunks after the sound data. */
    override fun finish(metadata: List<MetadataChunk>): Long = writer.finish(metadata)
}
