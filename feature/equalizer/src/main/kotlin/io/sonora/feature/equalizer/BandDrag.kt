package io.sonora.feature.equalizer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sonora.core.domain.equalizer.EqualizerSettings

/**
 * Drag lifecycle of one vertical band slider, like the app's capsule sliders: relative, so the gain starts from
 * [currentGain] (not from the touch point) and moves by the finger's travel only, the full ±12 dB range over the
 * track height (up is a boost). Reported gains are rounded to a tenth of a decibel; the drag itself keeps the exact
 * travel, so it follows the finger smoothly. A release commits through [onFinished]; a cancel (another gesture took
 * the pointer) only reports [onCancelled], with the gain the drag started from. An end or cancel without a drag
 * in progress is ignored.
 */
internal class BandDrag(
    private val currentGain: () -> Float,
    private val onChange: (Float) -> Unit,
    private val onFinished: (Float) -> Unit,
    private val onCancelled: (startGain: Float) -> Unit,
) {
    var dragging by mutableStateOf(false)
        private set

    /** The exact gain under the finger, in dB. */
    var gain by mutableFloatStateOf(0f)
        private set

    /** The gain the current (or last) drag started from, to put back when it is cancelled. */
    var startGain = 0f
        private set

    private var heightPx = 1f

    /** Starts from the current gain plus the travel before the drag was recognised (screen pixels, down positive). */
    fun start(trackHeightPx: Float, travelBeforeStartPx: Float = 0f) {
        heightPx = trackHeightPx.coerceAtLeast(1f)
        dragging = true
        startGain = currentGain()
        gain = (startGain.coerceIn(-MAX, MAX) + toDb(travelBeforeStartPx)).coerceIn(-MAX, MAX)
        onChange(EqualizerSettings.quantize(gain))
    }

    fun dragBy(dyPx: Float) {
        gain = (gain + toDb(dyPx)).coerceIn(-MAX, MAX)
        onChange(EqualizerSettings.quantize(gain))
    }

    fun end() = finish { onFinished(EqualizerSettings.quantize(gain)) }

    fun cancel() = finish { onCancelled(startGain) }

    private fun toDb(dyPx: Float): Float = -dyPx / heightPx * 2 * MAX

    private inline fun finish(report: () -> Unit) {
        if (!dragging) return
        report()
        dragging = false
    }

    private companion object {
        const val MAX = EqualizerSettings.MAX_GAIN_DB
    }
}
