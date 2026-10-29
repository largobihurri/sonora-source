package io.sonora.feature.dj

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.djmodel.DjCue
import io.sonora.feature.dj.controls.GridEditedChip
import io.sonora.feature.dj.waveform.BeatIndex
import io.sonora.feature.dj.waveform.CueMarkerIndex
import io.sonora.feature.dj.waveform.LoopRegion
import io.sonora.feature.dj.waveform.ScrollingWaveform
import io.sonora.feature.dj.waveform.SkipSeek
import io.sonora.feature.dj.waveform.WaveformOverview
import io.sonora.feature.dj.waveform.WaveformZoom
import io.sonora.feature.dj.waveform.zoomedIn
import io.sonora.feature.dj.waveform.zoomedOut

/**
 * The heights under the DJ timeline's scrolling waveform: the gap above the [overviewHeight] strip, the gap above the
 * [timeRow] and the row itself. They are fixed so the screen can budget its height and promise the waveform a minimum
 * (D4.1b); the waveform takes whatever the column's height leaves.
 */
data class DjTimelineSizes(
    val overviewGap: Dp,
    val overviewHeight: Dp,
    val timeGap: Dp,
    val timeRow: Dp,
    /** The waveform is short (the landscape layouts): the state cards drop to a compact look that fits about 110 dp (D2.10). */
    val compactCards: Boolean = false,
) {

    /** Everything but the scrolling waveform: the two gaps, the overview and the time row. */
    val fixedHeight: Dp get() = overviewGap + overviewHeight + timeGap + timeRow

    companion object {
        /** Room to spare: 10 dp over a 40 dp overview, 4 dp over a 20 dp time row. */
        val Comfortable = DjTimelineSizes(overviewGap = 10.dp, overviewHeight = 40.dp, timeGap = 4.dp, timeRow = 20.dp)

        /** A phone about 700 dp tall: 6 dp over the same 40 dp overview, 2 dp over the time row. */
        val Compact = DjTimelineSizes(overviewGap = 6.dp, overviewHeight = 40.dp, timeGap = 2.dp, timeRow = 20.dp)

        /** A tablet (T6): a 56 dp overview to seek on with a finger, 24 dp times. */
        val Tablet = DjTimelineSizes(overviewGap = 8.dp, overviewHeight = 56.dp, timeGap = 4.dp, timeRow = 24.dp)
    }
}

/**
 * The DJ timeline: the scrolling waveform over a translucent panel (taking the height this column leaves it), the whole-track
 * overview over a thinner one, and under them the elapsed and remaining times with the analysis status (or the "Edited"
 * badge) between them.
 *
 * [timeline] says what can be drawn ([DjTimelineView]); while there is no waveform both panels stay empty but the
 * overview still seeks. [position] (ms) is the per-frame position ([io.sonora.feature.dj.waveform.rememberDjPosition]),
 * read in draw by the waveforms; the time labels recompose once a second only. Every seek, by drag, tap or an
 * accessibility action, goes to [onSeek]; [zoom] is hoisted with [onZoomChange].
 *
 * Each waveform is a sibling drawn over its [TranslucentSurface] in its own layer: never the surface's content, never
 * part of the backdrop the surface samples (that is the artwork behind Now Playing).
 */
