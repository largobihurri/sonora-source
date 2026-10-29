package io.sonora.feature.nowplaying

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.core.designsystem.theme.SonoraTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The backdrop without artwork colours: dark tints of the app's [accent]. */
private fun fallbackColors(accent: Color) = listOf(
    accent.copy(alpha = 1f).darken(0.45f),
    Color(0xFF2C1A3A),
    Color(0xFF1C1C1E),
    accent.copy(alpha = 1f).darken(0.7f),
)

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

/**
 * Apple Music style backdrop: a heavily blurred copy of the artwork under slowly drifting radial gradients
 * built from Palette swatches, finished with a dark scrim so white content stays legible.
 *
 * The drift ([BackdropDrift], one turn in 24 s) runs only while [drifting], one step for each of [driftTicks]: each
 * step redraws this backdrop, and with it every translucent control that samples it. Stopped, it holds its phase and asks
 * for no frame, so a still screen goes idle (DJ mode, a sheet in motion, [backdropDrifts]); started again, it goes on from
 * there. The phase is read in the draw phase only, so the drift never recomposes anything.
 *
 * The picture is laid out in the display's natural orientation and turned to stay put on the display ([fixedOnTranslucent]),
 * so a rotation leaves it exactly as it was: Now Playing's turn has nothing to cross-fade here, and no edge between an
 * old and a new picture can show.
 */
@Composable
internal fun ArtworkBackdrop(artworkUri: String?, modifier: Modifier = Modifier, drifting: Boolean = true, driftTicks: Flow<*> = emptyFlow<Unit>()) {
    val context = LocalContext.current
    val accent = SonoraTheme.accentOnDark
    val fallback = remember(accent) { fallbackColors(accent) }
    // The shell reads the playing track's colours ahead (WarmBackdropPalette), so an opening finds them in the cache.
    val c = rememberBackdropColors(artworkUri, fallback) { extractBackdropColors(context, it) }
    val drift = remember { BackdropDrift() }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(drifting) {
        drift.pause()
        if (!drifting) return@LaunchedEffect
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        // Animations removed: the drift holds still, as the infinite transition did.
        if (scale <= 0f) return@LaunchedEffect
        followBackdropDrift(drift, scale, driftTicks) { phase.floatValue = it }
    }

    val view = LocalView.current
    val divisor = backdropDivisor(LocalWindowClass.current)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().fixedOnTranslucent { view.displayRotation() }) {
            Backdrop(artworkUri, c, divisor) { phase.floatValue }
        }
    }
}

/**
 * The backdrop colours last extracted from each artwork, most recently used first: an artwork shown before opens on its
 * own colours. Process-wide ([BackdropPalettes]), a few dozen entries of four colours each.
 */
internal class BackdropPaletteCache(private val capacity: Int) {
    private val palettes = object : LinkedHashMap<String, List<Color>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>) = size > capacity
    }

    @Synchronized
    operator fun get(artworkUri: String): List<Color>? = palettes[artworkUri]

    @Synchronized
    operator fun set(artworkUri: String, colors: List<Color>) {
        palettes[artworkUri] = colors
    }
}

internal val BackdropPalettes = BackdropPaletteCache(capacity = 64)

/** What the blobs hold while a new artwork's colours are extracted: the sheet's dark grey, no hue of their own. */
private val PendingColors = List(4) { Color(0xFF1C1C1E) }

/**
 * The backdrop's four colours, read in draw. The first frame shows the artwork's cached colours ([cache]), [fallback]
 * without artwork, or a hueless dark grey while a new artwork is [extract]ed; its colours then show at once, so an
 * opening never fades from the fallback's tints. Once the backdrop has shown colours of its own, a new artwork (a
 * track change while it is open, or a cached artwork whose picture changed) cross-fades over 900 ms. The artwork is
 * always extracted again, and the cache updated, since its picture can change under the same URI (tag editor).
 */
@Composable
internal fun rememberBackdropColors(
    artworkUri: String?,
    fallback: List<Color>,
    cache: BackdropPaletteCache = BackdropPalettes,
    extract: suspend (String) -> List<Color>?,
): List<State<Color>> {
    val shown = remember { BackdropShown(artworkUri == null || cache[artworkUri] != null) }
    val slots = remember {
        val first = if (artworkUri == null) fallback else cache[artworkUri] ?: PendingColors
        first.map { Animatable(it) }
    }
    LaunchedEffect(artworkUri, fallback) {
        val target = artworkUri?.let { uri -> extract(uri)?.also { cache[uri] = it } } ?: fallback
        if (shown.value) {
            slots.forEachIndexed { i, slot -> launch { slot.animateTo(target[i], tween(BACKDROP_FADE_MS)) } }
        } else {
            slots.forEachIndexed { i, slot -> slot.snapTo(target[i]) }
            shown.value = true
        }
    }
    return remember(slots) { slots.map { it.asState() } }
}

/** Whether the backdrop has shown colours of its own yet; read only by the effect, so it is not state. */
private class BackdropShown(var value: Boolean)

private const val BACKDROP_FADE_MS = 900

