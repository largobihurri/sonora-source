package io.sonora.core.designsystem.translucent

import androidx.compose.ui.geometry.Offset
import io.sonora.core.designsystem.theme.ColorMath
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How a translucent surface of one [TranslucentTier] lights and bends what it shows, free of Android so it is
 * unit-tested. Translucency lets the content glow through it: its own colours, more saturated ([saturation]) and
 * blooming where bright ([bloom] past [bloomThreshold]), shown almost unfiltered ([edgeGlow]) where the lens bends
 * them at the edge, with a slight chromatic [dispersion]. Its light (a specular rim, an inner band along the lit edge
 * and a caustic along the far one) takes the hue under it ([rimHue]). All of that stays within [EDGE_ZONE_DP] of the
 * edge, where no label sits; the only light reaching the labels is the soft [sheenAlpha], capped by
 * [TranslucentContrast.maximumSheen]. A label's reflection is drawn under the surface, [reflectionDepthDp] away from
 * the light. Lengths are in dp.
 */
data class TranslucentOptics(
    val blurDp: Float,
    val saturation: Float,
    val bloom: Float,
    val bloomThreshold: Float,
    val edgeGlow: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val dispersion: Boolean,
    val rimWidthDp: Float,
    val rimAlpha: Float,
    val rimHue: Float,
    val hairlineAlpha: Float,
    val bandDepthDp: Float,
    val bandWidthDp: Float,
    val bandAlpha: Float,
    val causticDepthDp: Float,
    val causticWidthDp: Float,
    val causticAlpha: Float,
    val sheenAlpha: Float,
    val reflectionAlpha: Float,
    val reflectionDepthDp: Float,
    val reflectionScale: Float,
    val reflectionAdds: Boolean,
) {

    /**
     * The most light the rim, the band and the caustic add [depthDp] inside the edge (the shader's formula, with the
     * light falling straight on each); nothing from [EDGE_ZONE_DP] in.
     */
    fun edgeLightAt(depthDp: Float): Float {
        val rim = rimAlpha * (1f - smoothstep(0f, rimWidthDp, depthDp))
        val band = bandAlpha * bell(depthDp, bandDepthDp, bandWidthDp)
        val caustic = causticAlpha * bell(depthDp, causticDepthDp, causticWidthDp)
        return rim + (band + caustic) * edgeFade(depthDp)
    }

    /** How much of the unfiltered, glowing content shows [depthDp] inside the edge; none from [EDGE_ZONE_DP] in. */
    fun glowAt(depthDp: Float): Float = edgeGlow * (1f - smoothstep(EDGE_ZONE_DP / 2f, EDGE_ZONE_DP, depthDp))

    /** Where a label's reflection sits from the label, in dp: away from a light shining from [lightAngle] degrees. */
    fun reflectionOffset(lightAngle: Float): Offset {
        val radians = Math.toRadians(lightAngle.toDouble())
        return Offset((cos(radians) * reflectionDepthDp).toFloat(), (sin(radians) * reflectionDepthDp).toFloat())
    }

    companion object {
        /** The band along the edge that the surface's light and glow may brighten freely: labels sit further in. */
        const val EDGE_ZONE_DP = 6f

        /** The translucent surface for [tier], made of [material]; [highContrast] keeps only a plain rim. */
        fun of(tier: TranslucentTier, material: TranslucentMaterial, highContrast: Boolean = false): TranslucentOptics {
            val lightLabel = ColorMath.relativeLuminance(material.label) > 0.5
            val headroom = TranslucentContrast.maximumSheen(
                material.tint, material.tintAlpha, material.label, material.range, overTint = tier == TranslucentTier.FLAT,
            )
            val optics = when (tier) {
                TranslucentTier.LIQUID -> Liquid
                TranslucentTier.FROSTED -> Frosted
                TranslucentTier.FLAT -> if (lightLabel) FlatDark else FlatLight
            }
            if (highContrast) return optics.copy(bandAlpha = 0f, causticAlpha = 0f, sheenAlpha = 0f, reflectionAlpha = 0f)
            val reflection = when {
                optics.reflectionAlpha == 0f -> 0f
                lightLabel -> optics.reflectionAlpha
                else -> optics.reflectionAlpha * DARKENING_REFLECTION_SHARE
            }
            return optics.copy(
                sheenAlpha = minOf(optics.sheenAlpha, headroom),
                reflectionAlpha = reflection,
                reflectionAdds = lightLabel,
            )
        }

        /** A dark label's reflection darkens what is under it, which shows more than light added: it is drawn fainter. */
        private const val DARKENING_REFLECTION_SHARE = 0.5f

        private val Liquid = TranslucentOptics(
            blurDp = 2f,
            saturation = 1.6f,
            bloom = 0.35f,
            bloomThreshold = 0.55f,
            edgeGlow = 0.85f,
            refractionHeightDp = 20f,
            refractionAmountDp = 36f,
            dispersion = true,
            rimWidthDp = 1.5f,
            rimAlpha = 0.85f,
            rimHue = 0.6f,
            hairlineAlpha = 0.45f,
            bandDepthDp = 2f,
            bandWidthDp = 1f,
            bandAlpha = 0.32f,
            causticDepthDp = 3f,
            causticWidthDp = 1.25f,
            causticAlpha = 0.3f,
            sheenAlpha = 0.06f,
            reflectionAlpha = 0.35f,
            reflectionDepthDp = 1.5f,
            reflectionScale = 1.04f,
            reflectionAdds = true,
        )

        private val Frosted = Liquid.copy(
            blurDp = 6f,
            saturation = 1.5f,
            bloom = 0f,
            edgeGlow = 0f,
            refractionHeightDp = 0f,
            refractionAmountDp = 0f,
            dispersion = false,
            rimWidthDp = 1f,
            rimAlpha = 0.7f,
            rimHue = 0f,
            hairlineAlpha = 0.5f,
            bandAlpha = 0.24f,
            causticAlpha = 0.18f,
            // The frosted gloss keeps its sheen; Liquid's is thinner.
            sheenAlpha = 0.1f,
            reflectionAlpha = 0.25f,
            reflectionScale = 1f,
        )

        /** Flat translucency under a light label (dark translucency): today's dim rim and highlight, made glossier. */
        private val FlatDark = Frosted.copy(
            blurDp = 0f,
            saturation = 1f,
            hairlineAlpha = 0f,
            rimAlpha = 0.55f,
            bandAlpha = 0.2f,
            causticAlpha = 0.14f,
            sheenAlpha = 0.1f,
            reflectionAlpha = 0f,
        )

        /** Flat translucency under a dark label (light translucency). */
        private val FlatLight = FlatDark.copy(rimAlpha = 0.95f, bandAlpha = 0.5f, causticAlpha = 0.3f, sheenAlpha = 0.45f)

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun bell(x: Float, centre: Float, width: Float): Float {
            val d = (x - centre) / width
            return exp(-d * d)
        }

        private fun edgeFade(depthDp: Float): Float = 1f - smoothstep(EDGE_ZONE_DP * 0.75f, EDGE_ZONE_DP, depthDp)
    }
}

