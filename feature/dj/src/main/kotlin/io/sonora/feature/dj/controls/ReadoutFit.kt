package io.sonora.feature.dj.controls

/**
 * Fitting the tempo readout to its width. Compose's own `TextAutoSize` lays the whole text out about five times each time it
 * changes, and a tempo drag changes it on every step: 40 to 80 times a second, about 8 ms of the main thread each (22-27 % of
 * the frames of a drag missed their deadline on the Pad 6). The text's width with tabular figures depends only on its shape,
 * not on which digits it has, so the size is worked out once per shape and width ([largestFitting]) and kept.
 */
internal object ReadoutFit {

    /** [text] with every digit as 0: in a font with tabular figures, the same width as any text of the same shape. */
    fun shapeOf(text: String): String = text.map { if (it.isDigit()) '0' else it }.joinToString("")

    /**
     * The largest size on the [step] grid from [minSize] to [maxSize] (sp) at which a line of [widthAt] its size (px) is within
     * [available] px; [minSize] when none is, and when [maxSize] is below it. [widthAt] grows with the size, so a binary search
     * measures only about log2 of the grid's sizes.
     */
    fun largestFitting(minSize: Float, maxSize: Float, step: Float, available: Float, widthAt: (Float) -> Float): Float {
        if (maxSize <= minSize) return minSize
        var low = 0
        var high = ((maxSize - minSize) / step).toInt()
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (widthAt(minSize + middle * step) <= available) low = middle else high = middle - 1
        }
        return minSize + low * step
    }
}
