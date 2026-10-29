package io.sonora.core.transcoder.dsp

/**
 * Folds [sourceChannels] interleaved channels down to stereo (or mono) with fixed gains, assuming the WAV / Android
 * channel order decoders deliver: 3 = FL FR FC, 4 = FL FR BL BR, 5 = FL FR FC BL BR, 6 = 5.1 FL FR FC LFE BL BR,
 * 7 = 6.1 FL FR FC LFE BC SL SR, 8 = 7.1 FL FR FC LFE BL BR SL SR. Centre and surrounds enter at -3 dB, a back centre
 * at -6 dB into both sides, LFE is dropped (ITU-R BS.775 style). Unknown layouts (more than 8 channels) alternate
 * left / right. Gains are scaled so full scale in every channel just reaches full scale: the mix never clips. Mono
 * is the average of the stereo mix.
 */
class ChannelDownmixer(val sourceChannels: Int, val targetChannels: Int) {

    private val left: FloatArray
    private val right: FloatArray

    init {
        require(targetChannels in 1..2 && sourceChannels > targetChannels) {
            "Cannot downmix $sourceChannels channels to $targetChannels"
        }
        val gains = layout(sourceChannels)
        val scale = 1f / maxOf(gains.sumOf { it.first.toDouble() }, gains.sumOf { it.second.toDouble() }).toFloat()
        left = FloatArray(sourceChannels) { gains[it].first * scale }
        right = FloatArray(sourceChannels) { gains[it].second * scale }
    }

    /**
     * Mixes [frames] frames from [input] into [output] from index 0. [output] may be [input]: each frame is read
     * completely before its (shorter) result is written.
     */
    fun process(input: FloatArray, frames: Int, output: FloatArray) {
        require(frames >= 0 && frames.toLong() * sourceChannels <= input.size) { "$frames frames do not fit the input" }
        require(frames.toLong() * targetChannels <= output.size) { "$frames frames do not fit the output" }
        val c = sourceChannels
        for (f in 0 until frames) {
            var l = 0f
            var r = 0f
            val base = f * c
            for (ch in 0 until c) {
                val x = input[base + ch]
                l += left[ch] * x
                r += right[ch] * x
            }
            if (targetChannels == 2) {
                output[f * 2] = l
                output[f * 2 + 1] = r
            } else {
                output[f] = (l + r) * 0.5f
            }
        }
    }

    private companion object {
        const val K = 0.70710677f // -3 dB
        val FL = 1f to 0f
        val FR = 0f to 1f
        val C = K to K
        val LFE = 0f to 0f
        val SL = K to 0f
        val SR = 0f to K
        val BC = 0.5f to 0.5f

        fun layout(channels: Int): List<Pair<Float, Float>> = when (channels) {
            2 -> listOf(FL, FR)
            3 -> listOf(FL, FR, C)
            4 -> listOf(FL, FR, SL, SR)
            5 -> listOf(FL, FR, C, SL, SR)
            6 -> listOf(FL, FR, C, LFE, SL, SR)
            7 -> listOf(FL, FR, C, LFE, BC, SL, SR)
            8 -> listOf(FL, FR, C, LFE, SL, SR, SL, SR)
            else -> List(channels) { if (it % 2 == 0) FL else FR }
        }
    }
}
