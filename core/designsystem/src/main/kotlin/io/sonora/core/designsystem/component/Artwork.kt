package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import io.sonora.core.designsystem.theme.SonoraTheme

/** Album artwork loaded with Coil, falling back to a translucent record ([TranslucentVinyl]). */
@Composable
fun Artwork(
    model: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentDescription: String? = null,
) {
    Box(modifier.clip(shape)) {
        if (model == null) {
            ArtworkPlaceholder(Modifier.fillMaxSize())
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { ArtworkPlaceholder(Modifier.fillMaxSize()) },
                error = { ArtworkPlaceholder(Modifier.fillMaxSize()) },
            )
        }
    }
}

/** A track or album without a cover: the translucent record, lit by the user's accent. */
@Composable
private fun ArtworkPlaceholder(modifier: Modifier) {
    TranslucentVinyl(TranslucentVinylPalette.of(MaterialTheme.colorScheme.primary, SonoraTheme.isDark), modifier)
}
