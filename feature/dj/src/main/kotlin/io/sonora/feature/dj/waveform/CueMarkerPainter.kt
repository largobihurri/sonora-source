package io.sonora.feature.dj.waveform

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Draws the cue markers of the DJ waveforms in the draw phase: coloured flags lettered A to H for hot cues, triangles
 * for memory cues. One painter per canvas; it owns the reusable [Path], paint and letter buffer, so a frame allocates
 * nothing (the colours, offsets and sizes are value classes, the letters are drawn from a char array).
 *
 * Kept apart from the beat drawing of [ScrollingWaveform]: the waveform only calls [drawOnWaveform] after its beats.
 */
internal class CueMarkerPainter {

    private val path = Path()
    private val letter = CharArray(1)
    private val text = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = AndroidPaint.Align.CENTER
    }

    /**
     * The markers of [cues] in the view of the scrolling waveform: a coloured line over the full height at each cue
     * (fainter for a memory cue), a lettered flag at the top for a hot cue and a triangle at the top for a memory cue.
     * [leftPx] is the absolute pixel at the left edge of the view (see [WaveformScale]).
     */
    fun DrawScope.drawOnWaveform(cues: CueMarkerIndex, scale: WaveformScale, leftPx: Double) {
        if (cues.count == 0) return
        val flagWidth = FLAG_WIDTH.toPx()
        val flagHeight = FLAG_HEIGHT.toPx()
        val lineWidth = LINE_WIDTH.toPx()
        val startMs = scale.msAt(leftPx) - flagWidth / scale.pxPerMs
        val endMs = scale.msAt(leftPx + scale.widthPx)
        // A saved loop that starts before the view still shows where it runs in: look back as far as the longest one.
        var i = cues.firstAtOrAfter(startMs - cues.longestLoopMs)
        while (i < cues.count && cues.timeAt(i) < endMs) {
            val x = (scale.absolutePx(cues.timeAt(i)) - leftPx).toFloat()
            if (cues.hasLoop(i)) drawLoopSpanOnWaveform(cues, i, x, scale, leftPx)
            if (CueMarkerGeometry.flagVisible(x, flagWidth, scale.widthPx.toFloat())) {
                val color = Color(cues.colorAt(i))
                if (cues.isHot(i)) {
                    drawLine(color.copy(alpha = HOT_LINE_ALPHA), Offset(x, 0f), Offset(x, size.height), lineWidth)
                    drawFlag(x, 0f, flagWidth, flagHeight, color, cues.labelAt(i), LETTER_SIZE.toPx())
                } else {
                    drawLine(color.copy(alpha = MEMORY_LINE_ALPHA), Offset(x, 0f), Offset(x, size.height), lineWidth)
                    drawTriangle(x, 0f, MEMORY_TRIANGLE.toPx(), pointingDown = true, color)
                }
            }
            i++
        }
    }

    /**
     * The markers of [cues] on the overview: a thin coloured line at each cue, a small lettered tab at the top for a hot
     * cue (skipped when it would overlap the one before) and a triangle at the bottom for a memory cue.
     */
    fun DrawScope.drawOnOverview(cues: CueMarkerIndex, durationMs: Double) {
        if (cues.count == 0 || !(durationMs > 0.0)) return
        val tab = TAB_SIZE.toPx()
        val gap = TAB_GAP.toPx()
        val lineWidth = OVERVIEW_LINE_WIDTH.toPx()
        var previousRight = Float.NEGATIVE_INFINITY
        for (i in 0 until cues.count) {
            val x = CueMarkerGeometry.overviewX(cues.timeAt(i), durationMs, size.width)
            val color = Color(cues.colorAt(i))
            if (cues.hasLoop(i)) {
                val endX = CueMarkerGeometry.overviewX(cues.loopEndAt(i), durationMs, size.width)
                val rail = LOOP_RAIL.toPx()
                drawRect(color.copy(alpha = LOOP_RAIL_ALPHA), Offset(x, size.height - rail), Size(maxOf(endX - x, rail), rail))
            }
            if (cues.isHot(i)) {
                drawLine(color.copy(alpha = HOT_LINE_ALPHA), Offset(x, 0f), Offset(x, size.height), lineWidth)
                if (CueMarkerGeometry.labelFits(previousRight, x, gap)) {
                    drawFlag(x, 0f, tab, tab, color, cues.labelAt(i), OVERVIEW_LETTER_SIZE.toPx())
                    previousRight = x + tab
                }
            } else {
                drawLine(color.copy(alpha = MEMORY_LINE_ALPHA), Offset(x, 0f), Offset(x, size.height), lineWidth)
                val side = MEMORY_TRIANGLE.toPx() * OVERVIEW_TRIANGLE_SHARE
                drawTriangle(x, size.height, side, pointingDown = false, color)
            }
        }
    }

    /** The span of the saved loop of cue [index] along the bottom of the waveform: a rail from In to Out with a tick at Out. */
    private fun DrawScope.drawLoopSpanOnWaveform(cues: CueMarkerIndex, index: Int, x: Float, scale: WaveformScale, leftPx: Double) {
        val endX = (scale.absolutePx(cues.loopEndAt(index)) - leftPx).toFloat()
        if (!LoopGeometry.visible(x, endX, size.width)) return
        val color = Color(cues.colorAt(index))
        val rail = LOOP_RAIL.toPx()
        val left = LoopGeometry.clamped(x, size.width)
        val right = LoopGeometry.clamped(endX, size.width)
        drawRect(color.copy(alpha = LOOP_RAIL_ALPHA), Offset(left, size.height - rail), Size(right - left, rail))
        if (LoopGeometry.edgeVisible(endX, size.width)) {
            drawLine(color.copy(alpha = HOT_LINE_ALPHA), Offset(endX, size.height - LOOP_END_TICK.toPx()), Offset(endX, size.height), LINE_WIDTH.toPx())
        }
    }

    /** A rounded flag whose pole is at [x], [width] by [height], lettered [label] in the ink that reads on [color]. */
    private fun DrawScope.drawFlag(x: Float, y: Float, width: Float, height: Float, color: Color, label: Char, letterSize: Float) {
        drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(FLAG_CORNER.toPx()))
        letter[0] = label
        text.textSize = letterSize
        text.color = CueMarkerGeometry.inkFor(color.toArgb())
        // Centre the glyph vertically: the baseline sits a third of the letter's size below the middle.
        val baseline = y + height / 2f + letterSize * BASELINE_SHARE
        drawIntoCanvas { it.nativeCanvas.drawText(letter, 0, 1, x + width / 2f, baseline, text) }
    }

    /** A triangle [side] wide with its base on the line through [x]'s row at [edgeY], pointing down from it or up from it. */
    private fun DrawScope.drawTriangle(x: Float, edgeY: Float, side: Float, pointingDown: Boolean, color: Color) {
        val tipY = if (pointingDown) edgeY + side else edgeY - side
        path.reset()
        path.moveTo(x - side / 2f, edgeY)
        path.lineTo(x + side / 2f, edgeY)
        path.lineTo(x, tipY)
        path.close()
        drawPath(path, color)
    }

    private companion object {
        val FLAG_WIDTH = 18.dp
        val FLAG_HEIGHT = 16.dp
        val FLAG_CORNER = 3.dp
        val LINE_WIDTH = 1.5.dp
        val OVERVIEW_LINE_WIDTH = 1.dp
        val LOOP_RAIL = 3.dp
        val LOOP_END_TICK = 14.dp
        const val LOOP_RAIL_ALPHA = 0.6f
        val MEMORY_TRIANGLE = 10.dp
        val TAB_SIZE = 11.dp
        val TAB_GAP = 1.dp
        val LETTER_SIZE = 11.sp
        val OVERVIEW_LETTER_SIZE = 8.sp
        const val OVERVIEW_TRIANGLE_SHARE = 0.7f
        const val HOT_LINE_ALPHA = 0.85f
        const val MEMORY_LINE_ALPHA = 0.45f
        const val BASELINE_SHARE = 0.35f
    }
}
