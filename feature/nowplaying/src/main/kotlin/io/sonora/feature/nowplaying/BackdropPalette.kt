package io.sonora.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Reads [artworkUri]'s backdrop colours ahead of Now Playing into [BackdropPalettes], so it opens already tinted by its own
 * colours ([rememberBackdropColors] starts from the cached ones) instead of the hueless grey it shows while they are read.
 */
@Composable
fun WarmBackdropPalette(artworkUri: String?) {
    val context = LocalContext.current
    LaunchedEffect(artworkUri) { warmBackdropPalette(artworkUri, BackdropPalettes) { extractBackdropColors(context, it) } }
}

/**
 * Puts [artworkUri]'s colours in [cache] unless they are there already: read with [extract] (nothing is stored when it
 * cannot be read). Now Playing reads them again as it opens, as the picture can change under the same URI.
 */
internal suspend fun warmBackdropPalette(
    artworkUri: String?,
    cache: BackdropPaletteCache,
    extract: suspend (String) -> List<Color>?,
) {
    if (artworkUri == null || cache[artworkUri] != null) return
    extract(artworkUri)?.let { cache[artworkUri] = it }
}
