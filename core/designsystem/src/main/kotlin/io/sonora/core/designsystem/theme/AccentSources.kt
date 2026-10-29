package io.sonora.core.designsystem.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Side of the downscaled artwork the accent seed is read from: plenty for a dominant colour, cheap to scan. */
private const val ARTWORK_SAMPLE_PX = 64

/** The wallpaper (Material You) primary for a [dark] or light theme; `null` before Android 12. */
fun wallpaperAccent(context: Context, dark: Boolean): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return scheme.primary.toArgb()
}

/**
 * The accent seed of the artwork at [artworkUri] ([ArtworkColors.accentSeed]); `null` without artwork or colour.
 * While a new artwork is read the previous seed stays, so the accent blends from one track's colour to the next.
 */
@Composable
fun rememberArtworkAccentSeed(artworkUri: String?): Int? = rememberArtworkColor(artworkUri, ArtworkColors::accentSeed)

/**
 * The [ArtworkPalette] of the artwork at [artworkUri], from its dominant colour ([ArtworkColors.dominant]); the neutral
 * palette without artwork. `null` only while the first artwork is read, so a page can wait before tinting itself.
 */
@Composable
fun rememberArtworkPalette(artworkUri: String?): ArtworkPalette? {
    val context = LocalContext.current
    var palette by remember { mutableStateOf<ArtworkPalette?>(null) }
    LaunchedEffect(artworkUri) {
        val dominant = artworkUri?.let { artworkColor(context, it, ArtworkColors::dominant) }
        palette = withContext(Dispatchers.Default) { ArtworkPalette.from(dominant) }
    }
    return palette
}

/** [extract] of the artwork at [artworkUri]; while a new artwork is read the previous colour stays. */
@Composable
private fun rememberArtworkColor(artworkUri: String?, extract: (IntArray) -> Int?): Int? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(artworkUri) {
        color = artworkUri?.let { artworkColor(context, it, extract) }
    }
    return color
}

private suspend fun artworkColor(context: Context, uri: String, extract: (IntArray) -> Int?): Int? = try {
    val request = ImageRequest.Builder(context).data(uri).allowHardware(false).size(ARTWORK_SAMPLE_PX).build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.let { result ->
        withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            extract(pixels)
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null // unreadable artwork: the preset accent or the neutral palette stands in
}