@Composable
fun DjTimeline(
    timeline: DjTimelineState,
    durationMs: Long,
    position: () -> Double,
    zoom: WaveformZoom,
    onZoomChange: (WaveformZoom) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The cues of the playing track, drawn as markers on the waveform and on the overview. */
    cues: List<DjCue> = emptyList(),
    /** The loop set on the track, washed on both waveforms and announced with the waveform's state; `null` for none. */
    loop: LoopRegion? = null,
    /** The beat grid is the user's edit: a small "Edited" badge sits in the time row, between the two times. */
    gridEdited: Boolean = false,
    /** The stored analysis is from an older analyser: a small "Update" chip sits in the time row (D2.11). */
    outdated: Boolean = false,
    /** Analyse the track again, replacing its stored analysis (the chip and the strip of a track without a beat). */
    onReanalyse: () -> Unit = {},
    /** Try a failed analysis again. */
    onRetry: () -> Unit = {},
    /** The heights of everything under the scrolling waveform (D4.1b), which takes the rest of [modifier]'s height. */
    sizes: DjTimelineSizes = DjTimelineSizes.Comfortable,
    /**
     * Room kept free at the end of the overview and the time row (not of the waveform): the landscape layout stands the
     * tempo section there, beside them (D2.10).
     */
    endInset: Dp = 0.dp,
) {
    val markers = remember(cues) { if (cues.isEmpty()) null else CueMarkerIndex(cues) }
    val view = remember(timeline) { DjTimelineView.of(timeline) }
    val analysis = view.analysis
    // Built once per analysis: its arrays serve every frame of the scrolling view.
    val beats = remember(analysis) {
        view.grid?.let { BeatIndex(it, ((analysis?.durationMs ?: 0L).takeIf { ms -> ms > 0 } ?: durationMs).toDouble()) }
    }
    val second by remember(position, durationMs) { derivedStateOf { DjTimeLabels.secondOf(position(), durationMs) } }
    val labels = DjTimeLabels.ofSecond(second, durationMs)
    val seek = rememberUpdatedState(onSeek)
    val skips = skipActions(position, durationMs) { seek.value(it) }
    val positionState = stringResource(R.string.dj_position_state, labels.elapsed, LocaleFormats.duration(durationMs))
    val loopState = loop?.outMs?.let { out ->
        stringResource(R.string.dj_loop_region_state, LocaleFormats.duration(loop.inMs.toLong()), LocaleFormats.duration(out.toLong()))
    }

    Column(modifier) {
        val waveformLabel = stringResource(R.string.dj_waveform)
        val zoomState = pluralStringResource(R.plurals.dj_zoom_state, zoom.visibleSeconds, zoom.visibleSeconds)
        val zoomActions = zoomActions(zoom, onZoomChange)
        WaveformPanel(MainShape, MainInset, Modifier.weight(1f)) { inner ->
            ScrollingWaveform(
                waveform = view.waveform,
                beats = beats,
                durationMs = durationMs,
                position = position,
                zoom = zoom,
                onZoomChange = onZoomChange,
                onSeek = onSeek,
                cues = markers,
                loop = loop,
                showPlayhead = view.showsPlayhead,
                modifier = inner.semantics {
                    contentDescription = waveformLabel
                    stateDescription = listOfNotNull(positionState, zoomState, loopState).joinToString(", ")
                    customActions = skips + zoomActions
                },
            )
            // The state of the analysis (D2.11), over the panel; the waveform under a track without a beat stays in view.
            when (val panel = view.panel) {
                null -> Unit
                DjTimelinePanel.NoBeatGrid -> DjNoBeatStrip(onReanalyse, compact = sizes.compactCards)
                else -> DjStateCard(panel, onRetry, compact = sizes.compactCards)
            }
        }
        Spacer(Modifier.height(sizes.overviewGap))
        val overviewLabel = stringResource(R.string.dj_overview)
        WaveformPanel(OverviewShape, OverviewInset, Modifier.padding(end = endInset).height(sizes.overviewHeight)) { inner ->
            WaveformOverview(
                waveform = view.waveform,
                durationMs = durationMs,
                position = position,
                onSeek = onSeek,
                cues = markers,
                loop = loop,
                modifier = inner.semantics {
                    contentDescription = overviewLabel
                    stateDescription = positionState
                    if (durationMs > 0) {
                        progressBarRangeInfo = ProgressBarRangeInfo((second * 1000f).coerceAtMost(durationMs.toFloat()), 0f..durationMs.toFloat())
                        setProgress { target ->
                            seek.value(target.toLong().coerceIn(0L, durationMs))
                            true
                        }
                    }
                    customActions = skips
                },
            )
        }
        Spacer(Modifier.height(sizes.timeGap))
        TimeRow(labels, view.notice, gridEdited, outdated, onReanalyse, sizes.timeRow, Modifier.padding(end = endInset))
    }
}

/**
 * A translucent panel of [shape] with a waveform drawn over it: the waveform is the panel's sibling, clipped to the same
 * shape and kept [inset] from its top and bottom so the panel's lit rim stays visible. [content] gets the waveform's
 * modifier.
 */
