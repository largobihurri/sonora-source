package io.sonora.core.designsystem.theme

import androidx.compose.runtime.Immutable

/**
 * Colours of a page tinted by its artwork, iOS 26 Music style, as packed opaque ARGB: a solid, fairly dark
 * [background] in the artwork's dominant hue (white titles on it reach 7:1), a slightly darker [lowerBand] for the
 * sections below, light desaturated [secondaryText] and dimmer [mutedText] of the same hue (both AA on either band),
 * a translucent-looking [buttonFill] for round buttons and a [divider]. Pure, so it is unit-tested.
 */
@Immutable
data class ArtworkPalette(
    val background: Int,
    val lowerBand: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val buttonFill: Int,
    val divider: Int,
) {
    companion object {
        /** Colourfulness cap of the page, so a vivid cover still gives a calm page. */
        const val MAX_CHROMA = 0.09

        /** Page lightness range: dark enough for white text, never pure black. */
        private const val MIN_LIGHTNESS = 0.17
        private const val MAX_LIGHTNESS = 0.36

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val TITLE_CONTRAST = 7.0

        /** iOS dark grouped grey, for artwork without colour information. */
        private const val NEUTRAL = 0xFF2C2C2E.toInt()

        fun from(dominant: Int?): ArtworkPalette {
            val source = ColorMath.toOklch(dominant ?: NEUTRAL)
            // Near-grey sources keep no hue at all, so a white or black cover gives a neutral page.
            val chroma = if (source.c < 0.02) 0.0 else minOf(source.c, MAX_CHROMA)
            val hue = source.h
            fun tone(l: Double, c: Double = chroma) = ColorMath.fromOklch(Oklch(l.coerceIn(0.0, 1.0), c, hue))
            val background = ColorMath.ensureContrast(
                tone(source.l.coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)), listOf(WHITE), TITLE_CONTRAST,
            )
            val l = ColorMath.toOklch(background).l
            val lowerBand = tone(l - 0.04)
            val bands = listOf(background, lowerBand)
            return ArtworkPalette(
                background = background,
                lowerBand = lowerBand,
                secondaryText = ColorMath.ensureContrast(tone(0.84, minOf(chroma, 0.05)), bands),
                mutedText = ColorMath.ensureContrast(tone(0.74, minOf(chroma, 0.03)), bands),
                buttonFill = tone(l + 0.08),
                divider = tone(l + 0.14, chroma * 0.6),
            )
        }
    }
}
