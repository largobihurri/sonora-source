package io.sonora.feature.dj.waveform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * Draws the loop region of the DJ waveforms in the draw phase (nothing is allocated per frame): the loop colour as a
 * translucent wash over the loop's span, a bracket round it (a solid edge at In and at Out with a square cap inward at the
 * top and the bottom, and a rail along the top and bottom edges joining them), brighter while the loop plays and fainter the
 * less active it is. A loop still being marked has only its In edge and runs to the playhead.
 *
 * The scrolling waveform paints the wash before the beat lines (so they stay on top of it) and the bracket after them; the
 * cue markers and the playhead are drawn over both. The pure placement is [LoopGeometry].
 */
internal class LoopRegionPainter {

    /** The wash of [region] on the scrolling waveform, under the beat lines. [leftPx] is the absolute pixel at the view's left edge. */
    fun DrawScope.drawWash(region: LoopRegion, scale: WaveformScale, leftPx: Double, positionMs: Double) {
        val x0 = LoopGeometry.x(region.inMs, leftPx, scale.pxPerMs)
        val x1 = LoopGeometry.x(region.endAt(positionMs), leftPx, scale.pxPerMs)
        if (!LoopGeometry.visible(x0, x1, size.width)) return
        val left = LoopGeometry.clamped(x0, size.width)
        val right = LoopGeometry.clamped(x1, size.width)
        drawRect(LoopColor.copy(alpha = LoopGeometry.fillAlpha(region.phase)), Offset(left, 0f), Size(right - left, size.height))
    }

    /** The bracket of [region] on the scrolling waveform, over the beat lines. */
    fun DrawScope.drawBracket(region: LoopRegion, scale: WaveformScale, leftPx: Double, positionMs: Double) {
        val x0 = LoopGeometry.x(region.inMs, leftPx, scale.pxPerMs)
        val x1 = LoopGeometry.x(region.endAt(positionMs), leftPx, scale.pxPerMs)
        if (!LoopGeometry.visible(x0, x1, size.width)) return
        val color = LoopColor.copy(alpha = edgeAlpha(region.phase))
        val edge = EDGE_WIDTH.toPx()
        val rail = RAIL_WIDTH.toPx()
        val cap = CAP_LENGTH.toPx()
        val height = size.height
        val left = LoopGeometry.clamped(x0, size.width)
        val right = LoopGeometry.clamped(x1, size.width)
        drawRect(color, Offset(left, 0f), Size(right - left, rail))
        drawRect(color, Offset(left, height - rail), Size(right - left, rail))
        if (LoopGeometry.edgeVisible(x0, size.width)) {
            drawRect(color, Offset(x0 - edge / 2f, 0f), Size(edge, height))
            drawRect(color, Offset(x0, 0f), Size(cap, edge))
            drawRect(color, Offset(x0, height - edge), Size(cap, edge))
        }
        if (region.outMs != null && LoopGeometry.edgeVisible(x1, size.width)) {
            drawRect(color, Offset(x1 - edge / 2f, 0f), Size(edge, height))
            drawRect(color, Offset(x1 - cap, 0f), Size(cap, edge))
            drawRect(color, Offset(x1 - cap, height - edge), Size(cap, edge))
        }
    }

    /** [region] on the overview strip (the whole track): the wash and a thin edge at each end. */
    fun DrawScope.drawOnOverview(region: LoopRegion, positionMs: Double, durationMs: Double) {
        if (!(durationMs > 0.0)) return
        val left = LoopGeometry.overviewLeft(region.inMs, durationMs, size.width)
        val right = LoopGeometry.overviewRight(region.inMs, region.endAt(positionMs), durationMs, size.width, OVERVIEW_MIN.toPx())
        drawRect(LoopColor.copy(alpha = LoopGeometry.fillAlpha(region.phase) + OVERVIEW_BOOST), Offset(left, 0f), Size(right - left, size.height))
        val color = LoopColor.copy(alpha = edgeAlpha(region.phase))
        val edge = OVERVIEW_EDGE.toPx()
        drawRect(color, Offset(left, 0f), Size(edge, size.height))
        if (region.outMs != null) drawRect(color, Offset(right - edge, 0f), Size(edge, size.height))
    }

    private fun edgeAlpha(phase: LoopPhase): Float = when (phase) {
        LoopPhase.LOOPING, LoopPhase.MARKING -> 1f
        LoopPhase.ARMED -> 0.8f
        LoopPhase.EXITED -> 0.5f
    }

    private companion object {
        val EDGE_WIDTH = 3.dp
        val RAIL_WIDTH = 2.dp
        val CAP_LENGTH = 10.dp
        val OVERVIEW_EDGE = 1.5.dp
        val OVERVIEW_MIN = 2.dp
        const val OVERVIEW_BOOST = 0.1f
        val LoopColor = Color(DjWaveformPalette.LOOP_COLOR)
    }
}
