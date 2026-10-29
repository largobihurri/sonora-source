package io.sonora.feature.dj.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.sonora.core.djmodel.BandWaveform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign

/**
 * The scrolling coloured 3-band waveform of the DJ screen: the track flows right to left under a fixed playhead at the
 * centre, with beat ticks along the top and bottom edges, a full-height red line with heavier red ticks on every
 * downbeat, and a wider green one on the first downbeat of every phrase of four bars.
 *
 * Inputs are plain values: [waveform] (the analysis planes), [beats] (null hides the grid), [durationMs] (the
 * track's, for seeking), [position] (ms, read in the draw phase only: see [rememberDjPosition]) and [zoom], hoisted
 * with [onZoomChange]. Gestures: one finger drags the waveform (the drawn position follows the finger, previewed
 * without seeking) and [onSeek] runs once on release; two fingers pinch between the [WaveformZoom] levels
 * ([ZoomSnap]); a pinch cancels a drag in progress without seeking.
 *
 * Performance (60 Hz smooth with no finger down on the Realme, 120 Hz with one; see [WaveformTileCache] for memory):
 * - Nothing recomposes per frame: [position] is read inside the draw lambda, so a new position only redraws this
 *   canvas, which sits in its own graphics layer.
 * - No allocation per frame in steady state: the plan ([TilePlan]), the tile lookup ([TileLru]), the beat window
 *   ([BeatIndex.visible] into preallocated arrays) and the drawing (value-class offsets and colours) allocate nothing;
 *   objects are made only when the size, zoom or track changes, or when a tile has to be rendered.
 * - Tiles are prebuilt bitmaps drawn at a fractional offset with bilinear filtering, so the scroll moves by sub-pixel
 *   steps instead of jumping whole pixels; two tiles are rendered ahead in the playing direction.
 *
 * The loop region ([loop]) is washed in the loop colour under the beat lines and bracketed over them; the playhead stays fixed
 * at the centre, and the position the screen feeds in already wraps without a jump (the smoother is loop aware), so the view
 * runs back by the loop's length at each wrap, as a CDJ's does.
 *
 * Over a translucent panel this is a sibling drawn on top, in its own layer: never the panel's content, never inside
 * `translucentBackdropSource` (it would be sampled into the surface and re-record the backdrop every frame).
 */
@Composable
fun ScrollingWaveform(
    waveform: BandWaveform,
    beats: BeatIndex?,
    durationMs: Long,
    position: () -> Double,
    zoom: WaveformZoom,
    onZoomChange: (WaveformZoom) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The cue markers drawn over the beats (hot cue flags, memory cue triangles); `null` or empty draws none. */
    cues: CueMarkerIndex? = null,
    /** The loop to draw over the waveform (a wash with a bracket round it), `null` for none. */
    loop: LoopRegion? = null,
    /** Whether the fixed playhead line is drawn; off while a state card covers the waveform (a flag read in the draw phase). */
    showPlayhead: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val tilesReady = remember { mutableIntStateOf(0) }
    val cache = remember(waveform) { WaveformTileCache(waveform, scope, onTileReady = { tilesReady.intValue++ }) }
    val frame = remember(waveform) { WaveformFrame(waveform.durationMs) }
    val scrubMs = remember { mutableDoubleStateOf(Double.NaN) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentPosition by rememberUpdatedState(position)
    val markers = remember { CueMarkerPainter() }
    val loopPainter = remember { LoopRegionPainter() }
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    val currentOnSeek by rememberUpdatedState(onSeek)

    Canvas(
        modifier
            .graphicsLayer { clip = true }
            .pointerInput(frame) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    frame.seekHold.cancel()
                    val startZoom = currentZoom
                    val startMs = if (frame.lastPositionMs.isNaN()) currentPosition() else frame.lastPositionMs
                    val pxPerMs = frame.pxPerMs
                    val slop = viewConfiguration.touchSlop
                    var pinchScale = 1f
                    var pinching = false
                    var dragging = false
                    var dragPx = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        var pressed = 0
                        for (i in changes.indices) if (changes[i].pressed) pressed++
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            if (dragging) {
                                dragging = false
                                scrubMs.doubleValue = Double.NaN
                            }
                            pinching = true
                            pinchScale *= event.calculateZoom()
                            val next = ZoomSnap.snapped(startZoom, pinchScale)
                            if (next != currentZoom) currentOnZoomChange(next)
                            for (i in changes.indices) changes[i].consume()
                        } else if (!pinching) {
                            dragPx += event.calculatePan().x
                            if (!dragging && abs(dragPx) > slop) dragging = true
                            if (dragging) {
                                // The drag counts from where it left the touch slop, so the waveform does not jump.
                                val travelled = dragPx - sign(dragPx) * slop
                                scrubMs.doubleValue = DragSeek.positionMs(
                                    startMs,
                                    travelled.toDouble(),
                                    pxPerMs,
                                    currentDuration.toDouble(),
                                )
                                for (i in changes.indices) changes[i].consume()
                            }
                        }
                    }
                    if (dragging) {
                        val target = scrubMs.doubleValue
                        frame.seekHold.hold(target, System.nanoTime())
                        scrubMs.doubleValue = Double.NaN
                        currentOnSeek(DragSeek.seekMs(target))
                    }
                }
            },
    ) {
        val widthPx = size.width.toInt()
        val heightPx = size.height.toInt()
        if (widthPx <= 0 || heightPx <= 0) return@Canvas
        // Read so a tile landing redraws the canvas while it stands still.
        tilesReady.intValue
        cache.configure(widthPx, heightPx)

        val scale = frame.scaleFor(widthPx, zoom)
        val pxPerMs = scale.pxPerMs
        val scrub = scrubMs.doubleValue
        val positionMs = if (scrub.isNaN()) frame.seekHold.resolve(position(), System.nanoTime()) else scrub
        val direction = frame.directionTo(positionMs)
        frame.pxPerMs = pxPerMs

        val plan = frame.planFor(pxPerMs)
        plan.wanted(scale.absolutePx(positionMs), widthPx, direction)
        for (index in plan.firstVisible..plan.lastVisible) cache.request(zoom, index, plan)
        for (index in plan.firstWanted..plan.lastWanted) {
            if (index < plan.firstVisible || index > plan.lastVisible) cache.request(zoom, index, plan)
        }

        val leftPx = scale.leftPxFor(positionMs)
        if (drawTiles(cache, plan, zoom, leftPx, widthPx)) DjClockLog.stats.onTileMiss()
        if (loop != null) with(loopPainter) { drawWash(loop, scale, leftPx, positionMs) }
        if (beats != null) drawBeats(beats, frame, scale, leftPx)
        if (loop != null) with(loopPainter) { drawBracket(loop, scale, leftPx, positionMs) }
        if (cues != null) with(markers) { drawOnWaveform(cues, scale, leftPx) }
        if (showPlayhead) {
            val centre = widthPx / 2f
            drawLine(PlayheadColor, Offset(centre, 0f), Offset(centre, size.height), PLAYHEAD_WIDTH.toPx())
        }
    }
}

