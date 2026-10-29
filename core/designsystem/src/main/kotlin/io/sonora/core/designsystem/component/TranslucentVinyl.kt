package io.sonora.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp

/**
 * The colours of [TranslucentVinyl]: a neutral tile, the user's [accent] as the light behind the translucent disc and
 * as the tinted label (its centre brighter, where the light catches it).
 */
internal data class TranslucentVinylPalette(
    val tileTop: Color,
    val tileBottom: Color,
    val glow: Color,
    val labelCentre: Color,
    val labelEdge: Color,
) {
    companion object {
        fun of(accent: Color, dark: Boolean): TranslucentVinylPalette = TranslucentVinylPalette(
            tileTop = if (dark) Color(0xFF2E2B2F) else Color(0xFFF4F2F5),
            tileBottom = if (dark) Color(0xFF141215) else Color(0xFFDCD8DE),
            glow = accent.copy(alpha = 0.85f),
            labelCentre = lerp(accent, Color.White, 0.3f).copy(alpha = 0.95f),
            labelEdge = accent.copy(alpha = 0.85f),
        )
    }
}

/**
 * The artwork of a track without a cover: a translucent record over a soft light in [palette]'s accent. Drawn on a
 * 100-unit square scaled to the size; no blur (the frosted look comes from wider, fainter glows inside the disc), so it
 * costs the same on every translucent tier.
 */
@Composable
internal fun TranslucentVinyl(palette: TranslucentVinylPalette, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 100f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val centre = p(50f, 50f)
        val haze = lerp(palette.glow.copy(alpha = 1f), Color.White, 0.55f)

        drawRect(Brush.linearGradient(listOf(palette.tileTop, palette.tileBottom), Offset.Zero, Offset(size.width, size.height)))

        // The light behind the translucent record, sharp around the disc.
        glow(palette.glow, p(34f, 64f), 30f * u)
        glow(haze.copy(alpha = 0.7f), p(70f, 30f), 24f * u)

        // A soft shadow so the disc's edge reads on a light tile too.
        drawCircle(
            Brush.radialGradient(
                0.82f to Color.Black.copy(alpha = 0.16f), 1f to Color.Transparent,
                center = p(51f, 53f), radius = 41f * u,
            ),
            radius = 41f * u, center = p(51f, 53f),
        )

        // Through the disc the light is frosted: wider and fainter.
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, 38f * u)) }
        clipPath(disc) {
            drawCircle(Color.White.copy(alpha = 0.04f), radius = 38f * u, center = centre)
            glow(palette.glow.copy(alpha = 0.55f), p(38f, 60f), 38f * u)
            glow(haze.copy(alpha = 0.45f), p(66f, 34f), 32f * u)
        }

        drawCircle(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.42f), 0.5f to Color.White.copy(alpha = 0.12f),
                1f to Color.White.copy(alpha = 0.26f),
                start = p(12f, 12f), end = p(88f, 88f),
            ),
            radius = 38f * u, center = centre,
        )
        drawCircle(Color.Black.copy(alpha = 0.10f), radius = 38f * u, center = centre, style = Stroke(0.5f * u))

        // Grooves.
        for ((radius, alpha) in GROOVES) {
            drawCircle(Color.White.copy(alpha = alpha), radius = radius * u, center = centre, style = Stroke(0.6f * u))
        }

        // The rim, bright where the light falls.
        drawCircle(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.95f), 0.45f to Color.White.copy(alpha = 0.15f),
                1f to Color.White.copy(alpha = 0.6f),
                start = p(12f, 12f), end = p(88f, 88f),
            ),
            radius = 37.4f * u, center = centre, style = Stroke(1.2f * u),
        )

        // Specular arcs.
        arc(Color.White.copy(alpha = 0.9f), centre, 32f * u, 209.7f, 50f, 2.4f * u)
        arc(Color.White.copy(alpha = 0.45f), centre, 32f * u, 25f, 33f, 1.6f * u)

        // The tinted translucent label and the spindle hole.
        drawCircle(
            Brush.radialGradient(
                listOf(palette.labelCentre, palette.labelEdge),
                center = p(45f, 44f), radius = 13f * u * 1.6f,
            ),
            radius = 13f * u, center = centre,
        )
        drawCircle(Color.White.copy(alpha = 0.55f), radius = 12.6f * u, center = centre, style = Stroke(0.6f * u))
        arc(Color.White.copy(alpha = 0.8f), centre, 10f * u, 210.5f, 42f, 1.2f * u)
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 2.4f * u, center = centre)
    }
}

private val GROOVES = listOf(33.5f to 0.32f, 29.5f to 0.22f, 25.5f to 0.30f, 21.5f to 0.20f)

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
}

private fun DrawScope.arc(color: Color, centre: Offset, radius: Float, startDeg: Float, sweepDeg: Float, width: Float) {
    drawArc(
        color = color, startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}
