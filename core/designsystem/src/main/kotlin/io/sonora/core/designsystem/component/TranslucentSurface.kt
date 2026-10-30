package io.sonora.core.designsystem.component

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.TranslucentContentColors
import io.sonora.core.designsystem.translucent.TranslucentContrast
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentOptics
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentShaders
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme
import kotlin.math.cos
import kotlin.math.sin

/** Never more than this part of the shorter side refracts, so small buttons stay clear in the middle. */
private const val MAX_REFRACTION_FRACTION = 0.3f

private val NoShift: () -> Float = { 0f }

private val NoWash: () -> Color = { Color.Transparent }

/** False once the device failed to compile the surface's light shader: from then on it draws the frosted light. */
@Volatile
private var lightShaderUsable = Build.VERSION.SDK_INT >= TranslucentPolicy.REFRACTION_MIN_SDK

/**
 * iOS 26 style translucency. Over a [backdrop] (see [TranslucentBackdrop]) it samples the content behind it, which
 * glows through it ([TranslucentOptics]): on Android 13+ vibrancy and bloom, lens refraction with a slight dispersion
 * that carries the content's own colours at the edge, and a specular rim, an inner band and a caustic in the hue under
 * them, lit from [lightShift] (-1..1) while motion is allowed; on Android 12 blur, vibrancy and white light. Away from
 * the edge the content stays within a readable range ([TranslucentContrast]); [tinted] false makes it clear
 * translucency (only the tint contrast needs); [wash] lays a colour over the surface, read at draw time (e.g. the album
 * tint of [io.sonora.core.designsystem.theme.LocalChromeTint]). Without a backdrop, with Translucency off, on Android
 * 10-11 or with high-contrast text it is a flat translucent fill, opaque when [overContent] (a bar or the mini player over
 * scrolling content, whose text would otherwise read through). Either way the content gets
 * [LocalTranslucentContentColors] that keep WCAG AA contrast over very bright and very dark content. A sampling surface
 * also draws [reflection] (the content [TranslucentPill] marks) under itself, and blurs what it samples by at least
 * [minimumBlur] (an overlay's panel where the app under it is not blurred).
 */
@Composable
fun TranslucentSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CapsuleShape,
    elevation: Dp = 12.dp,
    darkTheme: Boolean = SonoraTheme.isDark,
    backdrop: TranslucentBackdrop? = LocalTranslucentBackdrop.current,
    lightShift: () -> Float = NoShift,
    tinted: Boolean = true,
    wash: () -> Color = NoWash,
    reflection: TranslucentReflection? = null,
    overContent: Boolean = false,
    minimumBlur: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val policy = LocalTranslucentPolicy.current
    val layer = backdrop?.layer?.takeIf { policy.samplesBackdrop }
    val tier = if (layer == null) TranslucentTier.FLAT else policy.tier
    val material = remember(darkTheme, tier, policy.highContrast, tinted, overContent) {
        TranslucentMaterial.of(darkTheme, tier, policy.highContrast, tinted, overContent)
    }
    val optics = remember(tier, material, policy.highContrast) { TranslucentOptics.of(tier, material, policy.highContrast) }
    val accent = if (darkTheme) SonoraTheme.accentOnDark else MaterialTheme.colorScheme.primary
    val colors = remember(material, accent, tier) {
        TranslucentContentColors(Color(material.label), Color(material.contentColor(accent.toArgb())), tier)
    }
    val latestShift by rememberUpdatedState(lightShift)
    val latestWash by rememberUpdatedState(wash)
    val translucent = if (layer != null) {
        remember(layer, shape, tier, material, optics, elevation, policy.specularMotion, reflection, minimumBlur) {
            Modifier.translucency(layer, shape, tier, material, optics, elevation, { TranslucentPolicy.specularAngle(latestShift(), policy.specularMotion) }, { latestWash() }, reflection, minimumBlur)
        }
    } else {
        Modifier.flatTranslucent(shape, elevation, material, optics) { latestWash() }
    }
    CompositionLocalProvider(LocalTranslucentContentColors provides colors) {
        val placed = if (reflection != null) Modifier.onPlaced { reflection.surface = it } else Modifier
        Box(modifier.then(translucent).then(placed), content = content)
    }
}

/**
 * The sampling surface ([TranslucentTier.LIQUID] or [TranslucentTier.FROSTED]), drawn from the one backdrop layer: its
 * effects run on the surface's own area (blur, lens, then one light shader on Liquid), never on the whole screen.
 */