/**
 * The tiles under the view; the previous zoom's tiles, stretched, fill in while the new zoom's are rendered. Returns
 * whether a visible tile was missing (counted for the `SonoraDjClock` log).
 */
private fun DrawScope.drawTiles(
    cache: WaveformTileCache,
    plan: TilePlan,
    zoom: WaveformZoom,
    leftPx: Double,
    widthPx: Int,
): Boolean {
    var missing = false
    for (index in plan.firstVisible..plan.lastVisible) {
        if (cache.tile(zoom, index) == null) {
            missing = true
            break
        }
    }
    val fallbackZoom = cache.fallbackZoom
    val fallbackTiles = cache.fallbackTiles
    if (missing && fallbackZoom != null && fallbackTiles != null && fallbackZoom != zoom) {
        // Pixels of the old scale times this ratio are pixels of the new one.
        val ratio = fallbackZoom.visibleMs / zoom.visibleMs
        fallbackTiles.forEachReady { index, image ->
            val start = WaveformTiles.startPx(index) * ratio - leftPx
            val end = start + WaveformTiles.TILE_PX * ratio
            if (end > 0 && start < widthPx) {
                val x0 = floor(start).toInt()
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(x0, 0),
                    dstSize = IntSize(ceil(end).toInt() - x0, image.height),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
    // Whole pixels go into the tile offsets; the fraction is one translation, so every tile moves by the same sub-pixel.
    val baseLeft = floor(leftPx)
    val baseLeftPx = baseLeft.toLong()
    translate(left = -(leftPx - baseLeft).toFloat()) {
        for (index in plan.firstVisible..plan.lastVisible) {
            val image = cache.tile(zoom, index) ?: continue
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset((WaveformTiles.startPx(index) - baseLeftPx).toInt(), 0),
                dstSize = IntSize(image.width, image.height),
                filterQuality = FilterQuality.Low,
            )
        }
    }
    return missing
}

/**
 * Beat ticks along the top and bottom edges; on each downbeat (beat 1 of a bar) a full-height red line with longer,
 * heavier red ticks, so the bars stand out from the beats; and on the first downbeat of each phrase of four bars the
 * same marks, wider, in green.
 */
private fun DrawScope.drawBeats(beats: BeatIndex, frame: WaveformFrame, scale: WaveformScale, leftPx: Double) {
    val height = size.height
    val startMs = scale.msAt(leftPx) - BEAT_PAD_MS
    val endMs = scale.msAt(leftPx + scale.widthPx) + BEAT_PAD_MS
    val count = beats.visible(startMs, endMs, frame.beatTimes, frame.beatBars, frame.beatPhrases)
    val tick = TICK_LENGTH.toPx()
    val downbeatTick = DOWNBEAT_TICK_LENGTH.toPx()
    val tickWidth = TICK_WIDTH.toPx()
    val downbeatWidth = DOWNBEAT_WIDTH.toPx()
    val downbeatTickWidth = DOWNBEAT_TICK_WIDTH.toPx()
    val phraseWidth = PHRASE_WIDTH.toPx()
    val phraseTickWidth = PHRASE_TICK_WIDTH.toPx()
    for (k in 0 until count) {
        val x = (scale.absolutePx(frame.beatTimes[k]) - leftPx).toFloat()
        val downbeat = frame.beatBars[k].toInt() == 1
        if (frame.beatPhrases[k]) {
            drawLine(PhraseColor, Offset(x, 0f), Offset(x, height), phraseWidth)
            drawLine(PhraseColor, Offset(x, 0f), Offset(x, downbeatTick), phraseTickWidth)
            drawLine(PhraseColor, Offset(x, height - downbeatTick), Offset(x, height), phraseTickWidth)
        } else if (downbeat) {
            drawLine(DownbeatColor, Offset(x, 0f), Offset(x, height), downbeatWidth)
            drawLine(DownbeatTickColor, Offset(x, 0f), Offset(x, downbeatTick), downbeatTickWidth)
            drawLine(DownbeatTickColor, Offset(x, height - downbeatTick), Offset(x, height), downbeatTickWidth)
        } else {
            drawLine(TickColor, Offset(x, 0f), Offset(x, tick), tickWidth)
            drawLine(TickColor, Offset(x, height - tick), Offset(x, height), tickWidth)
        }
    }
}

/**
 * The per-frame scratch of one [ScrollingWaveform]: plain fields, not snapshot state, so updating them in the draw
 * phase invalidates nothing. Reallocates only when the width, zoom or scale changes.
 */
private class WaveformFrame(private val waveformDurationMs: Double) {
    private var scale: WaveformScale? = null
    private var plan: TilePlan? = null
    private var planPxPerMs = Double.NaN

    /** The position drawn by the last frame (NaN before the first), read by the gestures. */
    var lastPositionMs: Double = Double.NaN
        private set

    /** The scale of the last frame, read by the gestures. */
    var pxPerMs: Double = 0.0

    val beatTimes = DoubleArray(BEAT_CAPACITY)
    val beatBars = ByteArray(BEAT_CAPACITY)
    val beatPhrases = BooleanArray(BEAT_CAPACITY)
    val seekHold = SeekHold()

    fun scaleFor(widthPx: Int, zoom: WaveformZoom): WaveformScale {
        val cached = scale
        if (cached != null && cached.widthPx == widthPx && cached.zoom == zoom) return cached
        return WaveformScale(widthPx, zoom).also { scale = it }
    }

    fun planFor(pxPerMs: Double): TilePlan {
        val cached = plan
        if (cached != null && pxPerMs == planPxPerMs) return cached
        planPxPerMs = pxPerMs
        return TilePlan(TilePlan.tileCountFor(waveformDurationMs, pxPerMs)).also { plan = it }
    }

    /** The sign of the motion since the last frame (0 when still), and records [positionMs] as the last one. */
    fun directionTo(positionMs: Double): Int {
        val delta = positionMs - lastPositionMs
        lastPositionMs = positionMs
        return if (delta.isNaN() || abs(delta) < STILL_MS) 0 else delta.sign.toInt()
    }
}

/** Beats looked up beyond each edge: one beat at 30 BPM, so a tick just outside the view is never missed. */
private const val BEAT_PAD_MS = 2_000.0

/** Beat slots per frame: 32 s at 200 BPM plus the padding is about 120 beats. */
private const val BEAT_CAPACITY = 256

/** A move smaller than this (ms) between frames is standing still. */
private const val STILL_MS = 0.5

private val PLAYHEAD_WIDTH = 2.dp
private val TICK_WIDTH = 1.dp
private val DOWNBEAT_WIDTH = 2.dp
private val DOWNBEAT_TICK_WIDTH = 3.5.dp
private val TICK_LENGTH = 5.dp
private val DOWNBEAT_TICK_LENGTH = 14.dp
private val PHRASE_WIDTH = 3.dp
private val PHRASE_TICK_WIDTH = 5.dp

private val TickColor = Color(DjWaveformPalette.BEAT_TICK_COLOR)
private val DownbeatColor = Color(DjWaveformPalette.DOWNBEAT_COLOR)
private val DownbeatTickColor = Color(DjWaveformPalette.DOWNBEAT_TICK_COLOR)
private val PhraseColor = Color(DjWaveformPalette.PHRASE_COLOR)
private val PlayheadColor = Color(DjWaveformPalette.PLAYHEAD_COLOR)
