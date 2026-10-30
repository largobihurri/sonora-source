package io.sonora.core.designsystem.component.visualcollection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.TranslucentContentColors
import io.sonora.core.designsystem.translucent.TranslucentContrast
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.theme.rememberArtworkPalette

/**
 * The back of a flipped visual collection card, filling [modifier]: translucency over [backdrop] (the visual collection behind the
 * card), tinted with the colour of [artwork] as [VisualCollectionBackTranslucent] sets it, more strongly behind [content] than at
 * the rim. [veil] (read at draw time, see [VisualCollectionBackTranslucent.veil]) lays today's opaque panel over the
 * translucency while the card turns, because the backdrop's sampling only follows a flat card. Without sampling
 * translucency (Translucency off, high contrast, the flat tier) the back is that opaque panel. [content] gets
 * [LocalTranslucentContentColors] either way.
 */
@Composable
internal fun VisualCollectionBackPanel(
    artwork: String?,
    backdrop: TranslucentBackdrop,
    veil: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val policy = LocalTranslucentPolicy.current
    val accent = SonoraTheme.accentOnDark
    if (!VisualCollectionBackTranslucent.showsTranslucent(policy, hasBackdrop = backdrop.layer != null)) {
        val colors = remember(accent) {
            val panel = BackPanel.copy(alpha = 1f).toArgb()
            val readable = TranslucentContrast.readableOn(accent.toArgb(), panel, BackPanel.alpha, TranslucentContrast.BackdropRange.Full)
            TranslucentContentColors(Color.White, Color(readable), TranslucentTier.FLAT)
        }
        CompositionLocalProvider(LocalTranslucentContentColors provides colors) {
            Box(modifier.background(BackPanel)) { content() }
        }
        return
    }
    // The material TranslucentSurface draws below for these settings (high contrast never reaches here).
    val material = remember(policy.tier) { TranslucentMaterial.of(dark = true, tier = policy.tier, highContrast = false, tinted = false) }
    val album = rememberArtworkPalette(artwork)?.background ?: NeutralAlbum
    val tint = remember(album, material) { VisualCollectionBackTranslucent.tint(album, material) }
    val color by animateColorAsState(Color(tint.color), tween(TINT_FADE_MS), label = "visualCollectionBackTint")
    TranslucentSurface(
        modifier,
        shape = BackTranslucentShape,
        elevation = 0.dp,
        darkTheme = true,
        backdrop = backdrop,
        tinted = false,
        wash = { color.copy(alpha = tint.rimAlpha) },
    ) {
        Box(Modifier.fillMaxSize().backTint({ color }, tint.bodyOverlayAlpha, RimWidth, veil)) { content() }
    }
}

/**
 * Behind the content: [color] at [overlayAlpha] over the translucency, fading out across the [rim] band along every
 * edge so the rim keeps the lighter translucency, then the opaque [BackPanel] at [veil].
 */
private fun Modifier.backTint(color: () -> Color, overlayAlpha: Float, rim: Dp, veil: () -> Float): Modifier = drawWithCache {
    val feather = minOf(rim.toPx(), size.minDimension / 2f)
    val vertical = feather / size.height
    val horizontal = feather / size.width
    val fadeY = Brush.verticalGradient(0f to Color.Transparent, vertical to Color.Black, (1f - vertical) to Color.Black, 1f to Color.Transparent)
    val fadeX = Brush.horizontalGradient(0f to Color.Transparent, horizontal to Color.Black, (1f - horizontal) to Color.Black, 1f to Color.Transparent)
    val bounds = size.toRect()
    onDrawBehind {
        if (overlayAlpha > 0f) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, Paint())
                drawRect(color().copy(alpha = overlayAlpha))
                drawRect(fadeY, blendMode = BlendMode.DstIn)
                drawRect(fadeX, blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
        val cover = veil().coerceIn(0f, 1f)
        if (cover > 0f) drawRect(BackPanel.copy(alpha = BackPanel.alpha * cover))
    }
}

/** Today's opaque back: the fallback, and the veil while the card turns. */
private val BackPanel = Color(0xF2121214)

/** A corner-based shape, so the translucent surface refracts at its edges; square like the cover it is the back of. */
private val BackTranslucentShape = RoundedCornerShape(0.dp)

/** The band along the edges where the translucent surface keeps its lighter rim tint. */
private val RimWidth = 16.dp

private val NeutralAlbum = ArtworkPalette.from(null).background
private const val TINT_FADE_MS = 300
