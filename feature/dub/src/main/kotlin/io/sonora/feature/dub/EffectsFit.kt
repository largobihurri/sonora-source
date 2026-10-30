package io.sonora.feature.dub

/**
 * How the effects page fits next to the preamp (landscape, or 840 dp and wider), in pixels: the XY pad, a square of
 * [pad], above the effects' fixed rows, or, when [beside], to their left with the rows [controlsWidth] wide.
 *
 * Above the rows the pad takes the height they leave, capped by the width and its largest side. On a large screen
 * ([besideAllowed]) whose area is wider than tall, a pad that would get less than its minimum there moves beside the
 * rows instead, as tall as the area, but leaving the rows at least their narrowest width (the Pad 6 in landscape: the
 * rows left the pad about 48 dp). Beside is only taken when it gives the pad more room than above.
 */
internal data class EffectsFit(val beside: Boolean, val pad: Int, val controlsWidth: Int) {
    companion object {
        fun of(
            width: Int,
            height: Int,
            controlsHeight: Int,
            gap: Int,
            minPad: Int,
            maxPad: Int,
            controlsMinWidth: Int,
            besideAllowed: Boolean,
        ): EffectsFit {
            val above = minOf(height - controlsHeight - gap, width, maxPad).coerceAtLeast(0)
            val stacked = EffectsFit(beside = false, pad = above, controlsWidth = width)
            if (!besideAllowed || width <= height || above >= minPad) return stacked
            val beside = minOf(height, width - gap - controlsMinWidth, maxPad)
            if (beside <= above) return stacked
            return EffectsFit(beside = true, pad = beside, controlsWidth = width - gap - beside)
        }
    }
}
