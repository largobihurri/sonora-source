package io.sonora.feature.dj.waveform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.sonora.core.djmodel.BandWaveform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws the three bands of a waveform into bitmaps with `android.graphics` (software canvas, any thread): per band one
 * filled path, mirrored around the centre line, layered in [DjWaveformPalette.DRAW_ORDER]. A column is one pixel wide
 * and drawn as a step, so tiles meet exactly at their seams.
 *
 * Not thread-safe: it reuses one [Path] and one [Paint]; [WaveformTileCache] calls it from a single worker.
 */
internal class WaveformBandPainter {

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Paints the absolute pixels `[startPx, startPx + widthPx)` of [envelopes] (in draw order) onto [canvas], whose
     * height is [heightPx]; the bars fill [HEIGHT_SHARE] of the half height at full level.
     */
    fun paint(canvas: Canvas, envelopes: Array<WaveformEnvelope>, startPx: Long, widthPx: Int, heightPx: Int) {
        val centre = heightPx / 2f
        val maxHalf = centre * HEIGHT_SHARE
        for (band in DjWaveformPalette.DRAW_ORDER.indices) {
            val envelope = envelopes[band]
            path.rewind()
            path.moveTo(0f, centre)
            for (x in 0 until widthPx) {
                val top = centre - DjWaveformPalette.halfHeightPx(envelope.levelAt(px(startPx, x)), maxHalf)
                path.lineTo(x.toFloat(), top)
                path.lineTo(x + 1f, top)
            }
            for (x in widthPx - 1 downTo 0) {
                val bottom = centre + DjWaveformPalette.halfHeightPx(envelope.levelAt(px(startPx, x)), maxHalf)
                path.lineTo(x + 1f, bottom)
                path.lineTo(x.toFloat(), bottom)
            }
            path.close()
            paint.color = DjWaveformPalette.colorOf(DjWaveformPalette.DRAW_ORDER[band])
            canvas.drawPath(path, paint)
        }
    }

    private fun px(startPx: Long, x: Int): Int = (startPx + x).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** The share of the half height a full-level bar reaches, leaving room for the beat ticks. */
        const val HEIGHT_SHARE: Float = 0.86f

        /** The band planes of [waveform] in draw order (copied once: [BandWaveform] copies on every read). */
        fun planesOf(waveform: BandWaveform): Array<ByteArray> = Array(DjWaveformPalette.DRAW_ORDER.size) {
            when (DjWaveformPalette.DRAW_ORDER[it]) {
                WaveformBand.LOW -> waveform.low()
                WaveformBand.MID -> waveform.mid()
                WaveformBand.HIGH -> waveform.high()
            }
        }

        /** The envelopes of [planes] at [pxPerMs], in the same order. */
        fun envelopesOf(planes: Array<ByteArray>, pxPerMs: Double): Array<WaveformEnvelope> =
            Array(planes.size) { WaveformEnvelope.build(planes[it], BandWaveform.COLUMNS_PER_SECOND, pxPerMs) }
    }
}

/**
 * The prebuilt tiles of the scrolling waveform: [WaveformTiles.TILE_PX]-wide `ImageBitmap`s of the three bands,
 * rendered off the main thread and drawn by the canvas at a fractional offset.
 *
 * Performance design (the waveform scrolls every frame, 60 Hz on the Realme with no finger down, 120 Hz with one):
 * - The draw phase only looks up tiles ([tile], an allocation-free scan of a [TileLru]) and blits them; a missing
 *   wanted tile is requested once ([request]) and rendered on one background worker (`Dispatchers.Default` limited to
 *   one thread), then `prepareToDraw()` uploads it before the frame that first shows it.
 * - Memory stays bounded for a track of any length: at most [TilePlan.capacityFor] tiles (12 or more, enough for the
 *   visible ones and the prefetch) for the current zoom, plus those of the previous zoom, drawn stretched for the frame
 *   or two the new zoom's tiles take after a pinch; other zooms are dropped. One 256 x 360 px tile is 368 KB, so a
 *   phone holds about 4-9 MB of tiles. The envelopes (one byte per pixel per band) exist for one zoom at a time on the
 *   worker: a 1 h track at 4 s per 1080 px is about 1 M px, about 3 MB for the three bands; the band planes, copied
 *   once, are 3 x 540 KB for that hour.
 * - Evicted bitmaps are left to the garbage collector, never recycled or reused: the render thread may still draw one
 *   in a recorded frame.
 *
 * Main thread only, except the rendering inside the worker. [onTileReady] runs on the main thread after a tile lands.
 */
