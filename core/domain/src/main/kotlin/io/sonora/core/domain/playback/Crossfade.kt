package io.sonora.core.domain.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The crossfade setting: how long one song blends into the next, in whole seconds; 0 (the default) is off. */
object Crossfade {
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 12
    const val DEFAULT_SECONDS = 0

    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}

/**
 * What [CrossfadePolicy] needs to know of a queue entry: its album ([albumId], 0 or less for none), its place in the
 * album ([discNumber], [trackNumber], `null` when the tags have none) and its length ([durationMs], 0 or less when
 * unknown).
 */
data class CrossfadeTrack(val albumId: Long, val discNumber: Int?, val trackNumber: Int?, val durationMs: Long)

/**
 * Decides how long [outgoing] blends into [incoming], the entry that plays after it; 0 means a plain (gapless)
 * transition.
 *
 * Gapless wins: the next track of the same album in album order (the next number on the same disc, or track 1 of the
 * next disc) is never crossfaded, so live albums and DJ mixes keep running seamlessly. The same album without track
 * numbers on either side is taken as album order too: without them the order cannot be told, and an unwanted fade
 * inside an album is worse than a missed one. Repeat One loops gaplessly, and the end of the queue has nothing to
 * fade into.
 *
 * A fade never takes more than half of either song: the outgoing one is then still heard for at least half its length
 * (the play-count rule), and a short song is never all fade. A fade under [MIN_FADE_MS] is not worth it and an
 * outgoing song of unknown length cannot be scheduled: both play gapless.
 */
object CrossfadePolicy {
    const val MIN_FADE_MS = 1_000L

    fun fadeMs(settingSeconds: Int, outgoing: CrossfadeTrack, incoming: CrossfadeTrack?, repeatOne: Boolean): Long {
        val chosenMs = Crossfade.clampSeconds(settingSeconds) * 1_000L
        if (chosenMs == 0L || incoming == null || repeatOne) return 0L
        if (isAlbumContinuation(outgoing, incoming)) return 0L
        if (outgoing.durationMs <= 0) return 0L
        var fadeMs = minOf(chosenMs, outgoing.durationMs / 2)
        if (incoming.durationMs > 0) fadeMs = minOf(fadeMs, incoming.durationMs / 2)
        return if (fadeMs < MIN_FADE_MS) 0L else fadeMs
    }

    /** Whether [next] is the track that follows [previous] on the same album. */
    fun isAlbumContinuation(previous: CrossfadeTrack, next: CrossfadeTrack): Boolean {
        if (previous.albumId <= 0 || previous.albumId != next.albumId) return false
        val previousNumber = previous.trackNumber ?: return true
        val nextNumber = next.trackNumber ?: return true
        val previousDisc = previous.discNumber ?: 1
        val nextDisc = next.discNumber ?: 1
        return (nextDisc == previousDisc && nextNumber == previousNumber + 1) ||
            (nextDisc == previousDisc + 1 && nextNumber == 1)
    }
}

/**
 * Equal-power fade gains (linear amplitude) over a fade's progress, 0 at its start and 1 at its end: the outgoing
 * song follows a quarter cosine and the incoming one a quarter sine, so their summed power stays constant and the
 * blend never dips or swells (each song is at -3 dB halfway). Progress outside 0..1 is held at the ends.
 */
object FadeCurve {
    fun fadeOut(progress: Float): Float = cos(angle(progress)).toFloat()

    fun fadeIn(progress: Float): Float = sin(angle(progress)).toFloat()

    /** The share of a fade of [durationMs] done after [elapsedMs], within 0..1; a fade of no length is done. */
    fun progress(elapsedMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    private fun angle(progress: Float): Double = progress.coerceIn(0f, 1f) * PI / 2
}