@Composable
private fun Backdrop(artworkUri: String?, c: List<State<Color>>, divisor: Int, phase: () -> Float) {
    Box(Modifier.fillMaxSize().drawnSmall(divisor)) {
        Artwork(
            artworkUri,
            Modifier.fillMaxSize().blur(reducedBackdropDp(80.dp, divisor)).alpha(0.55f),
            shape = RoundedCornerShape(reducedBackdropDp(6.dp, divisor)),
        )
        Canvas(Modifier.fillMaxSize()) {
            val t = phase()
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.75f
            fun blob(color: Color, cx: Float, cy: Float) = drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.85f), Color.Transparent), Offset(cx, cy), r),
                radius = r,
                center = Offset(cx, cy),
            )
            blob(c[0].value, w * (0.25f + 0.15f * cos(t)), h * (0.2f + 0.1f * sin(t)))
            blob(c[1].value, w * (0.8f + 0.12f * sin(t * 2)), h * (0.35f + 0.12f * cos(t)))
            blob(c[2].value, w * (0.3f + 0.15f * sin(t)), h * (0.8f + 0.08f * cos(t * 2)))
            blob(c[3].value, w * (0.75f + 0.1f * cos(t)), h * (0.9f + 0.1f * sin(t)))
            drawRect(Color.Black.copy(alpha = 0.28f))
        }
    }
}

/**
 * How many times smaller, per side, the backdrop is drawn than it is shown. It is a heavily blurred picture under soft
 * gradients, so nothing is lost drawing it small and scaling it up; on a tablet (1800 x 2880 px on the Pad 6) that is a
 * 16th of the pixels to blur and to fill with the four gradients and the scrim, on every frame the drift redraws it and
 * on every frame of Now Playing's opening, where the full-size blur was the heaviest layer (T10). Phones keep their size.
 */
internal fun backdropDivisor(windowClass: WindowClass): Int = if (windowClass.isLarge) LARGE_BACKDROP_DIVISOR else 1

private const val LARGE_BACKDROP_DIVISOR = 4

/** The size the backdrop is drawn at when shown in [shown] px: [divisor] times smaller per side, rounded up, at least 1 px. */
internal fun reducedBackdropSize(shown: IntSize, divisor: Int): IntSize =
    IntSize(ceilDiv(shown.width, divisor), ceilDiv(shown.height, divisor))

private fun ceilDiv(value: Int, divisor: Int): Int = ((value + divisor - 1) / divisor).coerceAtLeast(1)

/** A length of the full-size backdrop (the blur radius, a corner) as it is in the reduced one, so it looks the same scaled up. */
internal fun reducedBackdropDp(full: Dp, divisor: Int): Dp = full / divisor

/**
 * Draws this content [divisor] times smaller per side and scales it up to fill the box (no change for 1). The reduced
 * size is a hardware layer: the content is rasterised at the small size, kept as a texture while it does not change
 * (the blurred artwork is not blurred again on every frame of an opening), and only that texture is scaled.
 */
private fun Modifier.drawnSmall(divisor: Int): Modifier = if (divisor <= 1) this else layout { measurable, constraints ->
    val shown = IntSize(constraints.maxWidth, constraints.maxHeight)
    val small = reducedBackdropSize(shown, divisor)
    val placeable = measurable.measure(Constraints.fixed(small.width, small.height))
    layout(shown.width, shown.height) {
        placeable.placeWithLayer(0, 0) {
            compositingStrategy = CompositingStrategy.Offscreen
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = shown.width.toFloat() / small.width
            scaleY = shown.height.toFloat() / small.height
        }
    }
}

/**
 * Lays the content out in the display's natural orientation (as at [rotation] 0), centred, and turns it to cancel the
 * display's [rotation] (Surface.ROTATION_0..ROTATION_270 as 0..3, read when measuring): it covers the same box, and
 * shows the same picture in the same place on the display, whichever way the display is turned.
 */
private fun Modifier.fixedOnTranslucent(rotation: () -> Int): Modifier = layout { measurable, constraints ->
    val quarters = rotation()
    val box = IntSize(constraints.maxWidth, constraints.maxHeight)
    val natural = naturalSize(box, quarters)
    val placeable = measurable.measure(Constraints.fixed(natural.width, natural.height))
    layout(box.width, box.height) {
        placeable.placeWithLayer((box.width - natural.width) / 2, (box.height - natural.height) / 2) {
            rotationZ = counterRotation(quarters)
        }
    }
}

/** A [box] on a display turned [quarterTurns] quarter turns, measured in the display's natural orientation. */
internal fun naturalSize(box: IntSize, quarterTurns: Int): IntSize =
    if (Math.floorMod(quarterTurns, 2) == 1) IntSize(box.height, box.width) else box

/** Reads [uri]'s four backdrop colours from a 160 px decode of the picture (null when it cannot be read). */
internal suspend fun extractBackdropColors(context: android.content.Context, uri: String): List<Color>? = runCatching {
    val request = ImageRequest.Builder(context).data(uri).allowHardware(false).size(160).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    withContext(Dispatchers.Default) {
        val p = Palette.from(bitmap).generate()
        val dominant = p.getDominantColor(0xFF1C1C1E.toInt())
        listOf(
            p.getVibrantColor(dominant),
            p.getDarkVibrantColor(dominant),
            p.getMutedColor(dominant),
            p.getDarkMutedColor(dominant),
        ).map { Color(it).darken(0.15f) }
    }
}.getOrNull()
