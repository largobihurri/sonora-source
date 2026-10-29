package io.sonora.core.domain.conversion

import io.sonora.core.domain.audio.PcmFormat

/** What the converter does to one source to meet its [profile]. Built by [ConversionPlanner]. */
data class ConversionPlan(
    val profile: ConversionProfile,
    val sourceSampleRateHz: Int,
    val targetSampleRateHz: Int,
    val sourceChannels: Int,
    val targetChannels: Int,
    val targetBitsPerSample: Int,
    /** Whether the quantizer adds TPDF dither (see [DitherPolicy]). */
    val dither: Boolean,
) {
    val resample: Boolean get() = targetSampleRateHz != sourceSampleRateHz

    val downmix: Boolean get() = targetChannels != sourceChannels
}

/** Pure planning: from a decoded source's format and a profile to the processing chain. */
object ConversionPlanner {

    /** @throws IllegalArgumentException when [source] has no channels or no sample rate. */
    fun plan(source: PcmFormat, profile: ConversionProfile): ConversionPlan {
        require(source.sampleRateHz > 0) { "Source sample rate must be positive: ${source.sampleRateHz}" }
        require(source.channelCount > 0) { "Source must have channels: ${source.channelCount}" }
        val targetRate = profile.sampleRate.targetFor(source.sampleRateHz)
        val targetChannels = profile.channels.targetFor(source.channelCount)
        val targetBits = profile.sampleFormat.bitsPerSample
        // Integer samples no wider than the output land exactly on its grid, as long as nothing recomputes them.
        val onOutputGrid = source.sourceSampleFormat?.let { !it.isFloat && it.bitsPerSample <= targetBits } ?: false
        val untouched = targetRate == source.sampleRateHz && targetChannels == source.channelCount
        val dither = when (profile.dither) {
            DitherPolicy.NONE -> false
            DitherPolicy.TPDF_WHEN_REDUCING_PRECISION -> !(onOutputGrid && untouched)
        }
        return ConversionPlan(
            profile = profile,
            sourceSampleRateHz = source.sampleRateHz,
            targetSampleRateHz = targetRate,
            sourceChannels = source.channelCount,
            targetChannels = targetChannels,
            targetBitsPerSample = targetBits,
            dither = dither,
        )
    }
}
