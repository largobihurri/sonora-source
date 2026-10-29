package io.sonora.core.designsystem.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour in OKLCH: perceptual lightness 0..1, chroma, and hue in degrees. */
data class Oklch(val l: Double, val c: Double, val h: Double)

/**
 * Colour math on packed opaque ARGB ints, free of Android so it is unit-tested: WCAG contrast, OKLCH conversion and
 * a hue-preserving contrast fix.
 */
object ColorMath {
    /** WCAG AA for normal text. */
    const val AA_CONTRAST = 4.5

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun relativeLuminance(argb: Int): Double =
        0.2126 * linear(argb shr 16) + 0.7152 * linear(argb shr 8) + 0.0722 * linear(argb)

    /** WCAG contrast ratio, 1 .. 21, whichever colour is lighter. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** White or black, whichever reads better on [argb]. */
    fun contentOn(argb: Int): Int = if (contrastRatio(argb, WHITE) >= contrastRatio(argb, BLACK)) WHITE else BLACK

    /**
     * [argb] made to reach [minRatio] against every one of [backgrounds]: unchanged when it already does, otherwise
     * darkened (light backgrounds) or lightened (dark ones) in OKLCH, keeping its hue, only as far as needed.
     */
    fun ensureContrast(argb: Int, backgrounds: List<Int>, minRatio: Double = AA_CONTRAST): Int {
        val opaque = argb or BLACK
        fun passes(color: Int) = backgrounds.all { contrastRatio(color, it) >= minRatio }
        if (passes(opaque)) return opaque
        val darken = backgrounds.map(::relativeLuminance).average() > 0.5
        val start = toOklch(opaque)
        // Bisect lightness between the colour and the extreme (black or white) that surely passes.
        var near = start.l
        var far = if (darken) 0.0 else 1.0
        repeat(24) {
            val mid = (near + far) / 2
            if (passes(fromOklch(start.copy(l = mid)))) far = mid else near = mid
        }
        return fromOklch(start.copy(l = far)).takeIf(::passes) ?: if (darken) BLACK else WHITE
    }

    fun toOklch(argb: Int): Oklch {
        val r = linear(argb shr 16)
        val g = linear(argb shr 8)
        val b = linear(argb)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
        return Oklch(lightness, hypot(a, bb), hue)
    }

    /** The opaque sRGB colour of [color]; chroma is reduced (hue and lightness kept) when it lies outside sRGB. */
    fun fromOklch(color: Oklch): Int {
        val l = color.l.coerceIn(0.0, 1.0)
        linearRgb(l, color.c, color.h)?.let { return pack(it) }
        var inside = 0.0
        var outside = color.c
        repeat(20) {
            val mid = (inside + outside) / 2
            if (linearRgb(l, mid, color.h) != null) inside = mid else outside = mid
        }
        return pack(linearRgb(l, inside, color.h) ?: doubleArrayOf(l, l, l))
    }

    /** Linear sRGB of an OKLCH colour, or `null` when it is out of gamut. */
    private fun linearRgb(lightness: Double, chroma: Double, hue: Double): DoubleArray? {
        val rad = Math.toRadians(hue)
        val a = chroma * cos(rad)
        val b = chroma * sin(rad)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val rgb = doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
        return rgb.takeIf { channels -> channels.all { it in -GAMUT_EPSILON..1 + GAMUT_EPSILON } }
    }

    private fun pack(linear: DoubleArray): Int {
        val (r, g, b) = linear.map { (encode(it.coerceIn(0.0, 1.0)) * 255).roundToInt() }
        return BLACK or (r shl 16) or (g shl 8) or b
    }

    private fun linear(channel: Int): Double {
        val c = (channel and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun encode(linear: Double): Double =
        if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055

    private const val GAMUT_EPSILON = 1e-4
}
