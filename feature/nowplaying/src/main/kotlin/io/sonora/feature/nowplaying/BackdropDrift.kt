package io.sonora.feature.nowplaying

import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlinx.coroutines.flow.Flow

/**
 * The phase (radians, 0 until 2π) of the artwork backdrop's slow drift: one turn per [periodNanos] of frame time,
 * advanced only by the frames it is given. Unlike an infinite transition it can stop and start again where it stopped:
 * after [pause] the next frame only sets the time base, so the blobs never jump when the drift resumes. A
 * `durationScale` (the system's animator duration scale) above 1 slows it, 0 holds it still.
 *
 * Plain fields, no allocation; main thread only.
 */
internal class BackdropDrift(private val periodNanos: Long = PERIOD_NANOS) {

    /** The current phase, in radians. */
    var phase: Float = 0f
        private set

    private var lastNanos = NO_FRAME

    /** Advances to the frame timed [frameNanos] and returns the phase to draw it with. */
    fun frame(frameNanos: Long, durationScale: Float = 1f): Float {
        val last = lastNanos
        lastNanos = frameNanos
        if (last == NO_FRAME || durationScale <= 0f || frameNanos <= last) return phase
        val turns = (frameNanos - last).toDouble() / periodNanos / durationScale
        phase = ((phase + turns * TWO_PI) % TWO_PI).toFloat()
        return phase
    }

    /** Stops the drift: the phase holds, and the next frame starts a new time base. */
    fun pause() {
        lastNanos = NO_FRAME
    }

    companion object {
        /** One turn of the drift (it was a 24 s infinite transition). */
        const val PERIOD_NANOS: Long = 24_000_000_000L

        private const val NO_FRAME = Long.MIN_VALUE
        private const val TWO_PI = 2 * PI
    }
}

/**
 * Runs [drift] until cancelled: on every emission of [ticks] it takes the next display frame, advances the phase by the time
 * that passed since the last one ([BackdropDrift.frame]) and hands it to [publish]. One turn takes 24 s, so a step every 250 ms
 * moves the blobs at most 17 px (full size), which a radial gradient more than 1000 px wide cannot show; asking for a frame on
 * every vsync instead kept a 144 Hz tablet drawing (and every translucent control re-sampling) continuously for nothing (726 frames
 * in 5 s of an untouched, playing Now Playing, 70 % of the GPU). The ticks are the player's position changing, four a second
 * and only while it plays: the step shares the frame the progress bar draws anyway, and a paused screen draws none.
 */
internal suspend fun followBackdropDrift(drift: BackdropDrift, durationScale: Float, ticks: Flow<*>, publish: (Float) -> Unit) {
    ticks.collect { withFrameNanos { publish(drift.frame(it, durationScale)) } }
}
