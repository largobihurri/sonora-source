package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.theme.SonoraTheme

/** Scale of the covers behind the front one, nearest first. */
private val BackScales = floatArrayOf(0.88f, 0.76f)

/** Black scrim over the covers behind the front one, nearest first: each step back is darker. */
private val BackDims = floatArrayOf(0.28f, 0.5f)

/**
 * An artist's covers as a small stack: [covers]' first at the front, at [coverSize]; up to two more behind it, each
 * smaller, darker and shifted toward the trailing edge so only a sliver of each shows, hinting at more albums.
 * Everything is vertically centred in a fixed [width] (so rows line up whatever the count), the front cover at the
 * start. A thin ring in [gapColor] (the page background) parts each cover from the one behind. Without covers, the
 * artwork placeholder stands alone.
 */
@Composable
fun StackedArtwork(
    covers: List<String>,
    modifier: Modifier = Modifier,
    coverSize: Dp = 48.dp,
    width: Dp = 60.dp,
    cornerRadius: Dp = 6.dp,
    gapColor: Color = MaterialTheme.colorScheme.background,
    contentDescription: String? = null,
) {
    val behind = covers.drop(1).take(BackScales.size)
    val peek = (width - coverSize) / BackScales.size
    Box(modifier.size(width, coverSize)) {
        // Farthest first, so each nearer cover overlaps the one behind it.
        for (depth in behind.indices.reversed()) {
            val scale = BackScales[depth]
            val size = coverSize * scale
            val shape = RoundedCornerShape(cornerRadius * scale)
            val trailingEdge = coverSize + peek * (depth + 1)
            Box(Modifier.align(Alignment.CenterStart).offset(x = trailingEdge - size).size(size)) {
                Artwork(behind[depth], Modifier.matchParentSize(), shape)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = BackDims[depth]), shape))
                if (depth < behind.lastIndex) Box(Modifier.matchParentSize().border(GapWidth, gapColor, shape))
            }
        }
        val shape = RoundedCornerShape(cornerRadius)
        Box(Modifier.align(Alignment.CenterStart).size(coverSize)) {
            Artwork(covers.firstOrNull(), Modifier.matchParentSize(), shape, contentDescription)
            if (behind.isNotEmpty()) Box(Modifier.matchParentSize().border(GapWidth, gapColor, shape))
        }
    }
}

private val GapWidth = 1.dp

@Preview
@Composable
private fun StackedArtworkPreview() {
    SonoraTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            StackedArtwork(emptyList())
            StackedArtwork(listOf("a"), Modifier.padding(top = 8.dp))
            StackedArtwork(listOf("a", "b"), Modifier.padding(top = 8.dp))
            StackedArtwork(listOf("a", "b", "c"), Modifier.padding(top = 8.dp))
        }
    }
}
