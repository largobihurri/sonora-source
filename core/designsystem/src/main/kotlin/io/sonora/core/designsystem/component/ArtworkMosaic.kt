package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.theme.SonoraTheme

/**
 * Playlist cover as in Apple Music: four covers in a 2x2 mosaic, a single cover when [covers] holds fewer than
 * four, or the artwork placeholder when it is empty. The caller sizes it (square).
 */
@Composable
fun ArtworkMosaic(
    covers: List<String>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentDescription: String? = null,
) {
    if (covers.size < 4) {
        Artwork(covers.firstOrNull(), modifier, shape, contentDescription)
        return
    }
    Column(modifier.clip(shape)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (column in 0 until 2) {
                    Artwork(covers[row * 2 + column], Modifier.weight(1f).fillMaxHeight(), RectangleShape)
                }
            }
        }
    }
}

@Preview(name = "Mosaic and single cover")
@Composable
private fun ArtworkMosaicPreview() {
    SonoraTheme(darkTheme = true) {
        Row {
            ArtworkMosaic(listOf("a", "b", "c", "d"), Modifier.size(96.dp))
            ArtworkMosaic(emptyList(), Modifier.size(96.dp))
        }
    }
}
