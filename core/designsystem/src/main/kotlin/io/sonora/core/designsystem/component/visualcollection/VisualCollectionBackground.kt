package io.sonora.core.designsystem.component.visualcollection

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.rememberArtworkPalette

/**
 * The visual collection background: [artwork]'s palette colour (the album page tint), the artwork itself heavily blurred on
 * Android 12+ (a gradient alone below), a darkening scrim sized by [VisualCollectionBackdrop.scrimAlpha], a soft diagonal
 * specular sheen and a vignette. A new artwork cross-fades in over [fadeMs] (see [BackdropHold] for when it changes).
 * Everything is drawn once per artwork: the blur runs on a small layer that is scaled up, so moving covers above it
 * cost nothing here.
 */
@Composable
internal fun VisualCollectionBackground(artwork: String?, fadeMs: Int, modifier: Modifier = Modifier) {
    val palette = rememberArtworkPalette(artwork) ?: NeutralPalette
    val scrimArgb = remember(palette) { VisualCollectionBackdrop.darkened(palette.background) }
    val base by animateColorAsState(Color(palette.background), tween(fadeMs), label = "visualCollectionBase")
    val scrim by animateColorAsState(Color(scrimArgb), tween(fadeMs), label = "visualCollectionScrim")
    val scrimAlpha by animateFloatAsState(
        remember(palette, scrimArgb) { VisualCollectionBackdrop.scrimAlpha(palette.background, scrimArgb) },
        tween(fadeMs),
        label = "visualCollectionScrimAlpha",
    )
    Box(modifier.drawBehind { drawRect(Brush.verticalGradient(listOf(base, scrim))) }) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Crossfade(artwork, animationSpec = tween(fadeMs), label = "visualCollectionArtwork") { uri ->
                if (uri != null) BlurredArtwork(uri)
            }
        }
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val sheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.14f),
                    0.45f to Color.Transparent,
                    1f to Color.White.copy(alpha = 0.04f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val vignette = Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                    center = size.center,
                    radius = size.maxDimension * 0.62f,
                )
                onDrawBehind {
                    drawRect(scrim.copy(alpha = scrimAlpha))
                    drawRect(sheen)
                    drawRect(vignette)
                }
            },
        )
    }
}

/** [uri] filling the screen, blurred: a small blurred layer scaled up, at [VisualCollectionBackdrop.ARTWORK_ALPHA]. */
@Composable
private fun BlurredArtwork(uri: String) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = maxOf(maxWidth, maxHeight) / BlurSource
        Artwork(
            uri,
            Modifier.requiredSize(BlurSource).graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = VisualCollectionBackdrop.ARTWORK_ALPHA
                val radius = BlurRadius.toPx()
                renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            },
            shape = RectangleShape,
        )
    }
}

private val NeutralPalette = ArtworkPalette.from(null)
private val BlurSource = 48.dp
private val BlurRadius = 5.dp
