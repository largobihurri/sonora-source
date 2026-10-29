package io.sonora.feature.dj.waveform

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.sonora.core.djmodel.BandWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The whole track in one strip (the overview the DJ layout shows above the time labels): one bitmap of the three
 * bands, rendered off the main thread once per size and track, with the played part under a dark veil and a thin
 * playhead line. A tap or a horizontal drag previews the position under the finger and [onSeek] runs on release.
 *
 * [position] (ms) is read in the draw phase only, so playback redraws the strip (a bitmap, a rectangle and a line)
 * without recomposing; nothing is allocated per frame. Like [ScrollingWaveform], it draws in its own layer and must
 * stay out of any `translucentBackdropSource`.
 */
@Composable
fun WaveformOverview(
    waveform: BandWaveform,
    durationMs: Long,
    position: () -> Double,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The cue markers drawn over the strip; `null` or empty draws none. */
    cues: CueMarkerIndex? = null,
    /** The loop drawn over the strip, `null` for none. */
    loop: LoopRegion? = null,
) {
    val markers = remember { CueMarkerPainter() }
    val loopPainter = remember { LoopRegionPainter() }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val image by produceState<ImageBitmap?>(null, waveform, durationMs, sizePx) {
        val size = sizePx
        if (size.width <= 0 || size.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) { renderOverview(waveform, durationMs, size) }
    }
    val scrubMs = remember { mutableDoubleStateOf(Double.NaN) }
    val hold = remember { SeekHold() }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Canvas(
        modifier
            .onSizeChanged { sizePx = it }
            .graphicsLayer { clip = true }
            .pointerInput(hold) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    hold.cancel()
                    val widthPx = size.width.toDouble()
                    scrubMs.doubleValue = OverviewSeek.msAt(down.position.x.toDouble(), widthPx, currentDuration).toDouble()
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        scrubMs.doubleValue = OverviewSeek.msAt(change.position.x.toDouble(), widthPx, currentDuration)
                            .toDouble()
                        change.consume()
                    }
                    val target = scrubMs.doubleValue
                    hold.hold(target, System.nanoTime())
                    scrubMs.doubleValue = Double.NaN
                    currentOnSeek(DragSeek.seekMs(target))
                }
            },
    ) {
        image?.let { drawImage(it) }
        val scrub = scrubMs.doubleValue
        val positionMs = if (scrub.isNaN()) hold.resolve(position(), System.nanoTime()) else scrub
        val playedPx = OverviewGeometry.playedFraction(positionMs, durationMs.toDouble()).toFloat() * size.width
        drawRect(PlayedVeilColor, size = Size(playedPx, size.height))
        if (loop != null) with(loopPainter) { drawOnOverview(loop, positionMs, durationMs.toDouble()) }
        if (cues != null) with(markers) { drawOnOverview(cues, durationMs.toDouble()) }
        drawLine(OverviewPlayheadColor, Offset(playedPx, 0f), Offset(playedPx, size.height), PLAYHEAD_WIDTH.toPx())
    }
}

/** One bitmap of the whole track at [size]; the time base is [durationMs] (the plane's own length when unknown). */
private fun renderOverview(waveform: BandWaveform, durationMs: Long, size: IntSize): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val spanMs = if (durationMs > 0) durationMs.toDouble() else waveform.durationMs
    if (waveform.columnCount > 0 && spanMs > 0) {
        val planes = WaveformBandPainter.planesOf(waveform)
        val envelopes = WaveformBandPainter.envelopesOf(planes, size.width / spanMs)
        WaveformBandPainter().paint(AndroidCanvas(bitmap), envelopes, startPx = 0L, widthPx = size.width, heightPx = size.height)
    }
    bitmap.prepareToDraw()
    return bitmap.asImageBitmap()
}

private val PLAYHEAD_WIDTH = 1.5.dp
private val PlayedVeilColor = Color(DjWaveformPalette.PLAYED_VEIL_COLOR)
private val OverviewPlayheadColor = Color(DjWaveformPalette.PLAYHEAD_COLOR)
