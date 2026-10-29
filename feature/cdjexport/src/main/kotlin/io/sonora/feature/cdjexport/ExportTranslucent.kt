package io.sonora.feature.cdjexport

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.TranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.theme.CHROME_TINT_MILLIS
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.theme.rememberArtworkPalette

/** The tab's translucent panels: very round, like the Now Playing bubble. */
internal val PanelShape = RoundedCornerShape(28.dp)

/** Space between a panel's edge and its content: well clear of the 6 dp where the light brightens the edge. */
internal val PanelInset = 18.dp

/** Secondary text on translucent surfaces, as the other translucent panels draw it. */
private const val SECONDARY_ALPHA = 0.62f

/** Hairlines on translucent surfaces. */
private const val DIVIDER_ALPHA = 0.16f

/**
 * A translucent panel over the tab's [ExportWashLayer], for [content] to draw on. Anything inside that reads the
 * Material colours (`onSurface`, `onSurfaceVariant`, `primary`, `outlineVariant`) gets the panel's own colours instead
 * ([TranslucentContentColors]), so labels stay readable over any cover. No shadow, since several panels share the tab.
 * Must not be placed in a dialog or sheet, which cannot sample the backdrop.
 */
@Composable
internal fun TranslucentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TranslucentSurface(modifier.fillMaxWidth(), shape = PanelShape, elevation = 0.dp) {
        val translucent = LocalTranslucentContentColors.current
        val scheme = MaterialTheme.colorScheme
        val onTranslucent = remember(scheme, translucent) { scheme.onTranslucent(translucent) }
        MaterialTheme(colorScheme = onTranslucent) {
            CompositionLocalProvider(LocalContentColor provides translucent.label, content = content)
        }
    }
}

private fun ColorScheme.onTranslucent(translucent: TranslucentContentColors): ColorScheme = copy(
    onSurface = translucent.label,
    onSurfaceVariant = translucent.label.copy(alpha = SECONDARY_ALPHA),
    onBackground = translucent.label,
    primary = translucent.accent,
    outlineVariant = translucent.label.copy(alpha = DIVIDER_ALPHA),
)

/**
 * The tab's backdrop, the one [translucent] records for the panels above it: the theme's background with [ExportWash]'s soft
 * colour patches over it, tinted by the cover at [artworkUri] (the first track's) or, without one, by the app accent.
 * It is drawn once, and again only while the tint blends to a new cover.
 */
@Composable
internal fun ExportWashLayer(artworkUri: String?, translucent: TranslucentBackdrop, modifier: Modifier = Modifier) {
    val cover = rememberArtworkPalette(artworkUri)
    val palette = ExportWash.palette(cover, hasCover = artworkUri != null, accent = MaterialTheme.colorScheme.primary.toArgb())
    val dark = SonoraTheme.isDark
    val blobs = remember(palette, dark) { ExportWash.blobs(palette, dark) }
    val tints = blobs.map { animateColorAsState(Color(it.argb), tween(CHROME_TINT_MILLIS), label = "washTint") }
    val base = MaterialTheme.colorScheme.background
    // Recorded after the source modifier, so the opaque background and the patches are what the panels sample.
    Box(
        modifier.translucentBackdropSource(translucent).background(base).drawBehind {
            val longest = maxOf(size.width, size.height)
            blobs.forEachIndexed { i, blob ->
                val centre = Offset(blob.centerX * size.width, blob.centerY * size.height)
                val radius = blob.radius * longest
                val tint = tints[i].value
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to tint.copy(alpha = blob.alpha),
                        WASH_MID_STOP to tint.copy(alpha = blob.alpha * WASH_MID_ALPHA),
                        1f to Color.Transparent,
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        },
    )
}

/** Where a patch is half as strong as at its centre-ish: a soft shoulder, so the edge fades instead of ending. */
private const val WASH_MID_STOP = 0.5f
private const val WASH_MID_ALPHA = 0.45f
