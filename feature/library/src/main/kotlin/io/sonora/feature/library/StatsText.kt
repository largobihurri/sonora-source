package io.sonora.feature.library

/** A listening time as Statistics shows it: whole hours and the minutes left, seconds dropped. */
internal data class ListeningTime(val hours: Long, val minutes: Long) {
    companion object {
        fun of(ms: Long): ListeningTime {
            val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
            return ListeningTime(totalMinutes / 60, totalMinutes % 60)
        }
    }
}
