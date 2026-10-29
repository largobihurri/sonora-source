package io.sonora.feature.dub

/**
 * How the DUB screen fits a phone held upright (design §4), in pixels: the content above the flexible element (the
 * host's deck, the status row, the page switch), the flexible element itself (the XY pad, a square, or the preamp strips)
 * and the content under it (the effects' fixed rows), separated by [gaps] in all.
 *
 * The flexible element gets a [band] of the room left in the viewport and is [flex] tall inside it (a square stops at
 * its largest side and is centred in the band). When the room left is under the element's minimum, the band keeps the
 * minimum and the screen is [height] tall, taller than the viewport, so it scrolls instead of drawing one row over another.
 */
internal data class PortraitFit(val band: Int, val flex: Int, val height: Int) {
    companion object {
        fun of(viewport: Int, above: Int, below: Int, gaps: Int, minFlex: Int, maxFlex: Int): PortraitFit {
            val min = minOf(minFlex, maxFlex)
            val band = maxOf(viewport - above - below - gaps, min)
            return PortraitFit(band = band, flex = minOf(band, maxFlex), height = above + gaps + band + below)
        }
    }
}
