package io.sonora.player.wear.ui

/** The share of the song played, 0..1; 0 while the duration is unknown. */
fun progressFraction(positionMs: Long, durationMs: Long?): Float {
    if (durationMs == null || durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** A stream volume [level] out of [max] steps as a whole percent, 0..100. */
fun volumePercent(level: Int, max: Int): Int =
    (level.coerceAtLeast(0) * 100 / max.coerceAtLeast(1)).coerceAtMost(100)
