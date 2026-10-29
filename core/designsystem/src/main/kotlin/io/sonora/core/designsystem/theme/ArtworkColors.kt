package io.sonora.core.designsystem.theme

/**
 * Colours of an artwork: the accent seed (its most vivid colour that covers a meaningful part of the image) and the
 * dominant colour (the one covering the most area). Pure, over packed ARGB pixels (a small downscaled copy is enough),
 * so it is unit-tested without bitmaps.
 */
object ArtworkColors {
    private const val HUE_BINS = 36
    private const val MIN_SATURATION = 0.3f
    private const val MIN_VALUE = 0.25f

    /** Below these a pixel counts as neutral for [dominant]: grey, near-black. */
    private const val DOMINANT_MIN_SATURATION = 0.2f
    private const val DOMINANT_MIN_VALUE = 0.15f

    /** Below this share of the opaque pixels, colour is treated as specks on a grey image. */
    private const val MIN_COLOURED_SHARE = 0.02f

    /** The seed colour, or `null` for greyscale (or empty) artwork. */
    fun accentSeed(pixels: IntArray): Int? {
        val score = FloatArray(HUE_BINS)
        val count = IntArray(HUE_BINS)
        val red = LongArray(HUE_BINS)
        val green = LongArray(HUE_BINS)
        val blue = LongArray(HUE_BINS)
        var opaque = 0
        var coloured = 0
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            opaque++
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val value = max / 255f
            val saturation = if (max == 0) 0f else (max - min).toFloat() / max
            if (saturation < MIN_SATURATION || value < MIN_VALUE) continue
            coloured++
            val bin = (hue(r, g, b, max, min) / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            score[bin] += saturation * value // vivid pixels weigh more than muted ones
            count[bin]++
            red[bin] += r.toLong()
            green[bin] += g.toLong()
            blue[bin] += b.toLong()
        }
        if (opaque == 0 || coloured < opaque * MIN_COLOURED_SHARE) return null
        // A hue sitting on a bin edge is split in two, so each bin is judged with its neighbours.
        val best = (0 until HUE_BINS).maxBy { score[it] + 0.5f * (score[(it + 1) % HUE_BINS] + score[(it + HUE_BINS - 1) % HUE_BINS]) }
        val bins = listOf((best + HUE_BINS - 1) % HUE_BINS, best, (best + 1) % HUE_BINS)
        val n = bins.sumOf { count[it] }.toLong()
        val r = (bins.sumOf { red[it] } / n).toInt()
        val g = (bins.sumOf { green[it] } / n).toInt()
        val b = (bins.sumOf { blue[it] } / n).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The colour covering the largest part of the artwork, vivid or neutral; `null` without opaque pixels. */
    fun dominant(pixels: IntArray): Int? {
        // Bins 0 until HUE_BINS hold coloured pixels by hue; the last one holds the neutral (grey, near-black) ones.
        val neutral = HUE_BINS
        val count = IntArray(HUE_BINS + 1)
        val red = LongArray(HUE_BINS + 1)
        val green = LongArray(HUE_BINS + 1)
        val blue = LongArray(HUE_BINS + 1)
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max == 0) 0f else (max - min).toFloat() / max
            val bin = if (saturation < DOMINANT_MIN_SATURATION || max / 255f < DOMINANT_MIN_VALUE) {
                neutral
            } else {
                (hue(r, g, b, max, min) / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            }
            count[bin]++
            red[bin] += r.toLong()
            green[bin] += g.toLong()
            blue[bin] += b.toLong()
        }
        if (count.sum() == 0) return null
        // A hue's area is judged with its neighbours (a hue on a bin edge is split in two); neutral stands alone.
        fun area(bin: Int) = if (bin == neutral) count[bin] else count[bin] + count[(bin + 1) % HUE_BINS] + count[(bin + HUE_BINS - 1) % HUE_BINS]
        val best = (0..neutral).maxBy(::area)
        val bins = if (best == neutral) listOf(neutral) else listOf((best + HUE_BINS - 1) % HUE_BINS, best, (best + 1) % HUE_BINS)
        val n = bins.sumOf { count[it] }.toLong()
        val r = (bins.sumOf { red[it] } / n).toInt()
        val g = (bins.sumOf { green[it] } / n).toInt()
        val b = (bins.sumOf { blue[it] } / n).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
        val d = (max - min).toFloat()
        val h = when (max) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return if (h < 0) h + 360f else h
    }
}