private fun Modifier.translucency(
    layer: LayerBackdrop,
    shape: Shape,
    tier: TranslucentTier,
    material: TranslucentMaterial,
    optics: TranslucentOptics,
    elevation: Dp,
    lightAngle: () -> Float,
    wash: () -> Color,
    reflection: TranslucentReflection?,
    minimumBlur: Dp,
): Modifier {
    val range = rangeFilter(material.range)
    val tint = Color(material.tint).copy(alpha = material.tintAlpha)
    val liquid = tier == TranslucentTier.LIQUID
    val refracts = liquid && shape is CornerBasedShape
    return drawBackdrop(
        backdrop = layer,
        shape = { shape },
        effects = {
            val lit = liquid && lightShaderUsable && size.isSpecified
            if (!lit) {
                vibrancy()
                colorFilter(range)
            }
            blur(maxOf(optics.blurDp.dp, minimumBlur).toPx())
            if (refracts && size.isSpecified) {
                val height = minOf(optics.refractionHeightDp.dp.toPx(), size.minDimension * MAX_REFRACTION_FRACTION)
                lens(height, optics.refractionAmountDp.dp.toPx(), true, optics.dispersion)
            }
            // Colour filters and the light shader are per pixel, so after the lens they act as they would before it.
            if (lit && !translucentLight(material, optics, lightAngle())) {
                vibrancy()
                colorFilter(range)
            }
        },
        highlight = {
            if (liquid) {
                Highlight(alpha = optics.hairlineAlpha, style = HighlightStyle.Default(angle = lightAngle()))
            } else {
                Highlight.Plain.copy(alpha = optics.hairlineAlpha)
            }
        },
        shadow = if (elevation > 0.dp) {
            { Shadow(radius = elevation, color = Color.Black.copy(alpha = 0.18f)) }
        } else {
            null
        },
        onDrawBackdrop = { drawBackdrop ->
            drawBackdrop()
            // Under the surface, so the effects blur, bend and light it as part of what the surface shows.
            reflection?.draw(this, optics, lightAngle())
        },
        onDrawSurface = {
            // Frosted translucency has no light shader: its white light is drawn here, under the tint, as
            // TranslucentContrast models it.
            if (!liquid) Gloss(optics, shape, size, layoutDirection, this).draw(this)
            drawRect(tint)
            val over = wash()
            if (over.alpha > 0f) drawRect(over)
        },
    )
}

/**
 * Adds [TranslucentShaders.LIGHT_SHADER] for [material] and [optics], lit from [lightAngle] degrees; false (and never
 * again) when the device cannot compile it, so the caller falls back to the frosted colour filters.
 */
private fun BackdropEffectScope.translucentLight(material: TranslucentMaterial, optics: TranslucentOptics, lightAngle: Float): Boolean {
    val width = size.width
    val height = size.height
    val inset = padding
    val radii = cornerRadii()
    val radians = Math.toRadians(lightAngle.toDouble())
    val toLightX = -cos(radians).toFloat()
    val toLightY = -sin(radians).toFloat()
    val zone = TranslucentOptics.EDGE_ZONE_DP.dp.toPx()
    val rimWidth = optics.rimWidthDp.dp.toPx()
    val bandDepth = optics.bandDepthDp.dp.toPx()
    val bandWidth = optics.bandWidthDp.dp.toPx()
    val causticDepth = optics.causticDepthDp.dp.toPx()
    val causticWidth = optics.causticWidthDp.dp.toPx()
    return try {
        runtimeShaderEffect(TranslucentShaders.LIGHT_KEY, TranslucentShaders.LIGHT_SHADER, "content") {
            setFloatUniform("size", width, height)
            setFloatUniform("offset", -inset, -inset)
            setFloatUniform("cornerRadii", radii)
            setFloatUniform("range", material.range.darkest, material.range.lightest)
            setFloatUniform("eased", if (material.range.eased) 1f else 0f)
            setFloatUniform("knee", TranslucentContrast.BackdropRange.KNEE)
            setFloatUniform("saturation", optics.saturation)
            setFloatUniform("bloom", optics.bloom)
            setFloatUniform("threshold", optics.bloomThreshold)
            setFloatUniform("edgeGlow", optics.edgeGlow)
            setFloatUniform("edgeZone", zone)
            setFloatUniform("toLight", toLightX, toLightY)
            setFloatUniform("rimWidth", rimWidth)
            setFloatUniform("rimAlpha", optics.rimAlpha)
            setFloatUniform("rimHue", optics.rimHue)
            setFloatUniform("bandDepth", bandDepth)
            setFloatUniform("bandWidth", bandWidth)
            setFloatUniform("bandAlpha", optics.bandAlpha)
            setFloatUniform("causticDepth", causticDepth)
            setFloatUniform("causticWidth", causticWidth)
            setFloatUniform("causticAlpha", optics.causticAlpha)
            setFloatUniform("sheenAlpha", optics.sheenAlpha)
        }
        true
    } catch (e: IllegalArgumentException) {
        lightShaderUsable = false
        Log.w(LOG_TAG, "Translucent light shader unavailable; drawing frosted light", e)
        false
    }
}

