package io.sonora.feature.dj.controls

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * When a held step button repeats its step: a press does its step at once (the normal tap); if the finger stays down,
 * the first repeat comes after [initialDelayMs] and the following ones every [startIntervalMs], each interval
 * [decayPercent] percent of the one before, down to [minIntervalMs] (the held button speeds up, like a CDJ's jog
 * buttons, so a fine step stays fine and a long way is quick). Pure; the composable only sleeps these delays.
 *
 * Repeats are numbered from 0: repeat 0 is the first one after the press.
 */
class HoldRepeatSchedule(
    val initialDelayMs: Long,
    val startIntervalMs: Long,
    val minIntervalMs: Long,
    val decayPercent: Int,
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be positive: $initialDelayMs" }
        require(minIntervalMs > 0) { "minIntervalMs must be positive (a zero delay would spin): $minIntervalMs" }
        require(startIntervalMs >= minIntervalMs) { "startIntervalMs $startIntervalMs is below minIntervalMs $minIntervalMs" }
        require(decayPercent in 1 until PERCENT) { "decayPercent must be 1..99 to accelerate: $decayPercent" }
    }

    /** How long to wait after the previous step (the press, for repeat 0) before [repeat] fires. */
    fun delayBeforeRepeat(repeat: Int): Long {
        require(repeat >= 0) { "repeat must not be negative: $repeat" }
        if (repeat == 0) return initialDelayMs
        val interval = startIntervalMs * (decayPercent / PERCENT.toDouble()).pow(repeat - 1)
        return interval.roundToLong().coerceAtLeast(minIntervalMs)
    }

    companion object {
        private const val PERCENT = 100

        /** 400 ms to the first repeat, then 120 ms speeding up to 40 ms in about a second. */
        val Default = HoldRepeatSchedule(initialDelayMs = 400, startIntervalMs = 120, minIntervalMs = 40, decayPercent = 90)
    }
}