@Composable
private fun WaveformPanel(
    shape: RoundedCornerShape,
    inset: Dp,
    modifier: Modifier,
    content: @Composable BoxScope.(Modifier) -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        // Always dark, like the rest of Now Playing over its artwork; tinted, so the bands read on bright artwork.
        TranslucentSurface(Modifier.matchParentSize(), shape = shape, elevation = 0.dp, darkTheme = true, tinted = true) {}
        content(Modifier.matchParentSize().clip(shape).padding(vertical = inset))
    }
}

/**
 * The elapsed time, the remaining time and, centred between them, the analysis status (if any) or the badges: "Edited"
 * when the beat grid is the user's edit ([gridEdited]) and, when the stored analysis is from an older analyser
 * ([outdated]), the "Old analysis · Update" chip, which becomes just "Update" next to the other badge so neither shrinks
 * the waveform nor crowds the times. The row is [height] tall whatever it shows, so the screen's height budget holds.
 */
@Composable
private fun TimeRow(
    labels: DjTimeLabels,
    notice: DjTimelineNotice?,
    gridEdited: Boolean,
    outdated: Boolean,
    onReanalyse: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        // The times are spoken by the overview's state; the row itself stays out of the way.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(labels.elapsed, style = TimeStyle)
            Spacer(Modifier.weight(1f))
            Text(labels.remaining, style = TimeStyle)
        }
        if (notice != null) {
            Text(
                noticeText(notice),
                Modifier.align(Alignment.Center).padding(horizontal = NoticeClearance),
                style = TimeStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (gridEdited || outdated) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = NoticeClearance),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (outdated) OldAnalysisChip(short = gridEdited, onClick = onReanalyse, Modifier.weight(1f, fill = false))
                if (gridEdited) GridEditedChip(compact = true)
            }
        }
    }
}

@Composable
private fun noticeText(notice: DjTimelineNotice): String = when (notice) {
    is DjTimelineNotice.Reanalysing ->
        if (notice.paused) stringResource(R.string.dj_status_reanalysis_paused) else stringResource(R.string.dj_status_reanalysing, percentText(notice.percent))
    DjTimelineNotice.ReanalysisFailed -> stringResource(R.string.dj_status_reanalysis_failed)
}

/** Skip back and forward by [SkipSeek.STEP_MS] from the position drawn when the action runs. */
@Composable
private fun skipActions(position: () -> Double, durationMs: Long, onSeek: (Long) -> Unit): List<CustomAccessibilityAction> {
    val back = stringResource(R.string.dj_skip_back)
    val forward = stringResource(R.string.dj_skip_forward)
    val currentPosition by rememberUpdatedState(position)
    return remember(back, forward, durationMs) {
        fun skip(deltaMs: Long): Boolean {
            onSeek(SkipSeek.skipped(currentPosition().toLong(), deltaMs, durationMs))
            return true
        }
        listOf(
            CustomAccessibilityAction(back) { skip(-SkipSeek.STEP_MS) },
            CustomAccessibilityAction(forward) { skip(SkipSeek.STEP_MS) },
        )
    }
}

/** Zoom in and out by one [WaveformZoom] step, each offered only while there is a step left. */
@Composable
private fun zoomActions(zoom: WaveformZoom, onZoomChange: (WaveformZoom) -> Unit): List<CustomAccessibilityAction> {
    val zoomIn = stringResource(R.string.dj_zoom_in)
    val zoomOut = stringResource(R.string.dj_zoom_out)
    val change by rememberUpdatedState(onZoomChange)
    return remember(zoom, zoomIn, zoomOut) {
        listOfNotNull(
            zoom.zoomedIn()?.let { closer -> CustomAccessibilityAction(zoomIn) { change(closer); true } },
            zoom.zoomedOut()?.let { wider -> CustomAccessibilityAction(zoomOut) { change(wider); true } },
        )
    }
}

private val MainShape = RoundedCornerShape(16.dp)
private val MainInset = 6.dp

private val OverviewShape = RoundedCornerShape(10.dp)
private val OverviewInset = 4.dp

/** Keeps a centred status clear of the times at both ends. */
private val NoticeClearance = 52.dp

/** As Now Playing's scrubber times: small, soft white, tabular figures. */
private val TimeStyle = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), fontFeatureSettings = "tnum")
