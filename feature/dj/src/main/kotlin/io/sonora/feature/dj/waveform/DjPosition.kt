package io.sonora.feature.dj.waveform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.sonora.core.domain.playback.PlaybackClock

/**
 * The playback position for the DJ screen's drawing, as a lambda meant to be called in the draw phase only: it reads a
 * snapshot state, so each new position redraws the reader (the waveform canvas) without recomposing anything.
 *
 * While [playing] and the lifecycle is at least STARTED, a `withFrameNanos` loop reads [clock] once per frame and
 * smooths it ([DjPositionSource], [io.sonora.core.domain.playback.PositionSmoother]); the loop allocates nothing per
 * frame. Paused, there is no loop: one exact read when playback stops, and one more whenever the slow position
 * ([fallbackPositionMs], the 250 ms media-controller position, read as a snapshot state) changes, which is how a seek
 * while paused shows. Whenever the clock cannot be read or is on another queue item than [itemId], the drawn position
 * is [fallbackPositionMs]. While playing, the frames are counted into [DjClockLog.stats] and logged once a second
 * (`adb logcat -s SonoraDjClock`).
 */
@Composable
fun rememberDjPosition(
    clock: PlaybackClock,
    itemId: Long,
    playing: Boolean,
    fallbackPositionMs: () -> Long,
): () -> Double {
    val source = remember(clock) { DjPositionSource(clock, stats = DjClockLog.stats) }
    val drawn = remember { mutableDoubleStateOf(Double.NaN) }
    val fallback by rememberUpdatedState(fallbackPositionMs)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source, itemId, playing, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            followDjPosition(source, itemId, playing, { fallback() }, report = DjClockLog::report) {
                drawn.doubleValue = it
            }
        }
    }
    return remember(drawn) {
        {
            val value = drawn.doubleValue
            if (value.isNaN()) fallback().toDouble() else value
        }
    }
}

/**
 * The frame driver of [rememberDjPosition], kept apart so its frame requests can be counted in tests: while [playing]
 * it asks for every frame (`withFrameNanos`, from the caller's [androidx.compose.runtime.MonotonicFrameClock]) and
 * [publish]es the smoothed position of each; paused it never asks for a frame, publishing one exact read now and one
 * more each time the slow position ([fallbackPositionMs], a snapshot-state read) changes. A paused DJ screen therefore
 * schedules no frame from here. While playing, a source with [DjPositionSource.stats] hands them to [report] once a
 * second of frames and starts the next window (the default reports nothing; [rememberDjPosition] logs them). Runs
 * until cancelled.
 */
internal suspend fun followDjPosition(
    source: DjPositionSource,
    itemId: Long,
    playing: Boolean,
    fallbackPositionMs: () -> Long,
    report: (DjClockStats) -> Unit = {},
    publish: (Double) -> Unit,
) {
    source.reset()
    if (playing) {
        val stats = source.stats
        stats?.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                publish(source.frame(frameNanos, itemId, fallbackPositionMs()))
                if (stats != null && stats.due(frameNanos)) {
                    report(stats)
                    stats.clear(frameNanos)
                }
            }
        }
    } else {
        snapshotFlow { fallbackPositionMs() }.collect { slowMs -> publish(source.still(itemId, slowMs)) }
    }
}
