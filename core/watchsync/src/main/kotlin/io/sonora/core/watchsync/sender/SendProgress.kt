package io.sonora.core.watchsync.sender

/**
 * Where a send is, for a notification or a progress bar.
 *
 * @property trackNumber The song being handled, from 1, of [trackCount].
 * @property phaseFraction How far the current [phase] of this song is, `0..1`.
 * @property overallFraction How far the whole send is, `0..1`, songs weighted by their length.
 */
data class SendProgress(
    val phase: Phase,
    val trackNumber: Int,
    val trackCount: Int,
    val trackId: String,
    val title: String,
    val phaseFraction: Float,
    val overallFraction: Float,
) {
    enum class Phase { ENCODING, SENDING }
}

/** The arithmetic behind [SendProgress]. */
object SendProgressMath {
    /** Encoding counts for this share of a song's progress. A display estimate: sending is what takes long. */
    const val ENCODE_SHARE = 0.3f

    /** [done] of [total] bytes as `0..1`; a file of no size counts as complete (never a division by zero). */
    fun fraction(done: Long, total: Long): Float = if (total <= 0) 1f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** How far a song is overall, given the fraction of its current phase. */
    fun songFraction(phase: SendProgress.Phase, phaseFraction: Float): Float {
        val clamped = phaseFraction.coerceIn(0f, 1f)
        return when (phase) {
            SendProgress.Phase.ENCODING -> ENCODE_SHARE * clamped
            SendProgress.Phase.SENDING -> ENCODE_SHARE + (1f - ENCODE_SHARE) * clamped
        }
    }

    /**
     * Progress of the whole send: [finished] songs done, the next one at [currentSongFraction]. [weights] are the songs'
     * lengths (a song of no length still counts as 1).
     */
    fun overall(weights: List<Long>, finished: Int, currentSongFraction: Float): Float {
        if (weights.isEmpty()) return 1f
        val effective = weights.map { maxOf(1L, it).toDouble() }
        val total = effective.sum()
        val done = effective.take(finished).sum()
        val current = effective.getOrNull(finished)?.times(currentSongFraction.coerceIn(0f, 1f)) ?: 0.0
        return ((done + current) / total).toFloat().coerceIn(0f, 1f)
    }
}
