package io.sonora.feature.dj.waveform

/** The next closer span (fewer seconds across the view), or `null` at the closest. */
fun WaveformZoom.zoomedIn(): WaveformZoom? = WaveformZoom.entries.getOrNull(ordinal - 1)

/** The next wider span (more seconds across the view), or `null` at the widest. */
fun WaveformZoom.zoomedOut(): WaveformZoom? = WaveformZoom.entries.getOrNull(ordinal + 1)

/** The accessibility skips of the DJ timeline: a fixed step back or forward, kept inside the track. */
object SkipSeek {

    /** One skip, in ms. */
    const val STEP_MS: Long = 10_000L

    /**
     * [positionMs] moved by [deltaMs], never before the start nor, for a track of a known [durationMs] (above 0), past
     * its end.
     */
    fun skipped(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val moved = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}
