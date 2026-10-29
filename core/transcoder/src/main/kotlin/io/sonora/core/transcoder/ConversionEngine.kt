package io.sonora.core.transcoder

import io.sonora.core.audiodsp.resample.PolyphaseResampler
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmStream
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionPlanner
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.MetadataPolicy
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.transcoder.dsp.ChannelDownmixer
import io.sonora.core.transcoder.encoder.EncoderRegistry
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Converts one source to one output: [PcmDecoder] -> downmix (when the profile allows fewer channels) -> resampler
 * (when the rate is above the profile's maximum) -> encoder (TPDF dither and quantisation to 16 or 24 bits for AIFF,
 * WAV and FLAC; AAC in M4A, Opus in Ogg, MP3) -> metadata (after the sound data for AIFF and WAV, in the header
 * for the others). Pure Kotlin over a [SeekableByteChannel]: opening documents, thread priority and concurrency
 * belong to the caller. The block size changes memory use only, never the output.
 */
class ConversionEngine(
    private val decoder: PcmDecoder,
    private val encoders: EncoderRegistry,
    private val metadata: MetadataChunkSource,
) {

    /**
     * Converts [sourceUri] for [profile] into [output] (from its current position; the caller closes it), reading
     * blocks of [chunkFrames] frames. Runs the DSP in the caller's context and checks for cancellation between
     * blocks. [onProgress] gets the decoded fraction when the source states its length, and 1 at the end.
     *
     * @throws PcmDecodeException when the source cannot be decoded.
     * @throws IOException when the output cannot be written.
     * @throws io.sonora.core.transcoder.encoder.UnsupportedProfileException when nothing encodes [profile].
     */
    suspend fun convert(
        sourceUri: String,
        profile: ConversionProfile,
        output: SeekableByteChannel,
        chunkFrames: Int = PcmStream.DEFAULT_CHUNK_FRAMES,
        onProgress: (Float) -> Unit = {},
    ): ConversionResult {
        require(chunkFrames in 1..MAX_CHUNK_FRAMES) { "chunkFrames must be in 1..$MAX_CHUNK_FRAMES: $chunkFrames" }
        val encoderFactory = encoders.factoryFor(profile)
        return decoder.open(sourceUri).use { stream ->
            val format = stream.format
            val plan = ConversionPlanner.plan(format, profile)
            val encoder = encoderFactory.create(plan, output, ditherSeed = sourceUri.hashCode().toLong())
            try {
                val downmixer = if (plan.downmix) ChannelDownmixer(plan.sourceChannels, plan.targetChannels) else null
                val resampler = if (plan.resample) {
                    PolyphaseResampler(plan.sourceSampleRateHz, plan.targetSampleRateHz, plan.targetChannels)
                } else {
                    null
                }
                val block = FloatArray(chunkFrames * plan.sourceChannels)
                val resampled = resampler?.let { FloatArray(it.maxOutputFrames(chunkFrames) * plan.targetChannels) }
                val expectedFrames = format.totalFrames
                    ?: format.durationUs?.let { it * format.sampleRateHz / 1_000_000 }
                // Ogg puts the tags ahead of the audio, so those encoders get them now; AIFF takes them at the end.
                val upFront = if (encoder.metadataFirst) metadataFor(sourceUri, profile) else null
                upFront?.let(encoder::begin)
                var decoded = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frames = stream.read(block)
                    if (frames < 0) break
                    downmixer?.process(block, frames, block)
                    if (resampler != null && resampled != null) {
                        val produced = resampler.process(block, frames, resampled)
                        if (produced > 0) encoder.write(resampled, produced)
                    } else {
                        encoder.write(block, frames)
                    }
                    decoded += frames
                    if (expectedFrames != null && expectedFrames > 0) {
                        onProgress((decoded.toDouble() / expectedFrames).coerceIn(0.0, 1.0).toFloat())
                    }
                }
                if (resampler != null && resampled != null) {
                    val produced = resampler.flush(resampled)
                    if (produced > 0) encoder.write(resampled, produced)
                }
                currentCoroutineContext().ensureActive()
                val chunks = upFront ?: metadataFor(sourceUri, profile)
                val written = encoder.finish(if (upFront != null) emptyList() else chunks)
                onProgress(1f)
                ConversionResult(plan = plan, outputFrames = written, sourceFrames = decoded, tagsWritten = chunks.isNotEmpty())
            } finally {
                encoder.close()
            }
        }
    }

    private suspend fun metadataFor(sourceUri: String, profile: ConversionProfile): List<MetadataChunk> =
        if (profile.metadata == MetadataPolicy.NONE) emptyList() else metadata.chunksFor(sourceUri, profile)

    /**
     * Decodes [outputUri] to the end and checks its rate, channels, sample size and frame count against [expected]
     * (lossy files may come out a few codec frames longer or shorter: decoders differ in how they trim their ends).
     * Run it before replacing an original with its conversion. Failures come back as [OutputVerification.Invalid];
     * only cancellation is thrown.
     */
    suspend fun verifyDecodes(outputUri: String, expected: ConversionResult): OutputVerification = try {
        decoder.open(outputUri).use { stream -> check(stream, expected) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: PcmDecodeException) {
        OutputVerification.Invalid("Output does not decode: ${e.message}", e)
    } catch (e: IOException) {
        OutputVerification.Invalid("Output cannot be read: ${e.message}", e)
    } catch (e: Exception) {
        // Revoked access (SecurityException), a provider rejecting the URI, a decoder in a bad state: still a verdict
        // on this one output, never a reason to abort the rest of a batch.
        OutputVerification.Invalid("Output cannot be verified: ${e.message}", e)
    }

    private suspend fun check(stream: PcmStream, expected: ConversionResult): OutputVerification {
        val format = stream.format
        val plan = expected.plan
        if (format.sampleRateHz != plan.targetSampleRateHz || format.channelCount != plan.targetChannels) {
            return OutputVerification.Invalid(
                "Output is ${format.sampleRateHz} Hz x ${format.channelCount}, expected " +
                    "${plan.targetSampleRateHz} Hz x ${plan.targetChannels}",
            )
        }
        val bits = format.sourceSampleFormat?.bitsPerSample
        if (bits != null && bits != plan.targetBitsPerSample) {
            return OutputVerification.Invalid("Output has $bits-bit samples, expected ${plan.targetBitsPerSample}")
        }
        val buffer = FloatArray(PcmStream.DEFAULT_CHUNK_FRAMES * format.channelCount)
        var frames = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            frames += read
        }
        return if (abs(frames - expected.outputFrames) <= toleranceFrames(plan)) {
            OutputVerification.Valid
        } else {
            OutputVerification.Invalid("Output decodes to $frames frames, expected ${expected.outputFrames}")
        }
    }

    /**
     * Decoded lengths may differ by this many frames from the written ones: none for lossless containers, 20 ms for
     * Opus, four AAC frames for M4A (the platform encoder's 2048 frames of priming, which MediaMuxer does not mark,
     * plus the last frame's padding) and two MP3 frames (for decoders that ignore the LAME gapless data).
     */
    private fun toleranceFrames(plan: ConversionPlan): Int = when (plan.profile.container) {
        OutputContainer.AIFF, OutputContainer.WAV, OutputContainer.FLAC -> 0
        OutputContainer.OGG_OPUS -> plan.targetSampleRateHz / OPUS_FRAMES_PER_SECOND
        OutputContainer.M4A -> 4 * AAC_FRAME_SAMPLES
        OutputContainer.MP3 -> 2 * MP3_FRAME_SAMPLES
    }

    private companion object {
        const val OPUS_FRAMES_PER_SECOND = 50
        const val AAC_FRAME_SAMPLES = 1_024
        const val MP3_FRAME_SAMPLES = 1_152

        /** 64 Ki frames: 2 MB per float block at 8 channels; far beyond any useful block. */
        const val MAX_CHUNK_FRAMES = 65_536
    }
}