/** The surface's corner radii in px, clockwise from the top-left as the shaders read them; none for other shapes. */
private fun BackdropEffectScope.cornerRadii(): FloatArray {
    val corners = shape as? CornerBasedShape ?: return FloatArray(4)
    val start = corners.topStart.toPx(size, this)
    val end = corners.topEnd.toPx(size, this)
    val bottomEnd = corners.bottomEnd.toPx(size, this)
    val bottomStart = corners.bottomStart.toPx(size, this)
    return if (layoutDirection == LayoutDirection.Rtl) {
        floatArrayOf(end, start, bottomStart, bottomEnd)
    } else {
        floatArrayOf(start, end, bottomEnd, bottomStart)
    }
}

/**
 * Flat translucency: a translucent fill, then its white light (a rim lit from the top-left, a band, a caustic,
 * a sheen).
 */
private fun Modifier.flatTranslucent(shape: Shape, elevation: Dp, material: TranslucentMaterial, optics: TranslucentOptics, wash: () -> Color): Modifier {
    val fill = Color(material.tint).copy(alpha = material.tintAlpha)
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(fill)
        .drawBehind {
            val over = wash()
            if (over.alpha > 0f) drawRect(over)
        }
        .gloss(shape, optics)
}

/**
 * The white light of flat translucency, lit from the top-left, for a translucent surface that cannot be a
 * [TranslucentSurface] (e.g. a veil drawn while Translucency is off): a bright rim, an inner band and a faint caustic
 * along the edges. Draw it after the surface's fill, clipped to [shape].
 */
fun Modifier.translucentGloss(shape: Shape, darkTheme: Boolean = true): Modifier {
    val material = TranslucentMaterial.of(darkTheme, TranslucentTier.FLAT, highContrast = false, tinted = false)
    return gloss(shape, TranslucentOptics.of(TranslucentTier.FLAT, material))
}

private fun Modifier.gloss(shape: Shape, optics: TranslucentOptics): Modifier = drawWithCache {
    val gloss = Gloss(optics, shape, size, layoutDirection, this)
    onDrawBehind { gloss.draw(this) }
}

/**
 * The white light of translucency without the light shader, built for one size; every part but the sheen hugs the
 * edge.
 */
private class Gloss(optics: TranslucentOptics, shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density) {
    private val outline = shape.createOutline(size, layoutDirection, density)
    private val rimWidth = with(density) { optics.rimWidthDp.dp.toPx() } * 2f // half of the stroke falls outside the clip
    private val rim = Brush.linearGradient(
        listOf(white(optics.rimAlpha), white(optics.rimAlpha * RIM_FAR_SHARE)),
        end = Offset(size.width, size.height),
    )
    private val sheen = if (optics.sheenAlpha > 0f) Brush.verticalGradient(listOf(white(optics.sheenAlpha), Color.Transparent), endY = size.height / 2f) else null
    private val band: Brush?
    private val caustic: Brush?

    init {
        val zone = with(density) { TranslucentOptics.EDGE_ZONE_DP.dp.toPx() }
        val bandPeak = with(density) { optics.bandDepthDp.dp.toPx() } / zone
        val bandEnd = with(density) { (optics.bandDepthDp + 2f * optics.bandWidthDp).dp.toPx() } / zone
        band = if (optics.bandAlpha > 0f) {
            Brush.verticalGradient(
                0f to white(optics.bandAlpha * EDGE_SHARE),
                bandPeak.coerceIn(0f, 1f) to white(optics.bandAlpha),
                bandEnd.coerceIn(0f, 1f) to Color.Transparent,
                endY = zone,
            )
        } else {
            null
        }
        val causticPeak = 1f - with(density) { optics.causticDepthDp.dp.toPx() } / zone
        val causticStart = 1f - with(density) { (optics.causticDepthDp + 2f * optics.causticWidthDp).dp.toPx() } / zone
        caustic = if (optics.causticAlpha > 0f) {
            Brush.verticalGradient(
                causticStart.coerceIn(0f, 1f) to Color.Transparent,
                causticPeak.coerceIn(0f, 1f) to white(optics.causticAlpha),
                1f to white(optics.causticAlpha * EDGE_SHARE),
                startY = size.height - zone,
                endY = size.height,
            )
        } else {
            null
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        sheen?.let { drawRect(it) }
        band?.let { drawRect(it) }
        caustic?.let { drawRect(it) }
        if (rimWidth > 0f) drawOutline(outline, rim, style = Stroke(rimWidth))
    }

    private companion object {
        /** The rim on the far side catches this share of the lit side's light. */
        const val RIM_FAR_SHARE = 0.2f

        /** The band and the caustic at the very edge, where the rim takes over. */
        const val EDGE_SHARE = 0.3f
    }
}

private fun white(alpha: Float) = Color.White.copy(alpha = alpha)

private const val LOG_TAG = "SonoraTranslucent"

/** Maps every channel of the backdrop into [range] (the contrast model of [TranslucentContrast]). */
private fun rangeFilter(range: TranslucentContrast.BackdropRange): ColorFilter {
    val scale = range.lightest - range.darkest
    val offset = range.darkest * 255f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}
