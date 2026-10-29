package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * How a [GlossyGrabber] is lit, as white alphas and shares of its size: a body brighter on top ([bodyTop] to
 * [bodyBottom]), a specular sheen across the upper half (from [sheenTop] to [sheenBottom] of the height, [sheenStart] to
 * [sheenEnd] of the width, brightest [sheenPeak] near its start), a rim that catches the light on the top-left
 * ([rimTop]) and fades towards the bottom-right ([rimBottom]), and a faint caustic where the light leaves the
 * translucent surface ([causticPeak], centred at [causticX], [causticY]), as the translucent surfaces are lit.
 */
@Immutable
data class GrabberGloss(
    val bodyTop: Float,
    val bodyBottom: Float,
    val sheenPeak: Float,
    val sheenTop: Float,
    val sheenBottom: Float,
    val sheenStart: Float,
    val sheenEnd: Float,
    val rimTop: Float,
    val rimBottom: Float,
    val causticPeak: Float = 0f,
    val causticX: Float = 0f,
    val causticY: Float = 0f,
) {
    companion object {
        /** The plain handle's white: the glossy one reads as bright on average. */
        const val PLAIN_ALPHA = 0.45f

        val Default = GrabberGloss(
            bodyTop = 0.56f,
            bodyBottom = 0.34f,
            sheenPeak = 0.9f,
            sheenTop = 0.12f,
            sheenBottom = 0.46f,
            sheenStart = 0.1f,
            sheenEnd = 0.64f,
            rimTop = 0.9f,
            rimBottom = 0.12f,
            causticPeak = 0.3f,
            causticX = 0.82f,
            causticY = 0.7f,
        )
    }
}

/**
 * A sheet's drag handle with a subtle glossy, translucent-style finish (T63.7, G0): a capsule of the size [modifier]
 * gives it, lit from the top-left, with a still specular sheen, a bright rim and a faint caustic. Drawn once per size:
 * no animation, no backdrop.
 */
@Composable
fun GlossyGrabber(modifier: Modifier = Modifier, gloss: GrabberGloss = GrabberGloss.Default) {
    Spacer(
        modifier.drawWithCache {
            val radius = CornerRadius(size.height / 2f)
            val body = Brush.verticalGradient(listOf(white(gloss.bodyTop), white(gloss.bodyBottom)))
            val sheenLeft = size.width * gloss.sheenStart
            val sheenRight = size.width * gloss.sheenEnd
            val sheenY = size.height * gloss.sheenTop
            val sheenHeight = size.height * (gloss.sheenBottom - gloss.sheenTop)
            val sheen = Brush.horizontalGradient(
                0f to white(0f),
                0.3f to white(gloss.sheenPeak),
                1f to white(0f),
                startX = sheenLeft,
                endX = sheenRight,
            )
            val rimWidth = 0.6.dp.toPx()
            val rim = Brush.linearGradient(listOf(white(gloss.rimTop), white(gloss.rimBottom)), end = Offset(size.width, size.height))
            val causticCentre = Offset(size.width * gloss.causticX, size.height * gloss.causticY)
            val caustic = Brush.radialGradient(listOf(white(gloss.causticPeak), white(0f)), causticCentre, radius = size.height)
            onDrawBehind {
                drawRoundRect(body, cornerRadius = radius)
                drawRoundRect(caustic, cornerRadius = radius)
                drawRoundRect(
                    sheen,
                    topLeft = Offset(sheenLeft, sheenY),
                    size = Size(sheenRight - sheenLeft, sheenHeight),
                    cornerRadius = CornerRadius(sheenHeight / 2f),
                )
                drawRoundRect(
                    rim,
                    topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
                    size = Size(size.width - rimWidth, size.height - rimWidth),
                    cornerRadius = radius,
                    style = Stroke(rimWidth),
                )
            }
        },
    )
}

private fun white(alpha: Float) = Color.White.copy(alpha = alpha)