/** AGSL run by the translucent surfaces (API 33+, through the backdrop library's runtime shader effects). */
internal object TranslucentShaders {

    /** Cache key of [LIGHT_SHADER]. */
    const val LIGHT_KEY = "SonoraTranslucentLight"

    /**
     * The surface's light, after the lens: vibrancy and bloom of the content, then the readable range of
     * [TranslucentContrast] ([TranslucentContrast.BackdropRange.map]: content the label is already readable on shows as
     * it is) everywhere but the edge zone, where the bent content shows almost unfiltered; then a specular rim and an
     * inner band along the lit edge, a caustic along the far one and a soft sheen from the top, all in the hue under
     * them.
     * The formulas match [TranslucentOptics.edgeLightAt] and [TranslucentOptics.glowAt].
     */
    const val LIGHT_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float2 range;
uniform float eased;
uniform float knee;
uniform float saturation;
uniform float bloom;
uniform float threshold;
uniform float edgeGlow;
uniform float edgeZone;
uniform float2 toLight;
uniform float rimWidth;
uniform float rimAlpha;
uniform float rimHue;
uniform float bandDepth;
uniform float bandWidth;
uniform float bandAlpha;
uniform float causticDepth;
uniform float causticWidth;
uniform float causticAlpha;
uniform float sheenAlpha;

float radiusAt(float2 p, float4 radii) {
    if (p.x >= 0.0) {
        return p.y <= 0.0 ? radii.y : radii.z;
    }
    return p.y <= 0.0 ? radii.x : radii.w;
}

float sdRoundedRect(float2 p, float2 halfSize, float radius) {
    float2 corner = abs(p) - (halfSize - float2(radius));
    return length(max(corner, 0.0)) - radius + min(max(corner.x, corner.y), 0.0);
}

float2 edgeNormal(float2 p, float2 halfSize, float radius) {
    float2 corner = abs(p) - (halfSize - float2(radius));
    if (max(corner.x, corner.y) > 0.0) {
        return sign(p) * normalize(max(corner, 0.0001));
    }
    float alongX = step(corner.y, corner.x);
    return sign(p) * float2(alongX, 1.0 - alongX);
}

// TranslucentContrast.BackdropRange.easeInto: unchanged up to knee * bound, then bent into 0..bound with no kink.
float3 easeInto(float3 x, float bound) {
    if (bound >= 1.0) {
        return x;
    }
    float start = knee * bound;
    float n = (1.0 - start) / (bound - start);
    float3 t = clamp((x - start) / (1.0 - start), 0.0, 1.0);
    float3 bent = start + (bound - start) * (1.0 - pow(max(1.0 - t, 0.0), float3(n)));
    return mix(x, bent, step(start, x));
}

// TranslucentContrast.BackdropRange.map.
float3 toRange(float3 x) {
    if (eased < 0.5) {
        return range.x + (range.y - range.x) * x;
    }
    float3 top = easeInto(x, range.y);
    return clamp(1.0 - easeInto(1.0 - top, 1.0 - range.x), range.x, range.y);
}

float bell(float x, float centre, float width) {
    float d = (x - centre) / width;
    return exp(-d * d);
}

half4 main(float2 coord) {
    half4 sampled = content.eval(coord);
    float alpha = float(sampled.a);
    if (alpha <= 0.0) {
        return sampled;
    }
    float3 rgb = float3(sampled.rgb) / alpha;

    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = clamp(mix(float3(luma), rgb, saturation), 0.0, 1.0);
    float glow = bloom * smoothstep(threshold, 1.0, luma);
    rgb = clamp(rgb * (1.0 + glow) + glow * 0.2, 0.0, 1.0);
    float3 ranged = toRange(rgb);

    float2 halfSize = size * 0.5;
    float2 p = coord + offset - halfSize;
    float shortest = min(halfSize.x, halfSize.y);
    float radius = min(radiusAt(p, cornerRadii), shortest);
    float depth = -sdRoundedRect(p, halfSize, radius);
    if (depth <= 0.0) {
        return half4(half3(ranged * alpha), half(alpha));
    }

    float open = edgeGlow * (1.0 - smoothstep(edgeZone * 0.5, edgeZone, depth));
    float3 shown = mix(ranged, rgb, open);

    float2 normal = edgeNormal(p, halfSize, min(radius * 1.5, shortest));
    float facing = dot(normal, toLight);
    float lit = max(facing, 0.0);
    float away = max(-facing, 0.0);
    float fade = 1.0 - smoothstep(edgeZone * 0.75, edgeZone, depth);
    float rim = rimAlpha * (lit + 0.35 * away) * (1.0 - smoothstep(0.0, rimWidth, depth));
    float band = bandAlpha * lit * bell(depth, bandDepth, bandWidth) * fade;
    float caustic = causticAlpha * away * bell(depth, causticDepth, causticWidth) * fade;
    float sheen = sheenAlpha * (1.0 - smoothstep(0.0, halfSize.y, p.y + halfSize.y));

    float peak = max(max(shown.r, shown.g), shown.b);
    float3 hue = peak > 0.001 ? shown / peak : float3(1.0);
    float3 light = mix(float3(1.0), hue, rimHue);
    shown = mix(shown, light, clamp(rim + band + caustic + sheen, 0.0, 1.0));
    return half4(half3(shown * alpha), half(alpha));
}
"""
}