internal class WaveformTileCache(
    waveform: BandWaveform,
    private val scope: CoroutineScope,
    private val onTileReady: () -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private class ZoomTiles(val zoom: WaveformZoom, capacity: Int) {
        val lru = TileLru<ImageBitmap>(capacity)
    }

    /** Owned by the worker: the planes, copied lazily, and the envelopes of one (zoom, width). */
    private class Renderer(private val waveform: BandWaveform) {
        private val painter = WaveformBandPainter()
        private var planes: Array<ByteArray>? = null
        private var envelopes: Array<WaveformEnvelope>? = null
        private var envelopeZoom: WaveformZoom? = null
        private var envelopeWidth = 0

        fun render(zoom: WaveformZoom, widthPx: Int, heightPx: Int, index: Int): Bitmap {
            val planes = planes ?: WaveformBandPainter.planesOf(waveform).also { planes = it }
            val envelopes = envelopes.takeIf { envelopeZoom == zoom && envelopeWidth == widthPx }
                ?: WaveformBandPainter.envelopesOf(planes, WaveformScale(widthPx, zoom).pxPerMs).also {
                    envelopes = it
                    envelopeZoom = zoom
                    envelopeWidth = widthPx
                }
            val bitmap = Bitmap.createBitmap(WaveformTiles.TILE_PX, heightPx, Bitmap.Config.ARGB_8888)
            painter.paint(Canvas(bitmap), envelopes, WaveformTiles.startPx(index), WaveformTiles.TILE_PX, heightPx)
            bitmap.prepareToDraw()
            return bitmap
        }
    }

    private val renderer = Renderer(waveform)
    private var widthPx = 0
    private var heightPx = 0
    private var generation = 0
    private var current: ZoomTiles? = null
    private var previous: ZoomTiles? = null

    /** Sets the view size; a new size drops every tile. */
    fun configure(widthPx: Int, heightPx: Int) {
        if (widthPx == this.widthPx && heightPx == this.heightPx) return
        this.widthPx = widthPx
        this.heightPx = heightPx
        generation++
        current = null
        previous = null
    }

    /** The ready tile [index] at [zoom] (marking it used), or null. */
    fun tile(zoom: WaveformZoom, index: Int): ImageBitmap? = tilesFor(zoom).lru.get(index)

    /** The zoom of [fallbackTiles] (the zoom before the last change, kept to cover the switch), or null. */
    val fallbackZoom: WaveformZoom? get() = previous?.zoom

    /** The tiles of the previous zoom, or null. */
    val fallbackTiles: TileLru<ImageBitmap>? get() = previous?.lru

    /**
     * Asks for tile [index] at [zoom] when it is neither ready nor pending; the plan's wanted tiles are protected from
     * eviction. Allocates only when it starts a render.
     */
    fun request(zoom: WaveformZoom, index: Int, plan: TilePlan) {
        val tiles = tilesFor(zoom)
        if (tiles.lru.contains(index)) return
        tiles.lru.put(index, null, plan.firstWanted, plan.lastWanted)
        val generation = generation
        val width = widthPx
        val height = heightPx
        scope.launch {
            val bitmap = withContext(worker) { renderer.render(zoom, width, height, index) }
            if (generation != this@WaveformTileCache.generation) return@launch
            val owner = current?.takeIf { it.zoom == zoom } ?: previous?.takeIf { it.zoom == zoom } ?: return@launch
            // Evicted while rendering: no longer wanted.
            if (!owner.lru.contains(index)) return@launch
            owner.lru.put(index, bitmap.asImageBitmap(), protectFirst = 0, protectLast = -1)
            onTileReady()
        }
    }

    private fun tilesFor(zoom: WaveformZoom): ZoomTiles {
        current?.let { if (it.zoom == zoom) return it }
        val reused = previous?.takeIf { it.zoom == zoom }
        previous = current
        val tiles = reused ?: ZoomTiles(zoom, TilePlan.capacityFor(widthPx))
        current = tiles
        return tiles
    }
}
